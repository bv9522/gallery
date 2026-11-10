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

/**
 * Bounds model-facing tool output before it enters retained conversation history.
 *
 * Large outputs retain both their beginning and end with an explicit truncation marker. A single
 * oversized listing or shell command therefore cannot consume an unbounded portion of the model's
 * context. Budgets reset for each user turn; the coordinator's existing turn limit still bounds
 * repeated tool-call loops.
 */
class AgentToolResultBudget(
  private val maxCharsPerTool: Int = DEFAULT_MAX_CHARS_PER_TOOL,
  private val maxCharsPerTurn: Int = DEFAULT_MAX_CHARS_PER_TURN,
) {
  private var usedChars: Int = 0

  init {
    require(maxCharsPerTool > 0) { "Per-tool result budget must be positive." }
    require(maxCharsPerTurn > 0) { "Per-turn result budget must be positive." }
  }

  fun reset() {
    usedChars = 0
  }

  fun constrain(content: String): String {
    val remaining = (maxCharsPerTurn - usedChars).coerceAtLeast(0)
    val allowance = minOf(maxCharsPerTool, remaining)
    if (content.length <= allowance) {
      usedChars += content.length
      return content
    }

    // Never exceed the configured per-turn ceiling merely to append an omission message.
    // With no remaining allowance, an empty result is the only strictly bounded representation.
    if (allowance == 0) return ""

    val marker = "\n...[TRUNCATED original_chars=${content.length}; output budget applied]...\n"
    if (allowance <= marker.length) {
      // Do not emit a misleading partial marker such as "TRUNCAT".
      val compact = if (allowance >= "[TRUNCATED]".length) "[TRUNCATED]" else ""
      // Reserve the remaining allowance even when it is too small to hold a complete marker.
      usedChars += allowance
      return compact
    }

    val retained = allowance - marker.length
    val prefixLength = (retained + 1) / 2
    val suffixLength = retained - prefixLength
    val bounded = content.take(prefixLength) + marker +
      if (suffixLength > 0) content.takeLast(suffixLength) else ""
    usedChars += bounded.length
    return bounded
  }

  companion object {
    const val DEFAULT_MAX_CHARS_PER_TOOL = 6_000
    const val DEFAULT_MAX_CHARS_PER_TURN = 12_000
  }
}
