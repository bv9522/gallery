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
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModelBase
import com.google.gson.JsonObject

/** Release-build no-op. The actual diagnostics implementation exists only in src/debug. */
internal object AgentExecutionDiagnostics {
  data class ToolCallSummary(val name: String, val argumentKeys: List<String>)
  fun begin() = Unit
  fun recordRuntimeStage(stage: String, detail: String = "") = Unit
  fun recordRuntimeFailure(stage: String, error: Throwable) = Unit
  fun recordModelInput(modelTurn: Int, visibleTools: List<String>, nativeInputTokens: Int?) = Unit
  fun recordModelOutput(modelTurn: Int, structuredCalls: List<ToolCallSummary>, contentChars: Int, channelNames: List<String>, unparsedToolMarkers: Boolean) = Unit
  fun recordToolDispatch(toolName: String, arguments: JsonObject, dispatchState: String, succeeded: Boolean, durationMs: Long, failureText: String?) = Unit
  fun recordProjection(groups: Set<String>, visibleToolCount: Int, replayMessageCount: Int, nativeTokenCount: Int?, succeeded: Boolean, failureCategory: String?, durationMs: Long) = Unit
  fun finish(status: String) = Unit
}

internal object AgentExecutionDiagnosticsUi {
  fun appendIfAvailable(viewModel: LlmChatViewModelBase, model: Model) = Unit
}
