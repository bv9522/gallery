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

/** A callable capability exposed to the agent model. */
interface AgentTool {
  /** Stable, model-visible name. Must be unique within an [AgentToolRegistry]. */
  val name: String

  /** Short description supplied to model adapters when constructing tool definitions. */
  val description: String

  /** JSON Schema object describing the accepted arguments. */
  val parametersSchema: JsonObject
    get() = JsonObject().apply { addProperty("type", "object") }

  /** Validate arguments and execute the capability. Expected failures should be returned as results. */
  suspend fun execute(arguments: JsonObject): AgentToolResult
}

/** Result returned to the model after a tool invocation. */
data class AgentToolResult(
  val content: String,
  val isSuccessful: Boolean = true,
)
