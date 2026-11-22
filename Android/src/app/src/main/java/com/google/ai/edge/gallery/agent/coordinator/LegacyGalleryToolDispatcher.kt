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

import com.google.ai.edge.litertlm.ToolManager
import com.google.ai.edge.litertlm.ToolProvider
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Application-owned dispatcher for Gallery ToolDefinitions.
 *
 * Its ToolManager is constructed from the complete Gallery provider inventory, independently of
 * any LiteRT-LM Conversation or the schemas currently exposed to the model. The coordinator remains
 * the only caller of this dispatcher; this class never executes a model request on its own.
 */
class LegacyGalleryToolDispatcher(toolProviders: List<ToolProvider>) {
  private val toolManager = ToolManager(toolProviders)

  /** Returns the complete legacy dispatcher, with native tools taking precedence on name clashes. */
  fun registry(preferredTools: AgentToolRegistry = AgentToolRegistry(emptyList())): AgentToolRegistry {
    val preferredNames = preferredTools.names()
    val legacyTools = toolManager.getToolsDescription().map { entry ->
      val function = entry.asJsonObject.getAsJsonObject("function")
      val toolName = function.get("name").asString
      val toolDescription = function.get("description")?.asString.orEmpty()
      val schema = function.getAsJsonObject("parameters")?.deepCopy() ?: JsonObject()
      object : AgentTool {
        override val name: String = toolName
        override val description: String = toolDescription
        override val parametersSchema: JsonObject = schema

        override suspend fun execute(arguments: JsonObject): AgentToolResult {
          val result = withContext(Dispatchers.IO) {
            toolManager.execute(toolName, arguments.deepCopy())
          }
          val content =
            if (result is JsonPrimitive && result.isString) result.asString else result.toString()
          val failed =
            (result.isJsonObject && (
              result.asJsonObject.get("status")?.asString == "failed" ||
                result.asJsonObject.has("error")
            )) ||
              (result is JsonPrimitive && result.isString &&
                result.asString.startsWith("Error occured."))
          return AgentToolResult(content, !failed)
        }
      }
    }
    return AgentToolRegistry(
      preferredTools.all() + legacyTools.filterNot { it.name in preferredNames }
    )
  }
}
