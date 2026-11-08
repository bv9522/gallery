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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.google.ai.edge.litertlm.ToolCall

class LiteRtLmResponseProtocolTest {
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
