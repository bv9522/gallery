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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCapabilityGroupsTest {
  @Test
  fun deterministicRouterSelectsSpecificGroupsAndLeavesAmbiguousRequestsInCore() {
    assertEquals(
      AgentCapabilityGroups.ANDROID_CONNECTIVITY,
      AgentCapabilityGroups.route("Turn on Bluetooth."),
    )
    assertEquals(
      AgentCapabilityGroups.STORAGE_ARTIFACTS,
      AgentCapabilityGroups.route("Create a PDF report."),
    )
    assertEquals(
      AgentCapabilityGroups.TERMUX_API,
      AgentCapabilityGroups.route("Read the accelerometer sensor."),
    )
    assertEquals(
      AgentCapabilityGroups.TERMUX_API,
      AgentCapabilityGroups.route("Use Termux API to scan nearby Wi-Fi networks."),
    )
    assertEquals(AgentCapabilityGroups.ANDROID_SECURITY, AgentCapabilityGroups.route("Authenticate with biometrics."))
    assertEquals(null, AgentCapabilityGroups.route("Wi-Fi status"))
    assertEquals(null, AgentCapabilityGroups.route("What is the capital of France?"))
    assertEquals(null, AgentCapabilityGroups.route("Inspect the screen and save a PDF."))
  }

  @Test
  fun projectionAlwaysKeepsCoreAndOnlyAddsTheSelectedGroup() {
    val registry = AgentToolRegistry(
      listOf(
        testTool("execute_shell"),
        testTool("discover_capability_groups"),
        testTool("activate_capability_group"),
        *AgentCapabilityGroups.androidNativeGroups.map { testTool(it.id) }.toTypedArray(),
        testTool("media_sessions"),
        testTool("screen_inspect"),
        testTool("share_content"),
        testTool("document_tree"),
        testTool("artifact_create"),
        testTool("termux_api"),
        testTool("unknown_tool"),
      )
    )

    val projected = AgentCapabilityGroups.project(
      registry,
      setOf(AgentCapabilityGroups.STORAGE_ARTIFACTS),
    )

    assertEquals(
      setOf(
        "execute_shell",
        "discover_capability_groups",
        "activate_capability_group",
        "document_tree",
        "artifact_create",
      ),
      projected.names(),
    )
    assertTrue(registry.names().contains("unknown_tool"))
    assertFalse(projected.names().contains("unknown_tool"))
  }

  @Test
  fun projectionFailsClosedWhenAGroupToolIsMissing() {
    val incomplete = AgentToolRegistry(
      listOf(
        testTool("execute_shell"),
        testTool("discover_capability_groups"),
        testTool("activate_capability_group"),
        testTool("document_tree"),
      )
    )

    try {
      AgentCapabilityGroups.project(incomplete, setOf(AgentCapabilityGroups.STORAGE_ARTIFACTS))
      throw AssertionError("Expected an incomplete group mapping to fail closed.")
    } catch (expected: IllegalArgumentException) {
      assertTrue(expected.message.orEmpty().contains("artifact_create"))
    }
  }

  @Test
  fun eachNativeToolBelongsToAtMostOneGroup() {
    val assignedTools = AgentCapabilityGroups.definitions.flatMap { it.toolNames }
    assertEquals(assignedTools.size, assignedTools.toSet().size)
  }

  @Test
  fun everyNativeAndroidOperationBelongsToExactlyOneFocusedGroup() {
    val allOperations = AgentCapabilityGroups.androidNativeGroups.flatMap { it.operationNames }
    assertEquals(55, allOperations.size)
    assertEquals(allOperations.size, allOperations.toSet().size)
    assertEquals(
      setOf(
        "status", "download_status", "cancel_download", "device_info", "telephony_info", "dial_phone",
        "compose_sms", "biometric_auth", "keystore_create", "keystore_list", "keystore_delete",
        "keystore_encrypt", "keystore_decrypt", "battery_status", "volume_status", "audio_devices",
        "display_brightness", "set_display_brightness", "vibrate", "wake_lock_status", "set_wake_lock",
        "list_apps", "set_volume", "set_flashlight", "launch_app", "open_url", "wifi_open_settings",
        "bluetooth_bonded_devices", "bluetooth_le_scan", "bluetooth_gatt_services", "bluetooth_gatt_read",
        "bluetooth_gatt_write", "contacts_search", "clipboard_read", "clipboard_write", "post_notification",
        "list_posted_notifications", "cancel_posted_notification", "schedule_notification",
        "list_scheduled_notifications", "cancel_scheduled_notification", "list_notification_channels",
        "create_notification_channel", "read_notifications", "read_call_log", "read_sms_inbox",
        "usb_devices", "usb_read_descriptors", "record_audio", "capture_photo", "set_wallpaper",
        "speak_text", "media_search", "media_index", "media_play",
      ),
      allOperations.toSet(),
    )
  }

  @Test
  fun discoveryIsReadOnlyAndReturnsOnlyCompactGroupMetadata() = runBlocking {
    val result = DiscoverCapabilityGroupsTool().execute(
      JsonObject().apply { addProperty("query", "pdf") }
    )

    assertTrue(result.isSuccessful)
    assertTrue(result.content.contains(AgentCapabilityGroups.STORAGE_ARTIFACTS))
    assertFalse(result.content.contains("execute_shell"))
    assertFalse(result.content.contains("permission"))
  }

  @Test
  fun broadListQueryReturnsEveryAvailableCapabilityGroup() = runBlocking {
    val result = DiscoverCapabilityGroupsTool().execute(
      JsonObject().apply { addProperty("query", "List the available capability groups") }
    )

    assertTrue(result.isSuccessful)
    listOf(
      AgentCapabilityGroups.ANDROID_DEVICE_INFO,
      AgentCapabilityGroups.ANDROID_SYSTEM_CONTROLS,
      AgentCapabilityGroups.ANDROID_CONNECTIVITY,
      AgentCapabilityGroups.ANDROID_COMMUNICATIONS,
      AgentCapabilityGroups.ANDROID_SECURITY,
      AgentCapabilityGroups.ANDROID_MEDIA,
      AgentCapabilityGroups.ANDROID_DOWNLOADS,
      AgentCapabilityGroups.SCREEN_MEDIA,
      AgentCapabilityGroups.STORAGE_ARTIFACTS,
      AgentCapabilityGroups.TERMUX_API,
    ).forEach { groupId -> assertTrue("Missing group $groupId", result.content.contains(groupId)) }
  }

  @Test
  fun unmatchedDiscoveryQueryFallsBackToCompactCatalog() = runBlocking {
    val result = DiscoverCapabilityGroupsTool().execute(
      JsonObject().apply { addProperty("query", "quantum computing") }
    )

    assertTrue(result.isSuccessful)
    assertTrue(result.content.contains(AgentCapabilityGroups.ANDROID_DEVICE_INFO))
    assertTrue(result.content.contains(AgentCapabilityGroups.TERMUX_API))
  }

  @Test
  fun activationOnlyRequestsSchemaExposureAndNeverGrantsApproval() = runBlocking {
    val tool = ActivateCapabilityGroupTool()
    val result = tool.execute(
      JsonObject().apply { addProperty("group_id", AgentCapabilityGroups.TERMUX_API) }
    )

    assertTrue(result.isSuccessful)
    assertTrue(result.content.contains("activation_requested"))
    assertTrue(result.content.contains("No permission or operation approval"))
  }

  @Test
  fun activationRejectsUnknownGroupIds() = runBlocking {
    val result = ActivateCapabilityGroupTool().execute(
      JsonObject().apply { addProperty("group_id", "not-a-group") }
    )

    assertFalse(result.isSuccessful)
    assertTrue(result.content.contains("Unknown capability group"))
  }

  private fun testTool(toolName: String) = object : AgentTool {
    override val name = toolName
    override val description = "Test capability."
    override suspend fun execute(arguments: JsonObject) = AgentToolResult("ok")
  }
}
