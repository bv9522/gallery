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

import com.google.ai.edge.gallery.agent.coordinator.AuthoritativeToolResult
import com.google.ai.edge.gallery.agent.coordinator.AuthoritativeToolResultRenderer
import com.google.gson.JsonElement
import java.math.BigDecimal

/** Runtime-owned fact assembly with a conservative numeric-claim validation pass. */
internal object AuthoritativeAgentResponseAssembler {
  private val visibleToolNames = setOf("android_control", "android_device_info", "execute_shell")

  data class Validation(
    val numericClaims: Int,
    val unsupportedNumericClaims: List<String>,
  ) {
    val isConsistent: Boolean get() = unsupportedNumericClaims.isEmpty()
  }

  data class Assembly(
    val text: String,
    val validation: Validation,
    val hasAuthoritativeFacts: Boolean,
  )

  fun assemble(modelResponse: String, results: List<AuthoritativeToolResult>): Assembly {
    val eligible = results.filter { it.isEligibleForFacts && it.toolName in visibleToolNames }
    val facts = AuthoritativeToolResultRenderer.render(eligible, visibleToolNames)
    val validation = validate(modelResponse, eligible)
    if (facts.isNullOrBlank()) return Assembly(modelResponse, validation, false)

    return Assembly(
      text = buildString {
        appendLine("AUTHORITATIVE TOOL FACTS (exact bounded source values; use these over conflicting model claims):")
        appendLine(facts)
        appendLine()
        appendLine("MODEL RESPONSE (interpretation, not a source of verified values):")
        if (validation.unsupportedNumericClaims.isNotEmpty()) {
          appendLine(
            "Validation warning: ${validation.unsupportedNumericClaims.size} numeric claim(s) " +
              "were not found in the authoritative tool values. Check the facts above.",
          )
        }
        append(modelResponse)
      }.trimEnd(),
      validation = validation,
      hasAuthoritativeFacts = true,
    )
  }

  private fun validate(modelResponse: String, results: List<AuthoritativeToolResult>): Validation {
    val claims = NUMBER_PATTERN.findAll(modelResponse).map { it.value }.toList()
    val sourceNumbers = results.flatMap { result ->
      val values = mutableListOf<String>()
      if (result.structuredValue != null) {
        collectNumericValues(result.structuredValue).let(values::addAll)
      } else {
        // For unstructured output, scan text. For parsed JSON, do not treat digits in
        // string fields (serials, labels, versions) as numeric source facts.
        NUMBER_PATTERN.findAll(result.rawContent).forEach { values += it.value }
      }
      values
    }.mapNotNull(::canonicalNumber).toSet()

    val unsupported = claims.filter { canonicalNumber(it) !in sourceNumbers }.distinct()
    return Validation(claims.size, unsupported)
  }

  private fun collectNumericValues(value: JsonElement): List<String> {
    val output = mutableListOf<String>()
    fun visit(node: JsonElement) {
      when {
        node.isJsonObject -> node.asJsonObject.entrySet().forEach { visit(it.value) }
        node.isJsonArray -> node.asJsonArray.forEach(::visit)
        node.isJsonPrimitive && node.asJsonPrimitive.isNumber -> output += node.asJsonPrimitive.asString
      }
    }
    visit(value)
    return output
  }

  private fun canonicalNumber(value: String): String? =
    runCatching { BigDecimal(value).stripTrailingZeros().toPlainString() }.getOrNull()

  private val NUMBER_PATTERN =
    Regex("(?<![A-Za-z0-9_])[-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:(?![A-Za-z0-9_])|(?=mV))")
}
