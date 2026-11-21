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

/** Explicit, bounded screen inspection, semantic actions, and narrowly scoped text input. */
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

  override val description: String =
    "Inspect bounded semantic UI state. External inspection requires Gallery and exactly one target app visible together. Use fresh one-use handles; each UI action or text entry needs exact scoped approval and revalidation. Text entry is only for empty ordinary single-line fields. Never inspect or interact with credentials, payments, banking, identity, health, or other sensitive screens; no clipboard, coordinate gestures, or arbitrary keyboard events. Capture only an explicitly requested, approved external window when supported; no full-display fallback or background monitoring. The user manually enables AccessibilityService."

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
            add("inspect_inputs")
            add("enter_text")
            add("select_text")
            if (windowCaptureSupported) add("capture")
            add("open_settings")
          })
        })
        add("element_handle", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Fresh opaque element_handle returned by the most recent matching inspect_external or inspect_inputs operation.")
          addProperty("maxLength", 19)
        })
        add("action", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply {
            AccessibilitySemanticAction.values().forEach { add(it.name) }
          })
          addProperty("description", "One semantic action: CLICK, LONG_CLICK, SCROLL_FORWARD, or SCROLL_BACKWARD.")
        })
        add("input_text", JsonObject().apply {
          addProperty("type", "string")
          addProperty("maxLength", AccessibilityScreenInspectionPolicy.MAX_INPUT_TEXT_CHARS)
          addProperty("description", "Exact text to enter into a fresh, empty, safe ordinary single-line text field. No passwords or sensitive content.")
        })
        add("selection_start", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("minimum", 0)
          addProperty("maximum", AccessibilityScreenInspectionPolicy.MAX_INPUT_TEXT_CHARS)
        })
        add("selection_end", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("minimum", 1)
          addProperty("maximum", AccessibilityScreenInspectionPolicy.MAX_INPUT_TEXT_CHARS)
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
        "inspect_inputs" -> inspectInputs()
        "enter_text" -> enterText(arguments)
        "select_text" -> selectText(arguments)
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
      appendLine("availability=" + if (availability == ProviderAvailability.AVAILABLE) ProviderAvailability.AVAILABLE else availability)
      appendLine("detail=Requires inspect_inputs and a fresh one-shot handle. Only ordinary single-line plain-text EditText fields are supported. Entry is limited to empty fields (maximum 256 characters); selection uses explicit bounded character offsets. Every operation requires separate UI_TEXT_INPUT approval showing the exact text or range. No passwords, numeric/email/URI fields, multiline input, clipboard, or keyboard automation.")
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
    val supported = target.supports(action)
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

  private suspend fun inspectInputs(): AgentToolResult {
    if (adapter.enabledServiceAvailability() != ProviderAvailability.AVAILABLE) {
      return AgentToolResult("Gallery accessibility service is not enabled and connected. No external text fields were read.", false)
    }
    if (!authorizer.authorize(
        AndroidCapability.UI_TREE_READ,
        "Inspect exactly one visible external app window alongside Gallery for ordinary single-line plain-text fields only. Sensitive or unsupported editable fields block the entire inspection. Return safe labels and character counts only; never return current field contents. Issue 30-second one-shot handles. The user must verify the visible app context contains no private or sensitive information; text-based screening is heuristic and cannot guarantee detection."
      )) {
      return AgentToolResult("External text-field inspection was not approved.", false)
    }
    val snapshot = adapter.inspectExternalInputs().getOrElse { error ->
      return AgentToolResult(error.message ?: "External text-field inspection was blocked.", false)
    }
    return AgentToolResult(buildString {
      appendLine("safe_text_fields=" + snapshot.elements.size)
      appendLine("limit=32")
      snapshot.elements.forEachIndexed { index, element ->
        appendLine("field=" + (index + 1))
        appendLine("element_handle=" + element.handle)
        appendLine("role=text_field")
        element.label?.let { appendLine("label=" + it) }
        appendLine("text_length=" + element.textLength)
        appendLine("enabled=" + element.enabled)
      }
      appendLine("note=Current field contents are never returned. Handles expire after 30 seconds and are one-shot. Text entry is allowed only into empty fields; selection uses character offsets.")
    }.trimEnd())
  }

  private suspend fun enterText(arguments: JsonObject): AgentToolResult {
    val handle = arguments.get("element_handle").asString
    val value = arguments.get("input_text").asString
    val target = adapter.describeTextInputTarget(handle)
      ?: return AgentToolResult("Text-field handle is unknown, expired, or not an input target. Run inspect_inputs again.", false)
    if (!target.enabled || target.textLength != 0) {
      return AgentToolResult("Text entry is limited to a fresh, enabled, empty text field. Existing text is never overwritten.", false)
    }
    if (!AccessibilityScreenInspectionPolicy.isSafeInputText(value)) {
      return AgentToolResult("Text must contain 1–256 ordinary, non-sensitive, single-line characters.", false)
    }
    val targetLabel = target.label?.let { "'" + it + "'" } ?: "an unlabeled text field"
    val summary = "Enter exactly this text into " + targetLabel + ": " + value +
      ". The field must still be the same safe, enabled, empty, ordinary single-line text field immediately before dispatch. Existing text will not be overwritten. Review the surrounding app screen before approving; sensitive-screen screening is heuristic and cannot guarantee detection."
    if (!authorizer.authorize(AndroidCapability.UI_TEXT_INPUT, summary)) {
      return AgentToolResult("Text entry was not approved. No text was entered.", false)
    }
    val result = adapter.performSemanticTextAction(
      handle = handle,
      action = AccessibilityTextAction.ENTER_TEXT,
      inputText = value,
    )
    return AgentToolResult(
      result.getOrElse { error -> error.message ?: "Text entry was blocked." },
      result.isSuccess,
    )
  }

  private suspend fun selectText(arguments: JsonObject): AgentToolResult {
    val handle = arguments.get("element_handle").asString
    val start = arguments.get("selection_start").asInt
    val end = arguments.get("selection_end").asInt
    val target = adapter.describeTextInputTarget(handle)
      ?: return AgentToolResult("Text-field handle is unknown, expired, or not an input target. Run inspect_inputs again.", false)
    if (!target.enabled ||
      !AccessibilityScreenInspectionPolicy.isValidSelection(start, end, target.textLength)) {
      return AgentToolResult("The selection range is invalid for the current safe text field. Inspect inputs again.", false)
    }
    val targetLabel = target.label?.let { "'" + it + "'" } ?: "an unlabeled text field"
    val summary = "Select characters " + start + " through " + end +
      " (end-exclusive) in " + targetLabel +
      ". Gallery will revalidate the same safe field and current text length immediately before dispatch. No field contents are read back or changed. Review the surrounding app screen before approving; sensitive-screen screening is heuristic and cannot guarantee detection."
    if (!authorizer.authorize(AndroidCapability.UI_TEXT_INPUT, summary)) {
      return AgentToolResult("Text selection was not approved. No selection was changed.", false)
    }
    val result = adapter.performSemanticTextAction(
      handle = handle,
      action = AccessibilityTextAction.SELECT_TEXT,
      selectionStart = start,
      selectionEnd = end,
    )
    return AgentToolResult(
      result.getOrElse { error -> error.message ?: "Text selection was blocked." },
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
      ?: return "operation must be status, inspect, inspect_external, act, inspect_inputs, enter_text, select_text, capture, or open_settings."
    val accepted = when (operation) {
      "status", "inspect", "inspect_external", "inspect_inputs", "capture", "open_settings" -> setOf("operation")
      "act" -> setOf("operation", "element_handle", "action")
      "enter_text" -> setOf("operation", "element_handle", "input_text")
      "select_text" -> setOf("operation", "element_handle", "selection_start", "selection_end")
      else -> return "Unsupported operation."
    }
    val unexpected = arguments.keySet() - accepted
    if (unexpected.isNotEmpty()) return "Unexpected argument(s): " + unexpected.sorted().joinToString(", ") + "."
    if (operation in setOf("act", "enter_text", "select_text")) {
      val handle = arguments.get("element_handle")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: return "$operation requires element_handle from the latest matching inspection result."
      if (!AccessibilityScreenInspectionPolicy.isValidHandle(handle)) {
        return "element_handle is malformed; inspect the target again."
      }
    }
    if (operation == "act") {
      val action = arguments.get("action")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: return "act requires action CLICK, LONG_CLICK, SCROLL_FORWARD, or SCROLL_BACKWARD."
      if (action !in AccessibilitySemanticAction.values().map { it.name }) {
        return "Unsupported action. Choose CLICK, LONG_CLICK, SCROLL_FORWARD, or SCROLL_BACKWARD."
      }
    }
    if (operation == "enter_text") {
      val value = arguments.get("input_text")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: return "enter_text requires input_text as a string."
      if (!AccessibilityScreenInspectionPolicy.isSafeInputText(value)) {
        return "input_text must contain 1–256 ordinary, non-sensitive, single-line characters."
      }
    }
    if (operation == "select_text") {
      val startValue = arguments.get("selection_start")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toIntOrNull()
        ?: return "select_text requires integer selection_start."
      val endValue = arguments.get("selection_end")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toIntOrNull()
        ?: return "select_text requires integer selection_end."
      if (
        startValue < 0 ||
        endValue <= startValue ||
        endValue > AccessibilityScreenInspectionPolicy.MAX_INPUT_TEXT_CHARS
      ) {
        return "Selection offsets must be a valid non-empty range within 0–256."
      }
    }
    return null
  }
}
