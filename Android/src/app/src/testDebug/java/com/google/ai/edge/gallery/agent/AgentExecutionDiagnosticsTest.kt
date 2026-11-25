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

import com.google.gson.JsonObject
import com.google.ai.edge.gallery.agent.coordinator.AgentCoordinator
import com.google.ai.edge.gallery.agent.coordinator.AgentModel
import com.google.ai.edge.gallery.agent.coordinator.AgentModelResponse
import com.google.ai.edge.gallery.agent.coordinator.AgentTool
import com.google.ai.edge.gallery.agent.coordinator.AgentToolRegistry
import com.google.ai.edge.gallery.agent.coordinator.AgentToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentExecutionDiagnosticsTest {
  @Test
  fun traceContainsModelAndDispatchMetadataButNeverArgumentOrResultValues() {
    AgentExecutionDiagnostics.begin()
    AgentExecutionDiagnostics.recordModelInput(1, listOf("execute_shell", "android_device_info"), 812)
    AgentExecutionDiagnostics.recordModelOutput(
      1,
      listOf(AgentExecutionDiagnostics.ToolCallSummary("execute_shell", listOf("backend", "command"))),
      0,
      listOf("analysis"),
      false,
    )
    val arguments = JsonObject().apply {
      addProperty("backend", "TERMUX")
      addProperty("command", "echo PRIVATE_COMMAND_VALUE")
      addProperty("operation", "battery_status")
    }
    AgentExecutionDiagnostics.recordToolDispatch(
      "execute_shell", arguments, "dispatched", false, 19, "Permission denied for PRIVATE_RESULT_VALUE",
    )
    AgentExecutionDiagnostics.finish("failed")

    val snapshot = AgentExecutionDiagnostics.consume()
    assertNotNull(snapshot)
    val trace = snapshot!!.entries.joinToString(" ") { it.third }
    assertTrue(trace.contains("structuredToolCalls=1"))
    assertTrue(trace.contains("backend=TERMUX"))
    assertTrue(trace.contains("operation=battery_status"))
    assertTrue(trace.contains("args=[backend, command, operation]"))
    assertFalse(trace.contains("PRIVATE_COMMAND_VALUE"))
    assertFalse(trace.contains("PRIVATE_RESULT_VALUE"))
  }

  @Test
  fun capturesExactDeviceInfoHandoffRawModelOutputAndDeliveredResponse() {
    AgentExecutionDiagnostics.begin()
    val envelope = """{"tool":"android_device_info","success":true,"result":{"battery_percent":42}}"""
    AgentExecutionDiagnostics.recordModelToolHandoff(
      2,
      listOf(
        "android_device_info" to envelope,
        "android_communications" to """{"result":"PRIVATE_MESSAGE_BODY"}""",
      ),
    )
    AgentExecutionDiagnostics.recordRawModelOutput(2, "Battery level is 2%.")
    AgentExecutionDiagnostics.recordDeliveredResponse(
      "Battery level is 2%.\n\nVerified tool results: battery_percent=42"
    )
    AgentExecutionDiagnostics.finish("completed")

    val snapshot = AgentExecutionDiagnostics.consume()
    assertNotNull(snapshot)
    val trace = snapshot!!.entries.joinToString("\\n") { "${it.second}: ${it.third}" }
    assertTrue(trace.contains(envelope))
    assertTrue(trace.contains("Raw model output"))
    assertTrue(trace.contains("Battery level is 2%."))
    assertTrue(trace.contains("Delivered response"))
    assertTrue(trace.contains("battery_percent=42"))
    assertFalse(trace.contains("PRIVATE_MESSAGE_BODY"))
  }

  @Test
  fun coordinatorDispatchTraceShowsNativeRouteAndExecutionOutcome() = runBlocking {
    val nativeTool = object : AgentTool {
      override val name = "android_device_info"
      override val description = "Read device information."
      override suspend fun execute(arguments: JsonObject) = AgentToolResult("PRIVATE_DEVICE_RESULT")
    }
    var turn = 0
    val model = AgentModel {
      turn++
      if (turn == 1) {
        AgentModelResponse.ToolCall(
          "android_device_info",
          JsonObject().apply { addProperty("operation", "battery_status") },
          "diagnostic-call",
        )
      } else {
        AgentModelResponse.FinalAnswer("Completed.")
      }
    }
    AgentExecutionDiagnostics.begin()
    AgentExecutionDiagnostics.recordModelInput(1, listOf("android_device_info"), 100)
    val result = AgentCoordinator(model, AgentToolRegistry(listOf(nativeTool))).run("Check battery.")
    AgentExecutionDiagnostics.finish(if (result.isSuccessful) "completed" else "failed")

    val snapshot = AgentExecutionDiagnostics.consume()
    assertNotNull(snapshot)
    val trace = snapshot!!.entries.joinToString(" ") { it.third }
    assertTrue(result.isSuccessful)
    assertTrue(trace.contains("route=ANDROID_NATIVE"))
    assertTrue(trace.contains("identity=GALLERY_APP_UID"))
    assertTrue(trace.contains("operation=battery_status"))
    assertTrue(trace.contains("durationMs="))
    assertFalse(trace.contains("PRIVATE_DEVICE_RESULT"))
  }

  @Test
  fun malformedToolMarkerIsRecordedWithoutRetainingModelOutput() {
    AgentExecutionDiagnostics.begin()
    AgentExecutionDiagnostics.recordModelOutput(2, emptyList(), 48, emptyList(), true)
    AgentExecutionDiagnostics.finish("failed")
    val snapshot = AgentExecutionDiagnostics.consume()
    assertNotNull(snapshot)
    val trace = snapshot!!.entries.joinToString(" ") { it.third }
    assertTrue(trace.contains("unparsedToolMarkers=present"))
    assertFalse(trace.contains("<|tool_call>"))
  }
}
