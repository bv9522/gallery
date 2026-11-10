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
  fun unknownArgumentIsRejected() {
    val arguments = JsonObject().apply {
      addProperty("operation", "device_info")
      addProperty("stream", "MUSIC")
    }

    assertTrue(AndroidControlArguments.validate(arguments).orEmpty().contains("Accepted arguments"))
  }
}
