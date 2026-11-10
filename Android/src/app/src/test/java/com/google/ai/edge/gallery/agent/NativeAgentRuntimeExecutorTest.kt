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
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAgentRuntimeExecutorTest {
  @Test
  fun shellGuidanceSeparatesNativeShellFromExternalMcpAndForbidsFallback() {
    val instruction = buildAgentSystemInstruction("Existing system context")

    assertTrue(instruction.startsWith("Existing system context"))
    assertTrue(instruction.contains("native execute_shell tool directly"))
    assertTrue(instruction.contains("run_mcp_tool"))
    assertTrue(instruction.contains("Never silently switch or fall back"))
    assertTrue(instruction.contains("actual error"))
    assertTrue(instruction.contains("retry once using the exact corrected operation and arguments"))
  }

  @Test
  fun authoritativeTranscriptPreservesCurrentTurnToolOutputVerbatim() {
    val messages = listOf(
      AgentMessage(AgentMessage.Role.USER, content = "Earlier turn"),
      AgentMessage(
        AgentMessage.Role.TOOL,
        content = "modelIdentifier=OLD-VALUE",
        toolName = "android_control",
      ),
      AgentMessage(AgentMessage.Role.USER, content = "Current turn"),
      AgentMessage(
        AgentMessage.Role.TOOL,
        content = "modelIdentifier=SM-G991U\\nstatus=AVAILABLE",
        toolName = "android_control",
      ),
      AgentMessage(
        AgentMessage.Role.TOOL,
        content = "line-1\\n...[TRUNCATED original_chars=50000]...\\nline-9",
        toolName = "execute_shell",
      ),
      AgentMessage(
        AgentMessage.Role.TOOL,
        content = "unrelated",
        toolName = "other_tool",
      ),
    )

    val transcript = formatAuthoritativeToolResults(messages)

    assertTrue(transcript.orEmpty().contains("modelIdentifier=SM-G991U\\nstatus=AVAILABLE"))
    assertTrue(transcript.orEmpty().contains("TRUNCATED original_chars=50000"))
    assertFalse(transcript.orEmpty().contains("OLD-VALUE"))
    assertFalse(transcript.orEmpty().contains("unrelated"))
  }

  @Test
  fun noAuthoritativeToolsProducesNoTranscript() {
    val messages = listOf(AgentMessage(AgentMessage.Role.USER, content = "Hello"))
    assertTrue(formatAuthoritativeToolResults(messages) == null)
  }

  @Test
  fun shellGuidanceIsPresentWithoutAnExistingSystemInstruction() {
    val instruction = buildAgentSystemInstruction(null)

    assertTrue(instruction.contains("DEVICE SHELL TOOL RULES"))
    assertTrue(instruction.contains("TERMUX_RISH"))
  }
}
