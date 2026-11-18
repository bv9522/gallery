/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.capability

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class SemanticScreenElement(
  val handle: String,
  val role: String,
  val label: String?,
  val clickable: Boolean,
  val longClickable: Boolean,
  val scrollable: Boolean,
  val enabled: Boolean,
  val selected: Boolean,
  val checked: Boolean?,
  val textInputCapable: Boolean = false,
  val textLength: Int? = null,
)

data class SemanticScreenSnapshot(
  val elements: List<SemanticScreenElement>,
  val truncated: Boolean,
)

internal data class AccessibilityElementFingerprint(
  val packageName: String,
  val windowId: Int,
  val className: String,
  val label: String?,
  val bounds: android.graphics.Rect,
  val clickable: Boolean,
  val longClickable: Boolean,
  val scrollable: Boolean,
  val enabled: Boolean,
  val actionable: Boolean,
  val textInputCapable: Boolean = false,
  val inputType: Int? = null,
  val textLength: Int? = null,
  val expiresAtMs: Long,
)

internal data class AccessibilityActionTarget(
  val role: String,
  val label: String?,
  val clickable: Boolean,
  val longClickable: Boolean,
  val scrollable: Boolean,
) {
  fun supports(action: AccessibilitySemanticAction): Boolean =
    AccessibilityScreenInspectionPolicy.supportsAction(
      action = action,
      enabled = true,
      clickable = clickable,
      longClickable = longClickable,
      scrollable = scrollable,
    )
}

internal enum class AccessibilityTextAction {
  ENTER_TEXT,
  SELECT_TEXT,
}

internal data class AccessibilityTextInputTarget(
  val role: String,
  val label: String?,
  val enabled: Boolean,
  val textLength: Int,
) 

internal enum class AccessibilitySemanticAction(val frameworkAction: Int) {
  CLICK(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK),
  LONG_CLICK(android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK),
  SCROLL_FORWARD(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD),
  SCROLL_BACKWARD(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD),
}

class AndroidAccessibilityScreenAdapter(context: Context) {
  private val appContext = context.applicationContext
  private val component = ComponentName(appContext, GalleryAccessibilityService::class.java)
  private val handles = java.util.concurrent.ConcurrentHashMap<String, AccessibilityElementFingerprint>()

  fun enabledServiceAvailability(): ProviderAvailability {
    pruneExpiredHandles()
    val manager = appContext.getSystemService(AccessibilityManager::class.java)
      ?: return ProviderAvailability.UNSUPPORTED
    val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
      .any { info ->
        val service = info.resolveInfo?.serviceInfo
        service?.packageName == component.packageName && service.name == component.className
      }
    if (!enabled) {
      handles.clear()
      return ProviderAvailability.PERMISSION_REQUIRED
    }
    return if (AccessibilityServiceInstance.current != null) ProviderAvailability.AVAILABLE
    else {
      handles.clear()
      ProviderAvailability.SERVICE_STOPPED
    }
  }

  suspend fun inspect(): Result<SemanticScreenSnapshot> = withContext(Dispatchers.Main.immediate) {
    handles.clear()
    when (enabledServiceAvailability()) {
      ProviderAvailability.AVAILABLE -> Unit
      ProviderAvailability.PERMISSION_REQUIRED ->
        return@withContext Result.failure(SecurityException("Gallery accessibility access is not enabled. No screen content was read."))
      ProviderAvailability.SERVICE_STOPPED ->
        return@withContext Result.failure(IllegalStateException("Gallery accessibility is enabled but its service is not connected yet. No screen content was read."))
      else ->
        return@withContext Result.failure(IllegalStateException("Android accessibility inspection is unsupported on this device."))
    }
    when (val snapshot = AccessibilityServiceInstance.current?.inspectActiveWindow()) {
      is AccessibilityWindowSnapshot.Ready -> {
        val now = android.os.SystemClock.elapsedRealtime()
        val elements = snapshot.elements.map { item ->
          val handle = "ui_" + UUID.randomUUID().toString().replace("-", "").take(16)
          handles[handle] = AccessibilityElementFingerprint(
            packageName = item.packageName,
            windowId = item.windowId,
            className = item.className,
            label = item.label,
            bounds = android.graphics.Rect(item.bounds),
            clickable = item.clickable,
            longClickable = item.longClickable,
            scrollable = item.scrollable,
            enabled = item.enabled,
            actionable = false,
            expiresAtMs = now + HANDLE_TTL_MS,
          )
          SemanticScreenElement(
            handle = handle,
            role = AccessibilityScreenInspectionPolicy.role(item.className),
            label = item.label,
            clickable = item.clickable,
            longClickable = item.longClickable,
            scrollable = item.scrollable,
            enabled = item.enabled,
            selected = item.selected,
            checked = item.checked,
          )
        }
        Result.success(SemanticScreenSnapshot(elements, snapshot.truncated))
      }
      is AccessibilityWindowSnapshot.Excluded ->
        Result.failure(IllegalStateException("Screen inspection blocked: ${snapshot.reason}"))
      is AccessibilityWindowSnapshot.Unavailable ->
        Result.failure(IllegalStateException(snapshot.reason))
      null ->
        Result.failure(IllegalStateException("Gallery accessibility service disconnected before inspection."))
    }
  }

