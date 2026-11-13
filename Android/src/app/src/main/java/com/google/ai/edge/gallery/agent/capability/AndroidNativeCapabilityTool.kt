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
import com.google.gson.Gson
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
      actionChannel.send(action)
      withTimeoutOrNull(approvalTimeoutMs) { action.result.await() } ?: false
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
 * Read operations never prompt. Mutations are authorized by the adapter immediately before the
 * framework call. Every operation is routed through the capability registry; this tool does not
 * fall back to a shell provider when native Android support is unavailable.
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
      "set_display_brightness, vibrate, wake_lock_status, set_wake_lock. Mutations always require one-time user approval. " +
      "Bluetooth discovery and USB host enumeration require scoped approval; BLE scans are limited to 1–10 seconds. " +
      "Bluetooth GATT sessions and USB I/O are not yet exposed. This tool never executes shell commands."

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
              "set_wake_lock", "list_apps", "set_volume", "set_flashlight", "launch_app", "open_url", "bluetooth_bonded_devices", "bluetooth_le_scan", "usb_devices",
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
          addProperty("description", "Vibration duration (1-1000 ms) or wake-lock timeout (1-300000 ms).")
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
        add("duration_ms", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("minimum", 1_000)
          addProperty("maximum", 10_000)
          addProperty("description", "Bluetooth LE scan window from 1000 to 10000 milliseconds; defaults to 5000.")
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
    const val MIN_BLUETOOTH_SCAN_MS = 1_000L
    const val MAX_BLUETOOTH_SCAN_MS = 10_000L
  }
}
