/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.agent.coordinator

import com.google.gson.JsonObject

/**
 * Stable model-visible capability group identifiers. The complete registry remains application
 * side; a group only controls which schemas are projected into the active LiteRT-LM conversation.
 */
object AgentCapabilityGroups {
  const val CORE = "core"
  const val ANDROID_CONTROLS = "android_controls"
  const val SCREEN_MEDIA = "screen_media"
  const val STORAGE_ARTIFACTS = "storage_artifacts"
  const val TERMUX_API = "termux_api"

  data class Definition(
    val id: String,
    val title: String,
    val description: String,
    val keywords: Set<String>,
    val toolNames: Set<String>,
  )

  val definitions = listOf(
    Definition(
      CORE,
      "Core execution",
      "General shell execution plus read-only capability discovery and group activation.",
      emptySet(),
      setOf("execute_shell", "discover_capability_groups", "activate_capability_group"),
    ),
    Definition(
      ANDROID_CONTROLS,
      "Android controls",
      "Android device information and supported system, connectivity, and communication controls.",
      setOf("bluetooth", "wifi", "wi-fi", "notification", "notifications", "telephony", "phone", "sms", "call", "device setting", "brightness", "volume", "airplane mode", "battery"),
      setOf("android_control"),
    ),
    Definition(
      SCREEN_MEDIA,
      "Screen and media",
      "Approved screen inspection, active media sessions, media operations, and Android sharing.",
      setOf("screen", "screenshot", "accessibility", "media session", "media playback", "music", "share", "sharing", "cast"),
      setOf("media_sessions", "screen_inspect", "share_content"),
    ),
    Definition(
      STORAGE_ARTIFACTS,
      "Storage and artifacts",
      "Gallery SAF document trees, USB storage through SAF, and bounded document or image artifacts.",
      setOf("file", "files", "folder", "directory", "usb", "storage", "saf", "pdf", "docx", "xlsx", "pptx", "zip", "spreadsheet", "document", "artifact", "external drive"),
      setOf("document_tree", "artifact_create"),
    ),
    Definition(
      TERMUX_API,
      "Termux:API",
      "Bounded Termux:API sensor, location, NFC, infrared, camera, and related device APIs.",
      setOf("termux api", "sensor", "accelerometer", "gyroscope", "magnetometer", "light sensor", "proximity", "location", "gps", "nfc", "infrared", "ir blaster", "camera"),
      setOf("termux_api"),
    ),
  )

  private val byId = definitions.associateBy { it.id }
  private val toolToGroup = definitions.flatMap { group ->
    group.toolNames.map { it to group.id }
  }.toMap()

  fun find(id: String): Definition? = byId[id]

  fun groupForTool(toolName: String): String? = toolToGroup[toolName]

  /** The core is always visible; unknown tools are not silently promoted into the model surface. */
  fun project(fullRegistry: AgentToolRegistry, selectedGroupIds: Set<String>): AgentToolRegistry {
    require(selectedGroupIds.all { find(it) != null && it != CORE }) {
      "Selected capability groups must be known non-core group IDs."
    }
    val allowedGroups = selectedGroupIds + CORE
    val allowedNames = definitions
      .filter { it.id in allowedGroups }
      .flatMap { it.toolNames }
      .toSet()
    val missingTools = allowedNames - fullRegistry.names()
    require(missingTools.isEmpty()) {
      "Capability group registry is missing required tools: ${missingTools.sorted().joinToString(", ")}"
    }
    return AgentToolRegistry(fullRegistry.all().filter { it.name in allowedNames })
  }

  /** Deterministic, side-effect-free routing. A tie or no match selects no new group. */
  fun route(userText: String): String? {
    val normalized = userText.lowercase()
    val scored = definitions
      .filter { it.id != CORE }
      .map { group ->
        group to group.keywords.count { keyword ->
          Regex("(^|[^a-z0-9])" + Regex.escape(keyword) + "([^a-z0-9]|$)")
            .containsMatchIn(normalized)
        }
      }
      .filter { it.second > 0 }
      .sortedByDescending { it.second }
    if (scored.isEmpty()) return null
    if (scored.size > 1 && scored[0].second == scored[1].second) return null
    return scored.first().first.id
  }
}

/** Read-only catalog lookup. It never probes providers or requests Android permissions. */
class DiscoverCapabilityGroupsTool : AgentTool {
  override val name = "discover_capability_groups"
  override val description =
    "Search the available capability groups by task keywords. Read-only; does not activate or execute anything."
  override val parametersSchema = JsonObject().apply {
    addProperty("type", "object")
    add("properties", JsonObject().apply {
      add("query", JsonObject().apply {
        addProperty("type", "string")
        addProperty("description", "Task keywords used to find relevant capability groups.")
      })
    })
    add("required", com.google.gson.JsonArray().apply { add("query") })
    addProperty("additionalProperties", false)
  }

  override suspend fun execute(arguments: JsonObject): AgentToolResult {
    val query = arguments.get("query")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().lowercase()
    val terms = query.split(Regex("\\s+")).filter { it.isNotBlank() }
    val matches = AgentCapabilityGroups.definitions
      .filter { it.id != AgentCapabilityGroups.CORE }
      .map { group ->
        group to terms.count { term ->
          group.title.lowercase().contains(term) ||
            group.description.lowercase().contains(term) ||
            group.keywords.any { it.contains(term) || term.contains(it) }
        }
      }
      .filter { terms.isEmpty() || it.second > 0 }
      .sortedByDescending { it.second }
      .take(5)
      .map { (group, _) ->
        """{"group_id":"${group.id}","name":"${group.title}","description":"${group.description}"}"""
      }
    return AgentToolResult(matches.joinToString(prefix = "[", postfix = "]"))
  }
}

/**
 * A request to expose a group. The LiteRtLmAgentModel consumes this result at the next tool-result
 * boundary and rebuilds the conversation; this tool itself performs no provider operation.
 */
class ActivateCapabilityGroupTool : AgentTool {
  override val name = "activate_capability_group"
  override val description =
    "Request model-visible access to one capability group. This only changes available schemas; it does not grant permission or approve operations."
  override val parametersSchema = JsonObject().apply {
    addProperty("type", "object")
    add("properties", JsonObject().apply {
      add("group_id", JsonObject().apply {
        addProperty("type", "string")
        addProperty("enum", AgentCapabilityGroups.definitions.map { it.id }
          .filter { it != AgentCapabilityGroups.CORE }
          .let { values -> com.google.gson.JsonArray().also { array -> values.forEach { value -> array.add(value) } } })
        addProperty("description", "Exact group_id returned by discover_capability_groups.")
      })
    })
    add("required", com.google.gson.JsonArray().apply { add("group_id") })
    addProperty("additionalProperties", false)
  }

  override suspend fun execute(arguments: JsonObject): AgentToolResult {
    val id = arguments.get("group_id")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    val group = AgentCapabilityGroups.find(id)
      ?.takeIf { it.id != AgentCapabilityGroups.CORE }
      ?: return AgentToolResult(
        """{"status":"failed","error":"Unknown capability group. Discover available groups first."}""",
        isSuccessful = false,
      )
    return AgentToolResult(
      """{"status":"activation_requested","group_id":"${group.id}","name":"${group.title}","note":"No permission or operation approval has been granted."}"""
    )
  }
}
