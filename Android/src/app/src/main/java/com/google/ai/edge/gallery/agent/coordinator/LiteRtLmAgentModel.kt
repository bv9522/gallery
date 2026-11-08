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

package com.google.ai.edge.gallery.agent.coordinator

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Model-neutral coordinator adapter for a manually-driven LiteRT-LM conversation.
 *
 * The conversation must be created with automaticToolCalling=false and the providers returned by
 * [toolProviders]. This adapter never executes a tool; it only translates structured requests and
 * tool responses. The owning caller closes this adapter when the agent run ends. Closing it closes
 * the Conversation, not its shared Engine.
 *
 * Create one adapter per agent run because LiteRT-LM Conversation instances retain their own
 * message/KV-cache history.
 */
@OptIn(ExperimentalApi::class)
internal object LiteRtLmResponseProtocol {
  /** Native Gemma tool-call delimiters must be parsed into Message.toolCalls, never executed as text. */
  fun hasUnparsedToolCallMarkers(content: String): Boolean =
    content.contains("<|tool_call>") || content.contains("<tool_call|")
}

class LiteRtLmAgentModel(
  private val conversation: Conversation,
  private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
  private val onClosed: (() -> Unit)? = null,
) : AgentModel, Closeable {
  private val mutex = Mutex()
  private var consumedMessageCount = 0
  private var modelTurn = 0
  @Volatile private var closed = false
  private val registeredToolNames: List<String> =
    conversation.toolManager.getToolsDescription().mapNotNull { entry ->
      entry.asJsonObject.getAsJsonObject("function")?.get("name")?.asString
    }.sorted()

  init {
    require(!conversation.automaticToolCalling) {
      "Agent conversations must disable automatic tool calling."
    }
    Log.i(
      TAG,
      "Conversation ready: automaticToolCalling=${conversation.automaticToolCalling}, " +
        "registeredTools=${registeredToolNames.joinToString(prefix = "[", postfix = "]")}",
    )
  }

  override suspend fun generate(messages: List<AgentMessage>): AgentModelResponse =
    mutex.withLock {
      check(!closed) { "Agent conversation is closed." }
      require(messages.size >= consumedMessageCount) {
        "Agent message history cannot move backwards."
      }
      val pending = messages.drop(consumedMessageCount)
      consumedMessageCount = messages.size
      modelTurn++

      val userMessages = pending.filter { it.role == AgentMessage.Role.USER }
      val toolMessages = pending.filter { it.role == AgentMessage.Role.TOOL }
      require(
        (userMessages.size == 1 && toolMessages.isEmpty()) ||
          (userMessages.isEmpty() && toolMessages.isNotEmpty())
      ) {
        "Expected one new user message or a non-empty tool-result batch; got " +
          "${userMessages.size} user and ${toolMessages.size} tool messages."
      }

      val message =
        if (userMessages.isNotEmpty()) {
          userMessages.single().userContents?.let(Message::user)
            ?: Message.user(userMessages.single().content)
        } else {
          val toolResponses = toolMessages.map { message ->
            val name = message.toolName
              ?: error("Tool result is missing its tool name.")
            Content.ToolResponse(name, message.content)
          }
          Message.tool(Contents.of(toolResponses))
        }
      val response = sendMessageCancellable(
        message,
        extraContext = userMessages.singleOrNull()?.extraContext.orEmpty(),
      )

      val content = response.toString()
      val responseSummary = LiteRtLmResponseProtocol.summarizeResponse(
        content = content,
        toolCalls = response.toolCalls,
        channels = response.channels,
      )
      Log.i(TAG, "Response protocol: turn=$modelTurn, $responseSummary")

      if (response.toolCalls.isNotEmpty()) {
        val calls = response.toolCalls.mapIndexed { index, call ->
          AgentModelResponse.ToolCall(
            name = call.name,
            arguments = Gson().toJsonTree(call.arguments).asJsonObject,
            callId = "litert-${modelTurn}-${index + 1}",
          )
        }
        AgentModelResponse.ToolCalls(calls)
      } else {
        if (LiteRtLmResponseProtocol.hasUnparsedToolCallMarkers(content, response.channels)) {
          val failure =
            "The model emitted native tool-call markers, but LiteRT-LM did not return a structured " +
              "toolCalls entry. No tool was executed. Protocol diagnostics: $responseSummary. " +
              "Registered tools: ${registeredToolNames.joinToString(prefix = "[", postfix = "]")}."
          Log.e(TAG, failure)
          throw IllegalStateException(failure)
        }
        if (content.isBlank() && response.channels.isNotEmpty()) {
          val failure =
            "LiteRT-LM returned channel-only output without a final content message or structured " +
              "tool call. No tool was executed. Protocol diagnostics: $responseSummary."
          Log.e(TAG, failure)
          throw IllegalStateException(failure)
        }
        AgentModelResponse.FinalAnswer(content)
      }
    }

  /**
   * LiteRT-LM's synchronous sendMessage() is not coroutine-aware. Dispatch it to IO and attach
   * cancellation directly to Conversation.cancelProcess(), so cancelling AgentCoordinator.run()
   * interrupts native generation rather than merely abandoning the waiting coroutine.
   */
  private suspend fun sendMessageCancellable(
    message: Message,
    extraContext: Map<String, Any> = emptyMap(),
  ): Message =
    suspendCancellableCoroutine { continuation ->
      ioDispatcher.dispatch(continuation.context, Runnable {
        try {
          val response = conversation.sendMessage(message, extraContext = extraContext)
          if (continuation.isActive) continuation.resume(response)
        } catch (error: Throwable) {
          if (continuation.isActive) continuation.resumeWithException(error)
        }
      })
      continuation.invokeOnCancellation {
        runCatching { conversation.cancelProcess() }
      }
    }

  /**
   * Adapts the legacy Gallery tools registered on this manual conversation's ToolManager. The
   * coordinator remains the only dispatcher; the ToolManager is used only as the existing
   * reflection-backed invocation adapter for Gallery ToolDefinitions.
   */
  fun toolRegistry(additionalTools: AgentToolRegistry = AgentToolRegistry(emptyList())): AgentToolRegistry {
    val legacyTools = conversation.toolManager.getToolsDescription().map { entry ->
      val function = entry.asJsonObject.getAsJsonObject("function")
      val toolName = function.get("name").asString
      val toolDescription = function.get("description")?.asString.orEmpty()
      val schema = function.getAsJsonObject("parameters")?.deepCopy() ?: JsonObject()
      object : AgentTool {
        override val name: String = toolName
        override val description: String = toolDescription
        override val parametersSchema: JsonObject = schema

        override suspend fun execute(arguments: JsonObject): AgentToolResult {
          val result = withContext(Dispatchers.IO) {
            conversation.toolManager.execute(toolName, arguments)
          }
          val content =
            if (result is JsonPrimitive && result.isString) result.asString else result.toString()
          val failed =
            (result.isJsonObject && (
              result.asJsonObject.get("status")?.asString == "failed" ||
                result.asJsonObject.has("error")
            )) ||
              (result is JsonPrimitive && result.isString &&
                result.asString.startsWith("Error occured."))
          return AgentToolResult(content, !failed)
        }
      }
    }
    val preferredNames = additionalTools.names()
    return AgentToolRegistry(
      additionalTools.all() + legacyTools.filterNot { it.name in preferredNames }
    )
  }

  /** Interrupts native inference without closing the shared model engine. */
  fun interrupt() {
    if (!closed && conversation.isAlive) {
      runCatching { conversation.cancelProcess() }
    }
  }

  override fun close() {
    if (closed) return
    closed = true
    try {
      if (conversation.isAlive) {
        // Also cancel before closing: this is the final safety net for lifecycle teardown.
        runCatching { conversation.cancelProcess() }
        runCatching { conversation.close() }
      }
    } finally {
      onClosed?.invoke()
    }
  }

  companion object {
    private const val TAG = "LiteRtLmAgentModel"
    /**
     * Builds description-only LiteRT-LM providers. Their execute methods deliberately fail closed:
     * automatic execution is forbidden and the AgentCoordinator is the only tool dispatcher.
     */
    fun toolProviders(registry: AgentToolRegistry): List<ToolProvider> =
      registry.all().map { agentTool ->
        val definition = JsonObject().apply {
          addProperty("name", agentTool.name)
          addProperty("description", agentTool.description)
          add("parameters", agentTool.parametersSchema.deepCopy())
        }
        tool(
          object : OpenApiTool {
            override fun getToolDescriptionJsonString(): String = definition.toString()

            override fun execute(paramsJsonString: String): String =
              throw UnsupportedOperationException(
                "LiteRT-LM automatic tool execution is disabled; use AgentCoordinator."
              )
          }
        )
      }
  }
}