  /** Inspects exactly one visible external application window and issues action-capable handles. */
  suspend fun inspectExternal(): Result<SemanticScreenSnapshot> = withContext(Dispatchers.Main.immediate) {
    handles.clear()
    if (enabledServiceAvailability() != ProviderAvailability.AVAILABLE) {
      return@withContext Result.failure(
        IllegalStateException("Gallery accessibility service is not enabled and connected.")
      )
    }
    when (val snapshot = AccessibilityServiceInstance.current?.inspectExternalVisibleWindow()) {
      is AccessibilityWindowSnapshot.Ready -> {
        if (snapshot.truncated || snapshot.elements.isEmpty()) {
          return@withContext Result.failure(
            IllegalStateException("The external app hierarchy is empty or incomplete; no action handles were issued.")
          )
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val elements = snapshot.elements.map { item ->
          val handle = "ui_" + UUID.randomUUID().toString().replace("-", "").take(16)
          handles[handle] = AccessibilityElementFingerprint(
            packageName = item.packageName,
            windowId = item.windowId,
            className = item.className,
            label = item.label,
            bounds = android.graphics.Rect(item.bounds),
            clickable = item.clickable,
            longClickable = item.longClickable,
            scrollable = item.scrollable,
            enabled = item.enabled,
            actionable = true,
            expiresAtMs = now + HANDLE_TTL_MS,
          )
          SemanticScreenElement(
            handle = handle,
            role = AccessibilityScreenInspectionPolicy.role(item.className),
            label = item.label,
            clickable = item.clickable,
            longClickable = item.longClickable,
            scrollable = item.scrollable,
            enabled = item.enabled,
            selected = item.selected,
            checked = item.checked,
          )
        }
        Result.success(SemanticScreenSnapshot(elements, snapshot.truncated))
      }
      is AccessibilityWindowSnapshot.Excluded ->
        Result.failure(IllegalStateException("External screen inspection blocked: " + snapshot.reason))
      is AccessibilityWindowSnapshot.Unavailable ->
        Result.failure(IllegalStateException(snapshot.reason))
      null -> Result.failure(IllegalStateException("Gallery accessibility service disconnected before inspection."))
    }
  }

  /** Inspects one external app using a dedicated, stricter plain-text-field policy. */
  suspend fun inspectExternalInputs(): Result<SemanticScreenSnapshot> = withContext(Dispatchers.Main.immediate) {
    handles.clear()
    if (enabledServiceAvailability() != ProviderAvailability.AVAILABLE) {
      return@withContext Result.failure(
        IllegalStateException("Gallery accessibility service is not enabled and connected.")
      )
    }
    when (val snapshot = AccessibilityServiceInstance.current?.inspectExternalVisibleWindow(allowSafeTextInputs = true)) {
      is AccessibilityWindowSnapshot.Ready -> {
        if (snapshot.truncated || snapshot.elements.isEmpty()) {
          return@withContext Result.failure(
            IllegalStateException("The external app hierarchy is empty or incomplete; no text-input handles were issued.")
          )
        }
        val fields = snapshot.elements.filter { it.editable && it.inputType != null && it.textLength != null }
        if (fields.isEmpty()) {
          return@withContext Result.failure(
            IllegalStateException("No safe ordinary single-line text fields are available in the visible external app.")
          )
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val elements = fields.map { item ->
          val handle = "ui_" + UUID.randomUUID().toString().replace("-", "").take(16)
          handles[handle] = AccessibilityElementFingerprint(
            packageName = item.packageName,
            windowId = item.windowId,
            className = item.className,
            label = item.label,
            bounds = android.graphics.Rect(item.bounds),
            clickable = item.clickable,
            longClickable = item.longClickable,
            scrollable = item.scrollable,
            enabled = item.enabled,
            actionable = false,
            textInputCapable = true,
            inputType = item.inputType,
            textLength = item.textLength,
            expiresAtMs = now + HANDLE_TTL_MS,
          )
          SemanticScreenElement(
            handle = handle,
            role = "text_field",
            label = item.label ?: "unlabeled text field",
            clickable = item.clickable,
            longClickable = item.longClickable,
            scrollable = item.scrollable,
            enabled = item.enabled,
            selected = item.selected,
            checked = item.checked,
            textInputCapable = true,
            textLength = item.textLength,
          )
        }
        Result.success(SemanticScreenSnapshot(elements, snapshot.truncated))
      }
      is AccessibilityWindowSnapshot.Excluded ->
        Result.failure(IllegalStateException("External text input blocked: " + snapshot.reason))
      is AccessibilityWindowSnapshot.Unavailable ->
        Result.failure(IllegalStateException(snapshot.reason))
      null ->
        Result.failure(IllegalStateException("Gallery accessibility service disconnected before input inspection."))
    }
  }

  internal fun describeTextInputTarget(handle: String): AccessibilityTextInputTarget? {
    if (!AccessibilityScreenInspectionPolicy.isValidHandle(handle)) return null
    val fingerprint = handles[handle] ?: return null
    if (!fingerprint.textInputCapable || fingerprint.expiresAtMs <= android.os.SystemClock.elapsedRealtime()) {
      handles.remove(handle)
      return null
    }
    return AccessibilityTextInputTarget(
      role = "text_field",
      label = fingerprint.label,
      enabled = fingerprint.enabled,
      textLength = fingerprint.textLength ?: return null,
    )
  }

  /** Consumes an input handle before revalidating and dispatching exactly one text operation. */
  internal suspend fun performSemanticTextAction(
    handle: String,
    action: AccessibilityTextAction,
    inputText: String? = null,
    selectionStart: Int? = null,
    selectionEnd: Int? = null,
  ): Result<String> = withContext(Dispatchers.Main.immediate) {
    if (!AccessibilityScreenInspectionPolicy.isValidHandle(handle)) {
      return@withContext Result.failure(IllegalArgumentException("element_handle is malformed."))
    }
    val fingerprint = handles[handle]
      ?: return@withContext Result.failure(IllegalStateException("Text field handle is unknown or expired; inspect inputs again."))
    if (!fingerprint.textInputCapable || fingerprint.expiresAtMs <= android.os.SystemClock.elapsedRealtime()) {
      handles.remove(handle)
      return@withContext Result.failure(IllegalStateException("Handle is expired or is not a text-input target; inspect inputs again."))
    }
    handles.remove(handle)
    if (enabledServiceAvailability() != ProviderAvailability.AVAILABLE) {
      return@withContext Result.failure(IllegalStateException("Gallery accessibility service is no longer enabled and connected."))
    }
    val service = AccessibilityServiceInstance.current
      ?: return@withContext Result.failure(IllegalStateException("Gallery accessibility service disconnected."))
    service.performSemanticTextAction(fingerprint, action, inputText, selectionStart, selectionEnd)
  }

  internal fun describeActionTarget(handle: String): AccessibilityActionTarget? {
    if (!AccessibilityScreenInspectionPolicy.isValidHandle(handle)) return null
    val fingerprint = handles[handle] ?: return null
    if (!fingerprint.actionable || fingerprint.expiresAtMs <= android.os.SystemClock.elapsedRealtime()) {
      handles.remove(handle)
      return null
    }
    return AccessibilityActionTarget(
      role = AccessibilityScreenInspectionPolicy.role(fingerprint.className),
      label = fingerprint.label,
      clickable = fingerprint.clickable && fingerprint.enabled,
      longClickable = fingerprint.longClickable && fingerprint.enabled,
      scrollable = fingerprint.scrollable && fingerprint.enabled,
    )
  }

  /** Consumes a one-shot handle and revalidates the exact external node immediately before action. */
  internal suspend fun performSemanticAction(
    handle: String,
    action: AccessibilitySemanticAction,
  ): Result<String> = withContext(Dispatchers.Main.immediate) {
    if (!AccessibilityScreenInspectionPolicy.isValidHandle(handle)) {
      return@withContext Result.failure(IllegalArgumentException("element_handle is malformed."))
    }
    val fingerprint = handles[handle]
      ?: return@withContext Result.failure(IllegalStateException("Element handle is unknown or expired; inspect the target app again."))
    if (!fingerprint.actionable || fingerprint.expiresAtMs <= android.os.SystemClock.elapsedRealtime()) {
      handles.remove(handle)
      return@withContext Result.failure(IllegalStateException("Element handle is expired or is not an external action target; inspect the target app again."))
    }
    // Consume before dispatch so retries can never replay the same approved action.
    handles.remove(handle)
    if (enabledServiceAvailability() != ProviderAvailability.AVAILABLE) {
      return@withContext Result.failure(IllegalStateException("Gallery accessibility service is no longer enabled and connected."))
    }
    val service = AccessibilityServiceInstance.current
      ?: return@withContext Result.failure(IllegalStateException("Gallery accessibility service disconnected."))
    service.performSemanticAction(fingerprint, action)
  }

  /**
   * Captures one external visible app window only after its accessibility hierarchy passes the
   * existing sensitive-screen policy. The same package/window must still be uniquely visible after
   * capture; otherwise the image is erased and rejected.
   */
  suspend fun captureExternalWindow(): Result<ByteArray> {
    var producedBytes: ByteArray? = null
    var handedOff = false
    try {
      val result = withContext(Dispatchers.Main.immediate) {
        handles.clear()
        if (!AccessibilityScreenInspectionPolicy.supportsWindowScreenshot(android.os.Build.VERSION.SDK_INT)) {
          return@withContext Result.failure(
            UnsupportedOperationException(
              "Window-specific screenshots require Android API 34 or newer. Full-display capture is not used."
            )
          )
        }
        if (enabledServiceAvailability() != ProviderAvailability.AVAILABLE) {
          return@withContext Result.failure(
            IllegalStateException("Gallery accessibility service is not enabled and connected.")
          )
        }
        val service = AccessibilityServiceInstance.current
          ?: return@withContext Result.failure(
            IllegalStateException("Gallery accessibility service disconnected before capture.")
          )
        val before = service.inspectExternalVisibleWindow()
        val target = before as? AccessibilityWindowSnapshot.Ready
          ?: return@withContext Result.failure(
            IllegalStateException(
              when (before) {
                is AccessibilityWindowSnapshot.Excluded -> "Screen capture blocked: ${before.reason}"
                is AccessibilityWindowSnapshot.Unavailable -> before.reason
                else -> "The external screen could not be safely classified."
              }
            )
          )
        if (target.elements.isEmpty() || target.truncated) {
          return@withContext Result.failure(
            IllegalStateException(
              "The visible app window has no complete, safely classifiable accessibility hierarchy; capture was blocked."
            )
          )
        }
        if (target.elements.any { it.windowId != target.windowId }) {
          return@withContext Result.failure(
            IllegalStateException("The visible app window identity is inconsistent; capture was blocked.")
          )
        }
        val windowId = target.windowId
        val imageBytes = service.captureWindowJpeg(windowId).getOrElse { error ->
          return@withContext Result.failure(error)
        }
        var keepImage = false
        try {
          currentCoroutineContext().ensureActive()
          val after = service.inspectExternalVisibleWindow()
          val stillSameWindow = after is AccessibilityWindowSnapshot.Ready &&
            after.packageName == target.packageName &&
            !after.truncated &&
            after.elements.isNotEmpty() &&
            after.windowId == windowId &&
            after.elements.all { it.windowId == windowId }
          if (
            AccessibilityServiceInstance.current !== service ||
            enabledServiceAvailability() != ProviderAvailability.AVAILABLE ||
            !stillSameWindow ||
            imageBytes.isEmpty() ||
            imageBytes.size > GalleryAccessibilityService.MAX_SCREENSHOT_BYTES
          ) {
            return@withContext Result.failure(
              IllegalStateException(
                "The target window changed or could not be revalidated after capture; the screenshot was discarded."
              )
            )
          }
          currentCoroutineContext().ensureActive()
          producedBytes = imageBytes
          keepImage = true
          Result.success(imageBytes)
        } finally {
          if (!keepImage) imageBytes.fill(0)
        }
      }
      handedOff = result.isSuccess
      return result
    } finally {
      // withContext may discard a successful result if cancellation wins while dispatching back.
      if (!handedOff) producedBytes?.fill(0)
    }
  }

  fun isHandleFresh(handle: String): Boolean {
    if (!AccessibilityScreenInspectionPolicy.isValidHandle(handle)) return false
    val fingerprint = handles[handle] ?: return false
    if (fingerprint.expiresAtMs <= android.os.SystemClock.elapsedRealtime()) {
      handles.remove(handle)
      return false
    }
    return true
  }

  private fun pruneExpiredHandles() {
    val now = android.os.SystemClock.elapsedRealtime()
    handles.entries.removeIf { it.value.expiresAtMs <= now }
  }

  companion object {
    const val HANDLE_TTL_MS = 30_000L
  }
}
