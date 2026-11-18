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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Opt-in endpoint. Accessibility events are ignored; reads and one-shot actions require explicit requests. */
class GalleryAccessibilityService : AccessibilityService() {
  override fun onServiceConnected() {
    super.onServiceConnected()
    AccessibilityServiceInstance.current = this
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // No event data is read or retained. Inspection, capture, and actions are explicit and request-scoped.
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
  internal fun inspectExternalVisibleWindow(
    allowSafeTextInputs: Boolean = false,
  ): AccessibilityWindowSnapshot {
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
      return inspectRoot(selected.first, selected.second, allowSafeTextInputs).also { selectedRoot = null }
    } finally {
      selectedRoot?.recycle()
      candidateRoots.forEach { runCatching { it.first.recycle() } }
      windowInfos.forEach { runCatching { it.recycle() } }
    }
  }

  /**
   * Performs one semantic action on a freshly revalidated node in the single external app window.
   * No coordinates or gesture fallback are used. The caller must obtain per-action approval first.
   */
  internal fun performSemanticAction(
    fingerprint: AccessibilityElementFingerprint,
    action: AccessibilitySemanticAction,
  ): Result<String> {
    if (AccessibilityServiceInstance.current !== this) {
      return Result.failure(IllegalStateException("Gallery accessibility service changed before action dispatch."))
    }
    if (fingerprint.bounds.isEmpty) {
      return Result.failure(IllegalStateException("The inspected element has no verifiable visible bounds; no action was dispatched."))
    }
    val current = inspectExternalVisibleWindow() as? AccessibilityWindowSnapshot.Ready
      ?: return Result.failure(IllegalStateException("The external app window is unavailable, ambiguous, or sensitive; no action was dispatched."))
    if (
      current.packageName != fingerprint.packageName ||
      current.windowId != fingerprint.windowId ||
      current.truncated
    ) {
      return Result.failure(IllegalStateException("The target app or screen changed after inspection; no action was dispatched."))
    }

    val windowInfos = windows
    val visited = ArrayList<Pair<AccessibilityNodeInfo, Int>>(MAX_NODES)
    val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
    try {
      val window = windowInfos.singleOrNull {
        it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.id == fingerprint.windowId
      } ?: return Result.failure(IllegalStateException("The inspected application window is no longer uniquely available."))
      val visibleBounds = Rect()
      window.getBoundsInScreen(visibleBounds)
      if (visibleBounds.isEmpty) {
        return Result.failure(IllegalStateException("The inspected application window is no longer visible; no action was dispatched."))
      }
      val initialRoot = window.root
        ?: return Result.failure(IllegalStateException("The inspected application window no longer exposes a root node."))
      if (initialRoot.packageName?.toString() != fingerprint.packageName) {
        initialRoot.recycle()
        return Result.failure(IllegalStateException("The target application identity changed; no action was dispatched."))
      }
      when (val safe = inspectRoot(initialRoot, fingerprint.windowId)) {
        is AccessibilityWindowSnapshot.Ready -> {
          if (safe.packageName != fingerprint.packageName || safe.windowId != fingerprint.windowId || safe.truncated) {
            return Result.failure(IllegalStateException("The target hierarchy is no longer complete and safe; no action was dispatched."))
          }
        }
        else -> return Result.failure(IllegalStateException("The target hierarchy is no longer safe; no action was dispatched."))
      }

      val root = window.root
        ?: return Result.failure(IllegalStateException("The target window changed during revalidation."))
      queue.add(root to 0)
      val matches = ArrayList<AccessibilityNodeInfo>(1)
      var incomplete = false
      while (queue.isNotEmpty()) {
        if (visited.size >= MAX_NODES) {
          incomplete = true
          break
        }
        val (node, depth) = queue.removeFirst()
        visited += node to depth
        if (node.windowId != fingerprint.windowId ||
          node.packageName?.toString() != fingerprint.packageName) {
          incomplete = true
          break
        }
        if (node.isVisibleToUser) {
          if (depth >= MAX_DEPTH && node.childCount > 0) {
            incomplete = true
            break
          }
          if (depth < MAX_DEPTH) {
            for (index in 0 until node.childCount) {
              if (visited.size + queue.size >= MAX_NODES) {
                incomplete = true
                break
              }
              val child = runCatching { node.getChild(index) }.getOrNull()
              if (child == null) {
                incomplete = true
                break
              }
              queue.add(child to depth + 1)
            }
          }
          if (incomplete) break

          val rawText = node.text?.toString().orEmpty()
          val rawDescription = node.contentDescription?.toString().orEmpty()
          val rawHint = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString().orEmpty() else ""
          val label = AccessibilityScreenInspectionPolicy.sanitizeLabel(
            listOf(rawText, rawDescription, rawHint).firstOrNull { it.isNotBlank() }
          )
          val bounds = Rect()
          node.getBoundsInScreen(bounds)
          if (
            node.className?.toString().orEmpty().take(120) == fingerprint.className &&
            label == fingerprint.label &&
            bounds == fingerprint.bounds
          ) {
            matches += node
          }
        }
      }
      if (incomplete || queue.isNotEmpty()) {
        return Result.failure(IllegalStateException("The target hierarchy changed or exceeded its inspection bounds; no action was dispatched."))
      }
      val target = matches.singleOrNull()
        ?: return Result.failure(IllegalStateException("The inspected element is stale or ambiguous; inspect the target app again."))
      if (
        !target.isVisibleToUser ||
        !target.isEnabled ||
        target.isClickable != fingerprint.clickable ||
        target.isLongClickable != fingerprint.longClickable ||
        target.isScrollable != fingerprint.scrollable
      ) {
        return Result.failure(IllegalStateException("The inspected element changed or is disabled; no action was dispatched."))
      }
      val supported = when (action) {
        AccessibilitySemanticAction.CLICK -> target.isClickable
        AccessibilitySemanticAction.LONG_CLICK -> target.isLongClickable
        AccessibilitySemanticAction.SCROLL_FORWARD,
        AccessibilitySemanticAction.SCROLL_BACKWARD -> target.isScrollable
      } && target.actionList.any { it.id == action.frameworkAction }
      if (!supported) {
        return Result.failure(IllegalStateException("The target does not currently advertise the requested semantic action."))
      }
      if (!target.performAction(action.frameworkAction)) {
        return Result.failure(IllegalStateException("Android rejected the semantic action; no success is claimed."))
      }
      Result.success(
        "Android accepted the " + action.name + " action for the revalidated semantic element. " +
          "Dispatch was accepted; the resulting app state has not been verified."
      )
    } catch (error: Exception) {
      Result.failure(error)
    } finally {
      visited.forEach { (node, _) -> runCatching { node.recycle() } }
      while (queue.isNotEmpty()) runCatching { queue.removeFirst().first.recycle() }
      windowInfos.forEach { runCatching { it.recycle() } }
    }
  }

