/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.capability

import com.google.ai.edge.gallery.agent.coordinator.AgentToolResult
import com.google.ai.edge.gallery.tools.ToolAction
import com.google.gson.JsonObject
import kotlinx.coroutines.channels.Channel
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

    assertFalse(result.isError)
    assertTrue(result.text.contains("disabled"))
    assertEquals(0, access.openCount)
  }

  @Test
  fun statusReportsEnabledWithoutReadingScreenContent() = runBlocking {
    val access = FakeAccess(enabled = true)
    val result = AccessibilityControlCapabilityTool(access, FakeAuthorizer(true))
      .execute(args("status"))

    assertFalse(result.isError)
    assertTrue(result.text.contains("enabled"))
    assertTrue(result.text.contains("does not inspect"))
  }

  @Test
  fun settingsLaunchRequiresScopedApproval() = runBlocking {
    val access = FakeAccess(enabled = false)
    val result = AccessibilityControlCapabilityTool(access, FakeAuthorizer(false))
      .execute(args("open_settings"))

    assertTrue(result.isError)
    assertEquals(0, access.openCount)
  }

  @Test
  fun approvedSettingsLaunchDoesNotClaimAccessWasGranted() = runBlocking {
    val access = FakeAccess(enabled = false)
    val result = AccessibilityControlCapabilityTool(access, FakeAuthorizer(true))
      .execute(args("open_settings"))

    assertFalse(result.isError)
    assertTrue(result.text.contains("no access was granted automatically"))
    assertEquals(1, access.openCount)
  }

  @Test
  fun unknownArgumentsAndOperationsAreRejected() = runBlocking {
    val tool = AccessibilityControlCapabilityTool(FakeAccess(false), FakeAuthorizer(true))
    assertTrue(tool.execute(args("status").apply { addProperty("extra", true) }).isError)
    assertTrue(tool.execute(args("tap")).isError)
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
