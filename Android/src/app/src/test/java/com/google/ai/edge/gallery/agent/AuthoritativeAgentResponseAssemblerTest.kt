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
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthoritativeAgentResponseAssemblerTest {
  @Test
  fun authoritativeFactsArePlacedBeforeModelTextAndClearlySeparated() {
    val result = source("android_device_info", """{"battery_percent":2,"temperature_celsius":27.700,"voltage_millivolts":3775}""")
    val assembled = AuthoritativeAgentResponseAssembler.assemble("Battery is 2%; temperature is 27.7°C.", listOf(result))

    assertTrue(assembled.hasAuthoritativeFacts)
    assertTrue(assembled.text.indexOf("battery_percent = 2") < assembled.text.indexOf("MODEL RESPONSE"))
    assertTrue(assembled.text.contains("temperature_celsius = 27.700"))
    assertTrue(assembled.text.contains("voltage_millivolts = 3775"))
    assertTrue(assembled.text.contains("MODEL RESPONSE (interpretation, not a source of verified values)"))
    assertTrue(assembled.validation.isConsistent)
  }

  @Test
  fun flagsCorruptedOrUnsupportedNumbersWithoutSilentlyRewritingModelText() {
    val result = source("android_device_info", """{"battery_percent":27,"temperature_celsius":27.7,"voltage_millivolts":3775}""")
    val modelText = "Battery 7%, temperature 7.7°C, voltage 775mV."
    val assembled = AuthoritativeAgentResponseAssembler.assemble(modelText, listOf(result))

    assertFalse(assembled.validation.isConsistent)
    assertTrue(assembled.validation.unsupportedNumericClaims.containsAll(listOf("7", "7.7", "775")))
    assertTrue(assembled.text.contains("Validation warning:"))
    assertTrue(assembled.text.contains(modelText))
    assertTrue(assembled.text.contains("battery_percent = 27"))
    assertTrue(assembled.text.contains("temperature_celsius = 27.7"))
    assertTrue(assembled.text.contains("voltage_millivolts = 3775"))
  }

  @Test
  fun missingSourceValuesDoNotGetInventedOrValidated() {
    val result = source("android_device_info", """{"battery_percent":null,"temperature_celsius":27.7}""")
    val assembled = AuthoritativeAgentResponseAssembler.assemble("Battery is 0%; temperature is 27.7°C.", listOf(result))

    assertFalse(assembled.validation.isConsistent)
    assertTrue(assembled.validation.unsupportedNumericClaims.contains("0"))
    assertTrue(assembled.text.contains("battery_percent = null"))
  }

  @Test
  fun excludesFailedAndSensitiveToolResultsFromFactsAndValidation() {
    val failed = source("android_device_info", """{"battery_percent":99}""", succeeded = false)
    val sensitive = source("android_communications", """{"message":"secret","count":99}""")
    val assembled = AuthoritativeAgentResponseAssembler.assemble("Battery is 99%.", listOf(failed, sensitive))

    assertFalse(assembled.hasAuthoritativeFacts)
    assertEquals("Battery is 99%.", assembled.text)
    assertFalse(assembled.validation.isConsistent)
  }

  @Test
  fun multipleSuccessfulToolsAreRenderedWithCallProvenance() {
    val device = source("android_device_info", """{"battery_percent":59}""", callId = "device-call")
    val shell = source("execute_shell", "uid=2000(shell)", callId = "shell-call")
    val assembled = AuthoritativeAgentResponseAssembler.assemble("Battery is 59%; shell UID is 2000.", listOf(device, shell))

    assertTrue(assembled.text.contains("call device-call"))
    assertTrue(assembled.text.contains("call shell-call"))
    assertTrue(assembled.text.contains("battery_percent = 59"))
    assertTrue(assembled.text.contains("output | uid=2000(shell)"))
  }

  @Test
  fun numericValidationAcceptsEquivalentNumericFormattingButRendererKeepsSourceLexeme() {
    val result = source("android_device_info", """{"temperature_celsius":27.700}""")
    val assembled = AuthoritativeAgentResponseAssembler.assemble("Temperature: 27.7 C.", listOf(result))

    assertTrue(assembled.validation.isConsistent)
    assertTrue(assembled.text.contains("temperature_celsius = 27.700"))
  }

  @Test
  fun numericClaimsWithUnitsAreCheckedAndUnsupportedValuesAreReported() {
    val result = source("android_device_info", """{"temperature_celsius":27.7,"voltage_millivolts":3775}""")
    val modelText = "Temperature 7.7°C; voltage 775mV."
    val assembled = AuthoritativeAgentResponseAssembler.assemble(modelText, listOf(result))

    assertFalse(assembled.validation.isConsistent)
    assertTrue(assembled.validation.unsupportedNumericClaims.containsAll(listOf("7.7", "775")))
    assertTrue(assembled.text.contains(modelText))
  }

  @Test
  fun digitsInStructuredStringFieldsDoNotValidateNumericClaims() {
    val result = source("android_device_info", """{"battery_percent":27,"device_label":"Build 123"}""")
    val assembled = AuthoritativeAgentResponseAssembler.assemble("Battery 27%; build 123.", listOf(result))

    assertFalse(assembled.validation.isConsistent)
    assertTrue(assembled.validation.unsupportedNumericClaims.contains("123"))
    assertFalse(assembled.validation.unsupportedNumericClaims.contains("27"))
  }

  @Test
  fun numericValidationHandlesNestedArraysAndSignedDecimals() {
    val result = source("android_device_info", """{"values":[-2,0,3.25,{"nested":4.5}]}""")
    val assembled = AuthoritativeAgentResponseAssembler.assemble("Values: -2, 0, 3.25, and 4.5.", listOf(result))

    assertTrue(assembled.validation.isConsistent)
    assertEquals(4, assembled.validation.numericClaims)
  }

  @Test
  fun separateAssemblyCallsDoNotCarryPriorTurnFacts() {
    val firstTurn = AuthoritativeAgentResponseAssembler.assemble(
      "Battery is 42%.",
      listOf(source("android_device_info", """{"battery_percent":42}""", callId = "turn-one")),
    )
    val secondTurn = AuthoritativeAgentResponseAssembler.assemble("No tools were used this turn.", emptyList())

    assertTrue(firstTurn.text.contains("turn-one"))
    assertEquals("No tools were used this turn.", secondTurn.text)
    assertFalse(secondTurn.text.contains("42"))
    assertFalse(secondTurn.text.contains("turn-one"))
  }

  @Test
  fun noAuthoritativeFactsLeavesModelResponseUnchanged() {
    val assembled = AuthoritativeAgentResponseAssembler.assemble("A general explanation with no tool facts.", emptyList())
    assertEquals("A general explanation with no tool facts.", assembled.text)
    assertFalse(assembled.hasAuthoritativeFacts)
  }

  private fun source(
    name: String,
    raw: String,
    callId: String = "call-$name",
    succeeded: Boolean = true,
  ) = AuthoritativeToolResult(
    toolCallId = callId,
    toolName = name,
    succeeded = succeeded,
    rawContent = raw,
    structuredValue = runCatching { JsonParser.parseString(raw) }
      .getOrNull()?.takeIf { it.isJsonObject || it.isJsonArray },
  )
}
