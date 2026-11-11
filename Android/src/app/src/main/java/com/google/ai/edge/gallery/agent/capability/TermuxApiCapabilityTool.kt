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
 */
class TermuxApiCapabilityTool(
  context: Context,
  private val actionChannel: SendChannel<ToolAction>,
  private val client: TermuxShellExecutor = TermuxRunCommandClient(context.applicationContext),
) : AgentTool {
  private val appContext = context.applicationContext

  override val name: String = "termux_api"
  override val description: String =
    "Inspect and use the Termux:API provider. Operations: status, battery_status, volume_status, " +
      "set_volume, set_flashlight. This tool uses fixed Termux:API commands, never arbitrary shell " +
      "input. Mutations require one-time user approval."

  override val parametersSchema: JsonObject
    get() = JsonObject().apply {
      addProperty("type", "object")
      add("properties", JsonObject().apply {
        add("operation", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply {
            listOf("status", "battery_status", "volume_status", "set_volume", "set_flashlight")
              .forEach { add(it) }
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
      })
      add("required", JsonArray().apply { add("operation") })
      addProperty("additionalProperties", false)
    }

  override suspend fun execute(arguments: JsonObject): AgentToolResult {
    val allowed = setOf("operation", "stream", "volume", "enabled")
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
        else -> failure("Unsupported Termux:API operation '$operation'.")
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
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
      command = "for c in termux-battery-status termux-volume termux-torch; do " +
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
      if (probe.stdout.isNotBlank()) append(probe.stdout.trim())
      if (probe.stderr.isNotBlank()) appendLine("\nprobeStderr=${probe.stderr.take(500)}")
      probe.error?.let { appendLine("\nprobeError=$it") }
    }.trim()
    return AgentToolResult(output, probe.succeeded && companionInstalled)
  }

  private suspend fun runRead(command: String): AgentToolResult {
    val result = client.execute(command, "~/", READ_TIMEOUT_MS, MAX_OUTPUT_CHARS)
    if (!result.succeeded) return failure(result.error ?: result.stderr.ifBlank { "Termux:API command failed." })
    return AgentToolResult(result.stdout.trim(), true)
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

  private fun resultAsAction(
    result: com.google.ai.edge.gallery.termux.TermuxShellResult,
    label: String,
  ): AgentToolResult {
    if (!result.succeeded) return failure(result.error ?: result.stderr.ifBlank { "$label failed." })
    return AgentToolResult(
      buildString {
        appendLine("$label completed.")
        if (result.stdout.isNotBlank()) append(result.stdout.trim())
        if (result.stderr.isNotBlank()) appendLine("\nSTDERR: ${result.stderr.trim()}")
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

  private companion object {
    const val TERMUX_API_PACKAGE = "com.termux.api"
    const val READ_TIMEOUT_MS = 15_000L
    const val APPROVAL_TIMEOUT_MS = 120_000L
    const val MAX_OUTPUT_CHARS = 8_192
    val ALLOWED_STREAMS = setOf("alarm", "music", "notification", "ring", "system", "call")
  }
}
