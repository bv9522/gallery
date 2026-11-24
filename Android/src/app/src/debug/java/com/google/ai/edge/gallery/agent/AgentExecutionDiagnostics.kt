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

import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.agent.coordinator.AgentCapabilityGroups
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageCollapsableProgressPanel
import com.google.ai.edge.gallery.ui.common.chat.LogMessage
import com.google.ai.edge.gallery.ui.common.chat.LogMessageLevel
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel
import com.google.gson.JsonObject

/** Debug-build-only trace. It never enters prompts, model messages, or persisted history. */
internal object AgentExecutionDiagnostics {
  data class ToolCallSummary(val name: String, val argumentKeys: List<String>)
  private data class Entry(val level: LogMessageLevel, val source: String, val message: String)
  data class Snapshot(val status: String, val entries: List<Triple<LogMessageLevel, String, String>>)

  private val lock = Any()
  private var activeEntries: MutableList<Entry>? = null
  private var latest: Snapshot? = null
  private var currentVisibleTools: Set<String> = emptySet()
  private val nativeOperations by lazy { AgentCapabilityGroups.definitions.flatMap { it.operationNames }.toSet() }

  fun begin() = synchronized(lock) {
    activeEntries = mutableListOf()
    latest = null
    currentVisibleTools = emptySet()
  }

  fun recordRuntimeStage(stage: String, detail: String = "") {
    add(LogMessageLevel.Info, "Runtime", if (detail.isBlank()) stage else "$stage — $detail")
  }

  fun recordRuntimeFailure(stage: String, error: Throwable) {
    val category = when {
      error.message.orEmpty().contains("tool-call markers", ignoreCase = true) -> "unparsed_model_tool_markers"
      error.message.orEmpty().contains("context", ignoreCase = true) -> "context_or_prefill_failure"
      error is kotlinx.coroutines.CancellationException -> "cancelled"
      else -> error.javaClass.simpleName
    }
    add(LogMessageLevel.Error, "Runtime", "Failure stage=$stage, category=$category")
  }

  fun recordModelInput(modelTurn: Int, visibleTools: List<String>, nativeInputTokens: Int?) {
    synchronized(lock) { currentVisibleTools = visibleTools.toSet() }
    add(
      LogMessageLevel.Info,
      "Model input",
      "turn=$modelTurn, visibleTools=${visibleTools.size} [${visibleTools.joinToString()}], nativeInputTokens=${nativeInputTokens ?: "unavailable"}",
    )
  }

  fun recordModelOutput(
    modelTurn: Int,
    structuredCalls: List<ToolCallSummary>,
    contentChars: Int,
    channelNames: List<String>,
    unparsedToolMarkers: Boolean,
  ) {
    val calls = structuredCalls.joinToString { call ->
      val safeName = synchronized(lock) {
        if (call.name in currentVisibleTools) call.name else "unrecognized_tool"
      }
      val safeKeys = call.argumentKeys.mapNotNull(::safeIdentifier).distinct().take(20)
      "$safeName(keys=${safeKeys.joinToString()})"
    }
    val safeChannels = channelNames.mapNotNull(::safeIdentifier).distinct().take(10)
    val markerStatus = if (unparsedToolMarkers) "present" else "absent"
    val level = if (unparsedToolMarkers && structuredCalls.isEmpty()) LogMessageLevel.Warning else LogMessageLevel.Info
    add(
      level,
      "Model output",
      "turn=$modelTurn, structuredToolCalls=${structuredCalls.size} [${calls}], contentChars=$contentChars, channels=[${safeChannels.joinToString()}], unparsedToolMarkers=$markerStatus",
    )
  }

