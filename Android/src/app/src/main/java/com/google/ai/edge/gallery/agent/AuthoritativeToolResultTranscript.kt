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

import com.google.ai.edge.gallery.agent.coordinator.AgentMessage

/**
 * Returns the exact, already-budgeted output of native device and shell tools for the current turn.
 *
 * The model's final answer is intentionally not used as a source for these values: small local
 * models can alter identifiers, numbers, separators, or truncation notices while paraphrasing.
 */
internal fun formatAuthoritativeToolResults(messages: List<AgentMessage>): String? {
  val currentTurnStart = messages.indexOfLast { it.role == AgentMessage.Role.USER }
  if (currentTurnStart < 0) return null

  val results = messages.drop(currentTurnStart + 1).filter { message ->
    message.role == AgentMessage.Role.TOOL &&
      message.toolName?.let { it in AUTHORITATIVE_TOOL_NAMES } == true
  }
  if (results.isEmpty()) return null

  return buildString {
    appendLine("Verified tool results (exact bounded output returned by the tools):")
    results.forEach { result ->
      appendLine("--- ${result.toolName} ---")
      appendLine(result.content.ifEmpty { "[No output remained within the turn result budget.]" })
    }
  }.trimEnd()
}

private val AUTHORITATIVE_TOOL_NAMES = setOf("android_control", "execute_shell")
