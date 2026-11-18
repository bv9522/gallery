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
      "Email",
      "Phone",
      "Username",
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

  @Test
  fun semanticActionsRequireEnabledMatchingNodeCapabilities() {
    assertTrue(AccessibilityScreenInspectionPolicy.supportsAction(
      AccessibilitySemanticAction.CLICK, true, clickable = true,
      longClickable = false, scrollable = false,
    ))
    assertFalse(AccessibilityScreenInspectionPolicy.supportsAction(
      AccessibilitySemanticAction.CLICK, false, clickable = true,
      longClickable = false, scrollable = false,
    ))
    assertTrue(AccessibilityScreenInspectionPolicy.supportsAction(
      AccessibilitySemanticAction.LONG_CLICK, true, clickable = false,
      longClickable = true, scrollable = false,
    ))
    assertFalse(AccessibilityScreenInspectionPolicy.supportsAction(
      AccessibilitySemanticAction.LONG_CLICK, true, clickable = true,
      longClickable = false, scrollable = false,
    ))
    assertTrue(AccessibilityScreenInspectionPolicy.supportsAction(
      AccessibilitySemanticAction.SCROLL_FORWARD, true, clickable = false,
      longClickable = false, scrollable = true,
    ))
    assertTrue(AccessibilityScreenInspectionPolicy.supportsAction(
      AccessibilitySemanticAction.SCROLL_BACKWARD, true, clickable = false,
      longClickable = false, scrollable = true,
    ))
    assertFalse(AccessibilityScreenInspectionPolicy.supportsAction(
      AccessibilitySemanticAction.SCROLL_FORWARD, true, clickable = false,
      longClickable = false, scrollable = false,
    ))
  }

  @Test
  fun onlyOrdinarySingleLinePlainTextEditTextFieldsAreEligible() {
    val plainText = android.text.InputType.TYPE_CLASS_TEXT or
      android.text.InputType.TYPE_TEXT_VARIATION_NORMAL
    assertTrue(AccessibilityScreenInspectionPolicy.isSafeTextInputField(
      "android.widget.EditText", plainText, editable = true, password = false, currentText = "",
    ))
    assertFalse(AccessibilityScreenInspectionPolicy.isSafeTextInputField(
      "android.widget.EditText", plainText, editable = false, password = false, currentText = "",
    ))
    assertFalse(AccessibilityScreenInspectionPolicy.isSafeTextInputField(
      "android.widget.EditText", plainText, editable = true, password = true, currentText = "",
    ))
    assertFalse(AccessibilityScreenInspectionPolicy.isSafeTextInputField(
      "android.widget.EditText", android.text.InputType.TYPE_CLASS_NUMBER,
      editable = true, password = false, currentText = "",
    ))
    assertFalse(AccessibilityScreenInspectionPolicy.isSafeTextInputField(
      "android.widget.EditText",
      android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
      editable = true, password = false, currentText = "",
    ))
    assertFalse(AccessibilityScreenInspectionPolicy.isSafeTextInputField(
      "android.widget.EditText",
      plainText or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE,
      editable = true, password = false, currentText = "",
    ))
    assertFalse(AccessibilityScreenInspectionPolicy.isSafeTextInputField(
      "android.widget.EditText",
      plainText or android.text.InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE,
      editable = true, password = false, currentText = "",
    ))
    assertFalse(AccessibilityScreenInspectionPolicy.isSafeTextInputField(
      "android.widget.TextView", plainText, editable = true, password = false, currentText = "",
    ))
    assertFalse(AccessibilityScreenInspectionPolicy.isSafeTextInputField(
      "android.widget.EditText", plainText, editable = true, password = false, currentText = "password123",
    ))
  }

  @Test
  fun textEntryRejectsSensitiveAndUnboundedValues() {
    assertTrue(AccessibilityScreenInspectionPolicy.isSafeInputText("search for blue cars"))
    assertFalse(AccessibilityScreenInspectionPolicy.isSafeInputText(""))
    assertFalse(AccessibilityScreenInspectionPolicy.isSafeInputText("password123"))
    assertFalse(AccessibilityScreenInspectionPolicy.isSafeInputText("my bank account"))
    assertFalse(AccessibilityScreenInspectionPolicy.isSafeInputText("user@example.com"))
    assertFalse(AccessibilityScreenInspectionPolicy.isSafeInputText(listOf("line one", "line two").joinToString(System.lineSeparator())))
    assertFalse(AccessibilityScreenInspectionPolicy.isSafeInputText("x".repeat(257)))
  }

  @Test
  fun selectionRangesMustBeNonEmptyAndInsideCurrentText() {
    assertTrue(AccessibilityScreenInspectionPolicy.isValidSelection(0, 4, 4))
    assertTrue(AccessibilityScreenInspectionPolicy.isValidSelection(2, 5, 8))
    assertFalse(AccessibilityScreenInspectionPolicy.isValidSelection(0, 0, 8))
    assertFalse(AccessibilityScreenInspectionPolicy.isValidSelection(-1, 2, 8))
    assertFalse(AccessibilityScreenInspectionPolicy.isValidSelection(2, 9, 8))
    assertFalse(AccessibilityScreenInspectionPolicy.isValidSelection(0, 2, 257))
  }
}
