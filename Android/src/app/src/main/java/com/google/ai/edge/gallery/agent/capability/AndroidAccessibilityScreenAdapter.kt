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
