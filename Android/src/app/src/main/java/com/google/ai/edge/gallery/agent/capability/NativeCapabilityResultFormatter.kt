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

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/**
 * Converts native framework values into compact, typed JSON tool output.
 *
 * Structured values remain numbers, booleans, arrays, objects, or null instead of being flattened
 * into prose. Explicit field names and units help small models relay facts without inference.
 */
internal object NativeCapabilityResultFormatter {
  fun deviceInfo(info: NativeDeviceInfo): String = jsonObject {
    addProperty("manufacturer", oneLine(info.manufacturer))
    addProperty("brand", oneLine(info.brand))
    addProperty("model_identifier", oneLine(info.model))
    addProperty("device_code", oneLine(info.device))
    addProperty("android_version", oneLine(info.androidRelease))
    addProperty("sdk_int", info.sdkInt)
    addNullableString("security_patch", info.securityPatch?.let(::oneLine))
  }

  fun batteryStatus(status: NativeBatteryStatus): String = jsonObject {
    addNullableNumber("battery_percent", status.percent)
    addProperty("battery_state", status.state)
    addNullableBoolean("charging", status.charging)
    addProperty("power_source", status.plugged ?: "unknown")
    addNullableNumber("temperature_celsius", status.temperatureCelsius)
    addNullableNumber("voltage_millivolts", status.voltageMillivolts)
    add("units", JsonObject().apply {
      addProperty("battery_percent", "%")
      addProperty("temperature_celsius", "°C")
      addProperty("voltage_millivolts", "mV")
    })
  }

  fun audioDevices(devices: List<NativeAudioDevice>): String {
    val entries = devices.take(MAX_AUDIO_DEVICES_IN_TOOL_RESULT)
    val remaining = (devices.size - entries.size).coerceAtLeast(0)
    return jsonObject {
      addProperty("returned", entries.size)
      addProperty("total", devices.size)
      addProperty("truncated", remaining > 0)
      addProperty("remaining", remaining)
      add("devices", JsonArray().apply {
        entries.forEach { device ->
          add(JsonObject().apply {
            addProperty("id", device.id)
            addProperty("type", device.type)
            addProperty("name", oneLine(device.productName))
            addProperty("source", device.source)
            addProperty("sink", device.sink)
          })
        }
      })
    }
  }

  fun displayBrightness(brightness: NativeDisplayBrightness): String = jsonObject {
    addNullableNumber("brightness_level", brightness.level)
    addProperty("brightness_maximum", brightness.maximum)
    addProperty("brightness_mode", brightness.mode)
    addProperty("can_modify_system_settings", brightness.canModifySystemSettings)
    addProperty("units", "0-255")
  }

  fun providerAvailability(observation: CapabilityProviderObservation): String = jsonObject {
    addProperty("provider", observation.providerId.name)
    addProperty("provider_availability", observation.availability.name)
    addProperty("diagnostic_only", true)
    add("capabilities", JsonObject().apply {
      observation.capabilityAvailability.toSortedMap().forEach { (capability, state) ->
        addProperty(capability.name, state.name)
      }
    })
    add("details", JsonObject().apply {
      observation.capabilityDetails.toSortedMap().forEach { (capability, detail) ->
        addProperty(capability.name, oneLine(detail))
      }
    })
  }

  fun launchableApps(
    apps: List<NativeLaunchableApp>,
    limit: Int = MAX_APPS_IN_TOOL_RESULT,
  ): String {
    val safeLimit = limit.coerceIn(1, MAX_APPS_IN_TOOL_RESULT)
    val entries = apps.take(safeLimit)
    val remaining = (apps.size - entries.size).coerceAtLeast(0)
    return jsonObject {
      addProperty("returned", entries.size)
      addProperty("total", apps.size)
      addProperty("truncated", remaining > 0)
      addProperty("remaining", remaining)
      add("apps", JsonArray().apply {
        entries.forEach { app ->
          add(JsonObject().apply {
            addProperty("label", oneLine(app.label).take(MAX_APP_LABEL_LENGTH))
            addProperty("package_name", oneLine(app.packageName))
          })
        }
      })
    }
  }

  private fun jsonObject(build: JsonObject.() -> Unit): String =
    JsonObject().apply(build).toString()

  private fun JsonObject.addNullableString(name: String, value: String?) {
    add(name, value?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
  }

  private fun JsonObject.addNullableNumber(name: String, value: Number?) {
    add(name, value?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
  }

  private fun JsonObject.addNullableBoolean(name: String, value: Boolean?) {
    add(name, value?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
  }

  private fun oneLine(value: String): String =
    value.replace('\n', ' ').replace('\r', ' ').trim()

  private const val MAX_AUDIO_DEVICES_IN_TOOL_RESULT = 24
  private const val MAX_APPS_IN_TOOL_RESULT = 25
  private const val MAX_APP_LABEL_LENGTH = 48
}
