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

package com.google.ai.edge.gallery.agent.capability

import android.content.Context
import com.google.ai.edge.gallery.agent.coordinator.AgentTool
import com.google.ai.edge.gallery.agent.coordinator.AgentToolResult
import com.google.ai.edge.gallery.tools.AskNativeCapabilityPermissionAction
import com.google.ai.edge.gallery.tools.ToolAction
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withTimeoutOrNull

/** Sends exact, one-time native mutation requests through Agent Chat's existing action channel. */
class ChannelNativeCapabilityAuthorizer(
  private val actionChannel: SendChannel<ToolAction>,
  private val approvalTimeoutMs: Long = 120_000L,
) : NativeCapabilityAuthorizer {
  override suspend fun authorize(capability: AndroidCapability, summary: String): Boolean {
    val action = AskNativeCapabilityPermissionAction(capability.name, summary)
    return try {
      withTimeoutOrNull(approvalTimeoutMs) {
        actionChannel.send(action)
        action.result.await()
      } ?: false
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      false
    } finally {
      if (!action.result.isCompleted) action.result.complete(false)
    }
  }
}

/**
 * Compact model-facing interface for native Android capabilities.
 *
 * Ordinary state reads do not prompt. Privacy-sensitive device discovery and mutations require
 * one-time approval; runtime Android permissions are requested only when needed. Every operation
 * is routed through the capability registry; this tool does not fall back to a shell provider.
 */
