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

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationCompactionPolicyTest {
  @Test
  fun compactionStartsAtConfiguredThreshold() {
    assertFalse(AgentConversationCompactionPolicy.shouldCompact(2_999, 3_000))
    assertTrue(AgentConversationCompactionPolicy.shouldCompact(3_000, 3_000))
    assertTrue(AgentConversationCompactionPolicy.shouldCompact(3_001, 3_000))
  }

  @Test
  fun dropsOnlyOldestCompletedTurnAndKeepsToolTransactionTogether() {
    val first = AgentConversationTurn(
      messages = mutableListOf(
        Message.user("first"),
        Message.model(Contents.of("first answer"), emptyList(), emptyMap()),
      ),
      complete = true,
    )
    val second = AgentConversationTurn(
      messages = mutableListOf(
        Message.user("second"),
        Message.model(Contents.of("tool call"), emptyList(), emptyMap()),
        Message.tool(Contents.of("tool result")),
        Message.model(Contents.of("second answer"), emptyList(), emptyMap()),
      ),
      complete = true,
    )
    val active = AgentConversationTurn(
      messages = mutableListOf(Message.user("active")),
      complete = false,
    )

    val retained = AgentConversationCompactionPolicy.dropOldestCompletedTurn(
      listOf(first, second, active)
    )

    assertEquals(listOf(second, active), retained)
    assertEquals(4, retained?.first()?.messages?.size)
  }

  @Test
  fun neverDropsTheOnlyTurnOrAnIncompleteOldestTurn() {
    val only = AgentConversationTurn(mutableListOf(Message.user("only")), complete = true)
    val incomplete = AgentConversationTurn(mutableListOf(Message.user("pending")), complete = false)

    assertNull(AgentConversationCompactionPolicy.dropOldestCompletedTurn(listOf(only)))
    assertNull(AgentConversationCompactionPolicy.dropOldestCompletedTurn(listOf(incomplete, only)))
  }

  @Test
  fun replaySnapshotPreservesEveryMessageAndIncludesTheActiveTurn() {
    val completed = AgentConversationTurn(
      messages = mutableListOf(
        Message.user("first request"),
        Message.model(Contents.of("first answer"), emptyList(), emptyMap()),
      ),
      complete = true,
    )
    val active = AgentConversationTurn(
      messages = mutableListOf(
        Message.user("second request"),
        Message.model(Contents.of("tool call"), emptyList(), emptyMap()),
        Message.tool(Contents.of("tool result")),
      ),
      complete = false,
    )

    val replay = AgentConversationHistoryPolicy.replayMessages(listOf(completed, active))

    assertEquals(5, replay.size)
    assertEquals(
      listOf(
        Message.user("first request").toString(),
        Message.model(Contents.of("first answer"), emptyList(), emptyMap()).toString(),
        Message.user("second request").toString(),
        Message.model(Contents.of("tool call"), emptyList(), emptyMap()).toString(),
        Message.tool(Contents.of("tool result")).toString(),
      ),
      replay.map { it.toString() },
    )
  }

  @Test
  fun failedConversationCreationLeavesNoCandidateToPublish() {
    var published = "previous"
    val candidate = AgentConversationReplacementPolicy.createValidated(
      create = { error("simulated rebuild failure") },
      validate = { },
      closeRejected = { throw AssertionError("No candidate should be closed.") },
    )
    candidate.value?.let { published = it }

    assertEquals("previous", published)
    assertTrue(candidate.failure?.message.orEmpty().contains("simulated rebuild failure"))
  }

  @Test
  fun failedCandidateValidationClosesCandidateAndKeepsPreviousConversation() {
    class Candidate {
      var closed = false
    }
    val previous = Candidate()
    var active = previous
    val candidate = AgentConversationReplacementPolicy.createValidated(
      create = { Candidate() },
      validate = { error("simulated token-count validation failure") },
      closeRejected = { it.closed = true },
    )
    candidate.value?.let { active = it }

    assertTrue(active === previous)
    assertTrue(candidate.failure?.message.orEmpty().contains("validation failure"))
  }

  @Test
  fun recognizesNativeInputTokenOverflowWithoutMatchingUnrelatedErrors() {
    assertTrue(
      AgentConversationCompactionPolicy.isContextOverflow(
        IllegalArgumentException("INVALID_ARGUMENT: Input token ids are too long.")
      )
    )
    assertTrue(
      AgentConversationCompactionPolicy.isContextOverflow(
        IllegalStateException(
          "Conversation failed",
          IllegalArgumentException("Exceeding the maximum number of tokens"),
        )
      )
    )
    assertFalse(
      AgentConversationCompactionPolicy.isContextOverflow(
        IllegalStateException("The model file could not be opened.")
      )
    )
  }
}
