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

/**
 * Converts native framework values into compact, deterministic tool output.
 *
 * Field labels are explicit so a small model can relay facts without having to infer which
 * positional or raw integer value represents a device property.
 */
internal object NativeCapabilityResultFormatter {
  fun deviceInfo(info: NativeDeviceInfo): String = listOf(
    "manufacturer=${oneLine(info.manufacturer)}",
    "brand=${oneLine(info.brand)}",
    "modelIdentifier=${oneLine(info.model)}",
    "deviceCode=${oneLine(info.device)}",
    "androidVersion=${oneLine(info.androidRelease)}",
    "sdkInt=${info.sdkInt}",
    "securityPatch=${info.securityPatch?.let(::oneLine) ?: "unknown"}",
  ).joinToString("\n")

  fun batteryStatus(status: NativeBatteryStatus): String = listOf(
    "batteryPercent=${status.percent?.toString() ?: "unknown"}",
    "batteryState=${status.state}",
    "charging=${status.charging?.toString() ?: "unknown"}",
    "powerSource=${status.plugged ?: "unknown"}",
    "temperatureCelsius=${status.temperatureCelsius?.toString() ?: "unknown"}",
    "voltageMillivolts=${status.voltageMillivolts?.toString() ?: "unknown"}",
  ).joinToString("\n")

  fun providerAvailability(observation: CapabilityProviderObservation): String = buildString {
    appendLine("provider=${observation.providerId.name}")
    appendLine("providerAvailability=${observation.availability.name}")
    appendLine("diagnosticOnly=true")
    observation.capabilityAvailability.toSortedMap().forEach { (capability, state) ->
      appendLine("${capability.name}=${state.name}")
      observation.capabilityDetails[capability]?.let { detail ->
        appendLine("${capability.name}_detail=${oneLine(detail)}")
      }
    }
  }.trimEnd()

  fun launchableApps(
    apps: List<NativeLaunchableApp>,
    limit: Int = MAX_APPS_IN_TOOL_RESULT,
  ): String {
    val safeLimit = limit.coerceIn(1, MAX_APPS_IN_TOOL_RESULT)
    val entries = apps.take(safeLimit).map { app ->
      "${oneLine(app.label).take(MAX_APP_LABEL_LENGTH)} — ${oneLine(app.packageName)}"
    }
    val remaining = (apps.size - entries.size).coerceAtLeast(0)
    val truncated = remaining > 0
    return buildString {
      appendLine("returned=${entries.size}")
      appendLine("total=${apps.size}")
      appendLine("truncated=$truncated")
      appendLine("remaining=$remaining")
      appendLine(if (truncated) "Truncated: YES; ${remaining} apps are not included." else "Truncated: NO")
      if (entries.isNotEmpty()) append(entries.joinToString("\n"))
      else append("No launcher-visible apps were found.")
    }
  }

  private fun oneLine(value: String): String =
    value.replace('\n', ' ').replace('\r', ' ').trim()

  private const val MAX_APPS_IN_TOOL_RESULT = 25
  private const val MAX_APP_LABEL_LENGTH = 48
}
