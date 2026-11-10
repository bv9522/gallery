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
import com.google.gson.JsonPrimitive

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
    "list_apps" to setOf("operation"),
    "set_volume" to setOf("operation", "stream", "volume"),
    "set_flashlight" to setOf("operation", "enabled"),
    "launch_app" to setOf("operation", "package_name"),
    "open_url" to setOf("operation", "url"),
  )

  private val requiredArguments = mapOf(
    "set_volume" to setOf("volume"),
    "set_flashlight" to setOf("enabled"),
    "launch_app" to setOf("package_name"),
    "open_url" to setOf("url"),
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
      if (operation == "status" && "stream" in unexpected) {
        return "The status operation does not accept 'stream'. To read an audio stream, " +
          "retry android_control with operation='volume_status' and stream='MUSIC'. " +
          "Do not call status for volume readings."
      }
      return "Unsupported argument(s) for $operation: $names. Accepted arguments: " +
        accepted.sorted().joinToString(", ") + ". Retry using the same operation with only " +
        "its accepted arguments."
    }

    val missing = requiredArguments[operation].orEmpty() - arguments.keySet()
    if (missing.isNotEmpty()) {
      return "$operation requires: " + missing.sorted().joinToString(", ") +
        ". Retry the same operation with those arguments."
    }

    return null
  }
}
