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

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthoritativeToolResultRendererTest {
  private val visibleTools = setOf("android_device_info", "execute_shell")

  @Test
  fun rendersNestedTypedValuesWithoutChangingNumericLexemesOrUnits() {
    val payload = """{"battery_percent":59,"temperature_celsius":27.700,"charging":false,"state":"DISCHARGING","voltage_millivolts":3775,"samples":[1,2.50,null],"details":{"empty":{},"labels":["a","b"]}}"""
    val result = authoritativeResult("android_device_info", "device-call", payload)

    val rendered = AuthoritativeToolResultRenderer.render(listOf(result), visibleTools).orEmpty()

    assertTrue(rendered.contains("battery_percent = 59"))
    assertTrue(rendered.contains("temperature_celsius = 27.700"))
    assertTrue(rendered.contains("charging = false"))
    assertTrue(rendered.contains("state = \"DISCHARGING\""))
    assertTrue(rendered.contains("voltage_millivolts = 3775"))
    assertTrue(rendered.contains("samples[1] = 2.50"))
    assertTrue(rendered.contains("samples[2] = null"))
    assertTrue(rendered.contains("details.empty = {}"))
    assertTrue(rendered.contains("details.labels[1] = \"b\""))
    assertTrue(rendered.contains("call device-call"))
  }

  @Test
  fun rendersTopLevelArraysAndExplicitScalarValues() {
    val array = authoritativeResult("execute_shell", "array-call", """[10,{"ok":true}]""")
    val scalar = AuthoritativeToolResult(
      toolCallId = "scalar-call",
      toolName = "execute_shell",
      succeeded = true,
      rawContent = "42",
      structuredValue = JsonParser.parseString("42"),
    )

    val rendered = AuthoritativeToolResultRenderer.render(listOf(array, scalar), visibleTools).orEmpty()

    assertTrue(rendered.contains("value[0] = 10"))
    assertTrue(rendered.contains("value[1].ok = true"))
    assertTrue(rendered.contains("value = 42"))
  }

  @Test
  fun preservesPlainTextAndMalformedJsonAsBoundedText() {
    val plain = authoritativeResult("execute_shell", "plain-call", "line one\nline two")
    val malformed = authoritativeResult("execute_shell", "malformed-call", """{"value":""")

    val rendered = AuthoritativeToolResultRenderer.render(listOf(plain, malformed), visibleTools).orEmpty()

    assertTrue(rendered.contains("output | line one"))
    assertTrue(rendered.contains("output | line two"))
    assertTrue(rendered.contains("output | {\"value\":"))
  }

  @Test
  fun excludesFailedAndNonAllowlistedResults() {
    val failed = authoritativeResult(
      "android_device_info",
      "failed-call",
      """{"battery_percent":99}""",
      succeeded = false,
    )
    val sensitive = authoritativeResult(
      "android_communications",
      "private-call",
      """{"message":"private body"}""",
    )

    val rendered = AuthoritativeToolResultRenderer.render(listOf(failed, sensitive), visibleTools)

    assertNull(rendered)
    assertFalse(rendered.orEmpty().contains("99"))
    assertFalse(rendered.orEmpty().contains("private body"))
  }

  @Test
  fun returnsNullWhenNoSuccessfulAllowedFactsExist() {
    assertNull(AuthoritativeToolResultRenderer.render(emptyList(), visibleTools))
  }

  private fun authoritativeResult(
    toolName: String,
    callId: String,
    content: String,
    succeeded: Boolean = true,
  ) = AuthoritativeToolResult(
    toolCallId = callId,
    toolName = toolName,
    succeeded = succeeded,
    rawContent = content,
    structuredValue = runCatching { JsonParser.parseString(content) }
      .getOrNull()
      ?.takeIf { it.isJsonObject || it.isJsonArray },
  )
}
