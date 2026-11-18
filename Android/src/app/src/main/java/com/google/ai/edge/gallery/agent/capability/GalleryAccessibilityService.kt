/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.capability

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Opt-in, read-only endpoint. Accessibility events are ignored; reads occur only on approved requests. */
class GalleryAccessibilityService : AccessibilityService() {
  override fun onServiceConnected() {
    super.onServiceConnected()
    AccessibilityServiceInstance.current = this
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // No event data is read or retained. Inspection/capture is explicit and request-scoped.
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
    return inspectRoot(root)
  }

  /**
   * Selects exactly one visible, non-Gallery application window. This supports a user-arranged
   * split-screen target while Gallery chat remains available. Ambiguous or unreadable windows fail closed.
   */
  internal fun inspectExternalVisibleWindow(): AccessibilityWindowSnapshot {
    val windowInfos = windows
    if (windowInfos.isEmpty()) {
      return AccessibilityWindowSnapshot.Unavailable("Android did not expose any interactive application windows.")
    }
    val candidateRoots = ArrayList<Pair<AccessibilityNodeInfo, Int>>()
    var selectedRoot: AccessibilityNodeInfo? = null
    try {
      var incomplete = false
      for (window in windowInfos) {
        if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
        val bounds = Rect()
        window.getBoundsInScreen(bounds)
        if (bounds.isEmpty) continue
        val root = window.root
        if (root == null) {
          incomplete = true
          continue
        }
        val ownerPackage = root.packageName?.toString().orEmpty()
        if (ownerPackage.isBlank()) {
          root.recycle()
          incomplete = true
          continue
        }
        if (ownerPackage == packageName) {
          root.recycle()
        } else {
          candidateRoots += root to window.id
        }
      }
      if (incomplete) {
        return AccessibilityWindowSnapshot.Excluded(
          "At least one visible application window could not be classified; visual capture was blocked."
        )
      }
      if (candidateRoots.size != 1) {
        return if (candidateRoots.isEmpty()) {
          AccessibilityWindowSnapshot.Unavailable(
            "No external application window is visible. Arrange Gallery and one target app in split-screen, then try again."
          )
        } else {
          AccessibilityWindowSnapshot.Excluded(
            "More than one external application window is visible; the capture target is ambiguous."
          )
        }
      }
      val selected = candidateRoots.removeAt(0)
      selectedRoot = selected.first
      return inspectRoot(selected.first, selected.second).also { selectedRoot = null }
    } finally {
      selectedRoot?.recycle()
      candidateRoots.forEach { runCatching { it.first.recycle() } }
      windowInfos.forEach { runCatching { it.recycle() } }
    }
  }

