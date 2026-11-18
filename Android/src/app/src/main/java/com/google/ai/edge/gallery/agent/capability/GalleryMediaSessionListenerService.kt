/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.capability

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * User-enabled listener used for on-demand media-session discovery and notification reads.
 *
 * No notification callbacks are collected, persisted, or processed in the background. Notification
 * content is queried only through an explicit approved tool request.
 */
class GalleryMediaSessionListenerService : NotificationListenerService() {
  override fun onListenerConnected() {
    super.onListenerConnected()
    activeInstance = this
  }

  override fun onListenerDisconnected() {
    if (activeInstance === this) activeInstance = null
    super.onListenerDisconnected()
  }

  override fun onDestroy() {
    if (activeInstance === this) activeInstance = null
    super.onDestroy()
  }

  companion object {
    @Volatile private var activeInstance: GalleryMediaSessionListenerService? = null

    /** Returns a fresh snapshot only; this service retains no notification history. */
    fun activeNotificationsSnapshot(): Array<StatusBarNotification>? =
      activeInstance?.let { service ->
        runCatching { service.activeNotifications }.getOrNull()
      }

    fun isConnected(): Boolean = activeInstance != null
  }
}
