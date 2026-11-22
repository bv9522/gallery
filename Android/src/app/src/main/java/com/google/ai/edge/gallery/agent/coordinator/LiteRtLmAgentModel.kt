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
  fun hasUnparsedToolCallMarkers(
    content: String,
    channels: Map<String, String> = emptyMap(),
  ): Boolean =
    containsNativeToolCallMarkers(content) || channels.values.any(::containsNativeToolCallMarkers)

  /**
   * Produces a privacy-safe protocol summary. Argument values, message text, and thinking text are
   * intentionally excluded; names, keys, channel names, and lengths are sufficient for diagnosis.
   */
  fun summarizeResponse(
    content: String,
    toolCalls: List<ToolCall>,
    channels: Map<String, String>,
  ): String {
    val calls = toolCalls.joinToString(prefix = "[", postfix = "]", separator = ", ") { call ->
      "${call.name}(${call.arguments.keys.sorted().joinToString(",")})"
    }
    val channelLengths = channels.toSortedMap().entries.joinToString(prefix = "[", postfix = "]") {
      "${it.key}:${it.value.length}"
    }
    return "contentLength=${content.length}, structuredToolCalls=${toolCalls.size}$calls, " +
      "channels=$channelLengths, nativeToolMarkers=" +
      hasUnparsedToolCallMarkers(content, channels)
  }

  private fun containsNativeToolCallMarkers(value: String): Boolean =
    value.contains("<|tool_call>") || value.contains("<tool_call|>")
}

/**
 * Builds one LiteRT-LM tool-role message containing each tool response and any associated
 * multimodal image parts. Image parts precede their textual tool response.
 */
internal fun buildLiteRtToolResponseMessage(toolMessages: List<AgentMessage>): Message {
  require(toolMessages.isNotEmpty()) { "A tool response message must not be empty." }
  val contents = toolMessages.flatMap { message ->
    val name = message.toolName ?: error("Tool result is missing its tool name.")
    buildList<Content> {
      message.toolImageBytes?.let { add(Content.ImageBytes(it)) }
      add(Content.ToolResponse(name, message.content))
    }
  }
  return Message.tool(Contents.of(contents))
}

internal data class AgentConversationTurn(
  val messages: MutableList<Message> = mutableListOf(),
  var complete: Boolean = false,
)

/**
 * Validates the complete activation tool-result batch before changing the model-visible schema set.
 * A partial or fabricated result must never activate only a subset of a requested batch.
 */
internal object AgentCapabilityActivationPolicy {
  fun markActivationFailure(
    toolResults: List<AgentMessage>,
    note: String,
  ): List<AgentMessage> = toolResults.map { result ->
    if (result.toolName == "activate_capability_group") {
      result.copy(content = result.content + note, toolSucceeded = false)
    } else {
      result
    }
  }

  fun requestedGroups(
    conversationMessages: List<AgentMessage>,
    toolResults: List<AgentMessage>,
  ): Set<String>? {
    if (toolResults.isEmpty() || toolResults.any {
      it.role != AgentMessage.Role.TOOL ||
        it.toolName != "activate_capability_group" ||
        it.toolSucceeded != true ||
        it.toolCallId.isNullOrBlank()
    }) {
      return null
    }
    val callIds = toolResults.map { it.toolCallId!! }
    if (callIds.size != callIds.toSet().size) return null
    val calls = conversationMessages.filter {
      it.role == AgentMessage.Role.ASSISTANT &&
        it.toolName == "activate_capability_group" &&
        it.toolCallId != null && it.toolCallId in callIds
    }
    if (calls.size != callIds.size) return null
    val groups = calls.map { call ->
      val id = call.toolArguments
        ?.get("group_id")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        ?: return null
      if (AgentCapabilityGroups.find(id) == null || id == AgentCapabilityGroups.CORE) {
        return null
      }
      id
    }
    return groups.toSet()
  }
}

/** Pure history policy: compaction removes whole, completed turns only. */
internal object AgentConversationHistoryPolicy {
  /** Snapshot all retained turns in their original message order, including the active turn. */
  fun replayMessages(turns: List<AgentConversationTurn>): List<Message> =
    turns.flatMap { it.messages }
}

internal object AgentConversationReplacementPolicy {
  data class Candidate<T>(val value: T?, val failure: Exception?)