  /** Captures only the supplied AccessibilityWindowInfo ID; there is deliberately no display fallback. */
  internal suspend fun captureWindowJpeg(windowId: Int): Result<ByteArray> {
    if (!AccessibilityScreenInspectionPolicy.supportsWindowScreenshot(Build.VERSION.SDK_INT)) {
      return Result.failure(UnsupportedOperationException(
        "Window-specific screenshots require Android API 34 or newer; full-display capture is not used."
      ))
    }
    return try {
      val bytes = withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
          try {
            takeScreenshotOfWindow(
              windowId,
              Executor { command ->
                Dispatchers.Default.dispatch(EmptyCoroutineContext, command)
              },
              object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                  val hardwareBuffer = screenshot.hardwareBuffer
                  try {
                    if (!continuation.isActive) return
                    val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                      hardwareBuffer,
                      screenshot.colorSpace,
                    ) ?: error("Android returned an unreadable screenshot buffer.")
                    val softwareBitmap = try {
                      require(
                        hardwareBitmap.width <= MAX_SOURCE_DIMENSION &&
                          hardwareBitmap.height <= MAX_SOURCE_DIMENSION &&
                          hardwareBitmap.width.toLong() * hardwareBitmap.height <= MAX_SOURCE_PIXELS
                      ) { "Screenshot source dimensions exceed the safe processing limit." }
                      hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        ?: error("Screenshot could not be copied into a safe software bitmap.")
                    } finally {
                      hardwareBitmap.recycle()
                    }
                    val encoded = try {
                      encodeBoundedJpeg(softwareBitmap)
                    } finally {
                      softwareBitmap.recycle()
                    }
                    if (continuation.isActive) continuation.resume(encoded)
                  } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                  } finally {
                    hardwareBuffer.close()
                  }
                }

                override fun onFailure(errorCode: Int) {
                  if (continuation.isActive) {
                    continuation.resumeWithException(
                      IllegalStateException("Android window screenshot failed (error $errorCode).")
                    )
                  }
                }
              },
            )
          } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(error)
          }
        }
      } ?: return Result.failure(IllegalStateException("Window screenshot timed out; no image was delivered."))
      Result.success(bytes)
    } catch (error: Exception) {
      Result.failure(error)
    }
  }

  private fun encodeBoundedJpeg(source: Bitmap): ByteArray {
    val largestSide = maxOf(source.width, source.height)
    require(largestSide > 0) { "Screenshot dimensions are invalid." }
    val scale = minOf(1f, MAX_SCREENSHOT_DIMENSION.toFloat() / largestSide.toFloat())
    val scaled = if (scale < 1f) {
      Bitmap.createScaledBitmap(
        source,
        (source.width * scale).toInt().coerceAtLeast(1),
        (source.height * scale).toInt().coerceAtLeast(1),
        true,
      )
    } else {
      source
    }
    try {
      var quality = INITIAL_JPEG_QUALITY
      while (quality >= MIN_JPEG_QUALITY) {
        val output = ByteArrayOutputStream()
        check(scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
          "Screenshot JPEG encoding failed."
        }
        val bytes = output.toByteArray()
        if (bytes.size <= MAX_SCREENSHOT_BYTES) return bytes
        quality -= JPEG_QUALITY_STEP
      }
      error("Screenshot exceeds the $MAX_SCREENSHOT_BYTES-byte image limit.")
    } finally {
      if (scaled !== source) scaled.recycle()
    }
  }

  private fun inspectRoot(
    root: AccessibilityNodeInfo,
    expectedWindowId: Int = root.windowId,
  ): AccessibilityWindowSnapshot {
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
              if (visited.size + queue.size >= MAX_NODES) {
                overflow = true
                break
              }
              val childResult = runCatching { node.getChild(index) }
              val child = childResult.getOrNull()
              if (child == null) {
                traversalFailed = true
                break
              }
              queue.add(child to depth + 1)
            }
          }
          if (traversalFailed || overflow) break
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
        return AccessibilityWindowSnapshot.Unavailable("The selected application window is not visible.")
      }
      val packageName = root.packageName?.toString().orEmpty()
      if (packageName.isBlank()) {
        return AccessibilityWindowSnapshot.Excluded("The selected window identity is unavailable; inspection was blocked.")
      }
      if (AccessibilityScreenInspectionPolicy.isSensitivePackage(packageName)) {
        return AccessibilityWindowSnapshot.Excluded("This app category is excluded from accessibility inspection.")
      }

      val elements = ArrayList<AccessibilityElementSnapshot>()
      var meaningfulCount = 0
      var textTruncated = false
      for ((node, _) in visited) {
        if (node.windowId != expectedWindowId) {
          return AccessibilityWindowSnapshot.Excluded(
            "The visible hierarchy crossed an application-window boundary; capture was blocked."
          )
        }
        if (!node.isVisibleToUser) continue
        val rawText = node.text?.toString().orEmpty()
        val rawDescription = node.contentDescription?.toString().orEmpty()
        val rawHint = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString().orEmpty() else ""
        textTruncated = textTruncated || rawText.length > AccessibilityScreenInspectionPolicy.MAX_LABEL_CHARS ||
          rawDescription.length > AccessibilityScreenInspectionPolicy.MAX_LABEL_CHARS ||
          rawHint.length > AccessibilityScreenInspectionPolicy.MAX_LABEL_CHARS
        if (rawText.length > MAX_SOURCE_TEXT_CHARS ||
          rawDescription.length > MAX_SOURCE_TEXT_CHARS || rawHint.length > MAX_SOURCE_TEXT_CHARS) {
          return AccessibilityWindowSnapshot.Excluded("A visible element exceeds the safe text-inspection limit; no screen content was returned.")
        }
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
      return AccessibilityWindowSnapshot.Ready(
        packageName,
        expectedWindowId,
        elements,
        meaningfulCount > MAX_ELEMENTS || textTruncated,
      )
    } finally {
      visited.forEach { (node, _) -> runCatching { node.recycle() } }
      while (queue.isNotEmpty()) runCatching { queue.removeFirst().first.recycle() }
    }
  }

  companion object {
    const val MAX_NODES = 120
    const val MAX_DEPTH = 12
    const val MAX_ELEMENTS = 32
    const val MAX_SOURCE_TEXT_CHARS = 512
    const val MAX_SOURCE_DIMENSION = 4_096
    const val MAX_SOURCE_PIXELS = 16L * 1_024 * 1_024
    const val MAX_SCREENSHOT_DIMENSION = 1_280
    const val MAX_SCREENSHOT_BYTES = 2 * 1_024 * 1_024
    const val SCREENSHOT_TIMEOUT_MS = 10_000L
    private const val INITIAL_JPEG_QUALITY = 85
    private const val MIN_JPEG_QUALITY = 65
    private const val JPEG_QUALITY_STEP = 5
  }
}

internal object AccessibilityServiceInstance {
  @Volatile var current: GalleryAccessibilityService? = null
}

internal sealed class AccessibilityWindowSnapshot {
  data class Ready(
    val packageName: String,
    val windowId: Int,
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
