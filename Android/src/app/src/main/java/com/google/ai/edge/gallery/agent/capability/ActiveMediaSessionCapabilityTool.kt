/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.capability

import android.content.Context
import com.google.ai.edge.gallery.agent.coordinator.AgentTool
import com.google.ai.edge.gallery.agent.coordinator.AgentToolResult
import com.google.ai.edge.gallery.tools.OpenMediaSessionSettingsToolAction
import com.google.ai.edge.gallery.tools.ToolAction
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withTimeoutOrNull

/** User-controlled access and bounded controls for currently active Android media sessions. */
class ActiveMediaSessionCapabilityTool internal constructor(
  private val adapter: AndroidActiveMediaSessionAdapter,
  private val authorizer: NativeCapabilityAuthorizer,
  private val settingsRequester: MediaSessionSettingsRequester,
) : AgentTool {
  constructor(context: Context, actionChannel: SendChannel<ToolAction>) : this(
    AndroidActiveMediaSessionAdapter(context.applicationContext),
    ChannelNativeCapabilityAuthorizer(actionChannel),
    ChannelMediaSessionSettingsRequester(actionChannel),
  )

  override val name = "media_sessions"
  override val description =
    "Use operation=list to inspect up to 10 active media sessions after the user enables Gallery's notification-listener special access. Results include app label, track metadata, playback state, supported controls, and short-lived opaque handles. Use operation=control with a fresh handle and action PLAY, PAUSE, NEXT, PREVIOUS, or SEEK; seek requires position_ms within the current track duration. Each inspection and control requires one-time approval. Use operation=open_settings only when the user explicitly asks to enable access; it opens Android notification-listener settings but does not grant access. Never read/store notifications, expose package names/session tokens, guess handles, or claim that a dispatched command guarantees playback changed."

  override val parametersSchema: JsonObject
    get() = JsonObject().apply {
      addProperty("type", "object")
      add("properties", JsonObject().apply {
        add("operation", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply { listOf("list", "control", "open_settings").forEach { add(it) } })
        })
        add("session_handle", JsonObject().apply {
          addProperty("type", "string")
          addProperty("maxLength", 19)
          addProperty("description", "Short-lived opaque handle copied exactly from the latest list result.")
        })
        add("action", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply { ActiveMediaSessionAction.values().forEach { add(it.name) } })
        })
        add("position_ms", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("minimum", 0)
          addProperty("description", "Required only for SEEK; must not exceed the current track duration.")
        })
      })
      add("required", JsonArray().apply { add("operation") })
      addProperty("additionalProperties", false)
    }

  override suspend fun execute(arguments: JsonObject): AgentToolResult {
    MediaSessionArguments.validate(arguments)?.let { return AgentToolResult(it, false) }
    return try {
      when (arguments.get("operation").asString) {
        "list" -> listSessions()
        "control" -> control(arguments)
        "open_settings" -> openSettings()
        else -> AgentToolResult("Unsupported media session operation.", false)
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      AgentToolResult(error.message ?: "Media session operation failed.", false)
    }
  }

  private suspend fun listSessions(): AgentToolResult {
    if (!adapter.hasNotificationListenerAccess()) {
      return AgentToolResult(
        "Gallery does not have notification-listener special access. No sessions were read. If the user wants to enable it, call media_sessions with operation=open_settings; the user must enable Gallery manually in Android settings.",
        false,
      )
    }
    if (!authorizer.authorize(AndroidCapability.MEDIA_SESSION_CONTROL,
        "Read up to 10 active media sessions, including app labels, track title/artist/album, playback state, and supported controls.")) {
      return AgentToolResult("Active media-session inspection was not approved.", false)
    }
    val result = adapter.listActiveSessions()
    if (result.sessions.isEmpty()) return AgentToolResult("No active media sessions with playback state were found.")
    return AgentToolResult(buildString {
      appendLine("returned=${result.sessions.size}")
      appendLine("limit=${AndroidActiveMediaSessionAdapter.MAX_SESSIONS}")
      appendLine("truncated=${result.truncated}")
      result.sessions.forEachIndexed { index, session ->
        appendLine("session=${index + 1}")
        appendLine("session_handle=${session.handle}")
        appendLine("app=${session.appLabel}")
        session.title?.let { appendLine("title=$it") }
        session.artist?.let { appendLine("artist=$it") }
        session.album?.let { appendLine("album=$it") }
        appendLine("playback_state=${session.playbackState}")
        session.positionMs?.let { appendLine("position_ms=$it") }
        session.durationMs?.let { appendLine("duration_ms=$it") }
        appendLine("supported_actions=${session.supportedActions.joinToString(",")}")
      }
      if (result.truncated) appendLine("note=Additional sessions may be active; only the first 10 were returned.")
    }.trimEnd())
  }

  private suspend fun control(arguments: JsonObject): AgentToolResult {
    val handle = arguments.get("session_handle").asString
    val action = ActiveMediaSessionAction.valueOf(arguments.get("action").asString)
    val position = arguments.get("position_ms")?.asLong
    val summary = adapter.describeHandle(handle)
      ?: return AgentToolResult("Session handle is unknown or expired. Run media_sessions list again.", false)
    val detail = "Dispatch ${action.name} to ${summary.appLabel}" +
      (summary.title?.let { " — $it" } ?: "") +
      if (action == ActiveMediaSessionAction.SEEK) " at ${position} ms." else "."
    if (!authorizer.authorize(AndroidCapability.MEDIA_SESSION_CONTROL, detail)) {
      return AgentToolResult("Media-session control was not approved.", false)
    }
    val result = adapter.control(handle, action, position)
    return AgentToolResult(result.message, result.success)
  }

  private suspend fun openSettings(): AgentToolResult {
    if (!authorizer.authorize(AndroidCapability.MEDIA_SESSION_CONTROL,
        "Open Android notification-listener settings so the user can optionally enable Gallery media-session access. This does not grant access.")) {
      return AgentToolResult("Opening notification-listener settings was not approved.", false)
    }
    return if (settingsRequester.openSettings()) {
      AgentToolResult("Android notification-listener settings opened. Gallery still has no access unless the user explicitly enables it there.")
    } else {
      AgentToolResult("Android notification-listener settings could not be opened.", false)
    }
  }
}

