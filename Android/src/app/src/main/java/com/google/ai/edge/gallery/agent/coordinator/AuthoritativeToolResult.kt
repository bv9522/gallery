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

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/**
 * A bounded tool result captured independently of model-generated text for one coordinator run.
 *
 * [rawContent] is the exact result text after the existing per-tool/per-turn budget is applied.
 * [structuredValue] is populated only when the content is an object/array JSON document, or when
 * the tool explicitly supplies a matching scalar [AgentToolResult.structuredContent] value.
 */
data class AuthoritativeToolResult(
  val toolCallId: String,
  val toolName: String,
  val succeeded: Boolean,
  val rawContent: String,
  val structuredValue: JsonElement?,
) {
  /** Failed, rejected, or budget-rejected results are never authoritative facts. */
  val isEligibleForFacts: Boolean
    get() = succeeded

  companion object {
    internal fun capture(
      call: AgentModelResponse.ToolCall,
      result: AgentToolResult,
      boundedContent: String,
    ): AuthoritativeToolResult {
      val structured = explicitStructuredValue(result, boundedContent)
        ?: parseStructuredObjectOrArray(boundedContent)
      return AuthoritativeToolResult(
        toolCallId = call.callId,
        toolName = call.name,
        succeeded = result.isSuccessful,
        rawContent = boundedContent,
        structuredValue = structured?.deepCopy(),
      )
    }

    private fun explicitStructuredValue(
      result: AgentToolResult,
      boundedContent: String,
    ): JsonElement? {
      val explicit = result.structuredContent ?: return null
      // The side channel must describe exactly the bounded text sent to the model.
      if (explicit.toString() != boundedContent) return null
      return explicit
    }

    private fun parseStructuredObjectOrArray(content: String): JsonElement? =
      runCatching { JsonParser.parseString(content) }.getOrNull()
        ?.takeIf { it.isJsonObject || it.isJsonArray }
  }
}
