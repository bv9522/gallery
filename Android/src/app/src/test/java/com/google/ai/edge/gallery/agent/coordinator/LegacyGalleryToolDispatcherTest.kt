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

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyGalleryToolDispatcherTest {
  @Test
  fun fullLegacyDispatcherExecutesWithoutAConversationToolManager() = runBlocking {
    val dispatcher = LegacyGalleryToolDispatcher(listOf(tool(TestLegacyToolSet())))
    val registry = dispatcher.registry()

    assertTrue(registry.names().contains("echo"))
    val result = checkNotNull(registry.find("echo")).execute(
      JsonObject().apply { addProperty("value", "independent") }
    )

    assertTrue(result.isSuccessful)
    assertEquals("echo:independent", result.content)
  }

  @Test
  fun nativeDispatcherKeepsNamePrecedenceOverLegacyTool() = runBlocking {
    val legacy = LegacyGalleryToolDispatcher(listOf(tool(TestLegacyToolSet())))
    val native = object : AgentTool {
      override val name = "echo"
      override val description = "Native echo."
      override suspend fun execute(arguments: JsonObject) = AgentToolResult("native")
    }

    val registry = legacy.registry(AgentToolRegistry(listOf(native)))
    val result = checkNotNull(registry.find("echo")).execute(JsonObject())

    assertEquals("native", result.content)
  }

  private class TestLegacyToolSet : ToolSet {
    @Tool(description = "Echo a value for dispatcher tests.")
    fun echo(@ToolParam(description = "Value to echo.") value: String): String = "echo:$value"
  }
}
