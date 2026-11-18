/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.capability

import com.google.gson.JsonObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class AccessibilityScreenInspectionArgumentsTest {
  @Test
  fun validOneShotSemanticActionsAreAccepted() {
    AccessibilitySemanticAction.values().forEach { action ->
      val arguments = JsonObject().apply {
        addProperty("operation", "act")
        addProperty("element_handle", "ui_0123456789abcdef")
        addProperty("action", action.name)
      }
      assertNull(AccessibilityScreenInspectionArguments.validate(arguments))
    }
  }

  @Test
  fun actionRequiresFreshOpaqueHandleAndAction() {
    val missingHandle = JsonObject().apply {
      addProperty("operation", "act")
      addProperty("action", "CLICK")
    }
    val malformedHandle = JsonObject().apply {
      addProperty("operation", "act")
      addProperty("element_handle", "com.example:id/button")
      addProperty("action", "CLICK")
    }
    val missingAction = JsonObject().apply {
      addProperty("operation", "act")
      addProperty("element_handle", "ui_0123456789abcdef")
    }
    assertNotNull(AccessibilityScreenInspectionArguments.validate(missingHandle))
    assertNotNull(AccessibilityScreenInspectionArguments.validate(malformedHandle))
    assertNotNull(AccessibilityScreenInspectionArguments.validate(missingAction))
  }

  @Test
  fun unsupportedActionsAndUnexpectedArgumentsAreRejected() {
    val unsupported = JsonObject().apply {
      addProperty("operation", "act")
      addProperty("element_handle", "ui_0123456789abcdef")
      addProperty("action", "TYPE_TEXT")
    }
    val unexpected = JsonObject().apply {
      addProperty("operation", "act")
      addProperty("element_handle", "ui_0123456789abcdef")
      addProperty("action", "CLICK")
      addProperty("text", "do not accept model-supplied text")
    }
    assertNotNull(AccessibilityScreenInspectionArguments.validate(unsupported))
    assertNotNull(AccessibilityScreenInspectionArguments.validate(unexpected))
  }

  @Test
  fun unknownOperationsAndUnexpectedReadArgumentsAreRejected() {
    val unknown = JsonObject().apply { addProperty("operation", "swipe") }
    val extra = JsonObject().apply {
      addProperty("operation", "inspect_external")
      addProperty("package_name", "com.example.app")
    }
    assertNotNull(AccessibilityScreenInspectionArguments.validate(unknown))
    assertNotNull(AccessibilityScreenInspectionArguments.validate(extra))
  }
}
