/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file contains tests for native Android tool argument validation.
 */

package com.google.ai.edge.gallery.agent.capability

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidControlArgumentsTest {
  @Test
  fun dialPhoneAcceptsCommonInternationalNumberFormatting() {
    val arguments = JsonObject().apply {
      addProperty("operation", "dial_phone")
      addProperty("phone_number", "+1 (555) 010-1234")
    }

    assertNull(AndroidControlArguments.validate(arguments))
  }

  @Test
  fun dialPhoneRejectsInvalidCharactersAndTooFewDigits() {
    val invalidCharacters = JsonObject().apply {
      addProperty("operation", "dial_phone")
      addProperty("phone_number", "555-0100;123")
    }
    val tooFewDigits = JsonObject().apply {
      addProperty("operation", "dial_phone")
      addProperty("phone_number", "+12")
    }

    assertTrue(AndroidControlArguments.validate(invalidCharacters).orEmpty().contains("phone_number"))
    assertTrue(AndroidControlArguments.validate(tooFewDigits).orEmpty().contains("phone_number"))
  }

  @Test
  fun smsComposerRequiresBoundedNonBlankMessage() {
    val valid = JsonObject().apply {
      addProperty("operation", "compose_sms")
      addProperty("phone_number", "5550101234")
      addProperty("sms_message", "Hello there")
    }
    val blank = JsonObject().apply {
      addProperty("operation", "compose_sms")
      addProperty("phone_number", "5550101234")
      addProperty("sms_message", "   ")
    }
    val oversized = JsonObject().apply {
      addProperty("operation", "compose_sms")
      addProperty("phone_number", "5550101234")
      addProperty("sms_message", "x".repeat(1_601))
    }

    assertNull(AndroidControlArguments.validate(valid))
    assertTrue(AndroidControlArguments.validate(blank).orEmpty().contains("sms_message"))
    assertTrue(AndroidControlArguments.validate(oversized).orEmpty().contains("sms_message"))
  }

  @Test
  fun biometricAndKeystoreArgumentsAreBoundedAndValidated() {
    val biometric = JsonObject().apply {
      addProperty("operation", "biometric_auth")
      addProperty("reason", "Unlock protected data")
    }
    val invalidAlias = JsonObject().apply {
      addProperty("operation", "keystore_create")
      addProperty("key_alias", "../outside")
    }
    val validAlias = JsonObject().apply {
      addProperty("operation", "keystore_create")
      addProperty("key_alias", "private_key_01")
    }
    val oversizedPlaintext = JsonObject().apply {
      addProperty("operation", "keystore_encrypt")
      addProperty("key_alias", "private_key_01")
      addProperty("plaintext", "x".repeat(4_097))
    }
    val invalidCiphertext = JsonObject().apply {
      addProperty("operation", "keystore_decrypt")
      addProperty("key_alias", "private_key_01")
      addProperty("iv_base64", "not-base64")
      addProperty("ciphertext_base64", "not-base64")
    }

    assertNull(AndroidControlArguments.validate(biometric))
    assertNull(AndroidControlArguments.validate(validAlias))
    assertTrue(AndroidControlArguments.validate(invalidAlias).orEmpty().contains("key_alias"))
    assertTrue(AndroidControlArguments.validate(oversizedPlaintext).orEmpty().contains("plaintext"))
    assertTrue(AndroidControlArguments.validate(invalidCiphertext).orEmpty().contains("iv_base64"))
  }

  @Test
  fun speakTextRequiresBoundedNonBlankText() {
    val valid = JsonObject().apply {
      addProperty("operation", "speak_text")
      addProperty("speech_text", "Hello from Gallery.")
    }
    val blank = JsonObject().apply {
      addProperty("operation", "speak_text")
      addProperty("speech_text", "   ")
    }
    val oversized = JsonObject().apply {
      addProperty("operation", "speak_text")
      addProperty("speech_text", "x".repeat(1_001))
    }

    assertNull(AndroidControlArguments.validate(valid))
    assertTrue(AndroidControlArguments.validate(blank).orEmpty().contains("speak_text"))
    assertTrue(AndroidControlArguments.validate(oversized).orEmpty().contains("1000"))
  }

  @Test
  fun downloadStatusIsReadOnlyAndTakesNoArguments() {
    val arguments = JsonObject().apply {
      addProperty("operation", "download_status")
    }

    assertNull(AndroidControlArguments.validate(arguments))
  }

  @Test
  fun cancelDownloadRequiresAnOpaqueHandleInTheExpectedFormat() {
    val missing = JsonObject().apply {
      addProperty("operation", "cancel_download")
    }
    val malformed = JsonObject().apply {
      addProperty("operation", "cancel_download")
      addProperty("download_handle", "worker-uuid")
    }
    val valid = JsonObject().apply {
      addProperty("operation", "cancel_download")
      addProperty("download_handle", "dl_" + "a".repeat(32))
    }

    assertTrue(AndroidControlArguments.validate(missing).orEmpty().contains("download_handle"))
    assertTrue(AndroidControlArguments.validate(malformed).orEmpty().contains("download_handle"))
    assertNull(AndroidControlArguments.validate(valid))
  }

  @Test
  fun mediaSearchAcceptsBoundedVisualQueryAndDefaults() {
    val valid = JsonObject().apply {
      addProperty("operation", "media_search")
      addProperty("query", "Pitching At Field")
    }
    val invalidMode = JsonObject().apply {
      addProperty("operation", "media_search")
      addProperty("query", "Pitching At Field")
      addProperty("search_mode", "filesystem")
    }
    val oversizedLimit = JsonObject().apply {
      addProperty("operation", "media_search")
      addProperty("query", "Pitching At Field")
      addProperty("limit", 21)
    }

    assertNull(AndroidControlArguments.validate(valid))
    assertTrue(AndroidControlArguments.validate(invalidMode).orEmpty().contains("search_mode"))
    assertTrue(AndroidControlArguments.validate(oversizedLimit).orEmpty().contains("limit"))
  }

  @Test
  fun mediaIndexTakesNoArgumentsAndPlaybackRequiresFreshOpaqueHandle() {
    val index = JsonObject().apply { addProperty("operation", "media_index") }
    val missingHandle = JsonObject().apply { addProperty("operation", "media_play") }
    val malformedHandle = JsonObject().apply {
      addProperty("operation", "media_play")
      addProperty("media_handle", "content://media/external/video/media/123")
    }
    val validHandle = JsonObject().apply {
      addProperty("operation", "media_play")
      addProperty("media_handle", "media_" + "b".repeat(32))
    }

    assertNull(AndroidControlArguments.validate(index))
    assertTrue(AndroidControlArguments.validate(missingHandle).orEmpty().contains("media_handle"))
    assertTrue(AndroidControlArguments.validate(malformedHandle).orEmpty().contains("media_handle"))
    assertNull(AndroidControlArguments.validate(validHandle))
  }

  @Test
  fun statusWithStreamIsRejectedByTheGenericOperationSchema() {
    val arguments = JsonObject().apply {
      addProperty("operation", "status")
      addProperty("stream", "MUSIC")
    }

    val error = AndroidControlArguments.validate(arguments)

    assertTrue(error.orEmpty().contains("Unsupported argument(s) for status: stream"))
    assertTrue(error.orEmpty().contains("Accepted arguments: operation"))
    assertFalse(error.orEmpty().contains("volume_status"))
  }

  @Test
  fun volumeStatusAcceptsMusicStream() {
    val arguments = JsonObject().apply {
      addProperty("operation", "volume_status")
      addProperty("stream", "MUSIC")
    }

    assertNull(AndroidControlArguments.validate(arguments))
  }

  @Test
  fun volumeStatusCanUseDefaultStream() {
    val arguments = JsonObject().apply {
      addProperty("operation", "volume_status")
    }

    assertNull(AndroidControlArguments.validate(arguments))
  }

  @Test
  fun missingMutationArgumentIsReportedBeforeExecution() {
    val arguments = JsonObject().apply {
      addProperty("operation", "set_volume")
      addProperty("stream", "MUSIC")
    }

    assertEquals(
      "set_volume requires: volume. Retry the same operation with those arguments.",
      AndroidControlArguments.validate(arguments),
    )
  }

  @Test
  fun unsupportedOperationListsValidChoices() {
    val arguments = JsonObject().apply { addProperty("operation", "volume") }

    assertTrue(AndroidControlArguments.validate(arguments).orEmpty().contains("volume_status"))
  }

  @Test
  fun notificationScheduleWithoutExplicitDateIsAccepted() {
    val arguments = JsonObject().apply {
      addProperty("operation", "schedule_notification")
      addProperty("title", "Reminder")
      addProperty("message", "Time-only reminder")
      addProperty("hour", 9)
      addProperty("minute", 30)
      addProperty("repeat_daily", true)
    }

    assertNull(AndroidControlArguments.validate(arguments))
  }

  @Test
  fun notificationTimeMustBeNumericNotAString() {
    val arguments = JsonObject().apply {
      addProperty("operation", "schedule_notification")
      addProperty("title", "Reminder")
      addProperty("message", "Test")
      addProperty("hour", "9")
      addProperty("minute", 30)
    }

    assertTrue(AndroidControlArguments.validate(arguments).orEmpty().contains("hour must be an integer"))
  }

  @Test
  fun impossibleNotificationCalendarDateIsRejected() {
    val arguments = JsonObject().apply {
      addProperty("operation", "schedule_notification")
      addProperty("title", "Reminder")
      addProperty("message", "Test")
      addProperty("hour", 9)
      addProperty("minute", 30)
      addProperty("year", 2030)
      addProperty("month", 2)
      addProperty("day", 30)
    }

    assertTrue(AndroidControlArguments.validate(arguments).orEmpty().contains("valid calendar date"))
  }

  @Test
  fun unknownArgumentIsRejected() {
    val arguments = JsonObject().apply {
      addProperty("operation", "device_info")
      addProperty("stream", "MUSIC")
    }

    assertTrue(AndroidControlArguments.validate(arguments).orEmpty().contains("Accepted arguments"))
  }
}
