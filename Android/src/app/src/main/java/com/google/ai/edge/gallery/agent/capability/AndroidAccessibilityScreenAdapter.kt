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
import kotlinx.coroutines.withContext

data class SemanticScreenElement(
  val handle: String,
  val role: String,
  val label: String?,
  val clickable: Boolean,
  val scrollable: Boolean,
  val enabled: Boolean,
  val selected: Boolean,
  val checked: Boolean?,
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
  val expiresAtMs: Long,
)

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
            expiresAtMs = now + HANDLE_TTL_MS,
          )
          SemanticScreenElement(
            handle = handle,
            role = AccessibilityScreenInspectionPolicy.role(item.className),
            label = item.label,
            clickable = item.clickable,
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

  /**
   * Captures one external visible app window only after its accessibility hierarchy passes the
   * existing sensitive-screen policy. The same package/window must still be uniquely visible after
   * capture; otherwise the image is erased and rejected.
   */
  suspend fun captureExternalWindow(): Result<ByteArray> = withContext(Dispatchers.Main.immediate) {
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
      imageBytes.fill(0)
      return@withContext Result.failure(
        IllegalStateException(
          "The target window changed or could not be revalidated after capture; the screenshot was discarded."
        )
      )
    }
    Result.success(imageBytes)
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
