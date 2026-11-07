/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.agent

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.ai.edge.gallery.agent.coordinator.AgentCoordinator
import com.google.ai.edge.gallery.agent.coordinator.AgentRunResult
import com.google.ai.edge.gallery.agent.coordinator.AgentToolRegistry
import com.google.ai.edge.gallery.agent.coordinator.ChannelShellExecutionApprovalPolicy
import com.google.ai.edge.gallery.agent.coordinator.LiteRtLmAgentModel
import com.google.ai.edge.gallery.agent.coordinator.ShizukuShellTransport
import com.google.ai.edge.gallery.agent.coordinator.ShellExecutionBackend
import com.google.ai.edge.gallery.agent.coordinator.ShellExecutionRouter
import com.google.ai.edge.gallery.agent.coordinator.TermuxRishShellTransport
import com.google.ai.edge.gallery.agent.coordinator.TermuxShellTransport
import com.google.ai.edge.gallery.agent.coordinator.UnifiedShellTool
import com.google.ai.edge.gallery.customtasks.agentchat.AgentTools
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.shizuku.ShizukuShellClient
import com.google.ai.edge.gallery.termux.TermuxRunCommandClient
import com.google.ai.edge.gallery.tools.RuntimeToolDispatcher
import com.google.ai.edge.gallery.tools.ToolExecutionContext
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Agent Chat runtime backed by one coordinator and one manually-driven LiteRT-LM conversation.
 *
 * Existing Gallery tools are supplied to the same conversation ToolManager and adapted back into
 * AgentTools, so skills, MCP, JS, intents, and native shell all pass through AgentCoordinator.
 * AICore remains on the established executor because it does not expose the LiteRT-LM Conversation
 * API used by the manual structured-tool-call adapter.
 */
