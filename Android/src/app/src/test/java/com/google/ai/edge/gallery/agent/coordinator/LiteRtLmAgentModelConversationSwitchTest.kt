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
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtLmAgentModelConversationSwitchTest {
  @Test
  fun successfulGroupSwitchReplaysHistoryAndClosesPreviousOnlyAfterCandidateValidation() = runBlocking {
    val fullRegistry = fullRegistry()
    val coreRegistry = AgentCapabilityGroups.project(fullRegistry, emptySet())
    val original = FakeConversation(coreRegistry.names(), responses = listOf(finalMessage("First answer.")))
    val candidate = FakeConversation(
      AgentCapabilityGroups.project(fullRegistry, setOf(AgentCapabilityGroups.STORAGE_ARTIFACTS)).names(),
      tokenCount = 84,
    )
    var capturedReplay: List<Message>? = null
    var capturedProjection: AgentToolRegistry? = null
    val closed = mutableListOf<AgentNativeConversation>()

    val model = model(
      original = original,
      fullRegistry = fullRegistry,
      initialVisible = coreRegistry,
      groupedFactory = { replay, projected ->
        capturedReplay = replay.toList()
        capturedProjection = projected
        candidate
      },
      onClosed = closed::add,
    )

    model.generate(listOf(AgentMessage(AgentMessage.Role.USER, "First prompt.")))

    assertTrue(model.selectGroup(AgentCapabilityGroups.STORAGE_ARTIFACTS))
    assertEquals(2, capturedReplay?.size)
    assertSame(originalResponse(original), capturedReplay?.last())
    assertEquals(
      setOf("execute_shell", "discover_capability_groups", "activate_capability_group", "document_tree", "artifact_create"),
      capturedProjection?.names(),
    )
    assertEquals(capturedProjection?.names(), model.visibleToolNames())
    assertTrue(original.closed)
    assertFalse(candidate.closed)
    assertEquals(listOf(original), closed)
    assertEquals(84, model.currentTokenCount())

    model.close()
    assertTrue(candidate.closed)
  }

  @Test
  fun failedGroupConversationCreationRetainsOriginalAndReportsFailure() = runBlocking {
    val fullRegistry = fullRegistry()
    val coreRegistry = AgentCapabilityGroups.project(fullRegistry, emptySet())
    val original = FakeConversation(coreRegistry.names())
    var closedCount = 0
    val model = model(
      original = original,
      fullRegistry = fullRegistry,
      initialVisible = coreRegistry,
      groupedFactory = { _, _ -> error("simulated conversation factory failure") },
      onClosed = { closedCount++ },
    )

    assertFalse(model.selectGroup(AgentCapabilityGroups.STORAGE_ARTIFACTS))
    assertEquals("conversation_rebuild_failed", model.lastGroupSwitchFailureReason())
    assertEquals(coreRegistry.names(), model.visibleToolNames())
    assertFalse(original.closed)
    assertEquals(0, closedCount)

    model.close()
    assertTrue(original.closed)
  }

  @Test
  fun failedCandidateTokenValidationClosesCandidateButKeepsOriginalActive() = runBlocking {
    val fullRegistry = fullRegistry()
    val coreRegistry = AgentCapabilityGroups.project(fullRegistry, emptySet())
    val original = FakeConversation(coreRegistry.names())
    val rejected = FakeConversation(
      AgentCapabilityGroups.project(fullRegistry, setOf(AgentCapabilityGroups.STORAGE_ARTIFACTS)).names(),
      tokenCountFailure = IllegalStateException("candidate token-count failure"),
    )
    val closed = mutableListOf<AgentNativeConversation>()
    val model = model(
      original = original,
      fullRegistry = fullRegistry,
      initialVisible = coreRegistry,
      groupedFactory = { _, _ -> rejected },
      onClosed = closed::add,
    )

    assertFalse(model.selectGroup(AgentCapabilityGroups.STORAGE_ARTIFACTS))
    assertEquals("conversation_rebuild_failed", model.lastGroupSwitchFailureReason())
    assertTrue(rejected.closed)
    assertFalse(original.closed)
    assertEquals(listOf(rejected), closed)
    assertEquals(coreRegistry.names(), model.visibleToolNames())

    model.close()
    assertTrue(original.closed)
  }

  private fun model(
    original: FakeConversation,
    fullRegistry: AgentToolRegistry,
    initialVisible: AgentToolRegistry,
    groupedFactory: (List<Message>, AgentToolRegistry) -> AgentNativeConversation,
    onClosed: (AgentNativeConversation) -> Unit = {},
  ) = LiteRtLmAgentModel(
    conversation = original,
    conversationFactory = { _ -> FakeConversation(initialVisible.names()) },
    ioDispatcher = Dispatchers.Unconfined,
    onConversationClosed = onClosed,
    compactAtTokenCount = 10_000,
    compactToTokenCount = 8_000,
    fullToolRegistry = fullRegistry,
    initialVisibleToolRegistry = initialVisible,
    groupedConversationFactory = groupedFactory,
    initialGroupIds = emptySet(),
  )

  private fun fullRegistry(): AgentToolRegistry =
    AgentToolRegistry(
      AgentCapabilityGroups.definitions
        .flatMap { it.toolNames }
        .distinct()
        .map { testTool(it) },
    )

  private fun testTool(name: String) = object : AgentTool {
    override val name = name
    override val description = "Test tool $name."
    override suspend fun execute(arguments: JsonObject) = AgentToolResult("ok")
  }

  private fun finalMessage(text: String) = Message.model(Contents.of(text), emptyList(), emptyMap())

  private fun originalResponse(conversation: FakeConversation): Message =
    conversation.responsesInitially.single()

  private class FakeConversation(
    names: Set<String>,
    responses: List<Message> = emptyList(),
    private val tokenCount: Int = 10,
    private val tokenCountFailure: Exception? = null,
  ) : AgentNativeConversation {
    val responsesInitially = responses.toList()
    private val pendingResponses = responses.toMutableList()
    var closed = false
      private set

    override val automaticToolCalling = false
    override val isAlive: Boolean
      get() = !closed
    override val toolDescriptions = names.sorted().map(::makeToolDescription)
    override val nativeConversation = null

    override fun getTokenCount(): Int {
      tokenCountFailure?.let { throw it }
      return tokenCount
    }

    override fun sendMessage(message: Message, extraContext: Map<String, Any>): Message {
      check(pendingResponses.isNotEmpty()) { "No fake response was queued." }
      return pendingResponses.removeAt(0)
    }

    override fun cancelProcess() = Unit

    override fun close() {
      closed = true
    }
  }
}

private fun makeToolDescription(name: String): JsonElement =
  JsonObject().apply {
    add(
      "function",
      JsonObject().apply {
        addProperty("name", name)
        add("parameters", JsonObject())
      },
    )
  }

