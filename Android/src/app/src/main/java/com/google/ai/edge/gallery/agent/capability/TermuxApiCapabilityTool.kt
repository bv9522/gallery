/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.agent.capability

import android.content.Context
import android.content.pm.PackageManager
import com.google.ai.edge.gallery.agent.coordinator.AgentTool
import com.google.ai.edge.gallery.agent.coordinator.AgentToolResult
import com.google.ai.edge.gallery.termux.TermuxRunCommandClient
import com.google.ai.edge.gallery.termux.TermuxShellExecutor
import com.google.ai.edge.gallery.termux.TermuxShellResult
import com.google.ai.edge.gallery.tools.AskNativeCapabilityPermissionAction
import com.google.ai.edge.gallery.tools.ToolAction
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Termux:API provider. Commands are fixed allowlisted strings; model-supplied shell text is never
 * accepted. The companion app and the in-Termux termux-api package are probed independently.
 *
 * The first read-only slice intentionally exposes only finite sensor reads, location once/last,
 * and Wi-Fi connection/last-scan information. Continuous sensor/location sessions remain disabled.
 */
class TermuxApiCapabilityTool(
  context: Context,
  private val actionChannel: SendChannel<ToolAction>,
  private val client: TermuxShellExecutor = TermuxRunCommandClient(context.applicationContext),
) : AgentTool {
  private val appContext = context.applicationContext

  override val name: String = "termux_api"
  override val description: String =
    "Inspect and use the Termux:API provider. Read-only operations: status, sensor_list, " +
      "sensor_read, location, wifi_connection_info, wifi_last_scan, battery_status, volume_status. " +
      "Existing comparison mutations: set_volume, set_flashlight. Sensor reads are finite and " +
      "bounded; location supports only once or last. Wi-Fi scan information is the last scan, " +
      "not a request to initiate a fresh scan. This is not arbitrary shell access."

  override val parametersSchema: JsonObject
    get() = JsonObject().apply {
      addProperty("type", "object")
      add("properties", JsonObject().apply {
        add("operation", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply {
            listOf(
              "status", "battery_status", "volume_status", "set_volume", "set_flashlight",
              "sensor_list", "sensor_read", "location", "wifi_connection_info", "wifi_last_scan",
            ).forEach { add(it) }
          })
        })
        add("stream", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply {
            listOf("alarm", "music", "notification", "ring", "system", "call").forEach { add(it) }
          })
        })
        add("volume", JsonObject().apply { addProperty("type", "integer") })
        add("enabled", JsonObject().apply { addProperty("type", "boolean") })
        add("sensors", JsonObject().apply {
          addProperty("type", "array")
          add("items", JsonObject().apply { addProperty("type", "string") })
          addProperty("maxItems", TermuxApiReadCommand.MAX_SENSOR_SELECTIONS)
        })
        add("count", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("minimum", TermuxApiReadCommand.MIN_SAMPLE_COUNT)
          addProperty("maximum", TermuxApiReadCommand.MAX_SAMPLE_COUNT)
        })
        add("delay_ms", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("minimum", TermuxApiReadCommand.MIN_SENSOR_DELAY_MS)
          addProperty("maximum", TermuxApiReadCommand.MAX_SENSOR_DELAY_MS)
        })
        add("provider", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply { listOf("gps", "network", "passive").forEach { add(it) } })
        })
        add("request", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply { listOf("once", "last").forEach { add(it) } })
        })
      })
      add("required", JsonArray().apply { add("operation") })
      addProperty("additionalProperties", false)
    }

  override suspend fun execute(arguments: JsonObject): AgentToolResult {
    val allowed = setOf(
      "operation", "stream", "volume", "enabled", "sensors", "count", "delay_ms", "provider", "request",
    )
    val unexpected = arguments.keySet() - allowed
    if (unexpected.isNotEmpty()) return failure("Unsupported argument(s): ${unexpected.sorted()}.")
    val operation = arguments.string("operation") ?: return failure("Missing operation.")
    return try {
      when (operation) {
        "status" -> status()
        "battery_status" -> runRead("termux-battery-status")
        "volume_status" -> runRead("termux-volume")
        "set_volume" -> setVolume(arguments)
        "set_flashlight" -> setFlashlight(arguments)
        "sensor_list" -> runRead(TermuxApiReadCommand.sensorList())
        "sensor_read" -> sensorRead(arguments)
        "location" -> location(arguments)
        "wifi_connection_info" -> {
          if (!authorize(AndroidCapability.WIFI_CONNECTION_INFO, "Read current Wi-Fi connection details using Termux:API.")) {
            failure("Wi-Fi connection information request denied or approval unavailable.")
          } else runRead(TermuxApiReadCommand.wifiConnectionInfo())
        }
        "wifi_last_scan" -> {
          if (!authorize(AndroidCapability.WIFI_SCAN, "Read the last available Wi-Fi scan results using Termux:API.")) {
            failure("Wi-Fi scan information request denied or approval unavailable.")
          } else runRead(TermuxApiReadCommand.wifiLastScanInfo())
        }
        else -> failure("Unsupported Termux:API operation '$operation'.")
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: IllegalArgumentException) {
      failure(error.message ?: "Invalid Termux:API arguments.")
    } catch (error: Exception) {
      failure(error.message ?: error.javaClass.simpleName)
    }
  }

  private suspend fun status(): AgentToolResult {
    val companionInstalled = try {
      appContext.packageManager.getPackageInfo(TERMUX_API_PACKAGE, 0)
      true
    } catch (_: PackageManager.NameNotFoundException) {
      false
    }
    val probe = client.execute(
      command = "for c in termux-battery-status termux-volume termux-torch termux-sensor " +
        "termux-location termux-wifi-connectioninfo termux-wifi-scaninfo; do " +
        "if command -v \"\$c\" >/dev/null 2>&1; then printf '%s=AVAILABLE\\n' \"\$c\"; " +
        "else printf '%s=NOT_INSTALLED\\n' \"\$c\"; fi; done",
      workingDirectory = "~/",
      timeoutMs = 10_000L,
      maxOutputChars = 2_048,
    )
    val output = buildString {
      appendLine("provider=TERMUX_API")
      appendLine("companionAppInstalled=$companionInstalled")
      appendLine("commandProbeSucceeded=${probe.succeeded}")
      appendLine("permissionState=UNKNOWN (discovery does not request permissions)")
      if (probe.stdout.isNotBlank()) append(probe.stdout.trim())
      if (probe.outputTruncated) appendLine("\nprobeOutputTruncated=true")
      if (probe.stderr.isNotBlank()) appendLine("\nprobeStderr=${probe.stderr.take(500)}")
      probe.error?.let { appendLine("\nprobeError=$it") }
    }.trim()
    return AgentToolResult(output, probe.succeeded && companionInstalled && !probe.outputTruncated)
  }

  private suspend fun sensorRead(arguments: JsonObject): AgentToolResult {
    val sensors = arguments.stringList("sensors") ?: return failure("sensor_read requires a sensors array.")
    val count = arguments.int("count") ?: DEFAULT_SENSOR_COUNT
    val delayMs = arguments.int("delay_ms") ?: DEFAULT_SENSOR_DELAY_MS
    val listing = client.execute(
      TermuxApiReadCommand.sensorList(),
      "~/",
      READ_TIMEOUT_MS,
      MAX_OUTPUT_CHARS,
    )
    if (!listing.succeeded) return readFailure(listing, "Sensor listing")
    if (listing.outputTruncated) return failure("Sensor listing exceeded the output limit; no sensor read was started.")
    val command = TermuxApiReadCommand.sensorRead(
      sensorNames = sensors,
      availableSensorOutput = listing.stdout,
      count = count,
      delayMs = delayMs,
    )
    val timeout = (SENSOR_READ_BASE_TIMEOUT_MS + count.toLong() * delayMs)
      .coerceAtMost(MAX_SENSOR_READ_TIMEOUT_MS)
    return runRead(command, timeout)
  }

  private suspend fun location(arguments: JsonObject): AgentToolResult {
    val provider = arguments.string("provider") ?: DEFAULT_LOCATION_PROVIDER
    val request = arguments.string("request") ?: DEFAULT_LOCATION_REQUEST
    if (!authorize(AndroidCapability.LOCATION_READ, "Read a one-time or last-known location using Termux:API.")) {
      return failure("Location request denied or approval unavailable.")
    }
    return runRead(TermuxApiReadCommand.location(provider, request), LOCATION_TIMEOUT_MS)
  }

  private suspend fun runRead(
    command: String,
    timeoutMs: Long = READ_TIMEOUT_MS,
  ): AgentToolResult {
    val result = client.execute(command, "~/", timeoutMs, MAX_OUTPUT_CHARS)
    if (!result.succeeded) return readFailure(result, "Termux:API read")
    if (result.outputTruncated) {
      return failure("OUTPUT_TRUNCATED: Termux:API output exceeded the $MAX_OUTPUT_CHARS character limit; the partial result is not presented as complete.")
    }
    val output = result.stdout.trim()
    if (output.isBlank()) return failure("EMPTY_RESULT: Termux:API command completed without output.")
    return AgentToolResult(output, true)
  }

  private fun readFailure(result: TermuxShellResult, label: String): AgentToolResult {
    val detail = result.error ?: result.stderr.ifBlank { "$label failed (exit ${result.exitCode})." }
    return failure("${TermuxApiReadCommand.failureState(result)}: $label failed. $detail")
  }

  private suspend fun setVolume(arguments: JsonObject): AgentToolResult {
    val stream = arguments.string("stream") ?: "music"
    if (stream !in ALLOWED_STREAMS) return failure("Unsupported audio stream.")
    val volume = arguments.int("volume") ?: return failure("set_volume requires integer volume.")
    if (volume !in 0..100) return failure("Volume must be between 0 and 100.")
    val summary = "Set Termux:API audio stream '$stream' to volume $volume."
    if (!authorize(AndroidCapability.VOLUME_CONTROL, summary)) {
      return failure("Termux:API volume change denied or approval unavailable.")
    }
    val result = client.execute("termux-volume ${shellQuote(stream)} $volume", "~/", READ_TIMEOUT_MS, MAX_OUTPUT_CHARS)
    return resultAsAction(result, "Termux:API volume command")
  }

  private suspend fun setFlashlight(arguments: JsonObject): AgentToolResult {
    val enabled = arguments.boolean("enabled") ?: return failure("set_flashlight requires boolean enabled.")
    val state = if (enabled) "on" else "off"
    if (!authorize(AndroidCapability.FLASHLIGHT_CONTROL, "Turn flashlight $state using Termux:API.")) {
      return failure("Termux:API flashlight change denied or approval unavailable.")
    }
    val result = client.execute("termux-torch $state", "~/", READ_TIMEOUT_MS, MAX_OUTPUT_CHARS)
    return resultAsAction(result, "Termux:API flashlight command")
  }

  private suspend fun authorize(capability: AndroidCapability, summary: String): Boolean {
    val action = AskNativeCapabilityPermissionAction(capability.name, summary)
    return try {
      actionChannel.send(action)
      withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { action.result.await() } ?: false
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      false
    } finally {
      if (!action.result.isCompleted) action.result.complete(false)
    }
  }

  private fun resultAsAction(result: TermuxShellResult, label: String): AgentToolResult {
    if (!result.succeeded) return failure(result.error ?: result.stderr.ifBlank { "$label failed." })
    return AgentToolResult(
      buildString {
        appendLine("$label completed.")
        if (result.stdout.isNotBlank()) append(result.stdout.trim())
        if (result.stderr.isNotBlank()) appendLine("\nSTDERR: ${result.stderr.trim()}")
        if (result.outputTruncated) appendLine("\nOUTPUT_TRUNCATED=true")
      }.trim(),
      true,
    )
  }

  private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
  private fun failure(message: String) = AgentToolResult(message, false)
  private fun JsonObject.string(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
  private fun JsonObject.int(key: String): Int? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toIntOrNull()
  private fun JsonObject.boolean(key: String): Boolean? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
  private fun JsonObject.stringList(key: String): List<String>? {
    val value = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    if (value.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isString }) return null
    return value.map { it.asString }
  }

  private companion object {
    const val TERMUX_API_PACKAGE = "com.termux.api"
    const val READ_TIMEOUT_MS = 15_000L
    const val LOCATION_TIMEOUT_MS = 45_000L
    const val SENSOR_READ_BASE_TIMEOUT_MS = 10_000L
    const val MAX_SENSOR_READ_TIMEOUT_MS = 60_000L
    const val APPROVAL_TIMEOUT_MS = 120_000L
    const val MAX_OUTPUT_CHARS = 8_192
    const val DEFAULT_SENSOR_COUNT = 3
    const val DEFAULT_SENSOR_DELAY_MS = 250
    const val DEFAULT_LOCATION_PROVIDER = "network"
    const val DEFAULT_LOCATION_REQUEST = "once"
    val ALLOWED_STREAMS = setOf("alarm", "music", "notification", "ring", "system", "call")
  }
}
