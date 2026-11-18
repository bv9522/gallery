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

/** Explicit, bounded screen inspection and separately approved semantic UI actions. */
class AccessibilityScreenInspectionTool internal constructor(
  private val adapter: AndroidAccessibilityScreenAdapter,
  private val authorizer: NativeCapabilityAuthorizer,
  private val settingsRequester: AccessibilitySettingsRequester,
  private val imageInputSupported: Boolean = true,
) : AgentTool {
  constructor(
    context: Context,
    actionChannel: SendChannel<ToolAction>,
    imageInputSupported: Boolean = true,
  ) : this(
    AndroidAccessibilityScreenAdapter(context.applicationContext),
    ChannelNativeCapabilityAuthorizer(actionChannel),
    ChannelAccessibilitySettingsRequester(actionChannel),
    imageInputSupported,
  )

  override val name = "screen_inspect"
  private val windowCaptureSupported: Boolean
    get() = imageInputSupported &&
      AccessibilityScreenInspectionPolicy.supportsWindowScreenshot(android.os.Build.VERSION.SDK_INT)

  override val description: String
    get() = buildString {
      append("Use operation=status to check Gallery's manually enabled AccessibilityService. Use inspect for a bounded semantic summary of the active window. Use inspect_external when Gallery and exactly one target app are visible together (normally split-screen); it returns safe semantic elements with short-lived handles. Use act only after inspect_external and only for one CLICK, LONG_CLICK, SCROLL_FORWARD, or SCROLL_BACKWARD action on a fresh handle. Every action requires one-time UI_GESTURE approval and revalidates the exact app window, node identity, label, bounds, enabled state, and advertised action immediately before dispatch. Stale, ambiguous, sensitive, incomplete, or changed screens fail closed. The result confirms Android accepted dispatch only, not that the app state changed. No coordinate taps or gesture fallback is used. ")
      if (windowCaptureSupported) {
        append("Use capture only when the user explicitly asks for visual understanding; it requires API 34+, one-time SCREEN_CAPTURE approval, exactly one safe external app window, and sends a bounded in-memory window screenshot to the local multimodal model. ")
      } else {
        append("Visual capture is unavailable for this model/runtime or Android version; no full-display fallback is used. ")
      }
      append("Editable/password fields, sign-in/credential, payment/banking, identity, health, system-settings, detected personal identifiers, empty/incomplete trees, and ambiguous windows are excluded. Sensitive-screen detection is conservative and not guaranteed to identify every visual risk. The user must manually enable Gallery in Android Accessibility settings. No typing, background monitoring, or persistent screenshot files are implemented. Use open_settings only when the user explicitly asks to enable access.")
    }

  override val parametersSchema: JsonObject
    get() = JsonObject().apply {
      addProperty("type", "object")
      add("properties", JsonObject().apply {
        add("operation", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply {
            add("status")
            add("inspect")
            add("inspect_external")
            add("act")
            if (windowCaptureSupported) add("capture")
            add("open_settings")
          })
        })
        add("element_handle", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Fresh opaque element_handle returned by the most recent inspect_external operation.")
          addProperty("maxLength", 19)
        })
        add("action", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply {
            AccessibilitySemanticAction.values().forEach { add(it.name) }
          })
          addProperty("description", "One semantic action: CLICK, LONG_CLICK, SCROLL_FORWARD, or SCROLL_BACKWARD.")
        })
      })
      add("required", JsonArray().apply { add("operation") })
      addProperty("additionalProperties", false)
    }

  override suspend fun execute(arguments: JsonObject): AgentToolResult {
    AccessibilityScreenInspectionArguments.validate(arguments)?.let {
      return AgentToolResult(it, false)
    }
    val operation = arguments.get("operation").asString
    return try {
      when (operation) {
        "status" -> status()
        "inspect" -> inspect()
        "inspect_external" -> inspectExternal()
        "act" -> act(arguments)
        "capture" -> capture()
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
      appendLine(
        "availability=" + when {
          !windowCaptureSupported -> ProviderAvailability.UNSUPPORTED
          availability == ProviderAvailability.AVAILABLE -> ProviderAvailability.AVAILABLE
          else -> availability
        }
      )
      appendLine(
        "detail=" + when {
          !imageInputSupported -> "The selected model or runtime configuration does not support image input."
          !AccessibilityScreenInspectionPolicy.supportsWindowScreenshot(android.os.Build.VERSION.SDK_INT) -> "Window-specific screenshot API requires Android API 34 or newer; no full-display fallback is used."
          availability == ProviderAvailability.AVAILABLE -> "Requires one-time approval and exactly one safe, visible external app window alongside Gallery."
          else -> "Gallery accessibility service must be enabled and connected."
        }
      )
      appendLine("capability=UI_GESTURE")
      appendLine("availability=$availability")
      appendLine("detail=Requires inspect_external, a fresh element handle, one-time approval for each semantic action, and immediate target revalidation. Supports click, long-click, and semantic scroll only; no coordinate gesture fallback.")
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
        appendLine("long_clickable=${element.longClickable}")
        appendLine("scrollable=${element.scrollable}")
        appendLine("enabled=${element.enabled}")
        if (element.selected) appendLine("selected=true")
        element.checked?.let { appendLine("checked=$it") }
      }
      appendLine("note=Handles expire after 30 seconds. This active-window inspection is read-only; use inspect_external to obtain one-shot handles for separately approved semantic actions.")
    }.trimEnd())
  }

  private suspend fun inspectExternal(): AgentToolResult {
    if (adapter.enabledServiceAvailability() != ProviderAvailability.AVAILABLE) {
      return AgentToolResult("Gallery accessibility service is not enabled and connected. No external screen content was read.", false)
    }
    if (!authorizer.authorize(
        AndroidCapability.UI_TREE_READ,
        "Inspect exactly one visible external app window alongside Gallery (normally split-screen): at most 120 nodes, depth 12, and 32 semantic elements. Sensitive, incomplete, or ambiguous windows are excluded. This issues short-lived handles for separately approved semantic actions."
      )) {
      return AgentToolResult("External semantic screen inspection was not approved.", false)
    }
    val snapshot = adapter.inspectExternal().getOrElse { error ->
      return AgentToolResult(error.message ?: "External screen inspection was blocked.", false)
    }
    if (snapshot.elements.isEmpty()) {
      return AgentToolResult("The external app window contains no safe semantic elements to report.", false)
    }
    return AgentToolResult(buildString {
      appendLine("elements=" + snapshot.elements.size)
      appendLine("limit=32")
      appendLine("truncated=" + snapshot.truncated)
      snapshot.elements.forEachIndexed { index, element ->
        appendLine("element=" + (index + 1))
        appendLine("element_handle=" + element.handle)
        appendLine("role=" + element.role)
        element.label?.let { appendLine("label=" + it) }
        appendLine("clickable=" + element.clickable)
        appendLine("long_clickable=" + element.longClickable)
        appendLine("scrollable=" + element.scrollable)
        appendLine("enabled=" + element.enabled)
        if (element.selected) appendLine("selected=true")
        element.checked?.let { appendLine("checked=" + it) }
      }
      appendLine("note=Handles expire after 30 seconds and can be used once for a separately approved semantic action. No coordinates or raw node IDs are exposed.")
    }.trimEnd())
  }

  private suspend fun act(arguments: JsonObject): AgentToolResult {
    val handle = arguments.get("element_handle").asString
    val action = AccessibilitySemanticAction.valueOf(arguments.get("action").asString)
    val target = adapter.describeActionTarget(handle)
      ?: return AgentToolResult("Element handle is unknown, expired, or not an external action target. Run inspect_external again.", false)
    val supported = when (action) {
      AccessibilitySemanticAction.CLICK -> target.clickable
      AccessibilitySemanticAction.LONG_CLICK -> target.longClickable
      AccessibilitySemanticAction.SCROLL_FORWARD,
      AccessibilitySemanticAction.SCROLL_BACKWARD -> target.scrollable
    }
    if (!supported) {
      return AgentToolResult("The selected element did not advertise the requested action during inspection. No action was dispatched.", false)
    }
    val targetLabel = target.label?.let { "'" + it + "'" } ?: ("an unlabeled " + target.role)
    val summary = "Perform exactly one " + action.name + " action on " + targetLabel +
      " in the single visible external app window. The element handle is one-shot; Gallery will revalidate app/window identity, hierarchy safety, node class, label, bounds, enabled state, and action support immediately before dispatch. No coordinates or fallback gestures are used."
    if (!authorizer.authorize(AndroidCapability.UI_GESTURE, summary)) {
      return AgentToolResult("The semantic UI action was not approved. No action was dispatched.", false)
    }
    val result = adapter.performSemanticAction(handle, action)
    return AgentToolResult(
      result.getOrElse { error -> error.message ?: "The semantic UI action was blocked." },
      result.isSuccess,
    )
  }

  private suspend fun capture(): AgentToolResult {
    if (!windowCaptureSupported) {
      val reason = if (!imageInputSupported) {
        "The selected model or runtime configuration does not support image input."
      } else {
        "Window-specific screenshot capture requires Android API 34 or newer; full-display capture is not used."
      }
      return AgentToolResult(reason + " No screenshot was captured.", false)
    }
    if (adapter.enabledServiceAvailability() != ProviderAvailability.AVAILABLE) {
      return AgentToolResult(
        "Gallery accessibility service is not enabled and connected. No screen content was read or captured.",
        false,
      )
    }
    if (!authorizer.authorize(
        AndroidCapability.SCREEN_CAPTURE,
        "Capture one screenshot of the single visible non-Gallery app window (split-screen required): maximum 1280 px and 2 MiB JPEG. Gallery will check the accessibility hierarchy for sensitive or uncertain content first. The image is sent only to the selected local Gemma model in memory with raw coordinator bytes erased after inference; the model conversation may retain visual context in memory until reset. No image file or background capture is created. Confirm that the target screen is appropriate and contains no sensitive/private information."
      )) {
      return AgentToolResult("Visual screen capture was not approved.", false)
    }
    val image = adapter.captureExternalWindow().getOrElse { error ->
      return AgentToolResult(error.message ?: "Visual screen capture was blocked.", false)
    }
    return AgentToolResult(
      content = "A single bounded screenshot of the approved visible app window is attached to this tool result for visual understanding. The image is in-memory only; no screenshot file was saved.",
      imageBytes = image,
    )
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


internal object AccessibilityScreenInspectionArguments {
  fun validate(arguments: JsonObject): String? {
    val operation = arguments.get("operation")
      ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
      ?: return "operation must be status, inspect, inspect_external, act, capture, or open_settings."
    val accepted = when (operation) {
      "status", "inspect", "inspect_external", "capture", "open_settings" -> setOf("operation")
      "act" -> setOf("operation", "element_handle", "action")
      else -> return "Unsupported operation."
    }
    val unexpected = arguments.keySet() - accepted
    if (unexpected.isNotEmpty()) return "Unexpected argument(s): " + unexpected.sorted().joinToString(", ") + "."
    if (operation == "act") {
      val handle = arguments.get("element_handle")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: return "act requires element_handle from the latest inspect_external result."
      if (!AccessibilityScreenInspectionPolicy.isValidHandle(handle)) {
        return "element_handle is malformed; run inspect_external again."
      }
      val action = arguments.get("action")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: return "act requires action CLICK, LONG_CLICK, SCROLL_FORWARD, or SCROLL_BACKWARD."
      if (action !in AccessibilitySemanticAction.values().map { it.name }) {
        return "Unsupported action. Choose CLICK, LONG_CLICK, SCROLL_FORWARD, or SCROLL_BACKWARD."
      }
    }
    return null
  }
}
