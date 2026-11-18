/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationContentPolicyTest {
  @Test
  fun sanitizesControlsAndCollapsesWhitespace() {
    assertEquals(
      "A private message preview",
      NotificationContentPolicy.sanitize(" A private\nmessage\tpreview ", 80),
    )
  }

  @Test
  fun boundsTextWithoutReturningEmptyValues() {
    assertEquals("abcdef", NotificationContentPolicy.sanitize("abcdefgh", 6))
    assertNull(NotificationContentPolicy.sanitize(" \n\t ", 40))
    assertNull(NotificationContentPolicy.sanitize(null, 40))
  }

  @Test
  fun rejectsInvalidBounds() {
    try {
      NotificationContentPolicy.sanitize("text", 0)
      throw AssertionError("Expected a non-positive character bound to be rejected.")
    } catch (expected: IllegalArgumentException) {
      assertEquals("maxChars must be positive.", expected.message)
    }
  }
}
