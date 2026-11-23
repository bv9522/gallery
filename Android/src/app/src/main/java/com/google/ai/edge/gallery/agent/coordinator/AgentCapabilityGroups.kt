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
import java.util.Locale

/**
 * Stable model-visible capability group identifiers. The complete registry remains application
 * side; a group only controls which schemas are projected into the active LiteRT-LM conversation.
 */
object AgentCapabilityGroups {
  const val CORE = "core"
  const val ANDROID_DEVICE_INFO = "android_device_info"
  const val ANDROID_SYSTEM_CONTROLS = "android_system_controls"
  const val ANDROID_CONNECTIVITY = "android_connectivity"
  const val ANDROID_COMMUNICATIONS = "android_communications"
  const val ANDROID_SECURITY = "android_security"
  const val ANDROID_MEDIA = "android_media"
  const val ANDROID_DOWNLOADS = "android_downloads"
  const val SCREEN_MEDIA = "screen_media"
  const val STORAGE_ARTIFACTS = "storage_artifacts"
  const val TERMUX_API = "termux_api"

  data class Definition(
    val id: String,
    val title: String,
    val description: String,
    val keywords: Set<String>,
    val toolNames: Set<String>,
    val operationNames: Set<String> = emptySet(),
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
      ANDROID_DEVICE_INFO, "Android device information",
      "Read device, battery, audio, display, app, and provider status.",
      setOf("device info", "device status", "battery", "audio devices", "display status", "installed apps", "telephony info"),
      setOf(ANDROID_DEVICE_INFO),
      setOf("status", "device_info", "telephony_info", "battery_status", "volume_status", "audio_devices", "display_brightness", "wake_lock_status", "list_apps"),
    ),
    Definition(
      ANDROID_SYSTEM_CONTROLS, "Android system controls",
      "Control supported brightness, volume, flashlight, clipboard, wake lock, apps, links, speech, and wallpaper.",
      setOf("brightness", "volume", "flashlight", "vibrate", "wake lock", "clipboard", "launch app", "open url", "wifi settings", "wallpaper", "speak text"),
      setOf(ANDROID_SYSTEM_CONTROLS),
      setOf("set_display_brightness", "vibrate", "set_wake_lock", "set_volume", "set_flashlight", "launch_app", "open_url", "wifi_open_settings", "clipboard_read", "clipboard_write", "speak_text", "set_wallpaper"),
    ),
    Definition(
      ANDROID_CONNECTIVITY, "Android connectivity",
      "Inspect Bluetooth devices, BLE/GATT services, and connected USB devices and descriptors.",
      setOf("bluetooth", "bluetooth le", "gatt", "usb devices", "usb descriptors", "connectivity"),
      setOf(ANDROID_CONNECTIVITY),
      setOf("bluetooth_bonded_devices", "bluetooth_le_scan", "bluetooth_gatt_services", "bluetooth_gatt_read", "bluetooth_gatt_write", "usb_devices", "usb_read_descriptors"),
    ),
    Definition(
      ANDROID_COMMUNICATIONS, "Android communications",
      "Search contacts and access calls, SMS, and notification operations with explicit scoped approval.",
      setOf("contacts", "contact search", "phone", "dial", "sms", "text message", "call log", "notifications", "notification channel"),
      setOf(ANDROID_COMMUNICATIONS),
      setOf("dial_phone", "compose_sms", "contacts_search", "post_notification", "list_posted_notifications", "cancel_posted_notification", "schedule_notification", "list_scheduled_notifications", "cancel_scheduled_notification", "list_notification_channels", "create_notification_channel", "read_notifications", "read_call_log", "read_sms_inbox"),
    ),
    Definition(
      ANDROID_SECURITY, "Android security",
      "Use biometric authentication and the app-scoped Android Keystore.",
      setOf("biometric", "biometrics", "keystore", "encrypt", "decrypt", "cryptographic key"),
      setOf(ANDROID_SECURITY),
      setOf("biometric_auth", "keystore_create", "keystore_list", "keystore_delete", "keystore_encrypt", "keystore_decrypt"),
    ),
    Definition(
      ANDROID_MEDIA, "Android media library",
      "Search and index Gallery media, play selected videos, record audio, and capture photos.",
      setOf("media search", "photo capture", "take photo", "record audio", "media index", "gallery media"),
      setOf(ANDROID_MEDIA),
      setOf("media_search", "media_index", "media_play", "record_audio", "capture_photo"),
    ),
    Definition(
      ANDROID_DOWNLOADS, "Gallery downloads",
      "Inspect active Gallery model downloads and request cancellation of a selected download.",
      setOf("download status", "active downloads", "cancel download", "model download"),
      setOf(ANDROID_DOWNLOADS),
      setOf("download_status", "cancel_download"),
    ),
    Definition(
      SCREEN_MEDIA,
      "Screen and media",
      "Approved screen inspection, active media sessions, and Android sharing.",
      setOf("screen", "screenshot", "accessibility", "media session", "music", "share", "sharing", "cast"),
      setOf("media_sessions", "screen_inspect", "share_content"),
    ),
    Definition(
      STORAGE_ARTIFACTS,
      "Storage and artifacts",
      "Gallery SAF document trees, USB mass storage through SAF, and bounded document or image artifacts.",
      setOf("file", "files", "folder", "directory", "usb storage", "external drive", "saf", "pdf", "docx", "xlsx", "pptx", "zip", "spreadsheet", "document", "artifact"),
      setOf("document_tree", "artifact_create"),
    ),
    Definition(
      TERMUX_API,
      "Termux:API",
      "Bounded Termux:API sensor, location, NFC, infrared, camera, and related device APIs.",
      setOf("termux api", "sensor", "accelerometer", "gyroscope", "magnetometer", "light sensor", "proximity", "location", "gps", "wifi scan", "network scan", "nfc", "infrared", "ir blaster"),
      setOf("termux_api"),
    ),
  )

  val androidNativeGroups = definitions.filter { it.operationNames.isNotEmpty() }

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
    val normalized = userText.lowercase(Locale.ROOT)
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
    "List available capability groups, or search by task keywords. Broad or unmatched queries return the compact full catalog. Read-only; does not activate or execute anything."
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
    val query = arguments.get("query")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().lowercase(Locale.ROOT)
    val terms = query.split(Regex("\\s+")).filter { it.isNotBlank() }
    val rankedGroups = AgentCapabilityGroups.definitions
      .filter { it.id != AgentCapabilityGroups.CORE }
      .map { group ->
        group to terms.count { term ->
          group.title.lowercase(Locale.ROOT).contains(term) ||
            group.description.lowercase(Locale.ROOT).contains(term) ||
            group.keywords.any { it.contains(term) || term.contains(it) }
        }
      }
    val matchingGroups = rankedGroups.filter { it.second > 0 }
    // This catalog is intentionally small. Broad/list requests and unmatched queries should
    // reveal the catalog rather than falsely reporting that no capabilities exist.
    val candidates = if (terms.isEmpty() || matchingGroups.isEmpty()) rankedGroups else matchingGroups
    val resultLimit = if (terms.isEmpty() || matchingGroups.isEmpty()) candidates.size else 5
    val matches = candidates
      .sortedByDescending { it.second }
      .take(resultLimit)
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
        add("enum", AgentCapabilityGroups.definitions.map { it.id }
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
