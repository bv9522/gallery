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

/** Immutable registry that rejects ambiguous tool names at construction time. */
class AgentToolRegistry(tools: List<AgentTool>) {
  private val toolsByName: Map<String, AgentTool>

  init {
    require(tools.all { it.name.isNotBlank() }) { "Tool names must not be blank." }
    val grouped = tools.groupBy { it.name }
    val duplicates = grouped.filterValues { it.size > 1 }.keys
    require(duplicates.isEmpty()) {
      "Duplicate agent tool names: " + duplicates.sorted().joinToString(", ")
    }
    toolsByName = tools.associateBy { it.name }
  }

  fun find(name: String): AgentTool? = toolsByName[name]

  fun names(): Set<String> = toolsByName.keys

  /** Stable snapshot used to construct model-visible tool descriptions. */
  fun all(): List<AgentTool> = toolsByName.values.toList()
}