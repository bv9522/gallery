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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthoritativeToolResultTranscriptTest {
  @Test
  fun includesExactDeviceInfoOutputWithoutModelTranscription() {
    val rawOutput = """{"battery_percent":57,"temperature_celsius":27.1,"voltage_millivolts":3779}"""
    val transcript = formatAuthoritativeToolResults(
      listOf(
        AgentMessage(AgentMessage.Role.USER, content = "Read my battery status."),
        AgentMessage(
          AgentMessage.Role.TOOL,
          content = rawOutput,
          toolName = "android_device_info",
          toolSucceeded = true,
        ),
      )
    )

    assertTrue(transcript.orEmpty().contains(rawOutput))
    assertTrue(transcript.orEmpty().contains("--- android_device_info ---"))
  }

  @Test
  fun doesNotExposeSensitiveNativeToolResultsByDefault() {
    val transcript = formatAuthoritativeToolResults(
      listOf(
        AgentMessage(AgentMessage.Role.USER, content = "Read my messages."),
        AgentMessage(
          AgentMessage.Role.TOOL,
          content = "Private message body",
          toolName = "android_communications",
          toolSucceeded = true,
        ),
      )
    )

    assertNull(transcript)
    assertFalse(transcript.orEmpty().contains("Private message body"))
  }

  @Test
  fun includesOnlyResultsFromTheCurrentUserTurn() {
    val transcript = formatAuthoritativeToolResults(
      listOf(
        AgentMessage(AgentMessage.Role.USER, content = "Earlier request."),
        AgentMessage(
          AgentMessage.Role.TOOL,
          content = "old battery output",
          toolName = "android_device_info",
        ),
        AgentMessage(AgentMessage.Role.USER, content = "Current request."),
        AgentMessage(
          AgentMessage.Role.TOOL,
          content = "current battery output",
          toolName = "android_device_info",
        ),
      )
    )

    assertTrue(transcript.orEmpty().contains("current battery output"))
    assertFalse(transcript.orEmpty().contains("old battery output"))
  }
}
