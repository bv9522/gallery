/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityScreenInspectionPolicyTest {
  @Test
  fun sensitiveLabelsBlockEntireScreen() {
    listOf(
      "Sign in",
      "Password",
      "Enter your PIN",
      "Payment method",
      "Credit card",
      "Bank transfer",
      "My account",
      "Verification code",
      "Patient portal",
      "Recovery phrase",
    ).forEach { label ->
      assertTrue("Expected sensitive marker in: $label",
        AccessibilityScreenInspectionPolicy.containsSensitiveText(label))
    }
  }

  @Test
  fun personalIdentifiersBlockEntireScreen() {
    assertTrue(AccessibilityScreenInspectionPolicy.containsSensitiveText("Contact user@example.com"))
    assertTrue(AccessibilityScreenInspectionPolicy.containsSensitiveText("Reference 12345678"))
    assertTrue(AccessibilityScreenInspectionPolicy.containsSensitiveText("Call +1 (212) 555-0199"))
    assertTrue(AccessibilityScreenInspectionPolicy.containsSensitiveText("Call 212-555-0199"))
    assertFalse(AccessibilityScreenInspectionPolicy.containsSensitiveText("Welcome back"))
  }

  @Test
  fun protectedAppCategoriesAreExcluded() {
    listOf(
      "com.android.settings",
      "com.example.mobilebank",
      "com.example.wallet",
      "com.example.passwordmanager",
      "com.example.patientportal",
    ).forEach { packageName ->
      assertTrue("Expected protected package: $packageName",
        AccessibilityScreenInspectionPolicy.isSensitivePackage(packageName))
    }
    assertFalse(AccessibilityScreenInspectionPolicy.isSensitivePackage("com.example.reader"))
  }

  @Test
  fun labelsAreSanitizedAndBounded() {
    val result = AccessibilityScreenInspectionPolicy.sanitizeLabel("  Hello\n\u200B  world  ")
    assertEquals("Hello world", result)
    assertNull(AccessibilityScreenInspectionPolicy.sanitizeLabel(" \n "))
    assertEquals(
      AccessibilityScreenInspectionPolicy.MAX_LABEL_CHARS,
      AccessibilityScreenInspectionPolicy.sanitizeLabel("x".repeat(300))?.length,
    )
  }

  @Test
  fun semanticRolesAreNormalized() {
    assertEquals("button", AccessibilityScreenInspectionPolicy.role("android.widget.Button"))
    assertEquals("text", AccessibilityScreenInspectionPolicy.role("android.widget.TextView"))
    assertEquals("checkbox", AccessibilityScreenInspectionPolicy.role("android.widget.CheckBox"))
    assertEquals("element", AccessibilityScreenInspectionPolicy.role("com.example.CustomWidget"))
  }

  @Test
  fun windowScreenshotRequiresApi34AndNeverFallsBackOnOlderAndroid() {
    assertFalse(AccessibilityScreenInspectionPolicy.supportsWindowScreenshot(33))
    assertTrue(AccessibilityScreenInspectionPolicy.supportsWindowScreenshot(34))
    assertTrue(AccessibilityScreenInspectionPolicy.supportsWindowScreenshot(35))
  }

  @Test
  fun handlesMustBeOpaqueAndWellFormed() {
    assertTrue(AccessibilityScreenInspectionPolicy.isValidHandle("ui_0123456789abcdef"))
    assertFalse(AccessibilityScreenInspectionPolicy.isValidHandle("com.example:id/button"))
    assertFalse(AccessibilityScreenInspectionPolicy.isValidHandle("ui_0123456789ABCDEf"))
    assertFalse(AccessibilityScreenInspectionPolicy.isValidHandle("ui_0123456789abcdef-extra"))
  }
}
