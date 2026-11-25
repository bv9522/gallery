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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Role
import com.google.ai.edge.litertlm.ToolCall
import com.google.gson.JsonParser

class LiteRtLmResponseProtocolTest {
  @Test
  fun toolResponseMessageCarriesActualImageContentBesideToolResponse() {
    val screenshot = byteArrayOf(9, 8, 7, 6)
    val message = buildLiteRtToolResponseMessage(
      listOf(
        AgentMessage(
          role = AgentMessage.Role.TOOL,
          content = "Screenshot attached.",
          toolName = "screen_inspect",
          toolImageBytes = screenshot,
        )
      )
    )

    assertEquals(Role.TOOL, message.role)
    assertEquals(2, message.contents.contents.size)
    val image = message.contents.contents[0] as Content.ImageBytes
    assertSame(screenshot, image.bytes)
    val response = message.contents.contents[1] as Content.ToolResponse
    assertEquals("screen_inspect", response.name)
    val envelope = JsonParser.parseString(response.response.toString()).asJsonObject
    assertEquals("screen_inspect", envelope.get("tool").asString)
    assertTrue(envelope.get("success").isJsonNull)
    assertEquals("Screenshot attached.", envelope.get("result").asString)
  }

  @Test
  fun toolResultEnvelopePreservesStructuredJsonNumbersBooleansAndUnits() {
    val serialized = serializeToolResultForModel(
      toolName = "android_device_info",
      content = """{"battery_percent":55,"temperature_celsius":26.0,"charging":false,"units":{"temperature_celsius":"°C"}}""",
      succeeded = true,
    )
    val envelope = JsonParser.parseString(serialized).asJsonObject
    val result = envelope.getAsJsonObject("result")

    assertEquals("android_device_info", envelope.get("tool").asString)
    assertTrue(envelope.get("success").asBoolean)
    assertTrue(result.get("battery_percent").asJsonPrimitive.isNumber)
    assertEquals(55, result.get("battery_percent").asInt)
    assertTrue(result.get("temperature_celsius").asJsonPrimitive.isNumber)
    assertEquals(26.0, result.get("temperature_celsius").asDouble, 0.0001)
    assertTrue(result.get("charging").asJsonPrimitive.isBoolean)
    assertFalse(result.get("charging").asBoolean)
    assertEquals("°C", result.getAsJsonObject("units").get("temperature_celsius").asString)
  }

  @Test
  fun plainTextToolResultsRemainStringsInsteadOfBeingGuessedAsNumbers() {
    val serialized = serializeToolResultForModel("shell", "55", succeeded = true)
    val envelope = JsonParser.parseString(serialized).asJsonObject

    assertTrue(envelope.get("result").isJsonPrimitive)
    assertTrue(envelope.get("result").asJsonPrimitive.isString)
    assertEquals("55", envelope.get("result").asString)
  }

  @Test
  fun detectsCanonicalGemmaNativeToolCallAsUnparsedContent() {
    val response =
      """<|tool_call>call:execute_shell{backend:<|"|>SHIZUKU<|"|>,command:<|"|>id<|"|>}<tool_call|>"""

    assertTrue(LiteRtLmResponseProtocol.hasUnparsedToolCallMarkers(response))
  }

  @Test
  fun detectsNativeToolCallMarkersInThinkingOrOtherChannels() {
    assertTrue(
      LiteRtLmResponseProtocol.hasUnparsedToolCallMarkers(
        content = "I will now call a tool.",
        channels = mapOf("thought" to "<|tool_call>call:execute_shell{...}<tool_call|>"),
      )
    )
  }

  @Test
  fun doesNotFlagOrdinaryAssistantTextOrChannelContent() {
    assertFalse(
      LiteRtLmResponseProtocol.hasUnparsedToolCallMarkers(
        content = "The Android shell identity is uid=2000(shell).",
        channels = mapOf("thought" to "I should use the registered shell tool."),
      )
    )
  }

  @Test
  fun responseSummaryReportsProtocolShapeWithoutArgumentOrChannelContents() {
    val summary = LiteRtLmResponseProtocol.summarizeResponse(
      content = "Thinking Process: private prompt text",
      toolCalls = listOf(
        ToolCall("execute_shell", mapOf("backend" to "SHIZUKU", "command" to "private command"))
      ),
      channels = mapOf("thought" to "private reasoning text"),
    )

    assertTrue(summary.contains("contentLength="))
    assertTrue(summary.contains("execute_shell(backend,command)"))
    assertTrue(summary.contains("thought:"))
    assertFalse(summary.contains("private prompt text"))
    assertFalse(summary.contains("private command"))
    assertFalse(summary.contains("private reasoning text"))
    assertFalse(summary.contains("SHIZUKU"))
  }
}
