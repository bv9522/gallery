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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCoordinatorTest {
  @Test
  fun mockModelToolCallReturnsShellResultBeforeFinalAnswer() = runBlocking {
    val observedCommands = mutableListOf<String>()
    val shellTool = object : AgentTool {
      override val name = "shell"
      override val description = "Execute an Android shell command."

      override suspend fun execute(arguments: JsonObject): AgentToolResult {
        val command = arguments.get("command")?.asString
          ?: return AgentToolResult("Missing command.", isSuccessful = false)
        observedCommands += command
        return AgentToolResult("uid=2000(shell)")
      }
    }

    var modelTurn = 0
    val mockModel = AgentModel { history ->
      modelTurn++
      if (modelTurn == 1) {
        AgentModelResponse.ToolCall(
          name = "shell",
          arguments = JsonObject().apply { addProperty("command", "id") },
          callId = "call-1",
        )
      } else {
        assertEquals("uid=2000(shell)", history.last().content)
        assertEquals(AgentMessage.Role.TOOL, history.last().role)
        AgentModelResponse.FinalAnswer("The command ran as Android shell UID 2000.")
      }
    }

    val coordinator = AgentCoordinator(mockModel, AgentToolRegistry(listOf(shellTool)))
    val result = coordinator.run("Check the Android shell identity.")

    assertEquals(AgentRunStatus.COMPLETED, result.status)
    assertEquals("The command ran as Android shell UID 2000.", result.finalText)
    assertEquals(2, result.modelTurns)
    assertEquals(1, result.toolCalls)
    assertEquals(listOf("id"), observedCommands)
  }


  @Test
  fun toolCallBatchExecutesEveryCallSequentiallyAndReturnsEveryResult() = runBlocking {
    val executionOrder = mutableListOf<String>()
    val tool = object : AgentTool {
      override val name = "record"
      override val description = "Record a value."
      override suspend fun execute(arguments: JsonObject): AgentToolResult {
        val value = arguments.get("value").asString
        executionOrder += value
        return AgentToolResult("recorded:$value")
      }
    }
    var turns = 0
    val model = AgentModel { history ->
      turns++
      if (turns == 1) {
        AgentModelResponse.ToolCalls(
          listOf(
            AgentModelResponse.ToolCall("record", JsonObject().apply { addProperty("value", "one") }, "1"),
            AgentModelResponse.ToolCall("record", JsonObject().apply { addProperty("value", "two") }, "2"),
          )
        )
      } else {
        val results = history.filter { it.role == AgentMessage.Role.TOOL }
        assertEquals(listOf("recorded:one", "recorded:two"), results.map { it.content })
        AgentModelResponse.FinalAnswer("Both tools completed.")
      }
    }

    val result = AgentCoordinator(model, AgentToolRegistry(listOf(tool))).run("Record two values.")

    assertEquals(AgentRunStatus.COMPLETED, result.status)
    assertEquals(2, result.toolCalls)
    assertEquals(listOf("one", "two"), executionOrder)
  }

  @Test
  fun unknownToolIsReturnedToModelAsRecoverableToolError() = runBlocking {
    var modelTurn = 0
    val model = AgentModel { history ->
      modelTurn++
      if (modelTurn == 1) {
        AgentModelResponse.ToolCall("missing", JsonObject(), "unknown-1")
      } else {
        assertTrue(history.last().content.contains("Unknown tool 'missing'"))
        AgentModelResponse.FinalAnswer("I could not find that tool.")
      }
    }

    val result = AgentCoordinator(model, AgentToolRegistry(emptyList())).run("Do something.")
    assertEquals(AgentRunStatus.COMPLETED, result.status)
    assertEquals(1, result.toolCalls)
    assertEquals(2, result.modelTurns)
  }

  @Test
  fun toolExceptionIsReturnedToModelInsteadOfCrashingLoop() = runBlocking {
    val failingTool = object : AgentTool {
      override val name = "failure"
      override val description = "Always fails."
      override suspend fun execute(arguments: JsonObject): AgentToolResult {
        error("simulated failure")
      }
    }
    var modelTurn = 0
    val model = AgentModel {
      modelTurn++
      if (modelTurn == 1) {
        AgentModelResponse.ToolCall("failure", JsonObject(), "failure-1")
      } else {
        assertTrue(it.last().content.contains("simulated failure"))
        AgentModelResponse.FinalAnswer("Recovered.")
      }
    }

    val result = AgentCoordinator(model, AgentToolRegistry(listOf(failingTool))).run("Recover.")
    assertEquals(AgentRunStatus.COMPLETED, result.status)
    assertEquals("Recovered.", result.finalText)
  }

  @Test
  fun turnLimitStopsRepeatedToolRequests() = runBlocking {
    val tool = object : AgentTool {
      override val name = "noop"
      override val description = "Does nothing."
      override suspend fun execute(arguments: JsonObject) = AgentToolResult("done")
    }
    val model = AgentModel {
      AgentModelResponse.ToolCall("noop", JsonObject(), "repeat-" + it.size)
    }

    val result = AgentCoordinator(model, AgentToolRegistry(listOf(tool)), maxModelTurns = 2)
      .run("Keep working.")
    assertEquals(AgentRunStatus.TURN_LIMIT_REACHED, result.status)
    assertEquals(2, result.modelTurns)
    assertEquals(2, result.toolCalls)
    assertFalse(result.isSuccessful)
  }

  @Test
  fun cancellationPropagatesToCaller() = runBlocking {
    val model = AgentModel { throw CancellationException("cancelled") }
    try {
      AgentCoordinator(model, AgentToolRegistry(emptyList())).run("Cancel.")
      throw AssertionError("Expected cancellation to propagate.")
    } catch (_: CancellationException) {
      assertTrue(true)
    }
  }

  @Test
  fun duplicateToolNamesAreRejected() {
    val first = testTool("duplicate")
    val second = testTool("duplicate")
    try {
      AgentToolRegistry(listOf(first, second))
      throw AssertionError("Expected duplicate names to be rejected.")
    } catch (expected: IllegalArgumentException) {
      assertTrue(expected.message.orEmpty().contains("Duplicate"))
    }
  }

  private fun testTool(toolName: String) = object : AgentTool {
    override val name = toolName
    override val description = "Test tool."
    override suspend fun execute(arguments: JsonObject) = AgentToolResult("ok")
  }
}