  fun recordToolDispatch(
    toolName: String,
    arguments: JsonObject,
    dispatchState: String,
    succeeded: Boolean,
    durationMs: Long,
    failureText: String?,
  ) {
    val route = when {
      toolName.startsWith("android_") -> "ANDROID_NATIVE"
      toolName == "execute_shell" -> "SHELL_ROUTER"
      toolName == "termux_api" -> "TERMUX_API"
      toolName.contains("mcp", ignoreCase = true) -> "MCP"
      toolName.contains("skill", ignoreCase = true) || toolName == "load_skill" -> "SKILLS"
      else -> "GALLERY_TOOL"
    }
    val safeName = synchronized(lock) {
      if (toolName in currentVisibleTools) toolName else "unrecognized_tool"
    }
    val backend = safeArgument(arguments, "backend")?.takeIf { it in setOf("TERMUX", "SHIZUKU", "TERMUX_RISH") }
    val operation = safeArgument(arguments, "operation")?.takeIf { it in nativeOperations }
    val safeKeys = arguments.keySet().mapNotNull(::safeIdentifier).distinct().take(20)
    val failureCategory = if (succeeded) null else classifyFailure(failureText.orEmpty())
    val details = buildList {
      add("route=$route")
      add("args=[${safeKeys.joinToString()}]")
      backend?.let { add("backend=$it") }
      operation?.let { add("operation=$it") }
      add("state=$dispatchState")
      add("success=$succeeded")
      add("durationMs=$durationMs")
      failureCategory?.let { add("failure=$it") }
    }.joinToString(", ")
    add(if (succeeded) LogMessageLevel.Info else LogMessageLevel.Warning, "Tool dispatch", "$safeName — $details")
  }

  fun recordProjection(
    groups: Set<String>,
    visibleToolCount: Int,
    replayMessageCount: Int,
    nativeTokenCount: Int?,
    succeeded: Boolean,
    failureCategory: String?,
    durationMs: Long,
  ) {
    val details = "groups=[${groups.sorted().joinToString()}], visibleTools=$visibleToolCount, " +
      "replayMessages=$replayMessageCount, nativeTokens=${nativeTokenCount ?: "unavailable"}, durationMs=$durationMs" +
      (failureCategory?.let { ", failure=$it" } ?: "")
    add(
      if (succeeded) LogMessageLevel.Info else LogMessageLevel.Warning,
      "Conversation projection",
      if (succeeded) "Rebuild succeeded — $details" else "Rebuild failed — $details",
    )
  }

  fun finish(status: String) {
    synchronized(lock) {
      val entries = activeEntries ?: return
      entries += Entry(if (status == "completed") LogMessageLevel.Info else LogMessageLevel.Warning, "Runtime", "Run $status")
      latest = Snapshot(status, entries.map { Triple(it.level, it.source, it.message) })
      activeEntries = null
    }
  }

  fun consume(): Snapshot? = synchronized(lock) { latest.also { latest = null } }

  private fun add(level: LogMessageLevel, source: String, message: String) {
    synchronized(lock) { activeEntries?.add(Entry(level, source, message)) }
  }

  private fun safeArgument(arguments: JsonObject, name: String): String? {
    val value = arguments.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
    return safeIdentifier(value)
  }

  private fun safeIdentifier(value: String): String? =
    value.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,80}")) }

  private fun classifyFailure(text: String): String = when {
    text.contains("approval", ignoreCase = true) && text.contains("denied", ignoreCase = true) -> "approval_denied"
    text.contains("permission", ignoreCase = true) -> "permission"
    text.contains("timeout", ignoreCase = true) -> "timeout"
    text.contains("cancel", ignoreCase = true) -> "cancelled"
    else -> "tool_reported_failure"
  }
}

/** Release source set provides a no-op counterpart; this UI exists only in debug APKs. */
internal object AgentExecutionDiagnosticsUi {
  fun appendIfAvailable(viewModel: LlmChatViewModel, model: Model) {
    val snapshot = AgentExecutionDiagnostics.consume() ?: return
    viewModel.addMessage(
      model,
      ChatMessageCollapsableProgressPanel(
        title = "Execution Details",
        inProgress = false,
        accelerator = model.currentAccelerator?.name.orEmpty(),
        logMessages = snapshot.entries.map { (level, source, message) ->
          LogMessage(level = level, source = source, message = message)
        },
      ),
    )
  }
}
