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
