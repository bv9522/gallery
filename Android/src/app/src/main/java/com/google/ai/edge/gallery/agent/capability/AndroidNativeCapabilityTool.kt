/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.agent.capability

import android.content.Context
import com.google.ai.edge.gallery.agent.coordinator.AgentTool
import com.google.ai.edge.gallery.agent.coordinator.AgentToolResult
import com.google.ai.edge.gallery.data.AgentDownloadOperations
import com.google.ai.edge.gallery.data.AgentDownloadSnapshot
import com.google.ai.edge.gallery.tools.AskNativeCapabilityPermissionAction
import com.google.ai.edge.gallery.tools.AuthenticateBiometricToolAction
import com.google.ai.edge.gallery.tools.SpeakTextToolAction
import com.google.ai.edge.gallery.tools.PlayMediaToolAction
import com.google.ai.edge.gallery.tools.ToolAction
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.util.concurrent.ConcurrentHashMap
import androidx.biometric.BiometricPrompt

/** Sends exact, one-time native mutation requests through Agent Chat's existing action channel. */
class ChannelNativeBiometricAuthenticator(
  private val actionChannel: SendChannel<ToolAction>,
  private val timeoutMs: Long = 120_000L,
) : NativeBiometricAuthenticator {
  override suspend fun authenticate(
    title: String,
    subtitle: String,
    cryptoObject: BiometricPrompt.CryptoObject?,
  ): BiometricPrompt.AuthenticationResult? {
    val action = AuthenticateBiometricToolAction(title, subtitle, cryptoObject)
    return try {
      withTimeoutOrNull(timeoutMs) {
        actionChannel.send(action)
        action.result.await()
      }
    } finally {
      if (!action.result.isCompleted) {
        action.cancelPrompt?.invoke()
        action.result.complete(null)
      }
    }
  }
}

class ChannelNativeCapabilityAuthorizer(
  private val actionChannel: SendChannel<ToolAction>,
  private val approvalTimeoutMs: Long = 120_000L,
) : NativeCapabilityAuthorizer {
  override suspend fun authorize(capability: AndroidCapability, summary: String): Boolean {
    val action = AskNativeCapabilityPermissionAction(capability.name, summary)
    return try {
      withTimeoutOrNull(approvalTimeoutMs) {
        actionChannel.send(action)
        action.result.await()
      } ?: false
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      false
    } finally {
      if (!action.result.isCompleted) action.result.complete(false)
    }
  }
}

/**
 * Compact model-facing interface for native Android capabilities.
 *
 * Ordinary state reads do not prompt. Privacy-sensitive device discovery and mutations require
 * one-time approval; runtime Android permissions are requested only when needed. Every operation
 * is routed through the capability registry; this tool does not fall back to a shell provider.
 */
fun interface NativeSpeechPlaybackRequester {
  suspend fun speak(text: String): Boolean
}