fun interface MediaSessionSettingsRequester { suspend fun openSettings(): Boolean }

class ChannelMediaSessionSettingsRequester(
  private val actionChannel: SendChannel<ToolAction>,
  private val timeoutMs: Long = 120_000L,
) : MediaSessionSettingsRequester {
  override suspend fun openSettings(): Boolean {
    val action = OpenMediaSessionSettingsToolAction()
    return try {
      withTimeoutOrNull(timeoutMs) {
        actionChannel.send(action)
        action.result.await()
      } ?: false
    } finally {
      if (!action.result.isCompleted) action.result.complete(false)
    }
  }
}

internal object MediaSessionArguments {
  fun validate(arguments: JsonObject): String? {
    val operation = arguments.get("operation")
      ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
      ?: return "operation must be list, control, or open_settings."
    val accepted = when (operation) {
      "list", "open_settings" -> setOf("operation")
      "control" -> setOf("operation", "session_handle", "action", "position_ms")
      else -> return "Unsupported operation. Choose list, control, or open_settings."
    }
    val unexpected = arguments.keySet() - accepted
    if (unexpected.isNotEmpty()) return "Unexpected argument(s): " + unexpected.sorted().joinToString(", ") + "."
    if (operation == "control") {
      val handle = arguments.get("session_handle")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: return "control requires session_handle from the latest list result."
      if (!handle.matches(Regex("ms_[a-f0-9]{16}"))) return "session_handle is malformed; run list again."
      val action = arguments.get("action")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: return "control requires action PLAY, PAUSE, NEXT, PREVIOUS, or SEEK."
      if (action !in ActiveMediaSessionAction.values().map { it.name }) {
        return "Unsupported action. Choose PLAY, PAUSE, NEXT, PREVIOUS, or SEEK."
      }
      val position = arguments.get("position_ms")
      if (action == "SEEK") {
        if (position == null || !position.isJsonPrimitive || !position.asJsonPrimitive.isNumber) {
          return "SEEK requires integer position_ms."
        }
        if (position.asString.toLongOrNull()?.let { it < 0L } != false) return "position_ms must be zero or greater."
      } else if (position != null) {
        return "position_ms is accepted only with SEEK."
      }
    }
    return null
  }
}
