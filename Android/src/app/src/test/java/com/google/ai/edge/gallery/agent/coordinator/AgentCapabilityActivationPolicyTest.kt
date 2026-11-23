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

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentCapabilityActivationPolicyTest {
  @Test
  fun acceptsEveryMatchingSuccessfulActivationInABatch() {
    val calls = listOf(
      activationCall("activate-1", AgentCapabilityGroups.ANDROID_CONTROLS),
      activationCall("activate-2", AgentCapabilityGroups.STORAGE_ARTIFACTS),
    )
    val results = listOf(
      activationResult("activate-1"),
      activationResult("activate-2"),
    )

    assertEquals(
      setOf(AgentCapabilityGroups.ANDROID_CONTROLS, AgentCapabilityGroups.STORAGE_ARTIFACTS),
      AgentCapabilityActivationPolicy.requestedGroups(calls + results, results),
    )
  }

  @Test
  fun rejectsPartialBatchInsteadOfActivatingOnlyTheMatchingSubset() {
    val calls = listOf(
      activationCall("activate-1", AgentCapabilityGroups.ANDROID_CONTROLS),
    )
    val results = listOf(
      activationResult("activate-1"),
      activationResult("activate-2"),
    )

    assertNull(AgentCapabilityActivationPolicy.requestedGroups(calls + results, results))
  }

  @Test
  fun rejectsMissingResultForOneActivationInTheSameAssistantBatch() {
    val calls = listOf(
      activationCall("activate-1", AgentCapabilityGroups.ANDROID_CONTROLS),
      activationCall("activate-2", AgentCapabilityGroups.STORAGE_ARTIFACTS),
    )
    val partialResults = listOf(activationResult("activate-1"))

    assertNull(
      AgentCapabilityActivationPolicy.requestedGroups(
        calls + partialResults,
        partialResults,
      ),
    )
  }

  @Test
  fun acceptsActivationWhenEverySiblingToolCallHasAResult() {
    val activation = activationCall("activate-1", AgentCapabilityGroups.TERMUX_API)
    val shellCall = AgentMessage(
      role = AgentMessage.Role.ASSISTANT,
      toolCallId = "shell-1",
      toolName = "execute_shell",
    )
    val activationResult = activationResult("activate-1")
    val shellResult = AgentMessage(
      role = AgentMessage.Role.TOOL,
      toolCallId = "shell-1",
      toolName = "execute_shell",
      content = "uid=2000(shell)",
      toolSucceeded = true,
    )

    assertEquals(
      setOf(AgentCapabilityGroups.TERMUX_API),
      AgentCapabilityActivationPolicy.requestedGroups(
        listOf(activation, shellCall, activationResult, shellResult),
        listOf(activationResult),
      ),
    )
  }

  @Test
  fun rejectsActivationWhenANonActivationSiblingResultIsMissing() {
    val activation = activationCall("activate-1", AgentCapabilityGroups.TERMUX_API)
    val shellCall = AgentMessage(
      role = AgentMessage.Role.ASSISTANT,
      toolCallId = "shell-1",
      toolName = "execute_shell",
    )
    val activationResult = activationResult("activate-1")

    assertNull(
      AgentCapabilityActivationPolicy.requestedGroups(
        listOf(activation, shellCall, activationResult),
        listOf(activationResult),
      ),
    )
  }

  @Test
  fun rejectsFailedActivationToolResult() {
    val call = activationCall("activate-1", AgentCapabilityGroups.TERMUX_API)
    val result = activationResult("activate-1").copy(toolSucceeded = false)

    assertNull(AgentCapabilityActivationPolicy.requestedGroups(listOf(call, result), listOf(result)))
  }

  @Test
  fun rejectsUnknownOrCoreGroupIds() {
    val unknown = activationCall("activate-1", "not-a-group")
    val core = activationCall("activate-2", AgentCapabilityGroups.CORE)

    assertNull(
      AgentCapabilityActivationPolicy.requestedGroups(
        listOf(unknown, activationResult("activate-1")),
        listOf(activationResult("activate-1")),
      ),
    )
    assertNull(
      AgentCapabilityActivationPolicy.requestedGroups(
        listOf(core, activationResult("activate-2")),
        listOf(activationResult("activate-2")),
      ),
    )
  }

  @Test
  fun contextLimitActivationFailureIsNotMisreportedAsPermissionDenial() {
    val note = AgentCapabilityActivationPolicy.failureNote("context_limit")

    assertTrue(note.contains("context_limit"))
    assertTrue(note.contains("input-token budget"))
    assertTrue(note.contains("not a permission denial"))
    assertFalse(note.contains("permission not granted"))
  }

  @Test
  fun otherActivationFailuresKeepASeparateReason() {
    val note = AgentCapabilityActivationPolicy.failureNote("conversation_rebuild_failed")

    assertTrue(note.contains("conversation_rebuild_failed"))
    assertTrue(note.contains("no operation was executed or approved"))
  }

  @Test
  fun failedActivationResultIsAlsoTheResultRetainedForHistoryReplay() {
    val activation = activationResult("activate-1")
    val unrelated = AgentMessage(
      role = AgentMessage.Role.TOOL,
      toolName = "execute_shell",
      content = "shell output",
      toolSucceeded = true,
    )
    val note = "\nCapability group activation failed; the previous toolset remains active."

    val replayResults = AgentCapabilityActivationPolicy.markActivationFailure(
      listOf(activation, unrelated),
      note,
    )

    assertEquals(activation.content + note, replayResults[0].content)
    assertEquals(false, replayResults[0].toolSucceeded)
    assertEquals(unrelated, replayResults[1])
  }

  @Test
  fun rejectsMissingAndDuplicateCallIdentifiers() {
    val missingId = activationResult(callId = null)
    val validCall = activationCall("activate-1", AgentCapabilityGroups.TERMUX_API)
    assertNull(
      AgentCapabilityActivationPolicy.requestedGroups(listOf(validCall, missingId), listOf(missingId)),
    )

    val duplicateResults = listOf(
      activationResult("activate-1"),
      activationResult("activate-1"),
    )
    assertNull(
      AgentCapabilityActivationPolicy.requestedGroups(
        listOf(validCall) + duplicateResults,
        duplicateResults,
      ),
    )
  }

  private fun activationCall(callId: String, groupId: String) = AgentMessage(
    role = AgentMessage.Role.ASSISTANT,
    toolCallId = callId,
    toolName = "activate_capability_group",
    toolArguments = JsonObject().apply { addProperty("group_id", groupId) },
  )

  private fun activationResult(callId: String?) = AgentMessage(
    role = AgentMessage.Role.TOOL,
    toolCallId = callId,
    toolName = "activate_capability_group",
    content = "activation requested",
    toolSucceeded = true,
  )
}
