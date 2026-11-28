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
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthoritativeToolResultTest {
  @Test
  fun capturesStructuredObjectsArraysNestedValuesAndProvenance() = runBlocking {
    val payload = """{"battery_percent":59,"temperature_celsius":27.70,"charging":false,"details":{"voltage_millivolts":3775,"samples":[1,2,3]}}"""
    val tool = resultTool("device_info") { AgentToolResult(payload) }
    val result = runTool(tool, "call-device")

    val captured = result.authoritativeToolResults.single()
    assertEquals("call-device", captured.toolCallId)
    assertEquals("device_info", captured.toolName)
    assertTrue(captured.succeeded)
    assertTrue(captured.isEligibleForFacts)
    assertEquals(payload, captured.rawContent)
    assertEquals(JsonParser.parseString(payload), captured.structuredValue)
    assertEquals("27.70", captured.structuredValue!!.asJsonObject
      .get("temperature_celsius").asString)
    assertFalse(captured.structuredValue.asJsonObject.get("charging").asBoolean)
    assertEquals(3, captured.structuredValue.asJsonObject
      .getAsJsonObject("details").getAsJsonArray("samples").size())
  }

  @Test
  fun capturesTopLevelScalarOnlyWhenToolExplicitlyDeclaresItsJsonType() = runBlocking {
    val typed = resultTool("typed_scalar") {
      AgentToolResult("42", structuredContent = JsonParser.parseString("42"))
    }
    val plain = resultTool("plain_text") { AgentToolResult("42") }

    val typedResult = runTool(typed, "typed-call").authoritativeToolResults.single()
    val plainResult = runTool(plain, "plain-call").authoritativeToolResults.single()

    assertEquals("42", typedResult.rawContent)
    assertEquals(JsonParser.parseString("42"), typedResult.structuredValue)
    assertTrue(typedResult.structuredValue!!.isJsonPrimitive)
    assertNull(plainResult.structuredValue)
    assertEquals("42", plainResult.rawContent)
  }

  @Test
  fun retainsFailedResultsForProvenanceButMarksThemIneligibleAsFacts() = runBlocking {
    val tool = resultTool("failed_tool") {
      AgentToolResult("""{"temperature_celsius":7.7}""", isSuccessful = false)
    }

    val captured = runTool(tool, "failed-call").authoritativeToolResults.single()

    assertEquals("failed-call", captured.toolCallId)
    assertFalse(captured.succeeded)
    assertFalse(captured.isEligibleForFacts)
    assertEquals("""{"temperature_celsius":7.7}""", captured.rawContent)
  }

  @Test
  fun capturesMultipleToolCallsInExecutionOrderWithDistinctIdentities() = runBlocking {
    val first = resultTool("first") { AgentToolResult("""{"value":1}""") }
    val second = resultTool("second") { AgentToolResult("""{"value":2}""") }
    var modelTurn = 0
    val model = AgentModel {
      modelTurn++
      if (modelTurn == 1) {
        AgentModelResponse.ToolCalls(
          listOf(
            AgentModelResponse.ToolCall("first", JsonObject(), "call-1"),
            AgentModelResponse.ToolCall("second", JsonObject(), "call-2"),
          )
        )
      } else {
        AgentModelResponse.FinalAnswer("Both results received.")
      }
    }

    val result = AgentCoordinator(model, AgentToolRegistry(listOf(first, second)))
      .run("Run both tools.")

    assertEquals(listOf("call-1", "call-2"), result.authoritativeToolResults.map { it.toolCallId })
    assertEquals(listOf("first", "second"), result.authoritativeToolResults.map { it.toolName })
    assertEquals(listOf("""{"value":1}""", """{"value":2}"""),
      result.authoritativeToolResults.map { it.rawContent })
  }

  @Test
  fun streamsAuthoritativeResultsImmediatelyInExecutionOrder() = runBlocking {
    val first = resultTool("first") { AgentToolResult("first\\n") }
    val second = resultTool("second") { AgentToolResult("second\\n") }
    var modelTurn = 0
    val model = AgentModel {
      modelTurn++
      if (modelTurn == 1) {
        AgentModelResponse.ToolCalls(
          listOf(
            AgentModelResponse.ToolCall("first", JsonObject(), "stream-call-1"),
            AgentModelResponse.ToolCall("second", JsonObject(), "stream-call-2"),
          )
        )
      } else {
        AgentModelResponse.FinalAnswer("The model commentary is optional.")
      }
    }
    val emitted = mutableListOf<AuthoritativeToolResult>()

    val result = AgentCoordinator(model, AgentToolRegistry(listOf(first, second)))
      .run("Run both tools.") { emitted += it }

    assertEquals(result.authoritativeToolResults, emitted)
    assertEquals(listOf("stream-call-1", "stream-call-2"), emitted.map { it.toolCallId })
    assertEquals(listOf("first\\n", "second\\n"), emitted.map { it.rawContent })
  }

  @Test
  fun authoritativeResultsAreScopedToTheCurrentRunNotEarlierConversationTurns() = runBlocking {
    val tool = resultTool("device_info") { AgentToolResult("""{"battery_percent":59}""") }
    var modelTurn = 0
    val model = AgentModel {
      modelTurn++
      when (modelTurn) {
        1 -> AgentModelResponse.ToolCall("device_info", JsonObject(), "earlier-call")
        2 -> AgentModelResponse.FinalAnswer("First turn complete.")
        else -> AgentModelResponse.FinalAnswer("Second turn has no tool result.")
      }
    }
    val coordinator = AgentCoordinator(model, AgentToolRegistry(listOf(tool)))

    val firstRun = coordinator.run("Read the battery.")
    val secondRun = coordinator.run("What is the time?")

    assertEquals("earlier-call", firstRun.authoritativeToolResults.single().toolCallId)
    assertTrue(secondRun.authoritativeToolResults.isEmpty())
  }

  @Test
  fun structuredSideChannelIsNotRetainedWhenResultBudgetChangesTheContent() = runBlocking {
    val original = """{"value":123456789}"""
    val tool = resultTool("bounded") {
      AgentToolResult(original, structuredContent = JsonParser.parseString(original))
    }
    val coordinator = AgentCoordinator(
      model = AgentModel { history ->
        if (history.none { it.role == AgentMessage.Role.TOOL }) {
          AgentModelResponse.ToolCall("bounded", JsonObject(), "bounded-call")
        } else {
          AgentModelResponse.FinalAnswer("Done.")
        }
      },
      tools = AgentToolRegistry(listOf(tool)),
      resultBudget = AgentToolResultBudget(maxCharsPerTool = 10, maxCharsPerTurn = 10),
    )

    val captured = coordinator.run("Get the value.").authoritativeToolResults.single()

    assertTrue(captured.rawContent.length <= 10)
    assertNull(captured.structuredValue)
  }

  @Test
  fun preservesExactBoundedOutputWhitespaceAndDelimitersWhenModelCommentaryDiffers() = runBlocking {
    val rawOutput = "BEGIN|ALPHA|BRAVO|CHARLIE|END\n\n  "
    val tool = resultTool("execute_shell") { AgentToolResult(rawOutput) }
    var modelTurn = 0
    val model = AgentModel {
      modelTurn++
      if (modelTurn == 1) {
        AgentModelResponse.ToolCall("execute_shell", JsonObject(), "fidelity-call")
      } else {
        // Deliberately incorrect commentary must not mutate the independently retained result.
        AgentModelResponse.FinalAnswer("The output contained four fields.")
      }
    }

    val result = AgentCoordinator(model, AgentToolRegistry(listOf(tool))).run("Run the delimiter test.")

    assertEquals("The output contained four fields.", result.finalText)
    assertEquals(rawOutput, result.authoritativeToolResults.single().rawContent)
    assertEquals("fidelity-call", result.authoritativeToolResults.single().toolCallId)
  }

  @Test
  fun authoritativeOutputRemainsAvailableWhenModelProvidesNoCommentary() = runBlocking {
    val rawOutput = "uid=10486(u0_a486)\n"
    val tool = resultTool("execute_shell") { AgentToolResult(rawOutput) }
    var modelTurn = 0
    val model = AgentModel {
      modelTurn++
      if (modelTurn == 1) {
        AgentModelResponse.ToolCall("execute_shell", JsonObject(), "silent-call")
      } else {
        AgentModelResponse.FinalAnswer("")
      }
    }

    val result = AgentCoordinator(model, AgentToolRegistry(listOf(tool))).run("Run id.")

    assertEquals("", result.finalText)
    assertEquals(rawOutput, result.authoritativeToolResults.single().rawContent)
    assertTrue(result.authoritativeToolResults.single().succeeded)
  }

  private suspend fun runTool(tool: AgentTool, callId: String): AgentRunResult {
    var turn = 0
    val model = AgentModel {
      turn++
      if (turn == 1) {
        AgentModelResponse.ToolCall(tool.name, JsonObject(), callId)
      } else {
        AgentModelResponse.FinalAnswer("Done.")
      }
    }
    return AgentCoordinator(model, AgentToolRegistry(listOf(tool))).run("Run the tool.")
  }

  private fun resultTool(
    toolName: String,
    result: suspend () -> AgentToolResult,
  ) = object : AgentTool {
    override val name = toolName
    override val description = "Test result tool."
    override suspend fun execute(arguments: JsonObject): AgentToolResult = result()
  }
}
