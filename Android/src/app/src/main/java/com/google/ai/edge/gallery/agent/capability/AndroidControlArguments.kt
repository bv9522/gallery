/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file contains pure argument validation for the native Android control tool.
 */

package com.google.ai.edge.gallery.agent.capability

import com.google.gson.JsonObject
import java.util.Base64
import java.util.UUID

/**
 * Validates the compact android_control contract and returns actionable recovery guidance.
 *
 * Validation never guesses the requested operation or executes a substitute. The model receives
 * the exact correction needed and must issue a new structured call.
 */
internal object AndroidControlArguments {
  private val operationArguments = mapOf(
    "status" to setOf("operation"),
    "device_info" to setOf("operation"),
    "battery_status" to setOf("operation"),
    "volume_status" to setOf("operation", "stream"),
    "audio_devices" to setOf("operation"),
    "display_brightness" to setOf("operation"),
    "set_display_brightness" to setOf("operation", "level"),
    "vibrate" to setOf("operation", "duration_ms"),
    "wake_lock_status" to setOf("operation"),
    "set_wake_lock" to setOf("operation", "held", "duration_ms"),
    "list_apps" to setOf("operation"),
    "set_volume" to setOf("operation", "stream", "volume"),
    "set_flashlight" to setOf("operation", "enabled"),
    "launch_app" to setOf("operation", "package_name"),
    "open_url" to setOf("operation", "url"),
    "bluetooth_bonded_devices" to setOf("operation"),
    "bluetooth_le_scan" to setOf("operation", "duration_ms"),
    "bluetooth_gatt_services" to setOf("operation", "device_address", "timeout_ms"),
    "bluetooth_gatt_read" to setOf("operation", "device_address", "service_uuid", "characteristic_uuid", "timeout_ms"),
    "bluetooth_gatt_write" to setOf("operation", "device_address", "service_uuid", "characteristic_uuid", "value_base64", "timeout_ms"),
    "usb_devices" to setOf("operation"),
    "record_audio" to setOf("operation", "duration_ms"),
    "capture_photo" to setOf("operation"),
    "set_wallpaper" to setOf("operation", "content_uri", "target"),
  )

  private val requiredArguments = mapOf(
    "set_volume" to setOf("volume"),
    "set_display_brightness" to setOf("level"),
    "vibrate" to setOf("duration_ms"),
    "set_wake_lock" to setOf("held"),
    "set_flashlight" to setOf("enabled"),
    "launch_app" to setOf("package_name"),
    "open_url" to setOf("url"),
    "set_wallpaper" to setOf("content_uri"),
    "bluetooth_gatt_services" to setOf("device_address"),
    "bluetooth_gatt_read" to setOf("device_address", "service_uuid", "characteristic_uuid"),
    "bluetooth_gatt_write" to setOf("device_address", "service_uuid", "characteristic_uuid", "value_base64"),
  )

  fun validate(arguments: JsonObject): String? {
    val operation = arguments.get("operation")
      ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
      ?.asString
      ?: return "Missing required string argument: operation. Choose one of: " +
        operationArguments.keys.sorted().joinToString(", ") + "."

    val accepted = operationArguments[operation]
      ?: return "Unsupported operation '$operation'. Choose one of: " +
        operationArguments.keys.sorted().joinToString(", ") + "."

    val unexpected = arguments.keySet() - accepted
    if (unexpected.isNotEmpty()) {
      val names = unexpected.sorted().joinToString(", ")
      return "Unsupported argument(s) for $operation: $names. Accepted arguments: " +
        accepted.sorted().joinToString(", ") + ". Retry using the same operation with only " +
        "its accepted arguments."
    }

    val missing = requiredArguments[operation].orEmpty() - arguments.keySet()
    if (missing.isNotEmpty()) {
      return "$operation requires: " + missing.sorted().joinToString(", ") +
        ". Retry the same operation with those arguments."
    }

    if (operation in setOf("bluetooth_gatt_services", "bluetooth_gatt_read", "bluetooth_gatt_write")) {
      val address = arguments.get("device_address")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString.orEmpty()
      if (!address.matches(Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$"))) {
        return "device_address must be a valid Bluetooth MAC address, preferably copied from a prior Bluetooth discovery result."
      }
    }

    if (operation in setOf("bluetooth_gatt_read", "bluetooth_gatt_write")) {
      for (name in listOf("service_uuid", "characteristic_uuid")) {
        val value = arguments.get(name)
          ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
          ?.asString.orEmpty()
        val valid = runCatching {
          UUID.fromString(value).toString().equals(value, ignoreCase = true)
        }.getOrDefault(false)
        if (!valid) return "$name must be a full UUID returned by bluetooth_gatt_services."
      }
    }

    if (operation == "bluetooth_gatt_write") {
      val encoded = arguments.get("value_base64")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString.orEmpty()
      if (encoded.length > 684) {
        return "value_base64 must encode between 1 and 512 bytes using unwrapped Base64."
      }
      val decoded = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
      if (decoded == null || decoded.isEmpty() || decoded.size > 512) {
        return "value_base64 must encode between 1 and 512 bytes using unwrapped Base64."
      }
    }

    return null
  }
}
