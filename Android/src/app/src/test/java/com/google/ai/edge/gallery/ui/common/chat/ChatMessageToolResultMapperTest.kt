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

package com.google.ai.edge.gallery.ui.common.chat

import com.google.ai.edge.gallery.agent.coordinator.AuthoritativeToolResult
import com.google.ai.edge.gallery.agent.coordinator.AgentToolOutcome
import com.google.ai.edge.gallery.agent.coordinator.ShellToolResultDetails
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageToolResultMapperTest {
  @Test
  fun roundTripPreservesExactBoundedOutputAndTransactionIdentity() = runBlocking {
    val raw = "BEGIN|ALPHA|BRAVO|END\n\n  "
    val original = ChatMessageToolResult(
      AuthoritativeToolResult(
        toolCallId = "tool-call-7",
        toolName = "execute_shell",
        succeeded = false,
        rawContent = raw,
        structuredValue = JsonParser.parseString("""{"exit_code":7}"""),
        outcome = AgentToolOutcome.TIMED_OUT,
        sessionId = "session-42",
        userTurnId = "turn-9",
        shellDetails = ShellToolResultDetails(
          backend = "TERMUX",
          exitCode = 124,
          timedOut = true,
          outputTruncated = true,
          stdout = "line one\n  ",
          stderr = "timeout\n",
          error = "deadline exceeded",
          processMayStillBeRunning = true,
        ),
      )
    )

    val proto = checkNotNull(ChatMessageMapper.serializeMessage(original, "session-1"))
    val restored = ChatMessageMapper.deserializeProtoMessage(proto) as ChatMessageToolResult

    assertEquals(raw, restored.result.rawContent)
    assertEquals("tool-call-7", restored.result.toolCallId)
    assertEquals("execute_shell", restored.result.toolName)
    assertFalse(restored.result.succeeded)
    assertEquals(AgentToolOutcome.TIMED_OUT, restored.result.outcome)
    assertEquals("session-42", restored.result.sessionId)
    assertEquals("turn-9", restored.result.userTurnId)
    assertEquals(original.result.structuredValue, restored.result.structuredValue)
    assertEquals(original.result.shellDetails, restored.result.shellDetails)
  }

  @Test
  fun displayOnlyToolResultIsNotReplayedAsModelAuthoredText() {
    val message = ChatMessageToolResult(
      AuthoritativeToolResult(
        toolCallId = "call-display-only",
        toolName = "device_info",
        succeeded = true,
        rawContent = "uid=10486\n",
        structuredValue = null,
      )
    )

    assertNull(convertToLitertMessage(message))
  }
}