class ChannelNativeSpeechPlaybackRequester(
  private val actionChannel: SendChannel<ToolAction>,
  private val timeoutMs: Long = 120_000L,
) : NativeSpeechPlaybackRequester {
  override suspend fun speak(text: String): Boolean {
    val action = SpeakTextToolAction(text)
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

fun interface NativeMediaPlaybackRequester {
  suspend fun play(contentUri: String, displayName: String): Boolean
}

class ChannelNativeMediaPlaybackRequester(
  private val actionChannel: SendChannel<ToolAction>,
  private val timeoutMs: Long = 120_000L,
) : NativeMediaPlaybackRequester {
  override suspend fun play(contentUri: String, displayName: String): Boolean {
    val action = PlayMediaToolAction(contentUri, displayName)
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
class AndroidNativeCapabilityTool internal constructor(
  private val adapter: NativeCapabilityOperations,
  private val registry: CapabilityRegistry = CapabilityCatalog.createRegistry(),
  private val speechPlaybackRequester: NativeSpeechPlaybackRequester = NativeSpeechPlaybackRequester { false },
  private val speechAuthorizer: NativeCapabilityAuthorizer? = null,
  private val downloadOperations: AgentDownloadOperations? = null,
  private val downloadAuthorizer: NativeCapabilityAuthorizer? = null,
  private val mediaOperations: GalleryMediaLibraryOperations? = null,
  private val mediaPlaybackRequester: NativeMediaPlaybackRequester? = null,
  private val mediaPlaybackAuthorizer: NativeCapabilityAuthorizer? = null,
) : AgentTool {
  private val issuedDownloadSnapshots = ConcurrentHashMap<String, AgentDownloadSnapshot>()

  constructor(
    context: Context,
    actionChannel: SendChannel<ToolAction>,
    registry: CapabilityRegistry = CapabilityCatalog.createRegistry(),
    downloadOperations: AgentDownloadOperations? = null,
    mediaOperations: GalleryMediaLibraryOperations? = null,
  ) : this(
    AndroidNativeCapabilityAdapter(
      context = context.applicationContext,
      authorizer = ChannelNativeCapabilityAuthorizer(actionChannel),
      permissionRequester = ChannelNativeRuntimePermissionRequester(actionChannel),
      photoCaptureRequester = ChannelNativePhotoCaptureRequester(actionChannel),
      biometricAuthenticator = ChannelNativeBiometricAuthenticator(actionChannel),
    ),
    registry,
    ChannelNativeSpeechPlaybackRequester(actionChannel),
    ChannelNativeCapabilityAuthorizer(actionChannel),
    downloadOperations,
    ChannelNativeCapabilityAuthorizer(actionChannel),
    mediaOperations,
    ChannelNativeMediaPlaybackRequester(actionChannel),
    ChannelNativeCapabilityAuthorizer(actionChannel),
  )

  override val name: String = "android_control"
  override val description: String =
    "Read device, battery, connected audio endpoint, brightness, and wake-lock information, inspect volume " +
      "and launchable apps, or perform an approved native Android action. Use status to inspect " +
      "availability of each native Android " +
      "capability; status is a provider diagnostic, not a battery or volume reading. Use operation " +
      "values: speak_text, status, device_info, telephony_info, dial_phone, compose_sms, biometric_auth, keystore_create, keystore_list, keystore_delete, keystore_encrypt, keystore_decrypt, battery_status, volume_status, list_apps, set_volume, " +
      "set_flashlight, launch_app, open_url, audio_devices, display_brightness, " +
      "set_display_brightness, vibrate, wake_lock_status, set_wake_lock, bluetooth_bonded_devices, " +
      "bluetooth_le_scan, bluetooth_gatt_services, bluetooth_gatt_read, bluetooth_gatt_write, usb_devices, usb_read_descriptors, " +
      "record_audio, capture_photo, set_wallpaper, post_notification, list_posted_notifications, cancel_posted_notification, schedule_notification, list_scheduled_notifications, " +
      "cancel_scheduled_notification, list_notification_channels, create_notification_channel, read_notifications, read_call_log, read_sms_inbox, download_status, cancel_download, media_search, media_index, media_play. " +
      "download_status returns at most 20 active Gallery model downloads with short-lived opaque handles. " +
      "cancel_download requires a handle from the latest status result and one-time approval for that exact download. " +
      "No worker IDs, tags, URLs, tokens, or filesystem paths are exposed. Notifications " +
      "are posted/scheduled only after scoped approval and POST_NOTIFICATIONS permission. Use read_notifications only " +
      "when the user explicitly asks to inspect notifications; it requires manually enabled notification-listener " +
      "special access and a separate one-time approval for every read. It returns at most 20 currently active " +
      "notifications with app labels and bounded title/body text; it does not read history, process notification " +
      "callbacks, monitor in the background, or persist notification content. Notification text may be private; " +
      "never read it without an explicit request. Use read_call_log only when the user explicitly asks for call history; " +
      "each read requires one-time CALL_LOG_READ approval and Android READ_CALL_LOG permission, requested only after approval. " +
      "It returns at most 20 recent entries with cached caller name, number, call type, date/time, and duration. " +
      "Android may restrict READ_CALL_LOG eligibility by installer or role; if denied, report that limitation and never change the default dialer role. " +
      "Use read_sms_inbox only when the user explicitly asks to inspect received SMS messages; each read requires one-time SMS_READ approval and Android READ_SMS permission requested only after approval. " +
      "It returns at most 20 recent inbox entries with sender address, bounded message preview, timestamp, and read/unread state. Android may restrict READ_SMS by installer or default-SMS-role eligibility; if denied, report the limitation and never change the default SMS role. " +
      "Device discovery, GATT connections, GATT reads/writes, " +
      "audio recording (1–30 seconds, local MediaStore output), photo capture, and wallpaper changes " +
      "require one-time user approval. Bluetooth permissions are requested only after approval. BLE scans " +
      "are limited to 1–10 seconds; each GATT operation connects to one address for at most 20 seconds, " +
      "performs one bounded action, and disconnects on completion, timeout, or cancellation. GATT writes " +
      "are limited to 512 bytes and require approval showing the exact Base64 payload. No subscriptions or " +
      "persistent connections are retained. Phone calls and SMS use Android's dialer/composer only; " +
      "the user must confirm the call or press Send. No CALL_PHONE or SEND_SMS permission is requested, " +
      "No call or message is placed/sent silently. Telephony metadata excludes subscriber identifiers and cell-location data. " +
      "Keystore keys are non-exportable AES-256 keys and each encrypt/decrypt operation requires strong biometric authentication. " +
      "media_search searches filenames in MediaStore and persisted user-granted storage folders, and can use the existing EmbeddingGemma-2 Smart Album visual index. media_index queues the existing bounded Smart Album indexing worker after one-time approval; it analyzes permitted media on-device and does not upload media. Search results expose short-lived opaque handles, not content URIs or filesystem paths. media_play requires a fresh search handle and one-time approval, then opens the selected video in Gallery's built-in Media3 player. USB descriptor inspection is read-only and requires a fresh handle from usb_devices plus one-time approval and Android's per-device USB permission prompt. It returns standard descriptor metadata only; arbitrary endpoint/control/bulk transfers and writes are not exposed. USB mass storage must use the user-granted SAF document_tree provider. Agent speech uses Gallery's existing TtsServiceProvider and playback controller, preserves saved engine/voice/language, and requires one-time approval of the exact text. Speech text is limited to 1000 characters. This tool never executes shell commands."

  override val parametersSchema: JsonObject
    get() = JsonObject().apply {
      addProperty("type", "object")
      add("properties", JsonObject().apply {
        add("operation", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply {
            listOf(
              "status", "download_status", "cancel_download", "device_info", "telephony_info", "dial_phone", "compose_sms", "biometric_auth", "keystore_create", "keystore_list", "keystore_delete", "keystore_encrypt", "keystore_decrypt", "battery_status", "volume_status", "audio_devices",
              "display_brightness", "set_display_brightness", "vibrate", "wake_lock_status",
              "set_wake_lock", "list_apps", "set_volume", "set_flashlight", "launch_app", "open_url", "bluetooth_bonded_devices", "bluetooth_le_scan", "bluetooth_gatt_services", "bluetooth_gatt_read", "bluetooth_gatt_write", "contacts_search", "clipboard_read", "clipboard_write", "post_notification", "list_posted_notifications", "cancel_posted_notification", "schedule_notification", "list_scheduled_notifications", "cancel_scheduled_notification", "list_notification_channels", "create_notification_channel", "read_notifications", "read_call_log", "read_sms_inbox", "usb_devices", "usb_read_descriptors", "record_audio", "capture_photo", "set_wallpaper", "speak_text", "media_search", "media_index", "media_play",
            ).forEach { add(it) }
          })
        })
        add("usb_handle", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Short-lived one-shot USB device handle copied exactly from the latest usb_devices result.")
          addProperty("pattern", "^usb_[0-9a-f]{32}$")
          addProperty("maxLength", 36)
        })
        add("media_handle", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Short-lived opaque media handle copied exactly from the latest media_search result.")
          addProperty("maxLength", 38)
        })
        add("media_kind", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply { listOf("ANY", "IMAGE", "VIDEO").forEach { add(it) } })
          addProperty("description", "Optional media filter for media_search; defaults to ANY.")
        })
        add("search_mode", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply { listOf("AUTO", "NAME", "VISUAL").forEach { add(it) } })
          addProperty("description", "AUTO combines visual and filename search, NAME searches filenames only, VISUAL requests semantic visual search.")
        })
        add("download_handle", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Opaque handle copied exactly from the most recent download_status result; valid only briefly.")
          addProperty("maxLength", 35)
        })
        add("phone_number", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Destination phone number for dial_phone or compose_sms; 3–20 digits, optional leading plus, and common punctuation only.")
          addProperty("maxLength", 32)
        })
        add("sms_message", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Exact message to prefill in the SMS composer; maximum 1600 characters. Android will not send it automatically.")
          addProperty("maxLength", 1600)
        })
        add("reason", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Short purpose for standalone strong biometric authentication; maximum 160 characters.")
          addProperty("maxLength", 160)
        })
        add("key_alias", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Gallery Agent Keystore alias: 1–32 lowercase letters, digits, underscores, or hyphens.")
          addProperty("maxLength", 32)
        })
        add("plaintext", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Plaintext to encrypt using a biometric-protected AES-256 key; maximum 4096 characters.")
          addProperty("maxLength", 4096)
        })
        add("iv_base64", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "GCM IV returned by keystore_encrypt, encoded as unwrapped Base64.")
          addProperty("maxLength", 24)
        })
        add("ciphertext_base64", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Ciphertext returned by keystore_encrypt, encoded as unwrapped Base64.")
          addProperty("maxLength", 21868)
        })
        add("stream", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply { NativeAudioStream.values().forEach { add(it.name) } })
          addProperty("description", "Audio stream for volume_status or set_volume; defaults to MUSIC.")
        })
        add("volume", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("description", "Target volume; must be within the selected stream's range.")
        })
        add("level", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("description", "Display brightness level from 0 through 255.")
        })
        add("duration_ms", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("description", "Operation-specific duration: vibration 1–1000 ms, wake lock 1–300000 ms, Bluetooth LE scan 1000–10000 ms (defaults to 5000), audio recording 1000–30000 ms (defaults to 5000).")
        })
        add("content_uri", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Gallery-readable image content URI, such as one returned by capture_photo or a user-granted picker.")
        })
        add("target", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply { listOf("SYSTEM", "LOCK", "BOTH").forEach { add(it) } })
        })
        add("held", JsonObject().apply {
          addProperty("type", "boolean")
          addProperty("description", "True to acquire a bounded CPU wake lock; false to release it.")
        })
        add("enabled", JsonObject().apply {
          addProperty("type", "boolean")
          addProperty("description", "Whether to turn the flashlight on or off.")
        })
        add("package_name", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Exact package name returned by list_apps.")
        })
        add("url", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Absolute HTTP(S) URL with a host.")
        })
        add("device_address", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Bluetooth MAC address returned by bluetooth_bonded_devices or bluetooth_le_scan.")
        })
        add("timeout_ms", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("description", "Overall Bluetooth GATT connection, discovery, and characteristic operation timeout, 5000–20000 ms; defaults to 15000.")
        })
        add("service_uuid", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Full service UUID returned by bluetooth_gatt_services.")
        })
        add("characteristic_uuid", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Full characteristic UUID returned by bluetooth_gatt_services.")
        })
        add("value_base64", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "For bluetooth_gatt_write only: 1–512 bytes encoded as unwrapped Base64.")
        })
        add("query", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Text query for contacts_search (1–80 characters) or media_search (1–120 characters).")
          addProperty("minLength", 1)
          addProperty("maxLength", 120)
        })
        add("limit", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("description", "Maximum results for contacts_search or media_search; 1–20, defaults to 10.")
          addProperty("minimum", MIN_CONTACT_LIMIT)
          addProperty("maximum", MAX_CONTACT_LIMIT)
        })
        add("text", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Text to place on the clipboard; maximum 2048 characters.")
          addProperty("maxLength", MAX_CLIPBOARD_WRITE_CHARS)
        })
        add("speech_text", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Exact text for speak_text; maximum 1000 characters. Uses Gallery's configured TTS engine.")
          addProperty("maxLength", MAX_SPEAK_TEXT_CHARS)
        })
        add("title", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Notification title; 1–100 characters.")
          addProperty("maxLength", MAX_NOTIFICATION_TITLE_CHARS)
        })
        add("message", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Notification body; 1–1000 characters.")
          addProperty("maxLength", MAX_NOTIFICATION_MESSAGE_CHARS)
        })
        add("hour", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("description", "Local 24-hour clock hour, 0–23.")
          addProperty("minimum", 0)
          addProperty("maximum", 23)
        })
        add("minute", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("description", "Local minute, 0–59.")
          addProperty("minimum", 0)
          addProperty("maximum", 59)
        })
        add("year", JsonObject().apply { addProperty("type", "integer") })
        add("month", JsonObject().apply { addProperty("type", "integer") })
        add("day", JsonObject().apply { addProperty("type", "integer") })
        add("repeat_daily", JsonObject().apply {
          addProperty("type", "boolean")
          addProperty("description", "Repeat this notification daily at the specified local time.")
        })
        add("channel_id", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Existing channel ID from list_notification_channels or create_notification_channel; defaults to the Gallery task channel.")
          addProperty("maxLength", 64)
        })
        add("channel_name", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Channel display name for create_notification_channel.")
          addProperty("maxLength", MAX_NOTIFICATION_CHANNEL_NAME_CHARS)
        })
        add("notification_id", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Numeric ID returned by list_posted_notifications, or UUID returned by schedule_notification/list_scheduled_notifications.")
          addProperty("maxLength", 64)
        })
        add("importance", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("description", "Notification channel importance: 2 LOW, 3 DEFAULT, or 4 HIGH.")
          addProperty("minimum", 2)
          addProperty("maximum", 4)
        })
      })
      add("required", JsonArray().apply { add("operation") })
      addProperty("additionalProperties", false)
    }

  override suspend fun execute(arguments: JsonObject): AgentToolResult {
    AndroidControlArguments.validate(arguments)?.let { return failure(it) }
    val operation = arguments.string("operation")
      ?: return failure("Missing required string argument: operation.")

    return try {
      when (operation) {
        "status" -> status()
        "download_status" -> downloadStatus()
        "cancel_download" -> cancelDownload(arguments)
        "media_search" -> mediaSearch(arguments)
        "media_index" -> mediaIndex()
        "media_play" -> mediaPlay(arguments)
        "device_info" -> read(AndroidCapability.DEVICE_INFO) {
          NativeCapabilityResultFormatter.deviceInfo(adapter.getDeviceInfo())
        }
        "telephony_info" -> read(AndroidCapability.TELEPHONY_INFO) {
          Gson().toJson(adapter.getTelephonyInfo())
        }
        "battery_status" -> read(AndroidCapability.BATTERY_STATUS) {
          NativeCapabilityResultFormatter.batteryStatus(adapter.getBatteryStatus())
        }
        "volume_status" -> {
          val stream = audioStream(arguments) ?: return failure("Unsupported audio stream.")
          read(AndroidCapability.VOLUME_CONTROL) { Gson().toJson(adapter.getVolumeStatus(stream)) }
        }
        "audio_devices" -> read(AndroidCapability.AUDIO_DEVICE_INFO) {
          NativeCapabilityResultFormatter.audioDevices(adapter.getAudioDevices())
        }
        "display_brightness" -> read(AndroidCapability.DISPLAY_BRIGHTNESS) {
          NativeCapabilityResultFormatter.displayBrightness(adapter.getDisplayBrightness())
        }
        "set_display_brightness" -> {
          val level = arguments.int("level")
            ?: return failure("set_display_brightness requires integer level from 0 to 255.")
          mutate(AndroidCapability.DISPLAY_BRIGHTNESS) { adapter.setDisplayBrightness(level) }
        }
        "vibrate" -> {
          val duration = arguments.long("duration_ms")
            ?: return failure("vibrate requires integer duration_ms from 1 to 1000.")
          mutate(AndroidCapability.VIBRATION_CONTROL) { adapter.vibrate(duration) }
        }
        "wake_lock_status" -> read(AndroidCapability.WAKE_LOCK) {
          "wakeLockHeld=${adapter.isWakeLockHeld()}"
        }
        "set_wake_lock" -> {
          val held = arguments.boolean("held")
            ?: return failure("set_wake_lock requires boolean held.")
          val duration = if (held) {
            arguments.long("duration_ms")
              ?: return failure("Acquiring a wake lock requires duration_ms from 1 to 300000.")
          } else {
            0L
          }
          mutate(AndroidCapability.WAKE_LOCK) { adapter.setWakeLock(held, duration) }
        }
        "list_apps" -> read(AndroidCapability.APP_DISCOVERY) {
          NativeCapabilityResultFormatter.launchableApps(adapter.listLaunchableApps())
        }
        "set_volume" -> {
          val stream = audioStream(arguments) ?: return failure("Unsupported audio stream.")
          val volume = arguments.int("volume") ?: return failure("set_volume requires integer volume.")
          mutate(AndroidCapability.VOLUME_CONTROL) {
            adapter.setVolume(stream, volume)
          }
        }
        "set_flashlight" -> {
          val enabled = arguments.boolean("enabled")
            ?: return failure("set_flashlight requires boolean enabled.")
          mutate(AndroidCapability.FLASHLIGHT_CONTROL) { adapter.setFlashlight(enabled) }
        }
        "launch_app" -> {
          val packageName = arguments.string("package_name")
            ?: return failure("launch_app requires package_name from list_apps.")
          mutate(AndroidCapability.APP_LAUNCH) { adapter.launchApp(packageName) }
        }
        "open_url" -> {
          val url = arguments.string("url")
            ?: return failure("open_url requires url.")
          mutate(AndroidCapability.OPEN_URL) { adapter.openUrl(url) }
        }
        "dial_phone" -> {
          val phoneNumber = arguments.string("phone_number")
            ?: return failure("dial_phone requires phone_number.")
          mutate(AndroidCapability.PHONE_CALL_DIAL) { adapter.dialPhoneNumber(phoneNumber) }
        }
        "compose_sms" -> {
          val phoneNumber = arguments.string("phone_number")
            ?: return failure("compose_sms requires phone_number.")
          val message = arguments.string("sms_message")
            ?: return failure("compose_sms requires sms_message.")
          mutate(AndroidCapability.SMS_COMPOSE) { adapter.composeSms(phoneNumber, message) }
        }
        "biometric_auth" -> {
          val unavailable = availabilityFailure(AndroidCapability.BIOMETRIC_AUTH)
          if (unavailable != null) unavailable
          else {
            val reason = arguments.string("reason") ?: return failure("biometric_auth requires reason.")
            val authenticated = adapter.authenticateBiometric(reason)
            AgentToolResult("authenticated=$authenticated", authenticated)
          }
        }
        "keystore_create" -> {
          val alias = arguments.string("key_alias") ?: return failure("keystore_create requires key_alias.")
          mutate(AndroidCapability.KEYSTORE_OPERATION) { adapter.createKeystoreKey(alias) }
        }
        "keystore_list" -> {
          val unavailable = availabilityFailure(AndroidCapability.KEYSTORE_OPERATION)
          if (unavailable != null) unavailable
          else try {
            AgentToolResult(Gson().toJson(adapter.listKeystoreKeys()))
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Exception) {
            failure(error.message ?: "Keystore listing failed.")
          }
        }
        "keystore_delete" -> {
          val alias = arguments.string("key_alias") ?: return failure("keystore_delete requires key_alias.")
          mutate(AndroidCapability.KEYSTORE_OPERATION) { adapter.deleteKeystoreKey(alias) }
        }
        "keystore_encrypt" -> {
          val unavailable = availabilityFailure(AndroidCapability.KEYSTORE_OPERATION)
          if (unavailable != null) unavailable
          else {
            val alias = arguments.string("key_alias") ?: return failure("keystore_encrypt requires key_alias.")
            val plaintext = arguments.string("plaintext") ?: return failure("keystore_encrypt requires plaintext.")
            try {
              AgentToolResult(Gson().toJson(adapter.encryptKeystoreData(alias, plaintext)))
            } catch (cancelled: CancellationException) {
              throw cancelled
            } catch (error: Exception) {
              failure(error.message ?: "Keystore encryption failed.")
            }
          }
        }
        "keystore_decrypt" -> {
          val unavailable = availabilityFailure(AndroidCapability.KEYSTORE_OPERATION)
          if (unavailable != null) unavailable
          else {
            val alias = arguments.string("key_alias") ?: return failure("keystore_decrypt requires key_alias.")
            val iv = arguments.string("iv_base64") ?: return failure("keystore_decrypt requires iv_base64.")
            val ciphertext = arguments.string("ciphertext_base64") ?: return failure("keystore_decrypt requires ciphertext_base64.")
            try {
              AgentToolResult(adapter.decryptKeystoreData(alias, iv, ciphertext))
            } catch (cancelled: CancellationException) {
              throw cancelled
            } catch (error: Exception) {
              failure(error.message ?: "Keystore decryption failed.")
            }
          }
        }
        "bluetooth_bonded_devices" -> AgentToolResult(Gson().toJson(adapter.getBluetoothBondedDevices()))
        "bluetooth_gatt_services" -> {
          val address = arguments.string("device_address")
            ?: return failure("bluetooth_gatt_services requires device_address from Bluetooth discovery.")
          val timeout = if (arguments.has("timeout_ms")) {
            arguments.long("timeout_ms") ?: return failure("timeout_ms must be an integer.")
          } else DEFAULT_GATT_TIMEOUT_MS
          if (timeout !in MIN_GATT_TIMEOUT_MS..MAX_GATT_TIMEOUT_MS) {
            return failure("Bluetooth GATT timeout must be between $MIN_GATT_TIMEOUT_MS and $MAX_GATT_TIMEOUT_MS milliseconds.")
          }
          // The adapter requests BLUETOOTH_CONNECT only after the scoped approval.
          AgentToolResult(Gson().toJson(adapter.inspectBluetoothGatt(address, timeout)))
        }
        "bluetooth_gatt_read" -> {
          val address = arguments.string("device_address")
            ?: return failure("bluetooth_gatt_read requires device_address.")
          val serviceUuid = arguments.string("service_uuid")
            ?: return failure("bluetooth_gatt_read requires service_uuid.")
          val characteristicUuid = arguments.string("characteristic_uuid")
            ?: return failure("bluetooth_gatt_read requires characteristic_uuid.")
          val timeout = if (arguments.has("timeout_ms")) {
            arguments.long("timeout_ms") ?: return failure("timeout_ms must be an integer.")
          } else DEFAULT_GATT_TIMEOUT_MS
          if (timeout !in MIN_GATT_TIMEOUT_MS..MAX_GATT_TIMEOUT_MS) {
            return failure("Bluetooth GATT timeout must be between $MIN_GATT_TIMEOUT_MS and $MAX_GATT_TIMEOUT_MS milliseconds.")
          }
          AgentToolResult(Gson().toJson(
            adapter.readBluetoothGattCharacteristic(address, serviceUuid, characteristicUuid, timeout)
          ))
        }
        "bluetooth_gatt_write" -> {
          val address = arguments.string("device_address")
            ?: return failure("bluetooth_gatt_write requires device_address.")
          val serviceUuid = arguments.string("service_uuid")
            ?: return failure("bluetooth_gatt_write requires service_uuid.")
          val characteristicUuid = arguments.string("characteristic_uuid")
            ?: return failure("bluetooth_gatt_write requires characteristic_uuid.")
          val value = arguments.string("value_base64")
            ?: return failure("bluetooth_gatt_write requires value_base64.")
          val timeout = if (arguments.has("timeout_ms")) {
            arguments.long("timeout_ms") ?: return failure("timeout_ms must be an integer.")
          } else DEFAULT_GATT_TIMEOUT_MS
          if (timeout !in MIN_GATT_TIMEOUT_MS..MAX_GATT_TIMEOUT_MS) {
            return failure("Bluetooth GATT timeout must be between $MIN_GATT_TIMEOUT_MS and $MAX_GATT_TIMEOUT_MS milliseconds.")
          }
          val result = adapter.writeBluetoothGattCharacteristic(
            address, serviceUuid, characteristicUuid, value, timeout,
          )
          AgentToolResult(result.message, result.success)
        }
        "contacts_search" -> {
          val query = arguments.string("query")
            ?: return failure("contacts_search requires a non-empty query.")
          val limit = if (arguments.has("limit")) {
            arguments.int("limit") ?: return failure("limit must be an integer.")
          } else DEFAULT_CONTACT_LIMIT
          if (limit !in MIN_CONTACT_LIMIT..MAX_CONTACT_LIMIT) {
            return failure("Contact search limit must be between $MIN_CONTACT_LIMIT and $MAX_CONTACT_LIMIT.")
          }
          AgentToolResult(Gson().toJson(adapter.searchContacts(query, limit)))
        }
        "speak_text" -> {
          val text = arguments.string("speech_text") ?: return failure("speak_text requires speech_text.")
          if (text.isBlank() || text.length > MAX_SPEAK_TEXT_CHARS) {
            return failure("Speech text must contain 1–$MAX_SPEAK_TEXT_CHARS non-whitespace characters.")
          }
          val unavailable = availabilityFailure(AndroidCapability.TEXT_TO_SPEECH)
          if (unavailable != null) return unavailable
          val authorizer = speechAuthorizer ?: return failure("Speech approval is unavailable.")
          if (!authorizer.authorize(AndroidCapability.TEXT_TO_SPEECH, "Speak this exact text using Gallery's saved TTS engine and voice:\n\n$text")) {
            return failure("Speech playback was not approved.")
          }
          if (speechPlaybackRequester.speak(text)) AgentToolResult("Speech playback started using Gallery's configured TTS engine.")
          else failure("Speech playback could not be started or was cancelled.")
        }
        "clipboard_read" -> AgentToolResult(Gson().toJson(adapter.readClipboard()))
        "clipboard_write" -> {
          val text = arguments.string("text")
            ?: return failure("clipboard_write requires text.")
          if (text.length > MAX_CLIPBOARD_WRITE_CHARS) {
            return failure("Clipboard text must not exceed $MAX_CLIPBOARD_WRITE_CHARS characters.")
          }
          val result = adapter.writeClipboard(text)
          AgentToolResult(result.message, result.success)
        }
        "post_notification" -> {
          val title = arguments.string("title") ?: return failure("post_notification requires title.")
          val message = arguments.string("message") ?: return failure("post_notification requires message.")
          val channelId = arguments.string("channel_id") ?: DEFAULT_NOTIFICATION_CHANNEL_ID
          val result = adapter.postNotification(title, message, channelId)
          AgentToolResult(result.message, result.success)
        }
        "read_notifications" -> {
          val unavailable = availabilityFailure(AndroidCapability.NOTIFICATION_READ)
          if (unavailable != null) return unavailable
          AgentToolResult(Gson().toJson(adapter.readOtherNotifications()))
        }
        "read_call_log" ->
          AgentToolResult(Gson().toJson(adapter.readCallLog()))
        "read_sms_inbox" ->
          AgentToolResult(Gson().toJson(adapter.readSmsInbox()))
        "list_posted_notifications" ->
          AgentToolResult(Gson().toJson(adapter.listPostedNotifications()))
        "cancel_posted_notification" -> {
          val id = arguments.string("notification_id")
            ?: return failure("cancel_posted_notification requires notification_id from list_posted_notifications.")
          val result = adapter.cancelPostedNotification(id)
          AgentToolResult(result.message, result.success)
        }
        "schedule_notification" -> {
          val title = arguments.string("title") ?: return failure("schedule_notification requires title.")
          val message = arguments.string("message") ?: return failure("schedule_notification requires message.")
          val hour = arguments.int("hour") ?: return failure("schedule_notification requires integer hour.")
          val minute = arguments.int("minute") ?: return failure("schedule_notification requires integer minute.")
          val result = adapter.scheduleNotification(
            title, message, hour, minute,
            if (arguments.has("year")) arguments.int("year") else null,
            if (arguments.has("month")) arguments.int("month") else null,
            if (arguments.has("day")) arguments.int("day") else null,
            arguments.boolean("repeat_daily") ?: false,
          )
          AgentToolResult(result.message, result.success)
        }
        "list_scheduled_notifications" ->
          AgentToolResult(Gson().toJson(adapter.listScheduledNotifications()))
        "cancel_scheduled_notification" -> {
          val id = arguments.string("notification_id")
            ?: return failure("cancel_scheduled_notification requires notification_id.")
          val result = adapter.cancelScheduledNotification(id)
          AgentToolResult(result.message, result.success)
        }
        "list_notification_channels" ->
          AgentToolResult(Gson().toJson(adapter.listNotificationChannels()))
        "create_notification_channel" -> {
          val id = arguments.string("channel_id") ?: return failure("create_notification_channel requires channel_id.")
          val name = arguments.string("channel_name") ?: return failure("create_notification_channel requires channel_name.")
          val importance = arguments.int("importance") ?: return failure("create_notification_channel requires integer importance.")
          val result = adapter.createNotificationChannel(id, name, importance)
          AgentToolResult(result.message, result.success)
        }
        "bluetooth_le_scan" -> {
          val duration = if (arguments.has("duration_ms")) {
            arguments.long("duration_ms") ?: return failure("duration_ms must be an integer.")
          } else DEFAULT_BLUETOOTH_SCAN_MS
          if (duration !in MIN_BLUETOOTH_SCAN_MS..MAX_BLUETOOTH_SCAN_MS) {
            return failure("Bluetooth LE scan duration must be between $MIN_BLUETOOTH_SCAN_MS and $MAX_BLUETOOTH_SCAN_MS milliseconds.")
          }
          AgentToolResult(Gson().toJson(adapter.scanBluetoothLe(duration)))
        }
        "usb_devices" -> AgentToolResult(Gson().toJson(adapter.getUsbDevices()))
        "usb_read_descriptors" -> {
          val handle = arguments.string("usb_handle")
            ?: return failure("usb_read_descriptors requires usb_handle from the latest usb_devices result.")
          AgentToolResult(Gson().toJson(adapter.readUsbDescriptors(handle)))
        }
        "record_audio" -> {
          val duration = if (arguments.has("duration_ms")) {
            arguments.long("duration_ms") ?: return failure("duration_ms must be an integer.")
          } else DEFAULT_AUDIO_RECORD_MS
          if (duration !in MIN_AUDIO_RECORD_MS..MAX_AUDIO_RECORD_MS) {
            return failure("Audio recording duration must be between $MIN_AUDIO_RECORD_MS and $MAX_AUDIO_RECORD_MS milliseconds.")
          }
          AgentToolResult(adapter.recordAudio(duration))
        }
        "capture_photo" -> {
          val unavailable = availabilityFailure(AndroidCapability.CAMERA_CAPTURE)
          if (unavailable != null) return unavailable
          AgentToolResult(adapter.capturePhoto())
        }
        "set_wallpaper" -> {
          val unavailable = availabilityFailure(AndroidCapability.WALLPAPER_CONTROL)
          if (unavailable != null) return unavailable
          val uri = arguments.string("content_uri")
            ?: return failure("set_wallpaper requires content_uri from a Gallery-readable image.")
          val target = arguments.string("target") ?: "SYSTEM"
          val result = adapter.setWallpaper(uri, target)
          AgentToolResult(result.message, result.success)
        }
        else -> failure("Unsupported operation '$operation'.")
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      failure(error.message ?: error.javaClass.simpleName)
    }
  }

  private suspend fun mediaSearch(arguments: JsonObject): AgentToolResult {
    val operations = mediaOperations ?: return failure("Gallery media search is unavailable.")
    val unavailable = availabilityFailure(AndroidCapability.MEDIA_SCAN)
    if (unavailable != null) return unavailable
    val query = arguments.string("query") ?: return failure("media_search requires query.")
    val mediaKind = arguments.string("media_kind") ?: "ANY"
    val mode = arguments.string("search_mode") ?: "AUTO"
    val limit = if (arguments.has("limit")) {
      arguments.int("limit") ?: return failure("media_search limit must be an integer.")
    } else GalleryMediaLibraryOperations.DEFAULT_RESULT_LIMIT
    return try {
      AgentToolResult(Gson().toJson(operations.search(query, mediaKind, mode, limit)))
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      failure(error.message ?: "Media search failed.")
    }
  }

  private suspend fun mediaPlay(arguments: JsonObject): AgentToolResult {
    val operations = mediaOperations ?: return failure("Gallery media playback is unavailable.")
    val requester = mediaPlaybackRequester ?: return failure("Gallery media playback UI is unavailable.")
    val unavailable = availabilityFailure(AndroidCapability.MEDIA_PLAYBACK)
    if (unavailable != null) return unavailable
    val handle = arguments.string("media_handle")
      ?: return failure("media_play requires media_handle from the latest media_search result.")
    val media = operations.resolvePlaybackHandle(handle)
      ?: return failure("That media handle is unknown or expired. Run media_search again.")
    if (!media.isVideo) return failure("Gallery playback currently supports video results only.")
    val authorizer = mediaPlaybackAuthorizer
      ?: return failure("Media playback approval is unavailable.")
    if (!authorizer.authorize(AndroidCapability.MEDIA_PLAYBACK, "Play '${media.displayName}' using Gallery's built-in media player.")) {
      return failure("Media playback was not approved.")
    }
    if (!operations.consumePlaybackHandle(handle)) {
      return failure("That media handle expired while awaiting approval. Run media_search again.")
    }
    return if (requester.play(media.uri.toString(), media.displayName)) {
      AgentToolResult("Gallery playback opened for '${media.displayName}'.")
    } else {
      failure("Gallery could not start playback for '${media.displayName}'.")
    }
  }
  private suspend fun mediaIndex(): AgentToolResult {
    val operations = mediaOperations ?: return failure("Gallery media indexing is unavailable.")
    val unavailable = availabilityFailure(AndroidCapability.MEDIA_SCAN)
    if (unavailable != null) return unavailable
    return try {
      AgentToolResult(operations.startIndexing())
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      failure(error.message ?: "Media indexing could not be started.")
    }
  }
  private suspend fun downloadStatus(): AgentToolResult {
    val operations = downloadOperations
      ?: return failure("Gallery download management is unavailable.")
    val snapshots = suspendCancellableCoroutine { continuation ->
      operations.getAgentDownloadSnapshots { result ->
        if (continuation.isActive) continuation.resume(result)
      }
    }
    issuedDownloadSnapshots.clear()
    snapshots.forEach { issuedDownloadSnapshots[it.handle] = it }
    return AgentToolResult(formatDownloadSnapshots(snapshots))
  }

  private suspend fun cancelDownload(arguments: JsonObject): AgentToolResult {
    val operations = downloadOperations
      ?: return failure("Gallery download management is unavailable.")
    val handle = arguments.string("download_handle")
      ?: return failure("cancel_download requires download_handle from download_status.")
    val snapshot = issuedDownloadSnapshots[handle]
      ?: return failure("That download handle is unknown or expired. Run download_status again.")
    val authorizer = downloadAuthorizer
      ?: return failure("Download cancellation approval is unavailable.")
    val description = if (snapshot.extraDataOnly) "optional model files" else "model files"
    val approvalSummary = buildString {
      appendLine("Cancel this exact Gallery download?")
      appendLine("Model: ${snapshot.modelLabel}")
      appendLine("Content: $description")
      appendLine("Phase: ${snapshot.phase}")
      appendLine("Download reference: $handle")
      if (snapshot.totalBytes > 0L) {
        append("Progress: ${snapshot.receivedBytes} of ${snapshot.totalBytes} bytes")
      }
    }
    if (!authorizer.authorize(AndroidCapability.DOWNLOAD_MANAGEMENT, approvalSummary)) {
      return failure("Download cancellation was not approved.")
    }
    val cancelled = suspendCancellableCoroutine { continuation ->
      operations.cancelAgentDownload(handle) { result ->
        if (continuation.isActive) continuation.resume(result)
      }
    }
    issuedDownloadSnapshots.remove(handle)
    return if (cancelled) {
      AgentToolResult("Cancellation requested for ${snapshot.modelLabel}.")
    } else {
      failure("The selected download is no longer active or its handle expired. Run download_status again.")
    }
  }

  private fun formatDownloadSnapshots(snapshots: List<AgentDownloadSnapshot>): String {
    if (snapshots.isEmpty()) return "No active Gallery model downloads."
    return buildString {
      appendLine("returned=${snapshots.size}")
      appendLine("limit=20")
      appendLine("truncated=${if (snapshots.size == 20) "unknown" else "false"}")
      if (snapshots.size == 20) {
        appendLine("truncation_note=Additional active downloads may exist.")
      }
      snapshots.forEachIndexed { index, snapshot ->
        appendLine("download=${index + 1}")
        appendLine("download_handle=${snapshot.handle}")
        appendLine("model=${snapshot.modelLabel}")
        appendLine("content=${if (snapshot.extraDataOnly) "optional_model_files" else "model_files"}")
        appendLine("phase=${snapshot.phase}")
        appendLine("received_bytes=${snapshot.receivedBytes}")
        appendLine("total_bytes=${snapshot.totalBytes}")
        snapshot.bytesPerSecond?.let { appendLine("bytes_per_second=$it") }
        snapshot.remainingMs?.let { appendLine("remaining_ms=$it") }
      }
    }.trimEnd()
  }

  private suspend fun status(): AgentToolResult {
    // Provider discovery is deliberately distinct from reading device state. Download
    // availability reflects whether the existing Gallery repository was wired into this tool.
    val observation = adapter.inspect()
    val downloadAvailability =
      if (downloadOperations != null) ProviderAvailability.AVAILABLE
      else ProviderAvailability.UNSUPPORTED
    return AgentToolResult(
      NativeCapabilityResultFormatter.providerAvailability(
        observation.copy(
          capabilityAvailability =
            observation.capabilityAvailability +
              (AndroidCapability.DOWNLOAD_MANAGEMENT to downloadAvailability) +
              (AndroidCapability.MEDIA_SCAN to if (mediaOperations != null) ProviderAvailability.AVAILABLE else ProviderAvailability.UNSUPPORTED) +
              (AndroidCapability.MEDIA_PLAYBACK to if (mediaOperations != null && mediaPlaybackRequester != null) ProviderAvailability.AVAILABLE else ProviderAvailability.UNSUPPORTED),
        )
      )
    )
  }

  private suspend fun read(
    capability: AndroidCapability,
    block: () -> String,
  ): AgentToolResult {
    val unavailable = availabilityFailure(capability) ?: return AgentToolResult(block())
    return unavailable
  }

  private suspend fun mutate(
    capability: AndroidCapability,
    block: suspend () -> NativeCapabilityActionResult,
  ): AgentToolResult {
    val unavailable = availabilityFailure(capability)
    if (unavailable != null) return unavailable
    val result = block()
    return AgentToolResult(result.message, result.success)
  }

  private suspend fun availabilityFailure(capability: AndroidCapability): AgentToolResult? {
    val observation = adapter.inspect()
    val capabilityAvailability = observation.capabilityAvailability +
      (AndroidCapability.MEDIA_SCAN to if (mediaOperations != null) ProviderAvailability.AVAILABLE else ProviderAvailability.UNSUPPORTED) +
      (AndroidCapability.MEDIA_PLAYBACK to if (mediaOperations != null && mediaPlaybackRequester != null) ProviderAvailability.AVAILABLE else ProviderAvailability.UNSUPPORTED)
    val routedObservation = observation.copy(capabilityAvailability = capabilityAvailability)
    val resolution = registry.resolve(capability, listOf(routedObservation))
    if (resolution.selectedProviderId == CapabilityProviderId.ANDROID_NATIVE) return null
    val detail = resolution.candidates.firstOrNull()?.detail
    return failure(
      detail ?: "Native capability ${capability.name} is unavailable on this device or lacks permission."
    )
  }

  private fun audioStream(arguments: JsonObject): NativeAudioStream? {
    if (!arguments.has("stream")) return NativeAudioStream.MUSIC
    val value = arguments.string("stream") ?: return null
    return NativeAudioStream.fromName(value)
  }

  private fun failure(message: String) = AgentToolResult(message, false)

  private fun JsonObject.string(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

  private fun JsonObject.int(name: String): Int? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
      ?.asString?.toIntOrNull()

  private fun JsonObject.long(name: String): Long? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
      ?.asString?.toLongOrNull()

  private fun JsonObject.boolean(name: String): Boolean? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

  private companion object {
    const val DEFAULT_BLUETOOTH_SCAN_MS = 5_000L
    const val DEFAULT_AUDIO_RECORD_MS = 5_000L
    const val DEFAULT_GATT_TIMEOUT_MS = 15_000L
    const val DEFAULT_CONTACT_LIMIT = 10
    const val MIN_CONTACT_LIMIT = 1
    const val MAX_CONTACT_LIMIT = 20
    const val MAX_CLIPBOARD_WRITE_CHARS = 2_048
    const val MAX_SPEAK_TEXT_CHARS = 1_000
    const val MAX_NOTIFICATION_TITLE_CHARS = 100
    const val MAX_NOTIFICATION_MESSAGE_CHARS = 1_000
    const val MAX_NOTIFICATION_CHANNEL_NAME_CHARS = 80
    const val DEFAULT_NOTIFICATION_CHANNEL_ID = "agent_skill_tasks_channel"
    const val MIN_GATT_TIMEOUT_MS = 5_000L
    const val MAX_GATT_TIMEOUT_MS = 20_000L
    const val MIN_AUDIO_RECORD_MS = 1_000L
    const val MAX_AUDIO_RECORD_MS = 30_000L
    const val MIN_BLUETOOTH_SCAN_MS = 1_000L
    const val MAX_BLUETOOTH_SCAN_MS = 10_000L
  }
}
