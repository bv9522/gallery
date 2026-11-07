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

import com.google.gson.JsonObject
import com.google.ai.edge.litertlm.Contents

/** A model-facing conversation entry independent of any inference engine. */
data class AgentMessage(
  val role: Role,
  val content: String = "",
  val toolCallId: String? = null,
  val toolName: String? = null,
  val toolArguments: JsonObject? = null,
  val toolSucceeded: Boolean? = null,
  val userContents: Contents? = null,
) {
  enum class Role { USER, ASSISTANT, TOOL }
}

/** One response from an agent model: a final answer or one or more structured tool requests. */
sealed interface AgentModelResponse {
  data class FinalAnswer(val text: String) : AgentModelResponse

  /** Retained for simple adapters and existing deterministic tests. */
  data class ToolCall(
    val name: String,
    val arguments: JsonObject,
    val callId: String,
  ) : AgentModelResponse

  /** A model may request several independent tools in one inference turn. */
  data class ToolCalls(val calls: List<ToolCall>) : AgentModelResponse {
    init {
      require(calls.isNotEmpty()) { "A tool-call batch must not be empty." }
    }
  }
}

/**
 * Adapter implemented by a real model runtime or a deterministic test double.
 *
 * Implementations return structured tool requests; the coordinator remains the sole tool executor.
 */
fun interface AgentModel {
  suspend fun generate(messages: List<AgentMessage>): AgentModelResponse
}
