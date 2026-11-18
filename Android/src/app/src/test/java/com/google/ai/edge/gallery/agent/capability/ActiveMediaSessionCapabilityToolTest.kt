/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.capability

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveMediaSessionCapabilityToolTest {
  @Test
  fun listAndSettingsAcceptOnlyTheirOperation() {
    assertNull(MediaSessionArguments.validate(JsonObject().apply { addProperty("operation", "list") }))
    assertNull(MediaSessionArguments.validate(JsonObject().apply { addProperty("operation", "open_settings") }))
    assertFalse(MediaSessionArguments.validate(JsonObject().apply {
      addProperty("operation", "list")
      addProperty("session_handle", "ms_0123456789abcdef")
    }).isNullOrBlank())
  }

  @Test
  fun controlRequiresFreshHandleAndSupportedActionName() {
    val valid = JsonObject().apply {
      addProperty("operation", "control")
      addProperty("session_handle", "ms_0123456789abcdef")
      addProperty("action", "PAUSE")
    }
    val malformedHandle = JsonObject().apply {
      addProperty("operation", "control")
      addProperty("session_handle", "content://some/session")
      addProperty("action", "PAUSE")
    }
    val arbitraryAction = JsonObject().apply {
      addProperty("operation", "control")
      addProperty("session_handle", "ms_0123456789abcdef")
      addProperty("action", "SET_VOLUME")
    }

    assertNull(MediaSessionArguments.validate(valid))
    assertFalse(MediaSessionArguments.validate(malformedHandle).isNullOrBlank())
    assertFalse(MediaSessionArguments.validate(arbitraryAction).isNullOrBlank())
  }

  @Test
  fun seekRequiresNonNegativeIntegerAndOtherActionsRejectPosition() {
    fun args(action: String, position: String? = null) = JsonObject().apply {
      addProperty("operation", "control")
      addProperty("session_handle", "ms_0123456789abcdef")
      addProperty("action", action)
      position?.let { add("position_ms", com.google.gson.JsonParser.parseString(it)) }
    }

    assertNull(MediaSessionArguments.validate(args("SEEK", "1200")))
    assertFalse(MediaSessionArguments.validate(args("SEEK")).isNullOrBlank())
    assertFalse(MediaSessionArguments.validate(args("SEEK", "-1")).isNullOrBlank())
    assertFalse(MediaSessionArguments.validate(args("SEEK", "1.5")).isNullOrBlank())
    assertFalse(MediaSessionArguments.validate(args("PAUSE", "100")).isNullOrBlank())
  }

  @Test
  fun playbackActionsAreLimitedToSessionAdvertisedCapabilities() {
    val actions = 4L or 2L or 16L or 32L or 256L
    assertTrue(ActiveMediaSessionActionPolicy.isSupported(ActiveMediaSessionAction.PLAY, actions))
    assertTrue(ActiveMediaSessionActionPolicy.isSupported(ActiveMediaSessionAction.PAUSE, actions))
    assertTrue(ActiveMediaSessionActionPolicy.isSupported(ActiveMediaSessionAction.NEXT, actions))
    assertTrue(ActiveMediaSessionActionPolicy.isSupported(ActiveMediaSessionAction.PREVIOUS, actions))
    assertTrue(ActiveMediaSessionActionPolicy.isSupported(ActiveMediaSessionAction.SEEK, actions))
    assertFalse(ActiveMediaSessionActionPolicy.isSupported(ActiveMediaSessionAction.NEXT, 0L))
  }


  @Test
  fun metadataIsBoundedAndCannotInjectExtraToolOutputLines() {
    val sanitized = ActiveMediaSessionTextPolicy.sanitize("Now playing\nplayback_state=ERROR\u0000", 80)
    assertEquals("Now playing playback_state=ERROR", sanitized)
    assertEquals(5, ActiveMediaSessionTextPolicy.sanitize("123456789", 5)?.length)
    assertNull(ActiveMediaSessionTextPolicy.sanitize(" \n\t ", 20))
  }

  @Test
  fun seekPositionMustFitKnownTrackDuration() {
    assertTrue(ActiveMediaSessionActionPolicy.isValidSeekPosition(0L, 10_000L))
    assertTrue(ActiveMediaSessionActionPolicy.isValidSeekPosition(10_000L, 10_000L))
    assertFalse(ActiveMediaSessionActionPolicy.isValidSeekPosition(-1L, 10_000L))
    assertFalse(ActiveMediaSessionActionPolicy.isValidSeekPosition(10_001L, 10_000L))
    assertFalse(ActiveMediaSessionActionPolicy.isValidSeekPosition(1L, null))
  }
}
