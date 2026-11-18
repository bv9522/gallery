/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.capability

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ActiveMediaSessionSnapshot(
  val handle: String,
  val appLabel: String,
  val title: String?,
  val artist: String?,
  val album: String?,
  val playbackState: String,
  val positionMs: Long?,
  val durationMs: Long?,
  val supportedActions: List<String>,
)
data class ActiveMediaSessionList(val sessions: List<ActiveMediaSessionSnapshot>, val truncated: Boolean)
data class ActiveMediaSessionHandleSummary(val appLabel: String, val title: String?)

data class ActiveMediaSessionControlResult(val success: Boolean, val message: String)

enum class ActiveMediaSessionAction { PLAY, PAUSE, NEXT, PREVIOUS, SEEK }

object ActiveMediaSessionActionPolicy {
  fun isSupported(action: ActiveMediaSessionAction, playbackActions: Long): Boolean =
    when (action) {
      ActiveMediaSessionAction.PLAY ->
        playbackActions and PlaybackState.ACTION_PLAY != 0L ||
          playbackActions and PlaybackState.ACTION_PLAY_PAUSE != 0L
      ActiveMediaSessionAction.PAUSE ->
        playbackActions and PlaybackState.ACTION_PAUSE != 0L ||
          playbackActions and PlaybackState.ACTION_PLAY_PAUSE != 0L
      ActiveMediaSessionAction.NEXT -> playbackActions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L
      ActiveMediaSessionAction.PREVIOUS -> playbackActions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L
      ActiveMediaSessionAction.SEEK -> playbackActions and PlaybackState.ACTION_SEEK_TO != 0L
    }

  fun isValidSeekPosition(positionMs: Long, durationMs: Long?): Boolean =
    durationMs != null && durationMs > 0L && positionMs in 0L..durationMs
}

/** No listener is started here; Android binds the declared service only after user opt-in. */
class AndroidActiveMediaSessionAdapter(context: Context) {
  private val appContext = context.applicationContext
  private val component = ComponentName(appContext, GalleryMediaSessionListenerService::class.java)
  private val manager = appContext.getSystemService(MediaSessionManager::class.java)
  private val handles = ConcurrentHashMap<String, SessionEntry>()

  private data class SessionEntry(
    val token: MediaController.Token,
    val snapshot: ActiveMediaSessionSnapshot,
    val expiresAtMs: Long,
  )

  fun hasNotificationListenerAccess(): Boolean =
    appContext.getSystemService(NotificationManager::class.java)
      ?.isNotificationListenerAccessGranted(component) == true

