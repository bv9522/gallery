/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.capability

import android.service.notification.NotificationListenerService

/** Opt-in component used only to authorize MediaSessionManager.getActiveSessions(). */
class GalleryMediaSessionListenerService : NotificationListenerService()
