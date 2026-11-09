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
class AndroidNativeCapabilityTool(
  context: Context,
  actionChannel: SendChannel<ToolAction>,
  private val registry: CapabilityRegistry = CapabilityCatalog.createRegistry(),
) : AgentTool {
  private val adapter = AndroidNativeCapabilityAdapter(
    context = context.applicationContext,
    authorizer = ChannelNativeCapabilityAuthorizer(actionChannel),
  )

  override val name: String = "android_control"
  override val description: String =
    "Read device and battery information, inspect volume and launchable apps, or perform an " +
      "approved native Android action. Use operation values: status, device_info, battery_status, " +
      "volume_status, list_apps, set_volume, set_flashlight, launch_app, open_url. Mutations " +
      "always require one-time user approval. This tool never executes shell commands."

  override val parametersSchema: JsonObject
    get() = JsonObject().apply {
      addProperty("type", "object")
      add("properties", JsonObject().apply {
        add("operation", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply {
            listOf(
              "status", "device_info", "battery_status", "volume_status", "list_apps",
              "set_volume", "set_flashlight", "launch_app", "open_url",
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
      })
      add("required", JsonArray().apply { add("operation") })
      addProperty("additionalProperties", false)
    }

  override suspend fun execute(arguments: JsonObject): AgentToolResult {
    val allowed = setOf("operation", "stream", "volume", "enabled", "package_name", "url")
    val unexpected = arguments.keySet() - allowed
    if (unexpected.isNotEmpty()) {
      return failure("Unsupported argument(s): ${unexpected.sorted().joinToString(", ")}.")
    }
    val operation = arguments.string("operation")
      ?: return failure("Missing required string argument: operation.")
    val operationArguments = mapOf(
      "status" to setOf("operation"),
      "device_info" to setOf("operation"),
      "battery_status" to setOf("operation"),
      "volume_status" to setOf("operation", "stream"),
      "list_apps" to setOf("operation"),
      "set_volume" to setOf("operation", "stream", "volume"),
      "set_flashlight" to setOf("operation", "enabled"),
      "launch_app" to setOf("operation", "package_name"),
      "open_url" to setOf("operation", "url"),
    )[operation] ?: return failure("Unsupported operation '$operation'.")
    val unexpectedForOperation = arguments.keySet() - operationArguments
    if (unexpectedForOperation.isNotEmpty()) {
      return failure(
        "Unsupported argument(s) for $operation: ${unexpectedForOperation.sorted().joinToString(", ")}."
      )
    }

    return try {
      when (operation) {
        "status" -> status()
        "device_info" -> read(AndroidCapability.DEVICE_INFO) { Gson().toJson(adapter.getDeviceInfo()) }
        "battery_status" -> read(AndroidCapability.BATTERY_STATUS) { Gson().toJson(adapter.getBatteryStatus()) }
        "volume_status" -> {
          val stream = audioStream(arguments) ?: return failure("Unsupported audio stream.")
          read(AndroidCapability.VOLUME_CONTROL) { Gson().toJson(adapter.getVolumeStatus(stream)) }
        }
        "list_apps" -> read(AndroidCapability.APP_DISCOVERY) {
          Gson().toJson(adapter.listLaunchableApps())
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
        else -> failure("Unsupported operation '$operation'.")
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      failure(error.message ?: error.javaClass.simpleName)
    }
  }

  private suspend fun status(): AgentToolResult {
    val observation = adapter.inspect()
    val resolutions = registry.resolveAll(listOf(observation))
    val summary = resolutions.entries.sortedBy { it.key.name }.joinToString("\n") { (capability, resolution) ->
      val selected = resolution.selectedProviderId?.name ?: "UNAVAILABLE"
      val candidate = resolution.candidates.firstOrNull()
      "${capability.name}: $selected" +
        (candidate?.detail?.let { " ($it)" } ?: "")
    }
    return AgentToolResult(summary)
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

  private fun JsonObject.boolean(name: String): Boolean? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
}
