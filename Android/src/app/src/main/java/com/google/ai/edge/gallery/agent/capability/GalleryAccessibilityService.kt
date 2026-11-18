/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.capability

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/** Opt-in, read-only endpoint. Accessibility events are ignored; inspection is explicit. */
class GalleryAccessibilityService : AccessibilityService() {
  override fun onServiceConnected() {
    super.onServiceConnected()
    AccessibilityServiceInstance.current = this
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // No event data is read or retained. Inspection happens only after an explicit tool call.
  }

  override fun onInterrupt() = Unit

  override fun onUnbind(intent: android.content.Intent?): Boolean {
    if (AccessibilityServiceInstance.current === this) AccessibilityServiceInstance.current = null
    return super.onUnbind(intent)
  }

  override fun onDestroy() {
    if (AccessibilityServiceInstance.current === this) AccessibilityServiceInstance.current = null
    super.onDestroy()
  }

  internal fun inspectActiveWindow(): AccessibilityWindowSnapshot {
    val root = rootInActiveWindow
      ?: return AccessibilityWindowSnapshot.Unavailable("No active application window is available.")
    val visited = ArrayList<Pair<AccessibilityNodeInfo, Int>>(MAX_NODES)
    val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
    queue.add(root to 0)
    var overflow = false
    var traversalFailed = false
    try {
      while (queue.isNotEmpty()) {
        if (visited.size >= MAX_NODES) {
          overflow = true
          break
        }
        val (node, depth) = queue.removeFirst()
        visited += node to depth
        if (node.isVisibleToUser) {
          if (depth >= MAX_DEPTH && node.childCount > 0) {
            overflow = true
            break
          }
          if (depth < MAX_DEPTH) {
            for (index in 0 until node.childCount) {
              val childResult = runCatching { node.getChild(index) }
              val child = childResult.getOrNull()
              if (child == null) {
                traversalFailed = true
                break
              }
              queue.add(child to depth + 1)
            }
          }
          if (traversalFailed) break
        }
      }
      if (traversalFailed) {
        return AccessibilityWindowSnapshot.Excluded("The visible screen hierarchy changed during inspection; no screen content was returned.")
      }
      if (overflow || queue.isNotEmpty()) {
        return AccessibilityWindowSnapshot.Excluded(
          "The visible screen hierarchy exceeds the safe inspection limit; no screen content was returned."
        )
      }
      if (!root.isVisibleToUser) {
        return AccessibilityWindowSnapshot.Unavailable("The active application window is not visible.")
      }
      val packageName = root.packageName?.toString().orEmpty()
      if (packageName.isBlank()) {
        return AccessibilityWindowSnapshot.Excluded("The active window identity is unavailable; inspection was blocked.")
      }
      if (AccessibilityScreenInspectionPolicy.isSensitivePackage(packageName)) {
        return AccessibilityWindowSnapshot.Excluded("This app category is excluded from accessibility inspection.")
      }

      val elements = ArrayList<AccessibilityElementSnapshot>()
      var meaningfulCount = 0
      for ((node, _) in visited) {
        if (!node.isVisibleToUser) continue
        val rawText = node.text?.toString().orEmpty()
        val rawDescription = node.contentDescription?.toString().orEmpty()
        val rawHint = if (android.os.Build.VERSION.SDK_INT >= 26) node.hintText?.toString().orEmpty() else ""
        if (node.isPassword || node.isEditable) {
          return AccessibilityWindowSnapshot.Excluded(
            "This screen contains an editable or password field and is excluded from inspection."
          )
        }
        if (AccessibilityScreenInspectionPolicy.containsSensitiveText(rawText) ||
          AccessibilityScreenInspectionPolicy.containsSensitiveText(rawDescription) ||
          AccessibilityScreenInspectionPolicy.containsSensitiveText(rawHint)) {
          return AccessibilityWindowSnapshot.Excluded(
            "This screen contains sign-in, financial, identity, health, or other sensitive content and is excluded."
          )
        }
        val label = AccessibilityScreenInspectionPolicy.sanitizeLabel(
          listOf(rawText, rawDescription, rawHint).firstOrNull { it.isNotBlank() }
        )
        val useful = !label.isNullOrBlank() || node.isClickable || node.isScrollable ||
          node.isCheckable || node.isFocusable
        if (!useful) continue
        meaningfulCount++
        if (elements.size < MAX_ELEMENTS) {
          val bounds = Rect()
          node.getBoundsInScreen(bounds)
          elements += AccessibilityElementSnapshot(
            packageName = packageName,
            windowId = node.windowId,
            className = node.className?.toString().orEmpty().take(120),
            label = label,
            bounds = Rect(bounds),
            clickable = node.isClickable,
            scrollable = node.isScrollable,
            enabled = node.isEnabled,
            selected = node.isSelected,
            checked = if (node.isCheckable) node.isChecked else null,
          )
        }
      }
      return AccessibilityWindowSnapshot.Ready(packageName, elements, meaningfulCount > MAX_ELEMENTS)
    } finally {
      visited.forEach { (node, _) -> runCatching { node.recycle() } }
      while (queue.isNotEmpty()) runCatching { queue.removeFirst().first.recycle() }
    }
  }

  companion object {
    const val MAX_NODES = 120
    const val MAX_DEPTH = 12
    const val MAX_ELEMENTS = 32
  }
}

internal object AccessibilityServiceInstance {
  @Volatile var current: GalleryAccessibilityService? = null
}

internal sealed class AccessibilityWindowSnapshot {
  data class Ready(
    val packageName: String,
    val elements: List<AccessibilityElementSnapshot>,
    val truncated: Boolean,
  ) : AccessibilityWindowSnapshot()
  data class Excluded(val reason: String) : AccessibilityWindowSnapshot()
  data class Unavailable(val reason: String) : AccessibilityWindowSnapshot()
}

internal data class AccessibilityElementSnapshot(
  val packageName: String,
  val windowId: Int,
  val className: String,
  val label: String?,
  val bounds: Rect,
  val clickable: Boolean,
  val scrollable: Boolean,
  val enabled: Boolean,
  val selected: Boolean,
  val checked: Boolean?,
)