  /** A candidate is publishable only after creation and validation both succeed. */
  fun <T> createValidated(
    create: () -> T,
    validate: (T) -> Unit,
    closeRejected: (T) -> Unit,
  ): Candidate<T> {
    var candidate: T? = null
    return try {
      val value = create().also { candidate = it }
      validate(value)
      Candidate(value, null)
    } catch (error: Exception) {
      candidate?.let { rejected -> runCatching { closeRejected(rejected) } }
      Candidate(null, error)
    }
  }
}

internal object AgentConversationCompactionPolicy {
  fun shouldCompact(tokenCount: Int, threshold: Int): Boolean = tokenCount >= threshold

  fun dropOldestCompletedTurn(
    turns: List<AgentConversationTurn>,
  ): List<AgentConversationTurn>? {
    if (turns.size <= 1 || !turns.first().complete) return null
    return turns.drop(1)
  }

  fun isContextOverflow(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any { cause ->
      val message = cause.message.orEmpty().lowercase()
      message.contains("input token ids are too long") ||
        message.contains("exceeding the maximum number of tokens")
    }
}

class LiteRtLmAgentModel(
  private var conversation: Conversation,
  private val conversationFactory: (List<Message>) -> Conversation,
  private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
  private val onConversationClosed: ((Conversation) -> Unit)? = null,
  private val compactAtTokenCount: Int = 3_000,
  private val compactToTokenCount: Int = 2_400,
  private val fullToolRegistry: AgentToolRegistry? = null,
  initialVisibleToolRegistry: AgentToolRegistry? = null,
  private val groupedConversationFactory: ((List<Message>, AgentToolRegistry) -> Conversation)? = null,
  initialGroupIds: Set<String> = emptySet(),
) : AgentModel, Closeable {
  private val mutex = Mutex()
  private var consumedMessageCount = 0
  private var modelTurn = 0
  private val conversationTurns = mutableListOf<AgentConversationTurn>()
  private var visibleToolRegistry: AgentToolRegistry? = initialVisibleToolRegistry
  private val activeGroupIds = initialGroupIds.toMutableSet()
  @Volatile private var closed = false
  private val registeredToolSummaries: List<String> =
    conversation.toolManager.getToolsDescription().mapNotNull { entry ->
      val function = entry.asJsonObject.getAsJsonObject("function") ?: return@mapNotNull null
      val name = function.get("name")?.asString ?: return@mapNotNull null
      val schema = function.getAsJsonObject("parameters")
      val properties = schema?.getAsJsonObject("properties")?.keySet()?.sorted().orEmpty()
      val required = schema?.getAsJsonArray("required")?.map { it.asString }?.sorted().orEmpty()
      "$name(properties=${properties.joinToString(",")},required=${required.joinToString(",")})"
    }.sorted()

  init {
    require(!conversation.automaticToolCalling) {
      "Agent conversations must disable automatic tool calling."
    }
    Log.i(
      TAG,
      "Conversation ready: automaticToolCalling=${conversation.automaticToolCalling}, " +
        "registeredTools=${registeredToolSummaries.joinToString(prefix = "[", postfix = "]")}",
    )
  }

  /** Returns the native token count for the current prefetched conversation. */
  suspend fun currentTokenCount(): Int = mutex.withLock { conversation.getTokenCount() }

  /** Current model-visible names, including the always-registered Gallery legacy tools. */
  fun visibleToolNames(): Set<String> = conversation.toolManager.getToolsDescription().mapNotNull { entry ->
    entry.asJsonObject.getAsJsonObject("function")?.get("name")?.asString
  }.toSet()

  /** Deterministic routing selects one group; no match leaves the current projection unchanged. */
  suspend fun selectGroup(groupId: String): Boolean = mutex.withLock {
    switchGroupsLocked(setOf(groupId))
  }

  /** Model-driven activation adds one group to the current task's projection. */
  suspend fun activateGroup(groupId: String): Boolean = mutex.withLock {
    switchGroupsLocked(activeGroupIds + groupId)
  }

  private suspend fun switchGroupsLocked(groupIds: Set<String>): Boolean {
    val full = fullToolRegistry ?: return false
    if (groupedConversationFactory == null) return false
    if (groupIds.any { AgentCapabilityGroups.find(it) == null || it == AgentCapabilityGroups.CORE }) return false
    val projected = AgentCapabilityGroups.project(full, groupIds)
    if (projected.names() == visibleToolRegistry?.names()) {
      activeGroupIds.clear()
      activeGroupIds.addAll(groupIds)
      return true
    }
    val retainedMessages = AgentConversationHistoryPolicy.replayMessages(conversationTurns)
    var replacementTokenCount = -1
    val switchStartedAtNanos = System.nanoTime()
    val candidate = withContext(ioDispatcher) {
      AgentConversationReplacementPolicy.createValidated(
        create = { groupedConversationFactory(retainedMessages, projected) },
        validate = { replacementTokenCount = it.getTokenCount() },
        closeRejected = { failedCandidate ->
          runCatching { onConversationClosed?.invoke(failedCandidate) }
            .onFailure { Log.w(TAG, "Failed candidate cleanup callback failed.", it) }
          runCatching { failedCandidate.close() }
            .onFailure { Log.w(TAG, "Failed to close rejected capability conversation.", it) }
        },
      )
    }
    val replacement = candidate.value ?: run {
      Log.w(
        TAG,
        "Capability conversation rebuild failed; retaining the active conversation.",
        candidate.failure,
      )
      return false
    }
    val previous = conversation
    conversation = replacement
    visibleToolRegistry = projected
    activeGroupIds.clear()
    activeGroupIds.addAll(groupIds)
    runCatching { onConversationClosed?.invoke(previous) }
      .onFailure { Log.w(TAG, "Previous capability conversation cleanup callback failed.", it) }
    runCatching { previous.close() }
      .onFailure { Log.w(TAG, "Failed to close previous capability conversation.", it) }
    Log.i(
      TAG,
      "Capability group projection changed: groups=${activeGroupIds.sorted()}, " +
        "visibleNativeTools=${projected.names().size}, retainedTurns=${conversationTurns.size}, " +
        "nativeTokenCount=$replacementTokenCount, " +
        "switchLatencyMs=${(System.nanoTime() - switchStartedAtNanos) / 1_000_000}",
    )
    return true
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

      var message =
        if (userMessages.isNotEmpty()) {
          userMessages.single().userContents?.let(Message::user)
            ?: Message.user(userMessages.single().content)
        } else {
          buildLiteRtToolResponseMessage(toolMessages)
        }
      var replayToolMessages = toolMessages
      val activationResults = toolMessages.filter {
        it.toolName == "activate_capability_group"
      }
      if (activationResults.isNotEmpty()) {
        val requestedGroups = AgentCapabilityActivationPolicy.requestedGroups(
          conversationMessages = messages,
          toolResults = activationResults,
        )
        val activated = !requestedGroups.isNullOrEmpty() &&
          switchGroupsLocked(activeGroupIds + requestedGroups)
        if (!activated) {
          val note = "\\nCapability group activation failed; the previous toolset remains active."
          replayToolMessages = AgentCapabilityActivationPolicy.markActivationFailure(
            toolMessages,
            note,
          )
          message = buildLiteRtToolResponseMessage(replayToolMessages)
        }
      }
      // Replay history deliberately omits transient image/audio payloads. Tool screenshot bytes
      // are erased after inference, so retaining them here would replay zeroed or private data.
      val replayMessage = if (userMessages.isNotEmpty()) {
        val user = userMessages.single()
        if (user.userContents != null) {
          Message.user(
            user.content.ifBlank {
              "[A prior multimodal attachment was omitted during context compaction.]"
            }
          )
        } else {
          message
        }
      } else {
        Message.tool(
          Contents.of(replayToolMessages.map { toolResult ->
            val name = toolResult.toolName ?: error("Tool result is missing its tool name.")
            val replayText = if (toolResult.toolImageBytes != null) {
              toolResult.content +
                "\n[Transient image payload omitted during context compaction.]"
            } else {
              toolResult.content
            }
            Content.ToolResponse(name, replayText)
          })
        )
      }
      val turn = if (userMessages.isNotEmpty()) {
        AgentConversationTurn().also(conversationTurns::add)
      } else {
        conversationTurns.lastOrNull()?.takeIf { !it.complete }
          ?: error("Tool results have no active user turn.")
      }
      val turnExtraContext = userMessages.singleOrNull()?.extraContext.orEmpty()
      val thinkingRequested = turnExtraContext["enable_thinking"]?.toString() ?: "not-provided"
      Log.i(
        TAG,
        "Turn request: turn=$modelTurn, thinkingRequested=$thinkingRequested, " +
          "toolResultCount=${toolMessages.size}",
      )
      val response = try {
        sendMessageCancellable(message, extraContext = turnExtraContext)
      } catch (error: Throwable) {
        if (AgentConversationCompactionPolicy.isContextOverflow(error)) {
          throw IllegalStateException(
            "This turn is larger than the model's available context. No tool was executed for " +
              "this failed inference. Shorten the request or start a new agent conversation.",
            error,
          )
        }
        throw error
      }

      // Keep native structured tool calls and their corresponding tool-role responses together.
      turn.messages += replayMessage
      turn.messages += response
      if (response.toolCalls.isEmpty()) {
        turn.complete = true
        compactCompletedHistory()
      }

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
              "Registered tool schemas: ${registeredToolSummaries.joinToString(prefix = "[", postfix = "]")}."
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
   * Rebuilds the native KV cache from complete recent turns when it approaches the configured
   * context budget. The active/latest turn is never dropped; tool-call and tool-result messages
   * remain grouped inside their original turn.
   */
  private suspend fun compactCompletedHistory() = withContext(ioDispatcher) {
    var tokenCount = try {
      conversation.getTokenCount()
    } catch (error: Exception) {
      Log.w(TAG, "Unable to read native context token count; leaving conversation unchanged.", error)
      return@withContext
    }
    if (!AgentConversationCompactionPolicy.shouldCompact(tokenCount, compactAtTokenCount)) {
      return@withContext
    }

    while (tokenCount > compactToTokenCount) {
      val retainedTurns =
        AgentConversationCompactionPolicy.dropOldestCompletedTurn(conversationTurns) ?: break
      val retainedMessages = AgentConversationHistoryPolicy.replayMessages(retainedTurns)
      val candidate = AgentConversationReplacementPolicy.createValidated(
        create = {
          val groupedFactory = groupedConversationFactory
          val selectedRegistry = visibleToolRegistry
          if (groupedFactory != null && selectedRegistry != null) {
            groupedFactory(retainedMessages, selectedRegistry)
          } else {
            conversationFactory(retainedMessages)
          }
        },
        validate = { it.getTokenCount() },
        closeRejected = { failedCandidate ->
          runCatching { onConversationClosed?.invoke(failedCandidate) }
          runCatching { failedCandidate.close() }
        },
      )
      val replacementTokenCount = candidate.value?.getTokenCount() ?: run {
        Log.w(
          TAG,
          "Native conversation rebuild or validation failed; retaining the existing conversation.",
          candidate.failure,
        )
        break
      }
      // Commit the replacement only after it has been created and its native token count read.
      // A failed candidate must never displace the last known-good conversation or drop history.
      val previous = conversation
      conversation = candidate.value ?: error("Validated replacement conversation is missing.")
      conversationTurns.removeAt(0)
      runCatching { onConversationClosed?.invoke(previous) }
        .onFailure { Log.w(TAG, "Previous conversation cleanup callback failed.", it) }
      runCatching { previous.close() }
        .onFailure { Log.w(TAG, "Failed to close compacted native conversation.", it) }
      tokenCount = replacementTokenCount
      Log.i(
        TAG,
        "Native context compacted: tokenCount=$tokenCount, retainedTurns=${conversationTurns.size}",
      )
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
      onConversationClosed?.invoke(conversation)
    }
  }

  companion object {
    private const val TAG = "LiteRtLmAgentModel"
    /**
     * Builds description-only LiteRT-LM providers. Their execute methods deliberately fail closed:
     * automatic execution is forbidden and the AgentCoordinator is the only tool dispatcher.
     */
    fun toolProviders(registry: AgentToolRegistry): List<ToolProvider> {
      val tools = registry.all()
      var totalCharacters = 0
      var totalUtf8Bytes = 0
      val providers = tools.map { agentTool ->
        val definition = JsonObject().apply {
          addProperty("name", agentTool.name)
          addProperty("description", agentTool.description)
          add("parameters", agentTool.parametersSchema.deepCopy())
        }
        val serialized = definition.toString()
        val bytes = serialized.toByteArray(Charsets.UTF_8).size
        totalCharacters += serialized.length
        totalUtf8Bytes += bytes
        Log.i(
          TAG,
          "Native agent tool schema: name=${agentTool.name}, chars=${serialized.length}, utf8Bytes=$bytes",
        )
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
      Log.i(
        TAG,
        "Native agent tool schema totals: count=${tools.size}, chars=$totalCharacters, utf8Bytes=$totalUtf8Bytes",
      )
      return providers
    }
  }
}