/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.agent.capability

import com.google.ai.edge.gallery.termux.TermuxShellResult

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

  fun validateSensorRequest(sensorNames: List<String>, count: Int, delayMs: Int) {
    require(sensorNames.isNotEmpty() && sensorNames.size <= MAX_SENSOR_SELECTIONS) {
      "Select between 1 and $MAX_SENSOR_SELECTIONS sensors."
    }
    require(count in MIN_SAMPLE_COUNT..MAX_SAMPLE_COUNT) {
      "Sensor sample count must be between $MIN_SAMPLE_COUNT and $MAX_SAMPLE_COUNT."
    }
    require(delayMs in MIN_SENSOR_DELAY_MS..MAX_SENSOR_DELAY_MS) {
      "Sensor delay must be between $MIN_SENSOR_DELAY_MS and $MAX_SENSOR_DELAY_MS ms."
    }
    val names = sensorNames.map { raw ->
      val name = raw.trim()
      require(sensorNamePattern.matches(name)) { "Invalid sensor name." }
      name
    }
    require(names.distinctBy { it.lowercase() }.size == names.size) {
      "Duplicate sensor names are not allowed."
    }
  }

  fun sensorRead(
    sensorNames: List<String>,
    availableSensorOutput: String,
    count: Int,
    delayMs: Int,
  ): String {
    validateSensorRequest(sensorNames, count, delayMs)
    val names = sensorNames.map { it.trim() }
    names.forEach { name ->
      val exactName = Regex("(?i)(?<![A-Za-z0-9])${Regex.escape(name)}(?![A-Za-z0-9])")
      require(exactName.containsMatchIn(availableSensorOutput)) {
        "Sensor '$name' was not present in the latest Termux sensor listing."
      }
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

  fun failureState(result: TermuxShellResult): String {
    val detail = "${result.error.orEmpty()} ${result.stderr}".lowercase()
    return when {
      result.succeeded && result.outputTruncated -> "OUTPUT_TRUNCATED"
      result.succeeded && result.stdout.isBlank() -> "EMPTY_RESULT"
      listOf("permission denied", "permission required", "not authorized", "securityexception",
        "access denied", "permission is not granted").any { it in detail } -> "PERMISSION_REQUIRED"
      listOf("command not found", "not installed", "no such file or directory").any { it in detail } ->
        "COMMAND_NOT_INSTALLED"
      result.timedOut -> "TIMEOUT"
      result.processMayStillBeRunning -> "PROCESS_CLEANUP_UNCONFIRMED"
      result.outputTruncated -> "OUTPUT_TRUNCATED"
      else -> "OPERATION_FAILED"
    }
  }

  fun isCompleteRead(result: TermuxShellResult): Boolean =
    result.succeeded && !result.outputTruncated && result.stdout.isNotBlank()

  private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

  const val MIN_SAMPLE_COUNT = 1
  const val MAX_SAMPLE_COUNT = 10
  const val MIN_SENSOR_DELAY_MS = 100
  const val MAX_SENSOR_DELAY_MS = 5_000
  const val MAX_SENSOR_SELECTIONS = 8
}
