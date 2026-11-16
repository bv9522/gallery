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
import java.time.LocalDate
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
    "telephony_info" to setOf("operation"),
    "dial_phone" to setOf("operation", "phone_number"),
    "compose_sms" to setOf("operation", "phone_number", "sms_message"),
    "biometric_auth" to setOf("operation", "reason"),
    "keystore_create" to setOf("operation", "key_alias"),
    "keystore_list" to setOf("operation"),
    "keystore_delete" to setOf("operation", "key_alias"),
    "keystore_encrypt" to setOf("operation", "key_alias", "plaintext"),
    "keystore_decrypt" to setOf("operation", "key_alias", "iv_base64", "ciphertext_base64"),
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
    "contacts_search" to setOf("operation", "query", "limit"),
    "clipboard_read" to setOf("operation"),
    "clipboard_write" to setOf("operation", "text"),
    "post_notification" to setOf("operation", "title", "message", "channel_id"),
    "schedule_notification" to setOf("operation", "title", "message", "hour", "minute", "year", "month", "day", "repeat_daily"),
    "list_posted_notifications" to setOf("operation"),
    "cancel_posted_notification" to setOf("operation", "notification_id"),
    "list_scheduled_notifications" to setOf("operation"),
    "cancel_scheduled_notification" to setOf("operation", "notification_id"),
    "list_notification_channels" to setOf("operation"),
    "create_notification_channel" to setOf("operation", "channel_id", "channel_name", "importance"),
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
    "dial_phone" to setOf("phone_number"),
    "compose_sms" to setOf("phone_number", "sms_message"),
    "biometric_auth" to setOf("reason"),
    "keystore_create" to setOf("key_alias"),
    "keystore_delete" to setOf("key_alias"),
    "keystore_encrypt" to setOf("key_alias", "plaintext"),
    "keystore_decrypt" to setOf("key_alias", "iv_base64", "ciphertext_base64"),
    "set_wallpaper" to setOf("content_uri"),
    "bluetooth_gatt_services" to setOf("device_address"),
    "bluetooth_gatt_read" to setOf("device_address", "service_uuid", "characteristic_uuid"),
    "bluetooth_gatt_write" to setOf("device_address", "service_uuid", "characteristic_uuid", "value_base64"),
    "contacts_search" to setOf("query"),
    "clipboard_write" to setOf("text"),
    "post_notification" to setOf("title", "message"),
    "schedule_notification" to setOf("title", "message", "hour", "minute"),
    "cancel_posted_notification" to setOf("notification_id"),
    "cancel_scheduled_notification" to setOf("notification_id"),
    "create_notification_channel" to setOf("channel_id", "channel_name", "importance"),
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

    if (operation in setOf("dial_phone", "compose_sms")) {
      val number = arguments.get("phone_number")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString.orEmpty().trim()
      val validNumber = number.length <= 32 &&
        number.matches(Regex("^\\+?[0-9][0-9 .()-]*$")) &&
        number.count { it.isDigit() } in 3..20
      if (!validNumber) {
        return "phone_number must contain 3–20 digits and only an optional leading plus, spaces, dots, parentheses, or hyphens."
      }
    }

    if (operation == "compose_sms") {
      val message = arguments.get("sms_message")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString.orEmpty()
      if (message.isBlank() || message.length > 1_600) {
        return "sms_message must contain 1–1600 non-whitespace characters."
      }
    }

    if (operation == "biometric_auth") {
      val reason = arguments.get("reason")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString.orEmpty()
      if (reason.isBlank() || reason.length > 160) return "reason must contain 1–160 non-whitespace characters."
    }

    if (operation in setOf("keystore_create", "keystore_delete", "keystore_encrypt", "keystore_decrypt")) {
      val alias = arguments.get("key_alias")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString.orEmpty()
      if (!alias.trim().lowercase().matches(Regex("^[a-z0-9_-]{1,32}$"))) {
        return "key_alias must contain 1–32 lowercase letters, digits, underscores, or hyphens."
      }
    }

    if (operation == "keystore_encrypt") {
      val plaintext = arguments.get("plaintext")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString.orEmpty()
      if (plaintext.isEmpty() || plaintext.length > 4_096) {
        return "plaintext must contain 1–4096 characters."
      }
    }

    if (operation == "keystore_decrypt") {
      val ivText = arguments.get("iv_base64")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString.orEmpty()
      val cipherText = arguments.get("ciphertext_base64")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString.orEmpty()
      val iv = runCatching { Base64.getDecoder().decode(ivText) }.getOrNull()
      val cipher = runCatching { Base64.getDecoder().decode(cipherText) }.getOrNull()
      if (iv == null || iv.size !in 12..16) return "iv_base64 must decode to 12–16 bytes of unwrapped Base64."
      if (cipher == null || cipher.size !in 16..16_400) return "ciphertext_base64 must decode to 16–16400 bytes of unwrapped Base64."
    }

    if (operation == "contacts_search") {
      val query = arguments.get("query")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString.orEmpty()
      if (query.isBlank() || query.length > 80) {
        return "contacts_search query must contain 1–80 non-whitespace characters."
      }
      if (arguments.has("limit")) {
        val limit = arguments.get("limit")
          ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber() }
          ?.asString?.toIntOrNull()
        if (limit == null || limit !in 1..20) {
          return "contacts_search limit must be an integer from 1 to 20."
        }
      }
    }

    if (operation == "clipboard_write") {
      val text = arguments.get("text")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString.orEmpty()
      if (text.length > 2_048) {
        return "clipboard_write text must not exceed 2048 characters."
      }
    }

    if (operation in setOf("post_notification", "schedule_notification")) {
      val title = arguments.get("title")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
      val message = arguments.get("message")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
      if (title.isBlank() || title.length > 100) return "Notification title must contain 1–100 characters."
      if (message.isBlank() || message.length > 1_000) return "Notification message must contain 1–1000 characters."
    }

    if (operation == "post_notification" && arguments.has("channel_id")) {
      val id = arguments.get("channel_id")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString.orEmpty()
      if (!id.matches(Regex("^[A-Za-z0-9_.-]{1,64}$"))) {
        return "channel_id must be a safe 1–64 character ID from list_notification_channels or create_notification_channel."
      }
    }

    if (operation == "schedule_notification") {
      for (name in listOf("hour", "minute")) {
        val value = arguments.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber() }?.asString?.toIntOrNull()
          ?: return "$name must be an integer."
        if (name == "hour" && value !in 0..23) return "hour must be from 0 to 23."
        if (name == "minute" && value !in 0..59) return "minute must be from 0 to 59."
      }
      val dateKeys = listOf("year", "month", "day")
      val present = dateKeys.count { arguments.has(it) }
      if (present != 0 && present != 3) return "year, month, and day must either all be supplied or all be omitted."
      for (name in dateKeys.filter { arguments.has(it) }) {
        val value = arguments.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber() }?.asString?.toIntOrNull()
          ?: return "$name must be an integer."
        if (name == "year" && value !in 2000..2200) return "year must be from 2000 to 2200."
        if (name == "month" && value !in 1..12) return "month must be from 1 to 12."
        if (name == "day" && value !in 1..31) return "day must be from 1 to 31."
      }
      val year = arguments.get("year")?.asInt
      val month = arguments.get("month")?.asInt
      val day = arguments.get("day")?.asInt
      if (present == 3 && runCatching { LocalDate.of(year!!, month!!, day!!) }.isFailure) {
        return "year, month, and day must form a valid calendar date."
      }
      if (arguments.has("repeat_daily") &&
        !(arguments.get("repeat_daily")?.isJsonPrimitive == true &&
          arguments.get("repeat_daily")?.asJsonPrimitive?.isBoolean == true)
      ) return "repeat_daily must be a boolean."
    }

    if (operation == "cancel_scheduled_notification") {
      val id = arguments.get("notification_id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
      if (id.isBlank() || id.length > 64) return "notification_id must contain 1–64 characters."
    }

    if (operation == "cancel_posted_notification") {
      val id = arguments.get("notification_id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
      if (id.toIntOrNull() == null) return "notification_id must be the numeric ID returned by list_posted_notifications."
    }

    if (operation == "create_notification_channel") {
      val id = arguments.get("channel_id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
      val name = arguments.get("channel_name")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
      val importance = arguments.get("importance")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber() }?.asString?.toIntOrNull()
      if (!id.matches(Regex("^[A-Za-z0-9_.-]{1,64}$"))) return "channel_id must contain 1–64 letters, digits, dots, underscores, or hyphens."
      if (name.isBlank() || name.length > 80) return "channel_name must contain 1–80 characters."
      if (importance == null || importance !in 2..4) return "importance must be 2 (LOW), 3 (DEFAULT), or 4 (HIGH)."
    }

    return null
  }
}
