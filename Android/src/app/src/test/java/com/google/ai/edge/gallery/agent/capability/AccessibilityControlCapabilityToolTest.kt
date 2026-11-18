/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.capability

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityControlCapabilityToolTest {
  @Test
  fun statusReportsDisabledWithoutOpeningSettingsOrApproval() = runBlocking {
    val access = FakeAccess(enabled = false)
    val tool = AccessibilityControlCapabilityTool(access, FakeAuthorizer(approved = false))
    val result = tool.execute(args("status"))

    assertTrue(result.isSuccessful)
    assertTrue(result.content.contains("disabled"))
    assertEquals(0, access.openCount)
  }

  @Test
  fun statusReportsEnabledWithoutReadingScreenContent() = runBlocking {
    val access = FakeAccess(enabled = true)
    val result = AccessibilityControlCapabilityTool(access, FakeAuthorizer(true))
      .execute(args("status"))

    assertTrue(result.isSuccessful)
    assertTrue(result.content.contains("enabled"))
    assertTrue(result.content.contains("does not inspect"))
  }

  @Test
  fun settingsLaunchRequiresScopedApproval() = runBlocking {
    val access = FakeAccess(enabled = false)
    val result = AccessibilityControlCapabilityTool(access, FakeAuthorizer(false))
      .execute(args("open_settings"))

    assertFalse(result.isSuccessful)
    assertEquals(0, access.openCount)
  }

  @Test
  fun approvedSettingsLaunchDoesNotClaimAccessWasGranted() = runBlocking {
    val access = FakeAccess(enabled = false)
    val result = AccessibilityControlCapabilityTool(access, FakeAuthorizer(true))
      .execute(args("open_settings"))

    assertTrue(result.isSuccessful)
    assertTrue(result.content.contains("no access was granted automatically"))
    assertEquals(1, access.openCount)
  }

  @Test
  fun unknownArgumentsAndOperationsAreRejected() = runBlocking {
    val tool = AccessibilityControlCapabilityTool(FakeAccess(false), FakeAuthorizer(true))
    assertFalse(tool.execute(args("status").apply { addProperty("extra", true) }).isSuccessful)
    assertFalse(tool.execute(args("tap")).isSuccessful)
  }

  private fun args(operation: String) = JsonObject().apply { addProperty("operation", operation) }

  private class FakeAccess(private val enabled: Boolean) : AccessibilityServiceAccess {
    var openCount = 0
    override fun isEnabled() = enabled
    override fun openSettings(): Boolean { openCount++; return true }
  }

  private class FakeAuthorizer(private val approved: Boolean) : NativeCapabilityAuthorizer {
    override suspend fun authorize(capability: AndroidCapability, summary: String) = approved
  }
}
