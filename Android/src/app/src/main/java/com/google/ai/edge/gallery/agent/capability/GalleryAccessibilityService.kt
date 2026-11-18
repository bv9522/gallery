/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.capability

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Opt-in lifecycle endpoint for future, explicitly invoked UI operations.
 *
 * This foundation deliberately does not retrieve window content, inspect events, retain nodes,
 * capture screenshots, or perform gestures. Those capabilities are added in separately bounded
 * follow-up slices after the user enables the service.
 */
class GalleryAccessibilityService : AccessibilityService() {
  override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

  override fun onInterrupt() = Unit
}