class NativeAgentRuntimeExecutor(
  private val agentTools: AgentTools,
  private val fallbackExecutor: AgentRuntimeExecutor,
) : AgentRuntimeExecutor {

  private val stateLock = Any()
  private val activeJob = AtomicReference<Job?>(null)
  @Volatile private var context: Context? = null
  @Volatile private var config: AgentRuntimeConfig? = null
  @Volatile private var agentModel: LiteRtLmAgentModel? = null
  @Volatile private var coordinator: AgentCoordinator? = null
  @Volatile private var shellRouter: ShellExecutionRouter? = null
  @Volatile private var shizukuClient: ShizukuShellClient? = null
  @Volatile private var usingFallback = false
  @Volatile private var sessionId: String? = null

  override val activeSessionId: String?
    get() = if (usingFallback) fallbackExecutor.activeSessionId else sessionId

  override suspend fun initialize(
    context: Context,
    config: AgentRuntimeConfig,
    onDone: (errorMsg: String) -> Unit,
  ) {
    this.context = context.applicationContext
    this.config = config
    sessionId = config.sessionId.ifBlank { UUID.randomUUID().toString() }

    if (config.model.isAiCore) {
      usingFallback = true
      fallbackExecutor.initialize(context, config, onDone)
      return
    }

    usingFallback = false
    attachLegacyToolContext(config)
    val termux = TermuxRunCommandClient(context)
    val shizuku = ShizukuShellClient(context)
    shizukuClient = shizuku
    shellRouter = ShellExecutionRouter(
      mapOf(
        ShellExecutionBackend.TERMUX to TermuxShellTransport(termux),
        ShellExecutionBackend.SHIZUKU to ShizukuShellTransport(shizuku),
        ShellExecutionBackend.TERMUX_RISH to TermuxRishShellTransport(termux),
      )
    )

    // Initialize the shared engine only. The agent gets its own manual-tool-calling conversation
    // after initialization, leaving Gallery's ordinary conversation and MCP client intact.
    config.model.runtimeHelper.initialize(
      context = context.applicationContext,
      model = config.model,
      taskId = config.taskId,
      supportImage = config.supportImage,
      supportAudio = config.supportAudio,
      onDone = onDone,
      systemInstruction = config.systemInstruction?.takeIf { it.isNotBlank() }?.let { Contents.of(it) },
      tools = emptyList(),
      enableConversationConstrainedDecoding = false,
      initialMessages = emptyList(),
    )
  }

  override fun executeStream(
    context: AgentExecutionContext,
    request: AgentRequest,
  ): Flow<AgentEvent> {
    if (usingFallback) return fallbackExecutor.executeStream(context, request)
    return flow {
      val job = currentCoroutineContext()[Job]
      activeJob.set(job)
      emit(AgentEvent.LoopInitiated(request))
      try {
        val currentConfig = checkNotNull(config) { "Agent Chat has not been initialized." }
        val currentContext = checkNotNull(this@NativeAgentRuntimeExecutor.context)
        val contents = withContext(Dispatchers.IO) { buildUserContents(currentContext, request) }
        ensureAgentModel(currentConfig)
        val currentCoordinator = checkNotNull(coordinator)
        val result: AgentRunResult = currentCoordinator.run(request.query, contents)
        emit(AgentEvent.StreamToken(token = result.finalText, done = true))
        emit(AgentEvent.LoopTerminated(result.finalText))
      } catch (cancelled: CancellationException) {
        discardAgentConversation()
        throw cancelled
      } catch (error: Exception) {
        emit(AgentEvent.Error(error.message ?: error.javaClass.simpleName))
      } finally {
        activeJob.compareAndSet(job, null)
      }
    }
  }

  override suspend fun execute(
    context: AgentExecutionContext,
    request: AgentRequest,
  ): AgentResponse {
    if (usingFallback) return fallbackExecutor.execute(context, request)
    var output = ""
    var success = true
    executeStream(context, request).collect { event ->
      when (event) {
        is AgentEvent.LoopTerminated -> output = event.finalResponse
        is AgentEvent.Error -> {
          output = event.errorMessage
          success = false
        }
        else -> Unit
      }
    }
    return AgentResponse(output, success)
  }

  override fun interrupt() {
    if (usingFallback) {
      fallbackExecutor.interrupt()
      return
    }
    agentModel?.interrupt()
    activeJob.getAndSet(null)?.cancel(CancellationException("Agent execution interrupted."))
  }

  override suspend fun resetSession(config: AgentRuntimeConfig) {
    if (usingFallback) {
      fallbackExecutor.resetSession(config)
      return
    }
    this.config = config
    sessionId = config.sessionId.ifBlank { sessionId ?: UUID.randomUUID().toString() }
    attachLegacyToolContext(config)
    discardAgentConversation()
  }

  override fun cleanUp(onDone: () -> Unit) {
    if (usingFallback) {
      fallbackExecutor.cleanUp(onDone)
      return
    }
    val model = config?.model
    val shellClient = shizukuClient
    discardAgentConversation()
    shizukuClient = null
    shellRouter = null
    config = null
    sessionId = null
    CoroutineScope(Dispatchers.IO).launch {
      runCatching { shellClient?.close() }
      if (model == null) {
        onDone()
      } else {
        model.runtimeHelper.cleanUp(model, onDone)
      }
    }
  }

  private fun attachLegacyToolContext(config: AgentRuntimeConfig) {
    RuntimeToolDispatcher().setupExecutionContext(
      tools = agentTools.getAvailableTools(),
      context = ToolExecutionContext(
        taskId = config.taskId,
        actionChannel = config.actionChannel,
      ),
    )
  }

  private suspend fun ensureAgentModel(config: AgentRuntimeConfig): LiteRtLmAgentModel =
    withContext(Dispatchers.IO) {
      synchronized(stateLock) {
      agentModel?.let { return@synchronized it }
      val router = checkNotNull(shellRouter) { "Shell execution backends are not initialized." }
      val approval =
        config.actionChannel?.let { ChannelShellExecutionApprovalPolicy(it) }
          ?: com.google.ai.edge.gallery.agent.coordinator.ShellExecutionApprovalPolicy { false }
      val shellTools = AgentToolRegistry(
        listOf(UnifiedShellTool(router, approval))
      )
      val created = com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper.createAgentModel(
        model = config.model,
        tools = shellTools,
        systemInstruction = config.systemInstruction,
        additionalToolProviders = agentTools.getLiteRtToolProviders(),
        initialMessages = config.initialMessages,
      )
      agentModel = created
      coordinator = AgentCoordinator(created, created.toolRegistry(shellTools))
      created
      }
    }

  private fun discardAgentConversation() {
    synchronized(stateLock) {
      val old = agentModel
      agentModel = null
      coordinator = null
      old?.close()
    }
  }

  private fun buildUserContents(context: Context, request: AgentRequest): Contents? {
    if (request.attachments.isEmpty()) return null
    val contents = mutableListOf<Content>()
    request.attachments.forEach { attachment ->
      when (attachment) {
        is Attachment.ImageBitmap -> contents += Content.ImageBytes(attachment.bitmap.toPngBytes())
        is Attachment.ImageUri -> contents += Content.ImageBytes(readUri(context, attachment.uri))
        is Attachment.AudioBytes -> contents += Content.AudioBytes(attachment.audioBytes)
        is Attachment.AudioUri -> contents += Content.AudioBytes(readUri(context, attachment.uri))
        is Attachment.File -> {
          val file = File(attachment.path)
          require(file.isFile) { "Attached file is unavailable: ${attachment.path}" }
          require(file.length() <= MAX_ATTACHMENT_BYTES) { "Attached file exceeds the 16 MiB limit." }
          val bytes = file.readBytes()
          when {
            attachment.mimeType.startsWith("image/") -> contents += Content.ImageBytes(bytes)
            attachment.mimeType.startsWith("audio/") -> contents += Content.AudioBytes(bytes)
            else -> contents += Content.Text(
              "Attached text file (${file.name}):\n" + bytes.toString(Charsets.UTF_8)
            )
          }
        }
      }
    }
    if (request.query.isNotBlank()) contents += Content.Text(request.query)
    return Contents.of(contents)
  }

  private fun readUri(context: Context, uri: String): ByteArray {
    val bytes = context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
      ?: error("Could not open attachment URI.")
    require(bytes.size <= MAX_ATTACHMENT_BYTES) { "Attached file exceeds the 16 MiB limit." }
    return bytes
  }

  private fun Bitmap.toPngBytes(): ByteArray {
    val stream = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
  }

  companion object {
    private const val MAX_ATTACHMENT_BYTES = 16 * 1_024 * 1_024
  }
}
