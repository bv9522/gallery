/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.agent.capability

/**
 * Fixed command builders for the first read-only Termux:API slice.
 *
 * Values are validated before they are quoted into a fixed command template. This class never
 * accepts a command name, option name, or arbitrary shell fragment from the model.
 */
internal object TermuxApiReadCommand {
  private val sensorNamePattern = Regex("^[A-Za-z0-9][A-Za-z0-9 _().:/+\\-]{0,79}$")
  private val allowedProviders = setOf("gps", "network", "passive")
  private val allowedLocationRequests = setOf("once", "last")

  fun sensorList(): String = "termux-sensor -l"

  fun sensorRead(
    sensorNames: List<String>,
    availableSensorOutput: String,
    count: Int,
    delayMs: Int,
  ): String {
    require(sensorNames.isNotEmpty() && sensorNames.size <= MAX_SENSOR_SELECTIONS) {
      "Select between 1 and $MAX_SENSOR_SELECTIONS sensors."
    }
    require(count in MIN_SAMPLE_COUNT..MAX_SAMPLE_COUNT) {
      "Sensor sample count must be between $MIN_SAMPLE_COUNT and $MAX_SAMPLE_COUNT."
    }
    require(delayMs in MIN_SENSOR_DELAY_MS..MAX_SENSOR_DELAY_MS) {
      "Sensor delay must be between $MIN_SENSOR_DELAY_MS and $MAX_SENSOR_DELAY_MS ms."
    }
    val normalizedAvailable = availableSensorOutput.lowercase()
    val names = sensorNames.map { raw ->
      val name = raw.trim()
      require(sensorNamePattern.matches(name)) { "Invalid sensor name." }
      val exactName = Regex("(?i)(?<![A-Za-z0-9])${Regex.escape(name)}(?![A-Za-z0-9])")
      require(exactName.containsMatchIn(availableSensorOutput)) {
        "Sensor '$name' was not present in the latest Termux sensor listing."
      }
      name
    }
    require(names.distinctBy { it.lowercase() }.size == names.size) {
      "Duplicate sensor names are not allowed."
    }
    return "termux-sensor -s ${shellQuote(names.joinToString(","))} -d $delayMs -n $count"
  }

  fun location(provider: String, request: String): String {
    require(provider in allowedProviders) {
      "Location provider must be gps, network, or passive."
    }
    require(request in allowedLocationRequests) {
      "Location request must be once or last; continuous updates are not enabled."
    }
    return "termux-location -p $provider -r $request"
  }

  fun wifiConnectionInfo(): String = "termux-wifi-connectioninfo"

  fun wifiLastScanInfo(): String = "termux-wifi-scaninfo"

  private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

  const val MIN_SAMPLE_COUNT = 1
  const val MAX_SAMPLE_COUNT = 10
  const val MIN_SENSOR_DELAY_MS = 100
  const val MAX_SENSOR_DELAY_MS = 5_000
  const val MAX_SENSOR_SELECTIONS = 8
}
