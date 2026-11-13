/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.agent.capability

import com.google.ai.edge.gallery.termux.TermuxShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxApiReadCommandTest {
  private val sensors = """
    Accelerometer
    Gyroscope
    Magnetic Field
  """.trimIndent()

  @Test
  fun sensorListUsesOnlyFixedListOption() {
    assertEquals("termux-sensor -l", TermuxApiReadCommand.sensorList())
  }

  @Test
  fun finiteSensorReadUsesListedNamesAndBoundedArguments() {
    assertEquals(
      "termux-sensor -s 'Accelerometer,Gyroscope' -d 250 -n 3",
      TermuxApiReadCommand.sensorRead(
        listOf("Accelerometer", "Gyroscope"),
        sensors,
        count = 3,
        delayMs = 250,
      ),
    )
  }

  @Test
  fun sensorReadRejectsUnlistedNamesAndShellSyntax() {
    assertThrows(IllegalArgumentException::class.java) {
      TermuxApiReadCommand.sensorRead(listOf("Unlisted"), sensors, 1, 100)
    }
    assertThrows(IllegalArgumentException::class.java) {
      TermuxApiReadCommand.sensorRead(listOf("Accel"), sensors, 1, 100)
    }
    assertThrows(IllegalArgumentException::class.java) {
      TermuxApiReadCommand.sensorRead(listOf("Accelerometer; id"), sensors, 1, 100)
    }
  }

  @Test
  fun sensorReadRejectsEmptyDuplicateAndOutOfRangeArguments() {
    assertThrows(IllegalArgumentException::class.java) {
      TermuxApiReadCommand.sensorRead(emptyList(), sensors, 1, 100)
    }
    assertThrows(IllegalArgumentException::class.java) {
      TermuxApiReadCommand.sensorRead(listOf("Accelerometer", "accelerometer"), sensors, 1, 100)
    }
    assertThrows(IllegalArgumentException::class.java) {
      TermuxApiReadCommand.sensorRead(listOf("Accelerometer"), sensors, 0, 100)
    }
    assertThrows(IllegalArgumentException::class.java) {
      TermuxApiReadCommand.sensorRead(listOf("Accelerometer"), sensors, 11, 100)
    }
    assertThrows(IllegalArgumentException::class.java) {
      TermuxApiReadCommand.sensorRead(listOf("Accelerometer"), sensors, 1, 99)
    }
    assertThrows(IllegalArgumentException::class.java) {
      TermuxApiReadCommand.sensorRead(listOf("Accelerometer"), sensors, 1, 5_001)
    }
  }

  @Test
  fun locationAllowsOnlyFiniteOnceAndLastRequests() {
    assertEquals(
      "termux-location -p network -r once",
      TermuxApiReadCommand.location("network", "once"),
    )
    assertEquals(
      "termux-location -p passive -r last",
      TermuxApiReadCommand.location("passive", "last"),
    )
  }

  @Test
  fun locationRejectsUnsupportedProvidersAndContinuousUpdates() {
    assertThrows(IllegalArgumentException::class.java) {
      TermuxApiReadCommand.location("provider; id", "once")
    }
    assertThrows(IllegalArgumentException::class.java) {
      TermuxApiReadCommand.location("gps", "updates")
    }
  }

  @Test
  fun wifiCommandsTakeNoModelSuppliedArguments() {
    assertEquals("termux-wifi-connectioninfo", TermuxApiReadCommand.wifiConnectionInfo())
    assertEquals("termux-wifi-scaninfo", TermuxApiReadCommand.wifiLastScanInfo())
  }

  @Test
  fun infraredCommandRequiresReportedFrequencyAndSubTwoSecondNumericPattern() {
    assertEquals("termux-infrared-frequencies", TermuxApiReadCommand.infraredFrequencies())
    assertEquals(
      "termux-infrared-transmit -f 38000 '500,500,400,400'",
      TermuxApiReadCommand.infraredTransmit(38_000, listOf(500, 500, 400, 400), """{"frequencies":[30000,38000]}"""),
    )
    assertThrows(IllegalArgumentException::class.java) {
      TermuxApiReadCommand.infraredTransmit(40_000, listOf(500, 500), "[30000,38000]")
    }
    assertThrows(IllegalArgumentException::class.java) {
      TermuxApiReadCommand.infraredTransmit(38_000, listOf(1_000_000, 1_000_000), "[38000]")
    }
    assertThrows(IllegalArgumentException::class.java) {
      TermuxApiReadCommand.infraredTransmit(38_000, listOf(0, 500), "[38000]")
    }
    assertThrows(IllegalArgumentException::class.java) {
      TermuxApiReadCommand.infraredTransmit(38_000, listOf(500), "[38000]")
    }
  }

  @Test
  fun ttsCommandQuotesTextAndEnforcesBounds() {
    assertEquals("termux-tts-engines", TermuxApiReadCommand.ttsEngines())
    assertEquals("termux-tts-speak 'Hello Coco'\\''s world'", TermuxApiReadCommand.ttsSpeak("Hello Coco's world"))
    assertThrows(IllegalArgumentException::class.java) {
      TermuxApiReadCommand.ttsSpeak("  ")
    }
    assertThrows(IllegalArgumentException::class.java) {
      TermuxApiReadCommand.ttsSpeak("x".repeat(TermuxApiReadCommand.MAX_TTS_TEXT_CHARS + 1))
    }
  }

  @Test
  fun cameraInfoAndSpeechRecognitionUseFixedCommands() {
    assertEquals("termux-camera-info", TermuxApiReadCommand.cameraInfo())
    assertEquals("termux-speech-to-text", TermuxApiReadCommand.speechToText())
  }

  @Test
  fun nfcCommandsAreFixedAndWriteTextIsBoundedAndQuoted() {
    assertEquals("termux-nfc -r full", TermuxApiReadCommand.nfcRead())
    assertEquals("termux-nfc -w -t 'Hello NFC'\\''s tag'", TermuxApiReadCommand.nfcWrite("Hello NFC's tag"))
    assertThrows(IllegalArgumentException::class.java) { TermuxApiReadCommand.nfcWrite(" ") }
    assertThrows(IllegalArgumentException::class.java) {
      TermuxApiReadCommand.nfcWrite("x".repeat(TermuxApiReadCommand.MAX_NFC_TEXT_CHARS + 1))
    }
  }

  @Test
  fun readFailureStatesDistinguishPermissionMissingCommandTimeoutAndCleanup() {
    assertEquals(
      "PERMISSION_REQUIRED",
      TermuxApiReadCommand.failureState(TermuxShellResult("", "Permission denied", 1)),
    )
    assertEquals(
      "COMMAND_NOT_INSTALLED",
      TermuxApiReadCommand.failureState(TermuxShellResult("", "termux-location: command not found", 127)),
    )
    assertEquals(
      "TIMEOUT",
      TermuxApiReadCommand.failureState(
        TermuxShellResult("", "", -1, timedOut = true, error = "deadline exceeded"),
      ),
    )
    assertEquals(
      "PROCESS_CLEANUP_UNCONFIRMED",
      TermuxApiReadCommand.failureState(
        TermuxShellResult("", "", 0, processMayStillBeRunning = true, error = "cleanup failed"),
      ),
    )
  }

  @Test
  fun incompleteAndEmptyReadResultsAreNeverTreatedAsComplete() {
    val truncated = TermuxShellResult("partial", "", 0, outputTruncated = true)
    val empty = TermuxShellResult("", "", 0)
    val complete = TermuxShellResult("valid payload", "", 0)

    assertEquals("OUTPUT_TRUNCATED", TermuxApiReadCommand.failureState(truncated))
    assertEquals("EMPTY_RESULT", TermuxApiReadCommand.failureState(empty))
    assertFalse(TermuxApiReadCommand.isCompleteRead(truncated))
    assertFalse(TermuxApiReadCommand.isCompleteRead(empty))
    assertTrue(TermuxApiReadCommand.isCompleteRead(complete))
  }
}