  /**
   * Applies one approved text operation to a freshly revalidated ordinary single-line text field.
   * The field must be a normal text EditText; passwords, numeric/email/URI inputs, multiline fields,
   * sensitive screens, and unsupported accessibility actions are rejected.
   */
  internal fun performSemanticTextAction(
    fingerprint: AccessibilityElementFingerprint,
    action: AccessibilityTextAction,
    inputText: String? = null,
    selectionStart: Int? = null,
    selectionEnd: Int? = null,
  ): Result<String> {
    if (AccessibilityServiceInstance.current !== this) {
      return Result.failure(IllegalStateException("Gallery accessibility service changed before text action dispatch."))
    }
    if (!fingerprint.textInputCapable || fingerprint.bounds.isEmpty) {
      return Result.failure(IllegalStateException("The handle is not a safe text-input target. Run inspect_inputs again."))
    }
    val current = inspectExternalVisibleWindow(allowSafeTextInputs = true) as? AccessibilityWindowSnapshot.Ready
      ?: return Result.failure(IllegalStateException("The external app window is unavailable, ambiguous, or sensitive; no text action was dispatched."))
    if (
      current.packageName != fingerprint.packageName ||
      current.windowId != fingerprint.windowId ||
      current.truncated
    ) {
      return Result.failure(IllegalStateException("The target app or screen changed after input inspection; no text action was dispatched."))
    }

    val windowInfos = windows
    val visited = ArrayList<Pair<AccessibilityNodeInfo, Int>>(MAX_NODES)
    val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
    try {
      val window = windowInfos.singleOrNull {
        it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.id == fingerprint.windowId
      } ?: return Result.failure(IllegalStateException("The inspected application window is no longer uniquely available."))
      val visibleBounds = Rect()
      window.getBoundsInScreen(visibleBounds)
      if (visibleBounds.isEmpty) {
        return Result.failure(IllegalStateException("The inspected application window is no longer visible; no text action was dispatched."))
      }
      val initialRoot = window.root
        ?: return Result.failure(IllegalStateException("The inspected application window no longer exposes a root node."))
      if (initialRoot.packageName?.toString() != fingerprint.packageName) {
        initialRoot.recycle()
        return Result.failure(IllegalStateException("The target application identity changed; no text action was dispatched."))
      }
      when (val safe = inspectRoot(initialRoot, fingerprint.windowId, allowSafeTextInputs = true)) {
        is AccessibilityWindowSnapshot.Ready -> {
          if (safe.packageName != fingerprint.packageName || safe.windowId != fingerprint.windowId || safe.truncated) {
            return Result.failure(IllegalStateException("The target hierarchy is no longer complete and safe; no text action was dispatched."))
          }
        }
        else -> return Result.failure(IllegalStateException("The target hierarchy is no longer safe; no text action was dispatched."))
      }
      val root = window.root
        ?: return Result.failure(IllegalStateException("The target window changed during text-input revalidation."))
      queue.add(root to 0)
      val matches = ArrayList<AccessibilityNodeInfo>(1)
      var incomplete = false
      while (queue.isNotEmpty()) {
        if (visited.size >= MAX_NODES) {
          incomplete = true
          break
        }
        val (node, depth) = queue.removeFirst()
        visited += node to depth
        if (node.windowId != fingerprint.windowId ||
          node.packageName?.toString() != fingerprint.packageName) {
          incomplete = true
          break
        }
        if (node.isVisibleToUser) {
          if (depth >= MAX_DEPTH && node.childCount > 0) {
            incomplete = true
            break
          }
          if (depth < MAX_DEPTH) {
            for (index in 0 until node.childCount) {
              if (visited.size + queue.size >= MAX_NODES) {
                incomplete = true
                break
              }
              val child = runCatching { node.getChild(index) }.getOrNull()
              if (child == null) {
                incomplete = true
                break
              }
              queue.add(child to depth + 1)
            }
          }
          if (incomplete) break
          val rawText = node.text?.toString().orEmpty()
          val rawDescription = node.contentDescription?.toString().orEmpty()
          val rawHint = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString().orEmpty() else ""
          val label = AccessibilityScreenInspectionPolicy.sanitizeLabel(
            if (node.isEditable) listOf(rawHint, rawDescription).firstOrNull { it.isNotBlank() }
            else listOf(rawText, rawDescription, rawHint).firstOrNull { it.isNotBlank() }
          )
          val bounds = Rect()
          node.getBoundsInScreen(bounds)
          if (
            node.className?.toString().orEmpty().take(120) == fingerprint.className &&
            label == fingerprint.label &&
            bounds == fingerprint.bounds
          ) {
            matches += node
          }
        }
      }
      if (incomplete || queue.isNotEmpty()) {
        return Result.failure(IllegalStateException("The target hierarchy changed or exceeded its inspection bounds; no text action was dispatched."))
      }
      val target = matches.singleOrNull()
        ?: return Result.failure(IllegalStateException("The inspected text field is stale or ambiguous; inspect inputs again."))
      val currentText = target.text?.toString().orEmpty()
      if (
        !target.isVisibleToUser ||
        !target.isEnabled ||
        !AccessibilityScreenInspectionPolicy.isSafeTextInputField(
          target.className?.toString().orEmpty(),
          target.inputType,
          target.isEditable,
          target.isPassword,
          currentText,
        ) ||
        target.inputType != fingerprint.inputType ||
        currentText.length != fingerprint.textLength
      ) {
        return Result.failure(IllegalStateException("The text field changed, is disabled, or is no longer safe; no text action was dispatched."))
      }
      when (action) {
        AccessibilityTextAction.ENTER_TEXT -> {
          if (fingerprint.textLength != 0 || currentText.isNotEmpty()) {
            return Result.failure(IllegalStateException("Text entry is limited to an empty field; existing text is never overwritten."))
          }
          val value = inputText
            ?: return Result.failure(IllegalArgumentException("Text entry value is missing."))
          if (!AccessibilityScreenInspectionPolicy.isSafeInputText(value)) {
            return Result.failure(IllegalArgumentException("Text entry must be 1–256 ordinary non-sensitive single-line characters."))
          }
          val frameworkAction = AccessibilityNodeInfo.ACTION_SET_TEXT
          if (target.actionList.none { it.id == frameworkAction }) {
            return Result.failure(IllegalStateException("The field does not currently advertise the set-text action."))
          }
          val arguments = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
          }
          if (!target.performAction(frameworkAction, arguments)) {
            return Result.failure(IllegalStateException("Android rejected text entry; no success is claimed."))
          }
          Result.success("Android accepted text entry into the revalidated empty field. The resulting app state has not been verified.")
        }
        AccessibilityTextAction.SELECT_TEXT -> {
          val start = selectionStart
            ?: return Result.failure(IllegalArgumentException("Selection start is missing."))
          val end = selectionEnd
            ?: return Result.failure(IllegalArgumentException("Selection end is missing."))
          if (!AccessibilityScreenInspectionPolicy.isValidSelection(start, end, currentText.length)) {
            return Result.failure(IllegalArgumentException("Selection range is outside the current safe text field."))
          }
          val frameworkAction = AccessibilityNodeInfo.ACTION_SET_SELECTION
          if (target.actionList.none { it.id == frameworkAction }) {
            return Result.failure(IllegalStateException("The field does not currently advertise text selection."))
          }
          val arguments = android.os.Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
          }
          if (!target.performAction(frameworkAction, arguments)) {
            return Result.failure(IllegalStateException("Android rejected text selection; no success is claimed."))
          }
          Result.success("Android accepted selection of character range $start–$end. The resulting app state has not been verified.")
        }
      }
    } catch (error: Exception) {
      Result.failure(error)
    } finally {
      visited.forEach { (node, _) -> runCatching { node.recycle() } }
      while (queue.isNotEmpty()) runCatching { queue.removeFirst().first.recycle() }
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
          var pendingBytes: ByteArray? = null
          continuation.invokeOnCancellation { pendingBytes?.fill(0) }
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
                    pendingBytes = encoded
                    if (continuation.isActive) continuation.resume(encoded)
                    else encoded.fill(0)
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
    } catch (cancelled: CancellationException) {
      throw cancelled
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
    allowSafeTextInputs: Boolean = false,
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
        textTruncated = textTruncated ||
          (if (node.isEditable && allowSafeTextInputs) false else rawText.length > AccessibilityScreenInspectionPolicy.MAX_LABEL_CHARS) ||
          rawDescription.length > AccessibilityScreenInspectionPolicy.MAX_LABEL_CHARS ||
          rawHint.length > AccessibilityScreenInspectionPolicy.MAX_LABEL_CHARS
        if (rawText.length > MAX_SOURCE_TEXT_CHARS ||
          rawDescription.length > MAX_SOURCE_TEXT_CHARS || rawHint.length > MAX_SOURCE_TEXT_CHARS) {
          return AccessibilityWindowSnapshot.Excluded("A visible element exceeds the safe text-inspection limit; no screen content was returned.")
        }
        val safeTextInput = node.isEditable && allowSafeTextInputs &&
          AccessibilityScreenInspectionPolicy.isSafeTextInputField(
            className = node.className?.toString().orEmpty(),
            inputType = node.inputType,
            editable = node.isEditable,
            password = node.isPassword,
            currentText = rawText,
          )
        if (node.isPassword || (node.isEditable && !safeTextInput)) {
          return AccessibilityWindowSnapshot.Excluded(
            "This screen contains a password or unsupported editable field; text inspection and input were blocked."
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
          if (node.isEditable) listOf(rawHint, rawDescription).firstOrNull { it.isNotBlank() }
          else listOf(rawText, rawDescription, rawHint).firstOrNull { it.isNotBlank() }
        )
        val useful = !label.isNullOrBlank() || node.isClickable || node.isLongClickable ||
          node.isScrollable || node.isCheckable || node.isFocusable || safeTextInput
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
            longClickable = node.isLongClickable,
            scrollable = node.isScrollable,
            enabled = node.isEnabled,
            selected = node.isSelected,
            checked = if (node.isCheckable) node.isChecked else null,
            editable = safeTextInput,
            inputType = if (safeTextInput) node.inputType else null,
            textLength = if (safeTextInput) rawText.length else null,
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
  val longClickable: Boolean,
  val scrollable: Boolean,
  val enabled: Boolean,
  val selected: Boolean,
  val checked: Boolean?,
  val editable: Boolean = false,
  val inputType: Int? = null,
  val textLength: Int? = null,
)