  fun listActiveSessions(): ActiveMediaSessionList {
    requireAccess()
    val controllers = (manager ?: error("Android MediaSessionManager is unavailable."))
      .getActiveSessions(component)
    handles.clear()
    val now = System.currentTimeMillis()
    val sessions = controllers.take(MAX_SESSIONS).mapNotNull { controller ->
      val playback = controller.playbackState ?: return@mapNotNull null
      val metadata = controller.metadata
      val label = runCatching {
        val info = appContext.packageManager.getApplicationInfo(controller.packageName, 0)
        appContext.packageManager.getApplicationLabel(info).toString()
      }.getOrDefault("Media app")
      val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)
        ?.takeIf { it in 1L..MAX_DURATION_MS }
      val position = playback.position.takeIf { it >= 0L }
        ?.let { if (duration == null) it else it.coerceAtMost(duration) }
      val actions = ActiveMediaSessionAction.values()
        .filter { ActiveMediaSessionActionPolicy.isSupported(it, playback.actions) }
        .map { it.name }
      val handle = "ms_" + UUID.randomUUID().toString().replace("-", "").take(16)
      val snapshot = ActiveMediaSessionSnapshot(
        handle = handle,
        appLabel = label.take(MAX_LABEL_CHARS),
        title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.take(MAX_METADATA_CHARS),
        artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.take(MAX_METADATA_CHARS),
        album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)?.take(MAX_METADATA_CHARS),
        playbackState = playbackStateName(playback.state),
        positionMs = position,
        durationMs = duration,
        supportedActions = actions,
      )
      handles[handle] = SessionEntry(controller.sessionToken, snapshot, now + HANDLE_TTL_MS)
      snapshot
    }
    return ActiveMediaSessionList(sessions, controllers.size > MAX_SESSIONS)
  }

  fun describeHandle(handle: String): ActiveMediaSessionHandleSummary? {
    val entry = handles[handle] ?: return null
    if (entry.expiresAtMs < System.currentTimeMillis()) {
      handles.remove(handle, entry)
      return null
    }
    return ActiveMediaSessionHandleSummary(entry.snapshot.appLabel, entry.snapshot.title)
  }

  fun control(handle: String, action: ActiveMediaSessionAction, positionMs: Long? = null): ActiveMediaSessionControlResult {
    requireAccess()
    val entry = handles[handle]
      ?: return ActiveMediaSessionControlResult(false, "Session handle is unknown or expired. Run media_sessions list again.")
    if (entry.expiresAtMs < System.currentTimeMillis()) {
      handles.remove(handle, entry)
      return ActiveMediaSessionControlResult(false, "Session handle expired. Run media_sessions list again.")
    }
    val current = (manager ?: return ActiveMediaSessionControlResult(false, "Android MediaSessionManager is unavailable."))
      .getActiveSessions(component).firstOrNull { it.sessionToken == entry.token }
      ?: return ActiveMediaSessionControlResult(false, "The selected media session is no longer active. Run media_sessions list again.")
    val playback = current.playbackState
      ?: return ActiveMediaSessionControlResult(false, "The selected media session no longer exposes playback state.")
    if (!ActiveMediaSessionActionPolicy.isSupported(action, playback.actions)) {
      return ActiveMediaSessionControlResult(false, "The selected media session does not currently support \${action.name}. Run media_sessions list again.")
    }
    if (action == ActiveMediaSessionAction.SEEK) {
      val duration = current.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)
        ?.takeIf { it in 1L..MAX_DURATION_MS }
      if (positionMs == null || !ActiveMediaSessionActionPolicy.isValidSeekPosition(positionMs, duration)) {
        return ActiveMediaSessionControlResult(false, "SEEK requires position_ms between 0 and the current track duration.")
      }
    }
    try {
      when (action) {
        ActiveMediaSessionAction.PLAY -> current.transportControls.play()
        ActiveMediaSessionAction.PAUSE -> current.transportControls.pause()
        ActiveMediaSessionAction.NEXT -> current.transportControls.skipToNext()
        ActiveMediaSessionAction.PREVIOUS -> current.transportControls.skipToPrevious()
        ActiveMediaSessionAction.SEEK -> current.transportControls.seekTo(checkNotNull(positionMs))
      }
    } catch (error: RuntimeException) {
      return ActiveMediaSessionControlResult(false, error.message ?: "The media session rejected the command.")
    }
    handles.remove(handle, entry)
    return ActiveMediaSessionControlResult(true,
      "\${action.name} command dispatched to \${entry.snapshot.appLabel}. This confirms dispatch only, not the resulting playback state.")
  }

  private fun requireAccess() {
    if (!hasNotificationListenerAccess()) {
      throw SecurityException("Notification-listener special access is not enabled for Gallery.")
    }
  }

  private fun playbackStateName(state: Int): String = when (state) {
    PlaybackState.STATE_NONE -> "NONE"
    PlaybackState.STATE_STOPPED -> "STOPPED"
    PlaybackState.STATE_PAUSED -> "PAUSED"
    PlaybackState.STATE_PLAYING -> "PLAYING"
    PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
    PlaybackState.STATE_REWINDING -> "REWINDING"
    PlaybackState.STATE_BUFFERING -> "BUFFERING"
    PlaybackState.STATE_ERROR -> "ERROR"
    PlaybackState.STATE_CONNECTING -> "CONNECTING"
    PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "SKIPPING_TO_PREVIOUS"
    PlaybackState.STATE_SKIPPING_TO_NEXT -> "SKIPPING_TO_NEXT"
    PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "SKIPPING_TO_QUEUE_ITEM"
    else -> "UNKNOWN"
  }

  companion object {
    const val MAX_SESSIONS = 10
    const val HANDLE_TTL_MS = 120_000L
    const val MAX_LABEL_CHARS = 80
    const val MAX_METADATA_CHARS = 160
    const val MAX_DURATION_MS = 86_400_000L
  }
}