class AndroidNativeCapabilityTool internal constructor(
  private val adapter: NativeCapabilityOperations,
  private val registry: CapabilityRegistry = CapabilityCatalog.createRegistry(),
) : AgentTool {
  constructor(
    context: Context,
    actionChannel: SendChannel<ToolAction>,
    registry: CapabilityRegistry = CapabilityCatalog.createRegistry(),
  ) : this(
    AndroidNativeCapabilityAdapter(
      context = context.applicationContext,
      authorizer = ChannelNativeCapabilityAuthorizer(actionChannel),
      permissionRequester = ChannelNativeRuntimePermissionRequester(actionChannel),
      photoCaptureRequester = ChannelNativePhotoCaptureRequester(actionChannel),
    ),
    registry,
  )

  override val name: String = "android_control"
  override val description: String =
    "Read device, battery, connected audio endpoint, brightness, and wake-lock information, inspect volume " +
      "and launchable apps, or perform an approved native Android action. Use status to inspect " +
      "availability of each native Android " +
      "capability; status is a provider diagnostic, not a battery or volume reading. Use operation " +
      "values: status, device_info, battery_status, volume_status, list_apps, set_volume, " +
      "set_flashlight, launch_app, open_url, audio_devices, display_brightness, " +
      "set_display_brightness, vibrate, wake_lock_status, set_wake_lock, bluetooth_bonded_devices, " +
      "bluetooth_le_scan, bluetooth_gatt_services, bluetooth_gatt_read, bluetooth_gatt_write, usb_devices, " +
      "record_audio, capture_photo, set_wallpaper. Device discovery, GATT connections, GATT reads/writes, " +
      "audio recording (1–30 seconds, local MediaStore output), photo capture, and wallpaper changes " +
      "require one-time user approval. Bluetooth permissions are requested only after approval. BLE scans " +
      "are limited to 1–10 seconds; each GATT operation connects to one address for at most 20 seconds, " +
      "performs one bounded action, and disconnects on completion, timeout, or cancellation. GATT writes " +
      "are limited to 512 bytes and require approval showing the exact Base64 payload. No subscriptions or " +
      "persistent connections are retained. USB I/O is not yet exposed. This tool never executes shell commands."

  override val parametersSchema: JsonObject
    get() = JsonObject().apply {
      addProperty("type", "object")
      add("properties", JsonObject().apply {
        add("operation", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply {
            listOf(
              "status", "device_info", "battery_status", "volume_status", "audio_devices",
              "display_brightness", "set_display_brightness", "vibrate", "wake_lock_status",
              "set_wake_lock", "list_apps", "set_volume", "set_flashlight", "launch_app", "open_url", "bluetooth_bonded_devices", "bluetooth_le_scan", "bluetooth_gatt_services", "bluetooth_gatt_read", "bluetooth_gatt_write", "contacts_search", "clipboard_read", "clipboard_write", "usb_devices", "record_audio", "capture_photo", "set_wallpaper",
            ).forEach { add(it) }
          })
        })
        add("stream", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply { NativeAudioStream.values().forEach { add(it.name) } })
          addProperty("description", "Audio stream for volume_status or set_volume; defaults to MUSIC.")
        })
        add("volume", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("description", "Target volume; must be within the selected stream's range.")
        })
        add("level", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("description", "Display brightness level from 0 through 255.")
        })
        add("duration_ms", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("description", "Operation-specific duration: vibration 1–1000 ms, wake lock 1–300000 ms, Bluetooth LE scan 1000–10000 ms (defaults to 5000), audio recording 1000–30000 ms (defaults to 5000).")
        })
        add("content_uri", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Gallery-readable image content URI, such as one returned by capture_photo or a user-granted picker.")
        })
        add("target", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply { listOf("SYSTEM", "LOCK", "BOTH").forEach { add(it) } })
        })
        add("held", JsonObject().apply {
          addProperty("type", "boolean")
          addProperty("description", "True to acquire a bounded CPU wake lock; false to release it.")
        })
        add("enabled", JsonObject().apply {
          addProperty("type", "boolean")
          addProperty("description", "Whether to turn the flashlight on or off.")
        })
        add("package_name", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Exact package name returned by list_apps.")
        })
        add("url", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Absolute HTTP(S) URL with a host.")
        })
        add("device_address", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Bluetooth MAC address returned by bluetooth_bonded_devices or bluetooth_le_scan.")
        })
        add("timeout_ms", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("description", "Overall Bluetooth GATT connection, discovery, and characteristic operation timeout, 5000–20000 ms; defaults to 15000.")
        })
        add("service_uuid", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Full service UUID returned by bluetooth_gatt_services.")
        })
        add("characteristic_uuid", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Full characteristic UUID returned by bluetooth_gatt_services.")
        })
        add("value_base64", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "For bluetooth_gatt_write only: 1–512 bytes encoded as unwrapped Base64.")
        })
        add("query", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Name or text query for contacts_search; 1–80 characters.")
          addProperty("minLength", 1)
          addProperty("maxLength", 80)
        })
        add("limit", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("description", "Maximum matching contacts to return; 1–20, defaults to 10.")
          addProperty("minimum", MIN_CONTACT_LIMIT)
          addProperty("maximum", MAX_CONTACT_LIMIT)
        })
        add("text", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Text to place on the clipboard; maximum 2048 characters.")
          addProperty("maxLength", MAX_CLIPBOARD_WRITE_CHARS)
        })
      })
      add("required", JsonArray().apply { add("operation") })
      addProperty("additionalProperties", false)
    }

  override suspend fun execute(arguments: JsonObject): AgentToolResult {
    AndroidControlArguments.validate(arguments)?.let { return failure(it) }
    val operation = arguments.string("operation")
      ?: return failure("Missing required string argument: operation.")

    return try {
      when (operation) {
        "status" -> status()
        "device_info" -> read(AndroidCapability.DEVICE_INFO) {
          NativeCapabilityResultFormatter.deviceInfo(adapter.getDeviceInfo())
        }
        "battery_status" -> read(AndroidCapability.BATTERY_STATUS) {
          NativeCapabilityResultFormatter.batteryStatus(adapter.getBatteryStatus())
        }
        "volume_status" -> {
          val stream = audioStream(arguments) ?: return failure("Unsupported audio stream.")
          read(AndroidCapability.VOLUME_CONTROL) { Gson().toJson(adapter.getVolumeStatus(stream)) }
        }
        "audio_devices" -> read(AndroidCapability.AUDIO_DEVICE_INFO) {
          NativeCapabilityResultFormatter.audioDevices(adapter.getAudioDevices())
        }
        "display_brightness" -> read(AndroidCapability.DISPLAY_BRIGHTNESS) {
          NativeCapabilityResultFormatter.displayBrightness(adapter.getDisplayBrightness())
        }
        "set_display_brightness" -> {
          val level = arguments.int("level")
            ?: return failure("set_display_brightness requires integer level from 0 to 255.")
          mutate(AndroidCapability.DISPLAY_BRIGHTNESS) { adapter.setDisplayBrightness(level) }
        }
        "vibrate" -> {
          val duration = arguments.long("duration_ms")
            ?: return failure("vibrate requires integer duration_ms from 1 to 1000.")
          mutate(AndroidCapability.VIBRATION_CONTROL) { adapter.vibrate(duration) }
        }
        "wake_lock_status" -> read(AndroidCapability.WAKE_LOCK) {
          "wakeLockHeld=${adapter.isWakeLockHeld()}"
        }
        "set_wake_lock" -> {
          val held = arguments.boolean("held")
            ?: return failure("set_wake_lock requires boolean held.")
          val duration = if (held) {
            arguments.long("duration_ms")
              ?: return failure("Acquiring a wake lock requires duration_ms from 1 to 300000.")
          } else {
            0L
          }
          mutate(AndroidCapability.WAKE_LOCK) { adapter.setWakeLock(held, duration) }
        }
        "list_apps" -> read(AndroidCapability.APP_DISCOVERY) {
          NativeCapabilityResultFormatter.launchableApps(adapter.listLaunchableApps())
        }
        "set_volume" -> {
          val stream = audioStream(arguments) ?: return failure("Unsupported audio stream.")
          val volume = arguments.int("volume") ?: return failure("set_volume requires integer volume.")
          mutate(AndroidCapability.VOLUME_CONTROL) {
            adapter.setVolume(stream, volume)
          }
        }
        "set_flashlight" -> {
          val enabled = arguments.boolean("enabled")
            ?: return failure("set_flashlight requires boolean enabled.")
          mutate(AndroidCapability.FLASHLIGHT_CONTROL) { adapter.setFlashlight(enabled) }
        }
        "launch_app" -> {
          val packageName = arguments.string("package_name")
            ?: return failure("launch_app requires package_name from list_apps.")
          mutate(AndroidCapability.APP_LAUNCH) { adapter.launchApp(packageName) }
        }
        "open_url" -> {
          val url = arguments.string("url")
            ?: return failure("open_url requires url.")
          mutate(AndroidCapability.OPEN_URL) { adapter.openUrl(url) }
        }
        "bluetooth_bonded_devices" -> AgentToolResult(Gson().toJson(adapter.getBluetoothBondedDevices()))
        "bluetooth_gatt_services" -> {
          val address = arguments.string("device_address")
            ?: return failure("bluetooth_gatt_services requires device_address from Bluetooth discovery.")
          val timeout = if (arguments.has("timeout_ms")) {
            arguments.long("timeout_ms") ?: return failure("timeout_ms must be an integer.")
          } else DEFAULT_GATT_TIMEOUT_MS
          if (timeout !in MIN_GATT_TIMEOUT_MS..MAX_GATT_TIMEOUT_MS) {
            return failure("Bluetooth GATT timeout must be between $MIN_GATT_TIMEOUT_MS and $MAX_GATT_TIMEOUT_MS milliseconds.")
          }
          // The adapter requests BLUETOOTH_CONNECT only after the scoped approval.
          AgentToolResult(Gson().toJson(adapter.inspectBluetoothGatt(address, timeout)))
        }
        "bluetooth_gatt_read" -> {
          val address = arguments.string("device_address")
            ?: return failure("bluetooth_gatt_read requires device_address.")
          val serviceUuid = arguments.string("service_uuid")
            ?: return failure("bluetooth_gatt_read requires service_uuid.")
          val characteristicUuid = arguments.string("characteristic_uuid")
            ?: return failure("bluetooth_gatt_read requires characteristic_uuid.")
          val timeout = if (arguments.has("timeout_ms")) {
            arguments.long("timeout_ms") ?: return failure("timeout_ms must be an integer.")
          } else DEFAULT_GATT_TIMEOUT_MS
          if (timeout !in MIN_GATT_TIMEOUT_MS..MAX_GATT_TIMEOUT_MS) {
            return failure("Bluetooth GATT timeout must be between $MIN_GATT_TIMEOUT_MS and $MAX_GATT_TIMEOUT_MS milliseconds.")
          }
          AgentToolResult(Gson().toJson(
            adapter.readBluetoothGattCharacteristic(address, serviceUuid, characteristicUuid, timeout)
          ))
        }
        "bluetooth_gatt_write" -> {
          val address = arguments.string("device_address")
            ?: return failure("bluetooth_gatt_write requires device_address.")
          val serviceUuid = arguments.string("service_uuid")
            ?: return failure("bluetooth_gatt_write requires service_uuid.")
          val characteristicUuid = arguments.string("characteristic_uuid")
            ?: return failure("bluetooth_gatt_write requires characteristic_uuid.")
          val value = arguments.string("value_base64")
            ?: return failure("bluetooth_gatt_write requires value_base64.")
          val timeout = if (arguments.has("timeout_ms")) {
            arguments.long("timeout_ms") ?: return failure("timeout_ms must be an integer.")
          } else DEFAULT_GATT_TIMEOUT_MS
          if (timeout !in MIN_GATT_TIMEOUT_MS..MAX_GATT_TIMEOUT_MS) {
            return failure("Bluetooth GATT timeout must be between $MIN_GATT_TIMEOUT_MS and $MAX_GATT_TIMEOUT_MS milliseconds.")
          }
          val result = adapter.writeBluetoothGattCharacteristic(
            address, serviceUuid, characteristicUuid, value, timeout,
          )
          AgentToolResult(result.message, result.success)
        }
        "contacts_search" -> {
          val query = arguments.string("query")
            ?: return failure("contacts_search requires a non-empty query.")
          val limit = if (arguments.has("limit")) {
            arguments.int("limit") ?: return failure("limit must be an integer.")
          } else DEFAULT_CONTACT_LIMIT
          if (limit !in MIN_CONTACT_LIMIT..MAX_CONTACT_LIMIT) {
            return failure("Contact search limit must be between $MIN_CONTACT_LIMIT and $MAX_CONTACT_LIMIT.")
          }
          AgentToolResult(Gson().toJson(adapter.searchContacts(query, limit)))
        }
        "clipboard_read" -> AgentToolResult(Gson().toJson(adapter.readClipboard()))
        "clipboard_write" -> {
          val text = arguments.string("text")
            ?: return failure("clipboard_write requires text.")
          if (text.length > MAX_CLIPBOARD_WRITE_CHARS) {
            return failure("Clipboard text must not exceed $MAX_CLIPBOARD_WRITE_CHARS characters.")
          }
          val result = adapter.writeClipboard(text)
          AgentToolResult(result.message, result.success)
        }
        "bluetooth_le_scan" -> {
          val duration = if (arguments.has("duration_ms")) {
            arguments.long("duration_ms") ?: return failure("duration_ms must be an integer.")
          } else DEFAULT_BLUETOOTH_SCAN_MS
          if (duration !in MIN_BLUETOOTH_SCAN_MS..MAX_BLUETOOTH_SCAN_MS) {
            return failure("Bluetooth LE scan duration must be between $MIN_BLUETOOTH_SCAN_MS and $MAX_BLUETOOTH_SCAN_MS milliseconds.")
          }
          AgentToolResult(Gson().toJson(adapter.scanBluetoothLe(duration)))
        }
        "usb_devices" -> AgentToolResult(Gson().toJson(adapter.getUsbDevices()))
        "record_audio" -> {
          val duration = if (arguments.has("duration_ms")) {
            arguments.long("duration_ms") ?: return failure("duration_ms must be an integer.")
          } else DEFAULT_AUDIO_RECORD_MS
          if (duration !in MIN_AUDIO_RECORD_MS..MAX_AUDIO_RECORD_MS) {
            return failure("Audio recording duration must be between $MIN_AUDIO_RECORD_MS and $MAX_AUDIO_RECORD_MS milliseconds.")
          }
          AgentToolResult(adapter.recordAudio(duration))
        }
        "capture_photo" -> {
          val unavailable = availabilityFailure(AndroidCapability.CAMERA_CAPTURE)
          if (unavailable != null) return unavailable
          AgentToolResult(adapter.capturePhoto())
        }
        "set_wallpaper" -> {
          val unavailable = availabilityFailure(AndroidCapability.WALLPAPER_CONTROL)
          if (unavailable != null) return unavailable
          val uri = arguments.string("content_uri")
            ?: return failure("set_wallpaper requires content_uri from a Gallery-readable image.")
          val target = arguments.string("target") ?: "SYSTEM"
          val result = adapter.setWallpaper(uri, target)
          AgentToolResult(result.message, result.success)
        }
        else -> failure("Unsupported operation '$operation'.")
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      failure(error.message ?: error.javaClass.simpleName)
    }
  }

  private suspend fun status(): AgentToolResult {
    // Provider discovery is deliberately distinct from reading device state.
    return AgentToolResult(
      NativeCapabilityResultFormatter.providerAvailability(adapter.inspect())
    )
  }

  private suspend fun read(
    capability: AndroidCapability,
    block: () -> String,
  ): AgentToolResult {
    val unavailable = availabilityFailure(capability) ?: return AgentToolResult(block())
    return unavailable
  }

  private suspend fun mutate(
    capability: AndroidCapability,
    block: suspend () -> NativeCapabilityActionResult,
  ): AgentToolResult {
    val unavailable = availabilityFailure(capability)
    if (unavailable != null) return unavailable
    val result = block()
    return AgentToolResult(result.message, result.success)
  }

  private suspend fun availabilityFailure(capability: AndroidCapability): AgentToolResult? {
    val observation = adapter.inspect()
    val resolution = registry.resolve(capability, listOf(observation))
    if (resolution.selectedProviderId == CapabilityProviderId.ANDROID_NATIVE) return null
    val detail = resolution.candidates.firstOrNull()?.detail
    return failure(
      detail ?: "Native capability ${capability.name} is unavailable on this device or lacks permission."
    )
  }

  private fun audioStream(arguments: JsonObject): NativeAudioStream? {
    if (!arguments.has("stream")) return NativeAudioStream.MUSIC
    val value = arguments.string("stream") ?: return null
    return NativeAudioStream.fromName(value)
  }

  private fun failure(message: String) = AgentToolResult(message, false)

  private fun JsonObject.string(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

  private fun JsonObject.int(name: String): Int? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
      ?.asString?.toIntOrNull()

  private fun JsonObject.long(name: String): Long? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
      ?.asString?.toLongOrNull()

  private fun JsonObject.boolean(name: String): Boolean? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

  private companion object {
    const val DEFAULT_BLUETOOTH_SCAN_MS = 5_000L
    const val DEFAULT_AUDIO_RECORD_MS = 5_000L
    const val DEFAULT_GATT_TIMEOUT_MS = 15_000L
    const val DEFAULT_CONTACT_LIMIT = 10
    const val MIN_CONTACT_LIMIT = 1
    const val MAX_CONTACT_LIMIT = 20
    const val MAX_CLIPBOARD_WRITE_CHARS = 2_048
    const val MIN_GATT_TIMEOUT_MS = 5_000L
    const val MAX_GATT_TIMEOUT_MS = 20_000L
    const val MIN_AUDIO_RECORD_MS = 1_000L
    const val MAX_AUDIO_RECORD_MS = 30_000L
    const val MIN_BLUETOOTH_SCAN_MS = 1_000L
    const val MAX_BLUETOOTH_SCAN_MS = 10_000L
  }
}
