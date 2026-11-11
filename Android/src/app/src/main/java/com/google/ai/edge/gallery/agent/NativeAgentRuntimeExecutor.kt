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
import android.util.Log
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
import com.google.ai.edge.gallery.agent.capability.AndroidNativeCapabilityTool
import com.google.ai.edge.gallery.agent.capability.TermuxApiCapabilityTool
import com.google.ai.edge.gallery.customtasks.agentchat.AgentTools
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.shizuku.ShizukuShellClient
import com.google.ai.edge.gallery.shizuku.ShizukuManagerPackageResolver
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
 * Keeps native shell execution distinct from the external MCP wrapper for small on-device models.
 * Backend choice is explicit and is never inferred from whichever transport happens to be alive.
 */
internal fun buildAgentSystemInstruction(existing: String?): String = buildString {
  existing?.takeIf { it.isNotBlank() }?.let {
    append(it.trim())
    append("\n\n")
  }
  append(
    "DEVICE CONTROL TOOL RULES: Use android_control for native device information, battery, " +
      "volume, launcher-visible apps, flashlight, and HTTP(S) URL actions. Use list_apps before " +
      "launch_app and pass an exact returned package_name. Never guess package names. Read-only " +
      "operations do not require approval; every mutation requires the user's one-time approval. " +
      "Report unavailable capabilities and permission requirements honestly. When a tool returns an argument-validation error with accepted arguments, retry once using the same operation and only those accepted arguments; never infer or substitute another operation. Do not use shell " +
      "commands as an automatic fallback for android_control failures. When reporting device " +
      "identity, copy modelIdentifier exactly character-for-character from device_info; do not " +
      "remove punctuation, alter digits, or infer a product tier. When reporting battery status, " +
      "copy batteryPercent and batteryState exactly; batteryState is decoded text, and raw Android " +
      "integer constants must never be presented as charging states. When reporting volume, preserve " +
      "the exact current and maximum values returned by volume_status and name the stream. For " +
      "list_apps, obey returned, total, truncated, and remaining: if truncated=true, explicitly say " +
      "the list is truncated and state how many apps remain; never say it is complete. For " +
      "android_control operation=status, report the native provider availability diagnostic and its " +
      "capability states; do not substitute unrelated skill/tool inventories, and do not describe " +
      "provider availability as a live battery or volume reading.\n\n" +
      "TERMUX API RULES: Use termux_api only for its explicit allowlisted Termux:API operations. Start with " +
      "status to verify both the companion app and command package. Use android_control for native " +
      "operations unless the user explicitly requests Termux:API or is comparing providers. Never " +
      "invent Termux:API commands or use this tool as arbitrary shell access. Its mutations require " +
      "the same one-time approval boundary.\\n\\n" +
      "DEVICE SHELL TOOL RULES: For any request that requires a device shell command, call the " +
      "native execute_shell tool directly. Do not call run_mcp_tool to execute shell commands; " +
      "run_mcp_tool is only for tools hosted by external MCP servers. The execute_shell tool " +
      "requires an explicit backend and command. If the user names a backend, use that exact " +
      "backend. TERMUX, SHIZUKU, and TERMUX_RISH are distinct identities. Never silently switch " +
      "or fall back to another backend. If the tool returns an error, report that actual error " +
      "instead of guessing that a service is stopped. For a multi-part information request, " +
      "retrieve every requested field and clearly mark any field that could not be retrieved."
  )
}

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
    val appContext = context.applicationContext
    val termux = TermuxRunCommandClient(appContext)
    val shizuku = ShizukuShellClient(appContext)
    shizukuClient = shizuku
    shellRouter = ShellExecutionRouter(
      mapOf(
        ShellExecutionBackend.TERMUX to TermuxShellTransport(termux),
        ShellExecutionBackend.SHIZUKU to ShizukuShellTransport(shizuku),
        ShellExecutionBackend.TERMUX_RISH to TermuxRishShellTransport(termux) {
          ShizukuManagerPackageResolver.resolve(appContext)
        },
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
      var coordinatorStarted = false
      try {
        val currentConfig = checkNotNull(config) { "Agent Chat has not been initialized." }
        val requestedSessionId = request.metadata[AgentRequest.SESSION_ID] as? String
        if (!requestedSessionId.isNullOrBlank() && requestedSessionId != sessionId) {
          sessionId = requestedSessionId
          discardAgentConversation()
        }
        val currentContext = checkNotNull(this@NativeAgentRuntimeExecutor.context)
        val contents = withContext(Dispatchers.IO) { buildUserContents(currentContext, request) }
        ensureAgentModel(currentConfig)
        val currentCoordinator = checkNotNull(coordinator)
        coordinatorStarted = true
        @Suppress("UNCHECKED_CAST")
        val extraContext = request.metadata[AgentRequest.LITERTLM_EXTRA_CONTEXT] as? Map<String, Any>
          ?: emptyMap()
        val result: AgentRunResult =
          currentCoordinator.run(request.query, contents, extraContext)
        if (result.isSuccessful) {
          // Gemma's final prose can miscopy identifiers, counts, and command output. Append the
          // coordinator-retained, budgeted tool payload verbatim so the user can inspect the
          // authoritative result without relying on model transcription.
          val authoritativeResults = formatAuthoritativeToolResults(result.messages)
          val finalText = if (authoritativeResults.isNullOrBlank()) {
            result.finalText
          } else {
            result.finalText + "\n\n" + authoritativeResults
          }
          emit(AgentEvent.StreamToken(token = finalText, done = true))
          emit(AgentEvent.LoopTerminated(finalText))
        } else {
          discardAgentConversation()
          emit(AgentEvent.Error(result.finalText))
        }
      } catch (cancelled: CancellationException) {
        discardAgentConversation()
        throw cancelled
      } catch (error: Exception) {
        if (coordinatorStarted) discardAgentConversation()
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
    interrupt()
    val previousConfig = this.config
    val effectiveConfig = config.copy(
      actionChannel = config.actionChannel ?: previousConfig?.actionChannel ?: agentTools.sendActionChannel,
      // Agent Chat's stop/restart path rebuilds a partial config. Preserve the established
      // constrained-decoding requirement instead of silently disabling tool-call constraints.
      enableConversationConstrainedDecoding =
        config.enableConversationConstrainedDecoding ||
          previousConfig?.enableConversationConstrainedDecoding == true,
    )
    this.config = effectiveConfig
    sessionId = effectiveConfig.sessionId.ifBlank { sessionId ?: UUID.randomUUID().toString() }
    attachLegacyToolContext(effectiveConfig)
    discardAgentConversation()
  }

  override fun cleanUp(onDone: () -> Unit) {
    if (usingFallback) {
      fallbackExecutor.cleanUp(onDone)
      return
    }
    val model = config?.model
    val shellClient = shizukuClient
    activeJob.getAndSet(null)?.cancel(CancellationException("Agent runtime cleaned up."))
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
        val actionChannel = config.actionChannel ?: agentTools.sendActionChannel
        val approval = ChannelShellExecutionApprovalPolicy(actionChannel)
        val runtimeTools = AgentToolRegistry(
          listOf(
            UnifiedShellTool(router, approval),
            AndroidNativeCapabilityTool(
              context = checkNotNull(context) { "Agent application context is unavailable." },
              actionChannel = actionChannel,
            ),
          )
        )
        val created = com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper.createAgentModel(
          model = config.model,
          tools = runtimeTools,
          systemInstruction = buildAgentSystemInstruction(config.systemInstruction),
          additionalToolProviders = agentTools.getLiteRtToolProviders(),
          initialMessages = config.initialMessages,
          enableConversationConstrainedDecoding = config.enableConversationConstrainedDecoding,
        )
        Log.i(
          TAG,
          "Agent conversation created: model=${config.model.name}, " +
            "accelerator=${config.model.currentAccelerator}, " +
            "constrainedDecoding=${config.enableConversationConstrainedDecoding}",
        )
        agentModel = created
        coordinator = AgentCoordinator(created, created.toolRegistry(runtimeTools))
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
        is Attachment.ImageBitmap -> {
          val bytes = attachment.bitmap.toPngBytes()
          require(bytes.size <= MAX_ATTACHMENT_BYTES) { "Attached image exceeds the 16 MiB limit." }
          contents += Content.ImageBytes(bytes)
        }
        is Attachment.ImageUri -> contents += Content.ImageBytes(readUri(context, attachment.uri))
        is Attachment.AudioBytes -> {
          require(attachment.audioBytes.size <= MAX_ATTACHMENT_BYTES) {
            "Attached audio exceeds the 16 MiB limit."
          }
          contents += Content.AudioBytes(attachment.audioBytes)
        }
        is Attachment.AudioUri -> contents += Content.AudioBytes(readUri(context, attachment.uri))
        is Attachment.File -> {
          val uri = Uri.parse(attachment.path)
          val fileName = uri.lastPathSegment ?: File(attachment.path).name
          val bytes =
            if (uri.scheme == "content" || uri.scheme == "file") {
              readUri(context, attachment.path)
            } else {
              val file = File(attachment.path)
              require(file.isFile) { "Attached file is unavailable: ${attachment.path}" }
              require(file.length() <= MAX_ATTACHMENT_BYTES) {
                "Attached file exceeds the 16 MiB limit."
              }
              file.readBytes()
            }
          when {
            attachment.mimeType.startsWith("image/") -> contents += Content.ImageBytes(bytes)
            attachment.mimeType.startsWith("audio/") -> contents += Content.AudioBytes(bytes)
            attachment.mimeType.startsWith("text/") -> contents += Content.Text(
              "Attached text file ($fileName):\n" + bytes.toString(Charsets.UTF_8)
            )
            else -> error("Unsupported attachment type: ${attachment.mimeType}")
          }
        }
      }
    }
    if (request.query.isNotBlank()) contents += Content.Text(request.query)
    return Contents.of(contents)
  }

  private fun readUri(context: Context, uri: String): ByteArray {
    val stream = context.contentResolver.openInputStream(Uri.parse(uri))
      ?: error("Could not open attachment URI.")
    return stream.use { input ->
      val output = ByteArrayOutputStream()
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      var total = 0
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= MAX_ATTACHMENT_BYTES) {
          "Attached file exceeds the 16 MiB limit."
        }
        output.write(buffer, 0, count)
      }
      output.toByteArray()
    }
  }

  private fun Bitmap.toPngBytes(): ByteArray {
    val stream = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
  }

  private companion object {
    const val TAG = "NativeAgentRuntime"
    const val MAX_ATTACHMENT_BYTES = 16 * 1_024 * 1_024
  }
}
