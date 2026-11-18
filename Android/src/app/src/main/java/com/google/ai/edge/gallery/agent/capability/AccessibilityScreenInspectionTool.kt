/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.capability

import android.content.Context
import com.google.ai.edge.gallery.agent.coordinator.AgentTool
import com.google.ai.edge.gallery.agent.coordinator.AgentToolResult
import com.google.ai.edge.gallery.tools.OpenAccessibilitySettingsToolAction
import com.google.ai.edge.gallery.tools.ToolAction
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withTimeoutOrNull

/** Explicit, read-only semantic inspection of the currently visible application window. */
class AccessibilityScreenInspectionTool internal constructor(
  private val adapter: AndroidAccessibilityScreenAdapter,
  private val authorizer: NativeCapabilityAuthorizer,
  private val settingsRequester: AccessibilitySettingsRequester,
) : AgentTool {
  constructor(context: Context, actionChannel: SendChannel<ToolAction>) : this(
    AndroidAccessibilityScreenAdapter(context.applicationContext),
    ChannelNativeCapabilityAuthorizer(actionChannel),
    ChannelAccessibilitySettingsRequester(actionChannel),
  )

  override val name = "screen_inspect"
  override val description =
    "Use operation=status to check Gallery's opt-in AccessibilityService state. Use operation=inspect to read a bounded semantic summary of the current visible app window after Gallery accessibility special access is manually enabled. Inspection is read-only, requires one-time approval, traverses at most 120 nodes/depth 12, and returns at most 32 labeled elements with 30-second opaque handles. Screens containing editable/password fields, sign-in/credential, payment/banking, identity, health, system-settings, or detected personal identifiers are excluded in full. Only the active window at invocation is inspected; because Agent Chat is normally foreground, this usually means Gallery's own window unless the user has arranged another active window (for example, split-screen). Text is sanitized and bounded; package names, view IDs, coordinates, screenshots, hidden content, and notification contents are never returned. Use operation=open_settings only when the user explicitly asks to enable access; the user must manually enable Gallery in Android Accessibility settings. No gestures, typing, taps, screenshots, monitoring, or background collection are implemented."

  override val parametersSchema: JsonObject
    get() = JsonObject().apply {
      addProperty("type", "object")
      add("properties", JsonObject().apply {
        add("operation", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply { listOf("status", "inspect", "open_settings").forEach { add(it) } })
        })
      })
      add("required", JsonArray().apply { add("operation") })
      addProperty("additionalProperties", false)
    }

  override suspend fun execute(arguments: JsonObject): AgentToolResult {
    val operation = arguments.get("operation")
      ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
      ?: return AgentToolResult("operation must be status, inspect, or open_settings.", false)
    if (arguments.keySet() != setOf("operation")) {
      return AgentToolResult("Unexpected arguments are not accepted.", false)
    }
    return try {
      when (operation) {
        "status" -> status()
        "inspect" -> inspect()
        "open_settings" -> openSettings()
        else -> AgentToolResult("Unsupported operation.", false)
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      AgentToolResult(error.message ?: "Screen inspection failed.", false)
    }
  }

  private fun status(): AgentToolResult {
    val availability = adapter.enabledServiceAvailability()
    val detail = when (availability) {
      ProviderAvailability.AVAILABLE -> "Gallery accessibility service is enabled and connected."
      ProviderAvailability.PERMISSION_REQUIRED -> "Gallery accessibility special access is not enabled. No screen content was read."
      ProviderAvailability.SERVICE_STOPPED -> "Gallery accessibility is enabled but its service is not connected. No screen content was read."
      ProviderAvailability.UNSUPPORTED -> "Android AccessibilityManager is unavailable."
      else -> "Accessibility service state is unknown."
    }
    return AgentToolResult(buildString {
      appendLine("provider=ANDROID_ACCESSIBILITY")
      appendLine("capability=UI_TREE_READ")
      appendLine("availability=$availability")
      appendLine("detail=$detail")
      appendLine("capability=SCREEN_CAPTURE")
      appendLine("availability=UNSUPPORTED")
      appendLine("capability=UI_GESTURE")
      appendLine("availability=UNSUPPORTED")
      appendLine("capability=UI_TEXT_INPUT")
      appendLine("availability=UNSUPPORTED")
    }.trimEnd())
  }

  private suspend fun inspect(): AgentToolResult {
    if (adapter.enabledServiceAvailability() != ProviderAvailability.AVAILABLE) {
      return AgentToolResult(
        "Gallery accessibility service is not enabled and connected. No screen content was read. If the user wants to enable it, use operation=open_settings; the user must enable Gallery manually.",
        false,
      )
    }
    if (!authorizer.authorize(
        AndroidCapability.UI_TREE_READ,
        "Inspect the current visible app window: at most 120 accessibility nodes, depth 12, and 32 semantic elements. Sensitive screens are excluded; this is read-only and does not capture screenshots."
      )) {
      return AgentToolResult("Semantic screen inspection was not approved.", false)
    }
    val snapshot = adapter.inspect().getOrElse { error ->
      return AgentToolResult(error.message ?: "Screen inspection was blocked.", false)
    }
    if (snapshot.elements.isEmpty()) {
      return AgentToolResult("The current visible window contains no safe semantic elements to report.")
    }
    return AgentToolResult(buildString {
      appendLine("elements=${snapshot.elements.size}")
      appendLine("limit=32")
      appendLine("truncated=${snapshot.truncated}")
      snapshot.elements.forEachIndexed { index, element ->
        appendLine("element=${index + 1}")
        appendLine("element_handle=${element.handle}")
        appendLine("role=${element.role}")
        element.label?.let { appendLine("label=$it") }
        appendLine("clickable=${element.clickable}")
        appendLine("scrollable=${element.scrollable}")
        appendLine("enabled=${element.enabled}")
        if (element.selected) appendLine("selected=true")
        element.checked?.let { appendLine("checked=$it") }
      }
      appendLine("note=Handles expire after 30 seconds and are inspection references only; UI actions are not implemented in this slice.")
    }.trimEnd())
  }

  private suspend fun openSettings(): AgentToolResult {
    if (!authorizer.authorize(
        AndroidCapability.UI_TREE_READ,
        "Open Android Accessibility settings so the user can optionally enable Gallery's screen-inspection service. This grants no access by itself."
      )) {
      return AgentToolResult("Opening Android Accessibility settings was not approved.", false)
    }
    return if (settingsRequester.openSettings()) {
      AgentToolResult("Android Accessibility settings opened. Gallery remains unable to inspect screens unless the user manually enables its service.")
    } else {
      AgentToolResult("Android Accessibility settings could not be opened.", false)
    }
  }
}

fun interface AccessibilitySettingsRequester { suspend fun openSettings(): Boolean }

class ChannelAccessibilitySettingsRequester(
  private val actionChannel: SendChannel<ToolAction>,
  private val timeoutMs: Long = 120_000L,
) : AccessibilitySettingsRequester {
  override suspend fun openSettings(): Boolean {
    val action = OpenAccessibilitySettingsToolAction()
    return try {
      withTimeoutOrNull(timeoutMs) {
        actionChannel.send(action)
        action.result.await()
      } ?: false
    } finally {
      if (!action.result.isCompleted) action.result.complete(false)
    }
  }
}
