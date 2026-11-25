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
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive

/**
 * Deterministically renders bounded source values without consulting model-generated prose.
 *
 * The renderer is provider-neutral. Callers supply the presentation allowlist separately so a
 * future sensitive provider cannot become user-visible merely because it returns JSON.
 */
internal object AuthoritativeToolResultRenderer {
  fun render(
    results: List<AuthoritativeToolResult>,
    allowedToolNames: Set<String>,
  ): String? {
    val visibleResults = results.filter {
      it.isEligibleForFacts && it.toolName in allowedToolNames
    }
    if (visibleResults.isEmpty()) return null

    return buildString {
      appendLine("Authoritative tool facts (exact bounded source values):")
      visibleResults.forEachIndexed { index, result ->
        if (index > 0) appendLine()
        appendLine("--- ${result.toolName} (call ${result.toolCallId}) ---")
        val structured = result.structuredValue
        if (structured == null) {
          appendRawText(result.rawContent)
        } else {
          appendStructuredValue(structured)
        }
      }
    }.trimEnd()
  }

  private fun StringBuilder.appendRawText(content: String) {
    if (content.isEmpty()) {
      appendLine("output = \"\"")
      return
    }
    content.lineSequence().forEach { line ->
      appendLine("output | $line")
    }
  }

  private fun StringBuilder.appendStructuredValue(value: JsonElement) {
    when (value) {
      is JsonObject -> {
        if (value.size() == 0) appendLine("value = {}")
        value.entrySet().forEach { (name, child) ->
          appendValueAt(name, child)
        }
      }
      is JsonArray -> {
        if (value.size() == 0) appendLine("value = []")
        value.forEachIndexed { index, child -> appendValueAt("[$index]", child) }
      }
      else -> appendValueAt("value", value)
    }
  }

  private fun StringBuilder.appendValueAt(path: String, value: JsonElement) {
    when (value) {
      is JsonObject -> {
        if (value.size() == 0) appendLine("$path = {}")
        value.entrySet().forEach { (name, child) ->
          appendValueAt(if (path.isEmpty()) name else "$path.$name", child)
        }
      }
      is JsonArray -> {
        if (value.size() == 0) appendLine("$path = []")
        value.forEachIndexed { index, child ->
          appendValueAt("$path[$index]", child)
        }
      }
      is JsonPrimitive, is JsonNull -> appendLine("$path = ${value}")
      else -> appendLine("$path = ${value}")
    }
  }
}
