/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is an independent Android framework adapter. It does not copy implementation code
 * from third-party projects.
 */

package com.google.ai.edge.gallery.agent.capability

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.MediaRecorder
import android.media.AudioDeviceInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.Build
import android.os.PowerManager
import android.os.ParcelFileDescriptor
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.telephony.TelephonyManager
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import dagger.hilt.android.EntryPointAccessors
import com.google.ai.edge.gallery.notifications.NotificationScheduleManagerEntryPoint
import com.google.ai.edge.gallery.proto.ScheduledNotification
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
/** Small, privacy-conscious device facts suitable for a local agent. */
data class NativeDeviceInfo(
  val manufacturer: String,
  val brand: String,
  val model: String,
  val device: String,
  val androidRelease: String,
  val sdkInt: Int,
  val securityPatch: String?,
)

data class NativeBatteryStatus(
  val percent: Int?,
  /** Stable, human-readable BatteryManager status name; never expose the raw Android integer. */
  val state: String,
  val charging: Boolean?,
  val plugged: String?,
  val temperatureCelsius: Float?,
  val voltageMillivolts: Int?,
)

enum class NativeAudioStream(val androidValue: Int) {
  VOICE_CALL(AudioManager.STREAM_VOICE_CALL),
  SYSTEM(AudioManager.STREAM_SYSTEM),
  RING(AudioManager.STREAM_RING),
  MUSIC(AudioManager.STREAM_MUSIC),
  ALARM(AudioManager.STREAM_ALARM),
  NOTIFICATION(AudioManager.STREAM_NOTIFICATION);

  companion object {
    fun fromName(value: String): NativeAudioStream? =
      values().firstOrNull { it.name.equals(value, ignoreCase = true) }
  }
}

data class NativeVolumeStatus(
  val stream: NativeAudioStream,
  val current: Int,
  val maximum: Int,
)

data class NativeAudioDevice(
  val id: Int,
  val type: String,
  val productName: String,
  val source: Boolean,
  val sink: Boolean,
)

data class NativeDisplayBrightness(
  val level: Int?,
  val maximum: Int = 255,
  val mode: String,
  val canModifySystemSettings: Boolean,
)

data class NativeLaunchableApp(
  val packageName: String,
  val label: String,
)

data class NativeCapabilityActionResult(
  val success: Boolean,
  val message: String,
)

data class NativeBluetoothDevice(
  val address: String,
  val name: String,
  val type: String,
  val bondState: String,
  val rssi: Int? = null,
  val serviceUuids: List<String> = emptyList(),
)

data class NativeUsbInterface(
  val id: Int,
  val classCode: Int,
  val subclassCode: Int,
  val protocolCode: Int,
  val endpointCount: Int,
)

data class NativeUsbDevice(
  val deviceId: Int,
  val vendorId: Int,
  val productId: Int,
  val classCode: Int,
  val subclassCode: Int,
  val protocolCode: Int,
  val interfaces: List<NativeUsbInterface>,
)

fun interface NativeRuntimePermissionRequester {
  suspend fun request(permission: String): Boolean
}

class ChannelNativeRuntimePermissionRequester(
  private val actionChannel: kotlinx.coroutines.channels.SendChannel<com.google.ai.edge.gallery.tools.ToolAction>,
  private val timeoutMs: Long = 120_000L,
) : NativeRuntimePermissionRequester {
  override suspend fun request(permission: String): Boolean {
    val action = com.google.ai.edge.gallery.tools.RequestPermissionToolAction(permission)
    return try {
      kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        actionChannel.send(action)
        action.result.await()
      } ?: false
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      false
    } finally {
      if (!action.result.isCompleted) action.result.complete(false)
    }
  }
}

fun interface NativePhotoCaptureRequester {
  suspend fun capture(contentUri: String): Boolean
}

class ChannelNativePhotoCaptureRequester(
  private val actionChannel: kotlinx.coroutines.channels.SendChannel<com.google.ai.edge.gallery.tools.ToolAction>,
  private val timeoutMs: Long = 120_000L,
) : NativePhotoCaptureRequester {
  override suspend fun capture(contentUri: String): Boolean {
    val action = com.google.ai.edge.gallery.tools.CapturePhotoToolAction(contentUri)
    return try {
      kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        actionChannel.send(action)
        action.result.await()
      } ?: false
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      false
    } finally {
      if (!action.result.isCompleted) action.result.complete(false)
    }
  }
}

/**
 * The caller must connect this to the app's existing user-approval boundary. There is deliberately
 * no permissive default: device mutations fail closed unless the caller supplies authorization.
 */
fun interface NativeCapabilityAuthorizer {
  suspend fun authorize(capability: AndroidCapability, summary: String): Boolean
}

private sealed class BluetoothGattEvent {
  data class ConnectionState(val status: Int, val newState: Int) : BluetoothGattEvent()
  data class ServicesDiscovered(val status: Int) : BluetoothGattEvent()
  data class CharacteristicRead(val uuid: String, val value: ByteArray, val status: Int) : BluetoothGattEvent()
  data class CharacteristicWrite(val uuid: String, val status: Int) : BluetoothGattEvent()
}

private data class BluetoothGattSession(
  val gatt: BluetoothGatt,
  val services: List<BluetoothGattService>,
  val events: Channel<BluetoothGattEvent>,
)

fun interface NativeBiometricAuthenticator {
  suspend fun authenticate(
    title: String,
    subtitle: String,
    cryptoObject: BiometricPrompt.CryptoObject?,
  ): BiometricPrompt.AuthenticationResult?
}

/**
 * Typed Android framework operations. Discovery is observational; mutating methods require an
 * injected authorizer and validate their inputs before touching Android system services.
 *
 * Runtime permissions are requested only through the injected UI permission requester, and only
 * after the operation's scoped approval. Provider inspection never requests permissions.
 */
class AndroidNativeCapabilityAdapter(
  context: Context,
  private val authorizer: NativeCapabilityAuthorizer,
  private val permissionRequester: NativeRuntimePermissionRequester = NativeRuntimePermissionRequester { false },
  private val photoCaptureRequester: NativePhotoCaptureRequester = NativePhotoCaptureRequester { false },
  private val biometricAuthenticator: NativeBiometricAuthenticator = NativeBiometricAuthenticator { _, _, _ -> null },
) : NativeCapabilityOperations {
  private val appContext = context.applicationContext
  private val wakeLockGuard = Any()
  private var agentWakeLock: PowerManager.WakeLock? = null

  override val descriptor: CapabilityProviderDescriptor =
    CapabilityCatalog.providers.first { it.id == CapabilityProviderId.ANDROID_NATIVE }

  @SuppressLint("MissingPermission")
  override suspend fun inspect(): CapabilityProviderObservation {
    val capabilities = descriptor.capabilities.associateWith { ProviderAvailability.UNSUPPORTED }
      .toMutableMap()
    val details = mutableMapOf<AndroidCapability, String>()

    capabilities[AndroidCapability.DEVICE_INFO] = ProviderAvailability.AVAILABLE
    capabilities[AndroidCapability.BATTERY_STATUS] =
      if (appContext.getSystemService(BatteryManager::class.java) != null) {
        ProviderAvailability.AVAILABLE
      } else {
        details[AndroidCapability.BATTERY_STATUS] = "BatteryManager is unavailable."
        ProviderAvailability.UNSUPPORTED
      }

    capabilities[AndroidCapability.VOLUME_CONTROL] =
      if (appContext.getSystemService(AudioManager::class.java) != null) {
        ProviderAvailability.AVAILABLE
      } else {
        details[AndroidCapability.VOLUME_CONTROL] = "AudioManager is unavailable."
        ProviderAvailability.UNSUPPORTED
      }

    capabilities[AndroidCapability.AUDIO_DEVICE_INFO] =
      if (appContext.getSystemService(AudioManager::class.java) != null) {
        ProviderAvailability.AVAILABLE
      } else {
        details[AndroidCapability.AUDIO_DEVICE_INFO] = "AudioManager is unavailable."
        ProviderAvailability.UNSUPPORTED
      }

    capabilities[AndroidCapability.DISPLAY_BRIGHTNESS] =
      if (runCatching { Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.isSuccess) {
        ProviderAvailability.AVAILABLE
      } else {
        details[AndroidCapability.DISPLAY_BRIGHTNESS] = "System brightness settings are unavailable."
        ProviderAvailability.UNSUPPORTED
      }

    capabilities[AndroidCapability.VIBRATION_CONTROL] =
      if (appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator?.hasVibrator() == true) {
        ProviderAvailability.AVAILABLE
      } else {
        details[AndroidCapability.VIBRATION_CONTROL] = "No system vibrator is available."
        ProviderAvailability.UNSUPPORTED
      }

    capabilities[AndroidCapability.WAKE_LOCK] =
      if (appContext.getSystemService(PowerManager::class.java) != null) {
        ProviderAvailability.AVAILABLE
      } else {
        details[AndroidCapability.WAKE_LOCK] = "PowerManager is unavailable."
        ProviderAvailability.UNSUPPORTED
      }

    val bluetoothAdapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
    capabilities[AndroidCapability.BLUETOOTH_DISCOVERY] = when {
      bluetoothAdapter == null -> {
        details[AndroidCapability.BLUETOOTH_DISCOVERY] = "Bluetooth adapter is unavailable."
        ProviderAvailability.UNSUPPORTED
      }
      !hasRuntimePermission(Manifest.permission.BLUETOOTH_CONNECT) -> {
        details[AndroidCapability.BLUETOOTH_DISCOVERY] =
          "Nearby devices connect permission is required; discovery did not request it."
        ProviderAvailability.PERMISSION_REQUIRED
      }
      !bluetoothAdapter.isEnabled -> {
        details[AndroidCapability.BLUETOOTH_DISCOVERY] = "Bluetooth is turned off."
        ProviderAvailability.SERVICE_STOPPED
      }
      else -> ProviderAvailability.AVAILABLE
    }
    capabilities[AndroidCapability.BLUETOOTH_LE_SCAN] = when {
      bluetoothAdapter == null -> ProviderAvailability.UNSUPPORTED
      !hasRuntimePermission(Manifest.permission.BLUETOOTH_SCAN) ||
        !hasRuntimePermission(Manifest.permission.BLUETOOTH_CONNECT) -> {
        details[AndroidCapability.BLUETOOTH_LE_SCAN] =
          "Nearby devices scan/connect permissions are required; discovery did not request them."
        ProviderAvailability.PERMISSION_REQUIRED
      }
      !bluetoothAdapter.isEnabled -> {
        details[AndroidCapability.BLUETOOTH_LE_SCAN] = "Bluetooth is turned off."
        ProviderAvailability.SERVICE_STOPPED
      }
      else -> ProviderAvailability.AVAILABLE
    }
    val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
    capabilities[AndroidCapability.MICROPHONE_RECORD] =
      if (hasRuntimePermission(Manifest.permission.RECORD_AUDIO)) ProviderAvailability.AVAILABLE
      else {
        details[AndroidCapability.MICROPHONE_RECORD] =
          "Microphone permission is required; provider discovery did not request it."
        ProviderAvailability.PERMISSION_REQUIRED
      }
    capabilities[AndroidCapability.CAMERA_CAPTURE] =
      if (cameraIntent.resolveActivity(appContext.packageManager) != null) ProviderAvailability.AVAILABLE
      else {
        details[AndroidCapability.CAMERA_CAPTURE] = "No camera capture activity is available."
        ProviderAvailability.UNSUPPORTED
      }
    capabilities[AndroidCapability.WALLPAPER_CONTROL] =
      if (appContext.getSystemService(WallpaperManager::class.java)?.isWallpaperSupported == true) {
        ProviderAvailability.AVAILABLE
      } else {
        details[AndroidCapability.WALLPAPER_CONTROL] = "Wallpaper setting is unavailable on this device."
        ProviderAvailability.UNSUPPORTED
      }

    capabilities[AndroidCapability.BLUETOOTH_GATT] = when {
      bluetoothAdapter == null -> {
        details[AndroidCapability.BLUETOOTH_GATT] = "Bluetooth adapter is unavailable."
        ProviderAvailability.UNSUPPORTED
      }
      !hasRuntimePermission(Manifest.permission.BLUETOOTH_CONNECT) -> {
        details[AndroidCapability.BLUETOOTH_GATT] =
          "Nearby devices connect permission is required; discovery did not request it."
        ProviderAvailability.PERMISSION_REQUIRED
      }
      !bluetoothAdapter.isEnabled -> {
        details[AndroidCapability.BLUETOOTH_GATT] = "Bluetooth is turned off."
        ProviderAvailability.SERVICE_STOPPED
      }
      else -> ProviderAvailability.AVAILABLE
    }

    val usbManager = appContext.getSystemService(UsbManager::class.java)
    val usbHostSupported = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
    capabilities[AndroidCapability.USB_DEVICE_DISCOVERY] =
      if (usbManager != null && usbHostSupported) ProviderAvailability.AVAILABLE
      else {
        details[AndroidCapability.USB_DEVICE_DISCOVERY] = "USB host mode is unavailable."
        ProviderAvailability.UNSUPPORTED
      }
    capabilities[AndroidCapability.USB_DEVICE_IO] = ProviderAvailability.UNSUPPORTED
    details[AndroidCapability.USB_DEVICE_IO] =
      "USB permission, connection, and I/O sessions are not implemented in this onboarding phase."

    capabilities[AndroidCapability.FLASHLIGHT_CONTROL] = flashlightAvailability()
      .also { availability ->
        if (availability == ProviderAvailability.PERMISSION_REQUIRED) {
          details[AndroidCapability.FLASHLIGHT_CONTROL] =
            "Camera permission is required; discovery did not request it."
        } else if (availability == ProviderAvailability.UNSUPPORTED) {
          details[AndroidCapability.FLASHLIGHT_CONTROL] =
            "No camera with a controllable flash was found."
        }
      }

    val launchableApps = queryLaunchableApps()
    capabilities[AndroidCapability.APP_DISCOVERY] =
      if (launchableApps.isNotEmpty()) ProviderAvailability.AVAILABLE
      else ProviderAvailability.UNSUPPORTED
    capabilities[AndroidCapability.APP_LAUNCH] =
      if (launchableApps.isNotEmpty()) ProviderAvailability.AVAILABLE
      else ProviderAvailability.UNSUPPORTED
    if (launchableApps.isEmpty()) {
      details[AndroidCapability.APP_DISCOVERY] = "No launcher activities are visible to this app."
      details[AndroidCapability.APP_LAUNCH] = "No launchable applications are visible to this app."
    }

    capabilities[AndroidCapability.OPEN_URL] =
      if (canResolveWebUrl()) ProviderAvailability.AVAILABLE
      else ProviderAvailability.UNSUPPORTED
    if (capabilities[AndroidCapability.OPEN_URL] == ProviderAvailability.UNSUPPORTED) {
      details[AndroidCapability.OPEN_URL] = "No application can handle an HTTPS link."
    }

    val clipboardManager = appContext.getSystemService(ClipboardManager::class.java)
    capabilities[AndroidCapability.CLIPBOARD_READ] = when {
      clipboardManager == null -> {
        details[AndroidCapability.CLIPBOARD_READ] = "ClipboardManager is unavailable."
        ProviderAvailability.UNSUPPORTED
      }
      !isAppForeground() -> {
        details[AndroidCapability.CLIPBOARD_READ] =
          "Clipboard reads require Gallery to be in the foreground on modern Android."
        ProviderAvailability.SERVICE_STOPPED
      }
      else -> ProviderAvailability.AVAILABLE
    }
    capabilities[AndroidCapability.CLIPBOARD_WRITE] = when {
      clipboardManager == null -> {
        details[AndroidCapability.CLIPBOARD_WRITE] = "ClipboardManager is unavailable."
        ProviderAvailability.UNSUPPORTED
      }
      !isAppForeground() -> {
        details[AndroidCapability.CLIPBOARD_WRITE] = "Clipboard writes require Gallery to be in the foreground."
        ProviderAvailability.SERVICE_STOPPED
      }
      else -> ProviderAvailability.AVAILABLE
    }
    capabilities[AndroidCapability.CONTACT_READ] =
      if (hasRuntimePermission(Manifest.permission.READ_CONTACTS)) ProviderAvailability.AVAILABLE else {
        details[AndroidCapability.CONTACT_READ] =
          "Contacts permission is required; provider discovery did not request it."
        ProviderAvailability.PERMISSION_REQUIRED
      }

    val telephonyManager = appContext.getSystemService(TelephonyManager::class.java)
    val hasTelephony = telephonyManager != null &&
      appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    capabilities[AndroidCapability.TELEPHONY_INFO] =
      if (hasTelephony) ProviderAvailability.AVAILABLE else {
        details[AndroidCapability.TELEPHONY_INFO] = "This device does not expose a telephony service."
        ProviderAvailability.UNSUPPORTED
      }
    val dialIntent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", "123", null))
    capabilities[AndroidCapability.PHONE_CALL_DIAL] =
      if (dialIntent.resolveActivity(appContext.packageManager) != null) ProviderAvailability.AVAILABLE else {
        details[AndroidCapability.PHONE_CALL_DIAL] = "No dialer activity can handle telephone numbers."
        ProviderAvailability.UNSUPPORTED
      }
    val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", "123", null))
    capabilities[AndroidCapability.SMS_COMPOSE] =
      if (smsIntent.resolveActivity(appContext.packageManager) != null) ProviderAvailability.AVAILABLE else {
        details[AndroidCapability.SMS_COMPOSE] = "No SMS composer can handle smsto links."
        ProviderAvailability.UNSUPPORTED
      }
    val biometricStatus = BiometricManager.from(appContext)
      .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    capabilities[AndroidCapability.BIOMETRIC_AUTH] =
      if (biometricStatus == BiometricManager.BIOMETRIC_SUCCESS) ProviderAvailability.AVAILABLE
      else {
        details[AndroidCapability.BIOMETRIC_AUTH] = when (biometricStatus) {
          BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "No strong biometric is enrolled. Enroll a fingerprint or other strong biometric in Android settings."
          BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "This device has no strong biometric hardware."
          BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Strong biometric hardware is temporarily unavailable."
          else -> "Strong biometric authentication is unavailable (status $biometricStatus)."
        }
        if (biometricStatus == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) ProviderAvailability.PERMISSION_REQUIRED
        else ProviderAvailability.UNSUPPORTED
      }
    capabilities[AndroidCapability.KEYSTORE_OPERATION] =
      if (runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }.isSuccess) {
        ProviderAvailability.AVAILABLE
      } else {
        details[AndroidCapability.KEYSTORE_OPERATION] = "Android Keystore is unavailable."
        ProviderAvailability.UNSUPPORTED
      }

    capabilities[AndroidCapability.CALL_LOG_READ] = when {
      !hasTelephony -> {
        details[AndroidCapability.CALL_LOG_READ] = "This device does not expose telephony call history."
        ProviderAvailability.UNSUPPORTED
      }
      !hasRuntimePermission(Manifest.permission.READ_CALL_LOG) -> {
        details[AndroidCapability.CALL_LOG_READ] =
          "READ_CALL_LOG permission is required; discovery did not request it. Android or the installer may restrict eligibility."
        ProviderAvailability.PERMISSION_REQUIRED
      }
      else -> ProviderAvailability.AVAILABLE
    }
    capabilities[AndroidCapability.SMS_READ] = when {
      !hasTelephony -> {
        details[AndroidCapability.SMS_READ] = "This device does not expose telephony SMS inbox access."
        ProviderAvailability.UNSUPPORTED
      }
      !hasRuntimePermission(Manifest.permission.READ_SMS) -> {
        details[AndroidCapability.SMS_READ] =
          "READ_SMS permission is required; discovery did not request it. Android/installer or default-role eligibility may restrict access."
        ProviderAvailability.PERMISSION_REQUIRED
      }
      else -> ProviderAvailability.AVAILABLE
    }

    val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    val notificationsEnabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    val notificationPermissionGranted =
      Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        hasRuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
    val notificationAvailability = when {
      notificationManager == null -> ProviderAvailability.UNSUPPORTED
      !notificationPermissionGranted -> ProviderAvailability.PERMISSION_REQUIRED
      !notificationsEnabled -> ProviderAvailability.SERVICE_STOPPED
      else -> ProviderAvailability.AVAILABLE
    }
    capabilities[AndroidCapability.NOTIFICATION_POST] = notificationAvailability
    capabilities[AndroidCapability.BACKGROUND_SCHEDULE] = when {
      notificationManager == null || appContext.getSystemService(AlarmManager::class.java) == null ->
        ProviderAvailability.UNSUPPORTED
      else -> notificationAvailability
    }
    if (notificationAvailability != ProviderAvailability.AVAILABLE) {
      val reason = when (notificationAvailability) {
        ProviderAvailability.PERMISSION_REQUIRED -> "POST_NOTIFICATIONS permission is required; discovery did not request it."
        ProviderAvailability.SERVICE_STOPPED -> "Notifications are disabled for Gallery in Android settings."
        else -> "Android NotificationManager is unavailable."
      }
      details[AndroidCapability.NOTIFICATION_POST] = reason
      details[AndroidCapability.BACKGROUND_SCHEDULE] = reason
    }
    capabilities[AndroidCapability.NOTIFICATION_CHANNEL_MANAGEMENT] =
      if (notificationManager != null) ProviderAvailability.AVAILABLE else ProviderAvailability.UNSUPPORTED
    val notificationListenerComponent =
      ComponentName(appContext, GalleryMediaSessionListenerService::class.java)
    val notificationListenerGranted =
      notificationManager?.isNotificationListenerAccessGranted(notificationListenerComponent) == true
    capabilities[AndroidCapability.NOTIFICATION_READ] = when {
      notificationManager == null -> {
        details[AndroidCapability.NOTIFICATION_READ] = "Android NotificationManager is unavailable."
        ProviderAvailability.UNSUPPORTED
      }
      !notificationListenerGranted -> {
        details[AndroidCapability.NOTIFICATION_READ] =
          "Notification-listener special access is required; provider discovery did not open settings or read notification content."
        ProviderAvailability.PERMISSION_REQUIRED
      }
      !GalleryMediaSessionListenerService.isConnected() -> {
        details[AndroidCapability.NOTIFICATION_READ] =
          "Notification-listener access is enabled but Gallery's listener is not connected."
        ProviderAvailability.SERVICE_STOPPED
      }
      else -> ProviderAvailability.AVAILABLE
    }

    val textToSpeechAvailable = runCatching {
      appContext.packageManager.queryIntentServices(
        Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
        PackageManager.MATCH_DEFAULT_ONLY,
      ).isNotEmpty()
    }.getOrDefault(false)
    capabilities[AndroidCapability.TEXT_TO_SPEECH] =
      if (textToSpeechAvailable) ProviderAvailability.AVAILABLE else {
        details[AndroidCapability.TEXT_TO_SPEECH] = "No Android text-to-speech engine service is available."
        ProviderAvailability.UNSUPPORTED
      }

    val speechRecognitionAvailable = runCatching {
      SpeechRecognizer.isRecognitionAvailable(appContext)
    }.getOrDefault(false)
    capabilities[AndroidCapability.SPEECH_RECOGNITION] = when {
      !speechRecognitionAvailable -> {
        details[AndroidCapability.SPEECH_RECOGNITION] = "No Android speech-recognition service is available."
        ProviderAvailability.UNSUPPORTED
      }
      !hasRuntimePermission(Manifest.permission.RECORD_AUDIO) -> {
        details[AndroidCapability.SPEECH_RECOGNITION] =
          "Microphone permission is required; provider discovery did not request it."
        ProviderAvailability.PERMISSION_REQUIRED
      }
      else -> ProviderAvailability.AVAILABLE
    }

    val mediaSessionManager = appContext.getSystemService(MediaSessionManager::class.java)
    val mediaSessionComponent = ComponentName(appContext, GalleryMediaSessionListenerService::class.java)
    capabilities[AndroidCapability.MEDIA_SESSION_CONTROL] = when {
      mediaSessionManager == null -> {
        details[AndroidCapability.MEDIA_SESSION_CONTROL] = "Android MediaSessionManager is unavailable."
        ProviderAvailability.UNSUPPORTED
      }
      notificationManager?.isNotificationListenerAccessGranted(mediaSessionComponent) != true -> {
        details[AndroidCapability.MEDIA_SESSION_CONTROL] =
          "Notification-listener special access is not enabled for Gallery. It is opt-in and must be enabled manually in Android settings; provider discovery never opens settings or requests access."
        ProviderAvailability.PERMISSION_REQUIRED
      }
      else -> ProviderAvailability.AVAILABLE
    }

    return CapabilityProviderObservation(
      providerId = descriptor.id,
      availability = ProviderAvailability.AVAILABLE,
      detail = "Native Android framework probe completed.",
      capabilityAvailability = capabilities,
      capabilityDetails = details,
    )
  }

  @SuppressLint("MissingPermission")
  override suspend fun getBluetoothBondedDevices(): List<NativeBluetoothDevice> {
    if (!authorizer.authorize(AndroidCapability.BLUETOOTH_DISCOVERY,
        "Read paired Bluetooth device names, types, and addresses.")) {
      throw SecurityException("Bluetooth device discovery was not approved.")
    }
    ensureRuntimePermission(Manifest.permission.BLUETOOTH_CONNECT)
    val bluetooth = appContext.getSystemService(BluetoothManager::class.java)?.adapter
      ?: throw IllegalStateException("Bluetooth adapter is unavailable.")
    if (!bluetooth.isEnabled) throw IllegalStateException("Bluetooth is turned off.")
    return bluetooth.bondedDevices
      .take(MAX_BLUETOOTH_RESULTS)
      .map { it.toNativeBluetoothDevice() }
      .sortedWith(compareBy<NativeBluetoothDevice> { it.name.lowercase() }.thenBy { it.address })
  }

  @SuppressLint("MissingPermission")
  override suspend fun scanBluetoothLe(durationMs: Long): List<NativeBluetoothDevice> {
    require(durationMs in MIN_BLUETOOTH_SCAN_MS..MAX_BLUETOOTH_SCAN_MS) {
      "Bluetooth LE scan duration must be between $MIN_BLUETOOTH_SCAN_MS and $MAX_BLUETOOTH_SCAN_MS milliseconds."
    }
    if (!authorizer.authorize(AndroidCapability.BLUETOOTH_LE_SCAN,
        "Scan for nearby Bluetooth LE devices for $durationMs milliseconds.")) {
      throw SecurityException("Bluetooth discovery was not approved.")
    }
    ensureRuntimePermission(Manifest.permission.BLUETOOTH_SCAN)
    ensureRuntimePermission(Manifest.permission.BLUETOOTH_CONNECT)
    val bluetooth = appContext.getSystemService(BluetoothManager::class.java)?.adapter
      ?: throw IllegalStateException("Bluetooth adapter is unavailable.")
    if (!bluetooth.isEnabled) throw IllegalStateException("Bluetooth is turned off.")
    val scanner = bluetooth.bluetoothLeScanner
      ?: throw IllegalStateException("Bluetooth LE scanner is unavailable.")
    val found = linkedMapOf<String, NativeBluetoothDevice>()
    val scanError = AtomicInteger(-1)
    val callback = object : ScanCallback() {
      override fun onScanResult(callbackType: Int, result: ScanResult) {
        addResult(result)
      }
      override fun onBatchScanResults(results: MutableList<ScanResult>) {
        results.forEach(::addResult)
      }
      override fun onScanFailed(errorCode: Int) {
        scanError.set(errorCode)
      }
      private fun addResult(result: ScanResult) {
        val device = result.device
        val address = device.address ?: return
        val item = device.toNativeBluetoothDevice(
          rssi = result.rssi,
          serviceUuids = result.scanRecord?.serviceUuids?.take(MAX_BLUETOOTH_SERVICE_UUIDS)
            ?.map { it.uuid.toString() }.orEmpty(),
          advertisedName = result.scanRecord?.deviceName,
        )
        synchronized(found) {
          if (found.containsKey(address) || found.size < MAX_BLUETOOTH_RESULTS) found[address] = item
        }
      }
    }
    scanner.startScan(
      null,
      ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),
      callback,
    )
    var primaryFailure: Throwable? = null
    try {
      delay(durationMs)
    } catch (error: Throwable) {
      primaryFailure = error
      throw error
    } finally {
      try {
        scanner.stopScan(callback)
      } catch (cleanupError: Exception) {
        if (primaryFailure != null) primaryFailure.addSuppressed(cleanupError)
        else throw IllegalStateException("Bluetooth LE scan cleanup could not be confirmed.", cleanupError)
      }
    }
    scanError.get().takeIf { it >= 0 }?.let {
      throw IllegalStateException("Bluetooth LE scan failed with Android error code $it.")
    }
    return synchronized(found) { found.values.sortedWith(compareByDescending<NativeBluetoothDevice> { it.rssi ?: Int.MIN_VALUE }.thenBy { it.address }) }
  }

  @SuppressLint("MissingPermission")
  override suspend fun inspectBluetoothGatt(
    deviceAddress: String,
    timeoutMs: Long,
  ): NativeBluetoothGattInspection = withBluetoothGattSession(
    deviceAddress = deviceAddress,
    timeoutMs = timeoutMs,
    approvalSummary = "Inspect GATT services and characteristic properties.",
  ) { session ->
    val allServices = session.services
    var remaining = MAX_GATT_CHARACTERISTICS
    val services = allServices.take(MAX_GATT_SERVICES).map { service ->
      val characteristics = service.characteristics.orEmpty()
      val selected = characteristics.take(remaining.coerceAtLeast(0))
      remaining -= selected.size
      NativeBluetoothGattService(
        uuid = service.uuid.toString(),
        type = if (service.type == BluetoothGattService.SERVICE_TYPE_PRIMARY) "PRIMARY" else "SECONDARY",
        characteristics = selected.map { characteristic ->
          NativeBluetoothGattCharacteristic(
            uuid = characteristic.uuid.toString(),
            properties = characteristic.properties,
            canRead = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0,
            canWrite = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0,
            canWriteWithoutResponse = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0,
            canNotify = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0,
            canIndicate = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0,
            descriptorCount = characteristic.descriptors.size.coerceAtMost(MAX_GATT_DESCRIPTORS_PER_CHARACTERISTIC),
          )
        },
      )
    }
    val totalCharacteristics = allServices.sumOf { it.characteristics.size }
    NativeBluetoothGattInspection(
      deviceAddress = deviceAddress.uppercase(),
      services = services,
      totalCharacteristics = totalCharacteristics,
      truncated = allServices.size > MAX_GATT_SERVICES || totalCharacteristics > MAX_GATT_CHARACTERISTICS,
    )
  }

  @SuppressLint("MissingPermission")
  override suspend fun readBluetoothGattCharacteristic(
    deviceAddress: String,
    serviceUuid: String,
    characteristicUuid: String,
    timeoutMs: Long,
  ): NativeBluetoothGattValue = withBluetoothGattSession(
    deviceAddress = deviceAddress,
    timeoutMs = timeoutMs,
    approvalSummary = "Read characteristic $characteristicUuid from GATT service $serviceUuid.",
  ) { session ->
    val characteristic = findGattCharacteristic(session.services, serviceUuid, characteristicUuid)
    require((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
      "The selected GATT characteristic does not advertise read support."
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val started = session.gatt.readCharacteristic(characteristic)
      if (!started) {
        throw IllegalStateException("Android could not start the GATT characteristic read.")
      }
    } else {
      val started = runCatching {
        BluetoothGatt::class.java
          .getMethod("readCharacteristic", BluetoothGattCharacteristic::class.java)
          .invoke(session.gatt, characteristic) as? Boolean == true
      }.getOrDefault(false)
      if (!started) throw IllegalStateException("Android could not start the GATT characteristic read.")
    }
    while (true) {
      when (val event = session.events.receive()) {
        is BluetoothGattEvent.ConnectionState -> {
          if (event.newState == BluetoothProfile.STATE_DISCONNECTED) {
            throw IllegalStateException("Bluetooth device disconnected before the GATT read completed.")
          }
        }
        is BluetoothGattEvent.CharacteristicRead -> {
          if (!event.uuid.equals(characteristicUuid, ignoreCase = true)) continue
          if (event.status != BluetoothGatt.GATT_SUCCESS) {
            throw IllegalStateException("GATT characteristic read failed with status ${event.status}.")
          }
          require(event.value.size <= MAX_GATT_VALUE_BYTES) {
            "GATT characteristic value exceeded the $MAX_GATT_VALUE_BYTES-byte read limit."
          }
          return@withBluetoothGattSession NativeBluetoothGattValue(
            deviceAddress = deviceAddress.uppercase(),
            serviceUuid = serviceUuid.lowercase(),
            characteristicUuid = characteristicUuid.lowercase(),
            valueBase64 = Base64.encodeToString(event.value, Base64.NO_WRAP),
            byteCount = event.value.size,
          )
        }
        else -> Unit
      }
    }
    error("Unreachable GATT read state.")
  }

  @SuppressLint("MissingPermission")
  override suspend fun writeBluetoothGattCharacteristic(
    deviceAddress: String,
    serviceUuid: String,
    characteristicUuid: String,
    valueBase64: String,
    timeoutMs: Long,
  ): NativeCapabilityActionResult {
    require(valueBase64.length <= MAX_GATT_BASE64_CHARS) {
      "GATT Base64 payload exceeds the $MAX_GATT_VALUE_BYTES-byte input limit."
    }
    val value = try {
      Base64.decode(valueBase64, Base64.NO_WRAP)
    } catch (error: IllegalArgumentException) {
      throw IllegalArgumentException("value_base64 must contain valid unwrapped Base64.", error)
    }
    require(value.isNotEmpty() && value.size <= MAX_GATT_VALUE_BYTES) {
      "GATT write payload must contain 1–$MAX_GATT_VALUE_BYTES bytes."
    }
    return withBluetoothGattSession(
      deviceAddress = deviceAddress,
      timeoutMs = timeoutMs,
      approvalSummary = "Write ${value.size} bytes (Base64: $valueBase64) to characteristic $characteristicUuid in GATT service $serviceUuid.",
    ) { session ->
      val characteristic = findGattCharacteristic(session.services, serviceUuid, characteristicUuid)
      val writeType = when {
        (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ->
          BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 ->
          BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else -> throw IllegalArgumentException("The selected GATT characteristic does not advertise write support.")
      }
      val expectsRemoteResponse = writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val status = session.gatt.writeCharacteristic(characteristic, value, writeType)
        if (status != BluetoothStatusCodes.SUCCESS) {
          throw IllegalStateException("Android could not start the GATT characteristic write (status $status).")
        }
      } else {
        @Suppress("DEPRECATION")
        run {
          characteristic.writeType = writeType
          characteristic.value = value
          if (!session.gatt.writeCharacteristic(characteristic)) {
            throw IllegalStateException("Android could not start the GATT characteristic write.")
          }
        }
      }
      while (true) {
        when (val event = session.events.receive()) {
          is BluetoothGattEvent.ConnectionState -> {
            if (event.newState == BluetoothProfile.STATE_DISCONNECTED) {
              throw IllegalStateException("Bluetooth device disconnected before the GATT write completed.")
            }
          }
          is BluetoothGattEvent.CharacteristicWrite -> {
            if (!event.uuid.equals(characteristicUuid, ignoreCase = true)) continue
            if (event.status != BluetoothGatt.GATT_SUCCESS) {
              throw IllegalStateException("GATT characteristic write failed with status ${event.status}.")
            }
            val detail = if (expectsRemoteResponse) {
              "Write-with-response completed for ${value.size} bytes to characteristic ${characteristicUuid.lowercase()} in service ${serviceUuid.lowercase()}."
            } else {
              "Submitted ${value.size} bytes to characteristic ${characteristicUuid.lowercase()} in service ${serviceUuid.lowercase()} using write-without-response; remote receipt cannot be confirmed."
            }
            return@withBluetoothGattSession NativeCapabilityActionResult(true, detail)
          }
          else -> Unit
        }
      }
      error("Unreachable GATT write state.")
    }
  }

  private fun findGattCharacteristic(
    services: List<BluetoothGattService>,
    serviceUuid: String,
    characteristicUuid: String,
  ): BluetoothGattCharacteristic {
    val service = services.firstOrNull { it.uuid.toString().equals(serviceUuid, ignoreCase = true) }
      ?: throw IllegalArgumentException("GATT service $serviceUuid was not found on the connected device.")
    return service.characteristics.firstOrNull {
      it.uuid.toString().equals(characteristicUuid, ignoreCase = true)
    } ?: throw IllegalArgumentException(
      "GATT characteristic $characteristicUuid was not found in service $serviceUuid."
    )
  }

  @SuppressLint("MissingPermission")
  private suspend fun <T> withBluetoothGattSession(
    deviceAddress: String,
    timeoutMs: Long,
    approvalSummary: String,
    block: suspend (BluetoothGattSession) -> T,
  ): T {
    require(deviceAddress.matches(Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$"))) {
      "device_address must be a valid Bluetooth MAC address."
    }
    require(timeoutMs in MIN_GATT_TIMEOUT_MS..MAX_GATT_TIMEOUT_MS) {
      "GATT operation timeout must be between $MIN_GATT_TIMEOUT_MS and $MAX_GATT_TIMEOUT_MS milliseconds."
    }
    if (!authorizer.authorize(
        AndroidCapability.BLUETOOTH_GATT,
        "Connect briefly to Bluetooth device $deviceAddress. $approvalSummary Maximum session: $timeoutMs milliseconds; disconnect when complete.",
      )
    ) {
      throw SecurityException("Bluetooth GATT operation was not approved.")
    }
    ensureRuntimePermission(Manifest.permission.BLUETOOTH_CONNECT)
    val bluetooth = appContext.getSystemService(BluetoothManager::class.java)?.adapter
      ?: throw IllegalStateException("Bluetooth adapter is unavailable.")
    if (!bluetooth.isEnabled) throw IllegalStateException("Bluetooth is turned off.")
    val device = try {
      bluetooth.getRemoteDevice(deviceAddress.uppercase())
    } catch (error: IllegalArgumentException) {
      throw IllegalArgumentException("device_address is not a valid Bluetooth address.", error)
    }

    val events = Channel<BluetoothGattEvent>(Channel.UNLIMITED)
    var activeGatt: BluetoothGatt? = null
    val callback = object : BluetoothGattCallback() {
      override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        events.trySend(BluetoothGattEvent.ConnectionState(status, newState))
      }
      override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        events.trySend(BluetoothGattEvent.ServicesDiscovered(status))
      }
      @Suppress("DEPRECATION")
      override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
      ) {
        events.trySend(
          BluetoothGattEvent.CharacteristicRead(
            characteristic.uuid.toString(),
            characteristic.value?.copyOf() ?: byteArrayOf(),
            status,
          )
        )
      }
      override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
      ) {
        events.trySend(
          BluetoothGattEvent.CharacteristicRead(characteristic.uuid.toString(), value.copyOf(), status)
        )
      }
      override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
      ) {
        events.trySend(BluetoothGattEvent.CharacteristicWrite(characteristic.uuid.toString(), status))
      }
    }

    try {
      return try {
        withTimeout(timeoutMs) {
          activeGatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IllegalStateException("Android could not start a Bluetooth GATT connection.")
          val gatt = checkNotNull(activeGatt)
          val services = awaitGattServices(gatt, events)
          block(BluetoothGattSession(gatt, services, events))
        }
      } catch (timeout: TimeoutCancellationException) {
        if (!currentCoroutineContext().isActive) throw timeout
        throw IllegalStateException("Bluetooth GATT operation timed out after $timeoutMs milliseconds.", timeout)
      }
    } finally {
      withContext(NonCancellable) {
        activeGatt?.let { gatt ->
          runCatching { gatt.disconnect() }
          runCatching { gatt.close() }
        }
        events.close()
      }
    }
  }

  private suspend fun awaitGattServices(
    gatt: BluetoothGatt,
    events: Channel<BluetoothGattEvent>,
  ): List<BluetoothGattService> {
    while (true) {
      when (val event = events.receive()) {
        is BluetoothGattEvent.ConnectionState -> {
          if (event.status != BluetoothGatt.GATT_SUCCESS) {
            throw IllegalStateException("Bluetooth GATT connection failed with status ${event.status}.")
          }
          if (event.newState == BluetoothProfile.STATE_CONNECTED) {
            if (!gatt.discoverServices()) {
              throw IllegalStateException("Android could not start Bluetooth GATT service discovery.")
            }
          } else if (event.newState == BluetoothProfile.STATE_DISCONNECTED) {
            throw IllegalStateException("Bluetooth device disconnected before GATT discovery completed.")
          }
        }
        is BluetoothGattEvent.ServicesDiscovered -> {
          if (event.status != BluetoothGatt.GATT_SUCCESS) {
            throw IllegalStateException("Bluetooth GATT service discovery failed with status ${event.status}.")
          }
          return gatt.services.orEmpty()
        }
        else -> Unit
      }
    }
  }

  override suspend fun getUsbDevices(): List<NativeUsbDevice> {
    if (!authorizer.authorize(AndroidCapability.USB_DEVICE_DISCOVERY,
        "Inspect connected USB host devices and their interface descriptors.")) {
      throw SecurityException("USB device discovery was not approved.")
    }
    if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
      throw IllegalStateException("This device does not support USB host mode.")
    }
    val manager = appContext.getSystemService(UsbManager::class.java)
      ?: throw IllegalStateException("USB manager is unavailable.")
    return manager.deviceList.values.take(MAX_USB_DEVICES).map { device ->
      device.toNativeUsbDevice()
    }.sortedWith(compareBy<NativeUsbDevice> { it.vendorId }.thenBy { it.productId }.thenBy { it.deviceId })
  }

  override suspend fun searchContacts(query: String, limit: Int): List<NativeContactSummary> {
    val normalizedQuery = query.trim()
    require(normalizedQuery.isNotEmpty() && normalizedQuery.length <= MAX_CONTACT_QUERY_CHARS) {
      "Contact search query must contain 1–$MAX_CONTACT_QUERY_CHARS characters."
    }
    require(limit in 1..MAX_CONTACT_RESULTS) {
      "Contact search limit must be between 1 and $MAX_CONTACT_RESULTS."
    }
    if (!authorizer.authorize(
        AndroidCapability.CONTACT_READ,
        "Search Gallery's contacts for '$normalizedQuery' and return at most $limit matching names, phone numbers, and email addresses.",
      )
    ) {
      throw SecurityException("Contacts access was not approved.")
    }
    ensureRuntimePermission(Manifest.permission.READ_CONTACTS)
    val filterUri = Uri.withAppendedPath(
      ContactsContract.Contacts.CONTENT_FILTER_URI,
      Uri.encode(normalizedQuery),
    ).buildUpon()
      .appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, limit.toString())
      .build()
    val resolver = appContext.contentResolver
    return withContext(Dispatchers.IO) {
      val results = mutableListOf<NativeContactSummary>()
      resolver.query(
        filterUri,
        arrayOf(
          ContactsContract.Contacts._ID,
          ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
          ContactsContract.Contacts.HAS_PHONE_NUMBER,
        ),
        null,
        null,
        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC",
      )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
        val phoneColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)
        while (cursor.moveToNext() && results.size < limit) {
          val contactId = cursor.getString(idColumn) ?: continue
          val displayName = cursor.getString(nameColumn).orEmpty().trim().take(MAX_CONTACT_NAME_CHARS)
          if (displayName.isBlank()) continue
          val phoneNumbers = if (cursor.getInt(phoneColumn) != 0) {
            queryContactPhones(contactId)
          } else {
            emptyList()
          }
          results += NativeContactSummary(
            displayName = displayName,
            phoneNumbers = phoneNumbers,
            emailAddresses = queryContactEmails(contactId),
          )
        }
      }
      results
    }
  }

  private fun queryContactPhones(contactId: String): List<String> {
    val values = mutableListOf<String>()
    appContext.contentResolver.query(
      ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
      arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
      ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
      arrayOf(contactId),
      ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY + " DESC, " +
        ContactsContract.CommonDataKinds.Phone.IS_PRIMARY + " DESC",
    )?.use { cursor ->
      val numberColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
      while (cursor.moveToNext() && values.size < MAX_CONTACT_VALUES_PER_TYPE) {
        cursor.getString(numberColumn)?.trim()?.take(MAX_CONTACT_VALUE_CHARS)
          ?.takeIf { it.isNotBlank() }?.let(values::add)
      }
    }
    return values
  }

  private fun queryContactEmails(contactId: String): List<String> {
    val values = mutableListOf<String>()
    appContext.contentResolver.query(
      ContactsContract.CommonDataKinds.Email.CONTENT_URI,
      arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
      ContactsContract.CommonDataKinds.Email.CONTACT_ID + " = ?",
      arrayOf(contactId),
      ContactsContract.CommonDataKinds.Email.IS_SUPER_PRIMARY + " DESC, " +
        ContactsContract.CommonDataKinds.Email.IS_PRIMARY + " DESC",
    )?.use { cursor ->
      val addressColumn = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
      while (cursor.moveToNext() && values.size < MAX_CONTACT_VALUES_PER_TYPE) {
        cursor.getString(addressColumn)?.trim()?.take(MAX_CONTACT_VALUE_CHARS)
          ?.takeIf { it.isNotBlank() }?.let(values::add)
      }
    }
    return values
  }

  override suspend fun readClipboard(): NativeClipboardContent {
    if (!isAppForeground()) {
      throw IllegalStateException("Clipboard reads require Gallery to be in the foreground.")
    }
    if (!authorizer.authorize(
        AndroidCapability.CLIPBOARD_READ,
        "Read up to $MAX_CLIPBOARD_ITEMS plain-text clipboard items, capped at $MAX_CLIPBOARD_CHARS characters.",
      )
    ) {
      throw SecurityException("Clipboard read was not approved.")
    }
    if (!isAppForeground()) {
      throw IllegalStateException("Gallery left the foreground before the clipboard could be read.")
    }
    val manager = appContext.getSystemService(ClipboardManager::class.java)
      ?: throw IllegalStateException("ClipboardManager is unavailable.")
    val clip = manager.primaryClip
      ?: return NativeClipboardContent(text = "", hasText = false, itemCount = 0, truncated = false)
    val output = StringBuilder()
    var foundText = false
    var truncated = clip.itemCount > MAX_CLIPBOARD_ITEMS
    for (index in 0 until minOf(clip.itemCount, MAX_CLIPBOARD_ITEMS)) {
      val itemText = clip.getItemAt(index).text ?: continue
      foundText = true
      if (output.isNotEmpty()) output.append('\n')
      val remaining = MAX_CLIPBOARD_CHARS - output.length
      if (remaining <= 0) {
        truncated = true
        break
      }
      val take = minOf(itemText.length, remaining)
      output.append(itemText.subSequence(0, take))
      if (take < itemText.length) {
        truncated = true
        break
      }
    }
    return NativeClipboardContent(
      text = output.toString(),
      hasText = foundText,
      itemCount = clip.itemCount,
      truncated = truncated,
    )
  }

  override suspend fun writeClipboard(text: String): NativeCapabilityActionResult {
    require(text.length <= MAX_CLIPBOARD_WRITE_CHARS) {
      "Clipboard text must not exceed $MAX_CLIPBOARD_WRITE_CHARS characters."
    }
    if (!authorizer.authorize(
        AndroidCapability.CLIPBOARD_WRITE,
        if (text.isEmpty()) "Clear Gallery's current clipboard contents." else
          "Replace the clipboard with this exact text (${text.length} characters): $text",
      )
    ) {
      throw SecurityException("Clipboard write was not approved.")
    }
    if (!isAppForeground()) {
      throw IllegalStateException("Clipboard writes require Gallery to remain in the foreground.")
    }
    val manager = appContext.getSystemService(ClipboardManager::class.java)
      ?: throw IllegalStateException("ClipboardManager is unavailable.")
    if (text.isEmpty()) {
      manager.clearPrimaryClip()
    } else {
      manager.setPrimaryClip(ClipData.newPlainText("Gemma", text))
    }
    return NativeCapabilityActionResult(
      true,
      if (text.isEmpty()) "Clipboard cleared." else "Copied ${text.length} characters to the clipboard.",
    )
  }

  private val notificationScheduleManager by lazy {
    EntryPointAccessors.fromApplication(
      appContext,
      NotificationScheduleManagerEntryPoint::class.java,
    ).notificationScheduleManager()
  }

  override suspend fun postNotification(
    title: String,
    message: String,
    channelId: String,
  ): NativeCapabilityActionResult {
    validateNotificationText(title, message)
    validateChannelId(channelId)
    val manager = appContext.getSystemService(NotificationManager::class.java)
      ?: throw IllegalStateException("NotificationManager is unavailable.")
    val existingChannel = manager.getNotificationChannel(channelId)
    if (existingChannel == null && channelId != DEFAULT_NOTIFICATION_CHANNEL_ID) {
      throw IllegalArgumentException("Notification channel '$channelId' does not exist. Create it first with create_notification_channel.")
    }
    val channelName = existingChannel?.name?.toString() ?: DEFAULT_NOTIFICATION_CHANNEL_NAME
    if (existingChannel?.importance == NotificationManager.IMPORTANCE_NONE) {
      throw IllegalStateException("Notification channel '$channelId' is blocked in Android settings.")
    }
    if (!authorizer.authorize(
        AndroidCapability.NOTIFICATION_POST,
        "Post a notification on this device using channel '$channelName' ($channelId). Title: $title. Message: $message",
      )
    ) {
      throw SecurityException("Notification posting was not approved.")
    }
    ensureNotificationsAllowed()
    if (existingChannel == null) {
      ensureChannel(manager, DEFAULT_NOTIFICATION_CHANNEL_ID, DEFAULT_NOTIFICATION_CHANNEL_NAME)
    }
    if (manager.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE) {
      throw IllegalStateException("Notification channel '$channelId' is blocked in Android settings.")
    }
    val notification = NotificationCompat.Builder(appContext, channelId)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle(title)
      .setContentText(message)
      .setStyle(NotificationCompat.BigTextStyle().bigText(message))
      .setAutoCancel(true)
      .build()
    val notificationId = UUID.randomUUID().toString().hashCode()
    manager.notify(notificationId, notification)
    return NativeCapabilityActionResult(true, "Notification posted with ID $notificationId.")
  }

  override suspend fun readSmsInbox(): NativeSmsInboxSnapshot {
    if (!authorizer.authorize(
        AndroidCapability.SMS_READ,
        "Read up to $MAX_SMS_INBOX_ITEMS recent received SMS inbox entries, including sender address/number, message text preview, timestamp, and read/unread state. SMS content is private. Android may deny READ_SMS to apps that are not eligible for this restricted permission.",
      )
    ) {
      throw SecurityException("SMS inbox reading was not approved.")
    }
    ensureRuntimePermission(Manifest.permission.READ_SMS)
    return withContext(Dispatchers.IO) {
      val projection = arrayOf(
        android.provider.Telephony.Sms.ADDRESS,
        android.provider.Telephony.Sms.BODY,
        android.provider.Telephony.Sms.DATE,
        android.provider.Telephony.Sms.READ,
      )
      val cursor = appContext.contentResolver.query(
        android.provider.Telephony.Sms.Inbox.CONTENT_URI,
        projection,
        null,
        null,
        "${android.provider.Telephony.Sms.DATE} DESC LIMIT ${MAX_SMS_INBOX_ITEMS + 1}",
      ) ?: throw IllegalStateException("Android could not read the SMS inbox.")
      cursor.use { rows ->
        val entries = mutableListOf<NativeSmsInboxEntry>()
        var truncated = false
        val senderIndex = rows.getColumnIndexOrThrow(android.provider.Telephony.Sms.ADDRESS)
        val bodyIndex = rows.getColumnIndexOrThrow(android.provider.Telephony.Sms.BODY)
        val dateIndex = rows.getColumnIndexOrThrow(android.provider.Telephony.Sms.DATE)
        val readIndex = rows.getColumnIndexOrThrow(android.provider.Telephony.Sms.READ)
        while (rows.moveToNext()) {
          if (entries.size >= MAX_SMS_INBOX_ITEMS) {
            truncated = true
            break
          }
          val date = rows.getLong(dateIndex)
          if (date <= 0L) continue
          entries += NativeSmsInboxEntry(
            sender = NotificationContentPolicy.sanitize(
              rows.getString(senderIndex),
              MAX_SMS_SENDER_CHARS,
            ),
            bodyPreview = NotificationContentPolicy.sanitize(
              rows.getString(bodyIndex),
              MAX_SMS_BODY_PREVIEW_CHARS,
            ),
            dateMs = date,
            isRead = rows.getInt(readIndex) != 0,
          )
        }
        NativeSmsInboxSnapshot(entries = entries, truncated = truncated)
      }
    }
  }

  override suspend fun readCallLog(): NativeCallLogSnapshot {
    if (!authorizer.authorize(
        AndroidCapability.CALL_LOG_READ,
        "Read up to $MAX_CALL_LOG_ITEMS recent call-log entries, including caller name/number, call type, date/time, and duration. Call history is sensitive. Android may deny READ_CALL_LOG to apps that are not eligible for this restricted permission.",
      )
    ) {
      throw SecurityException("Call-log reading was not approved.")
    }
    ensureRuntimePermission(Manifest.permission.READ_CALL_LOG)
    return withContext(Dispatchers.IO) {
      val projection = arrayOf(
        CallLog.Calls.CACHED_NAME,
        CallLog.Calls.NUMBER,
        CallLog.Calls.TYPE,
        CallLog.Calls.DATE,
        CallLog.Calls.DURATION,
      )
      val cursor = appContext.contentResolver.query(
        CallLog.Calls.CONTENT_URI,
        projection,
        null,
        null,
        "${CallLog.Calls.DATE} DESC LIMIT ${MAX_CALL_LOG_ITEMS + 1}",
      ) ?: throw IllegalStateException("Android could not read the call log.")
      cursor.use { rows ->
        val entries = mutableListOf<NativeCallLogEntry>()
        var truncated = false
        val nameIndex = rows.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
        val numberIndex = rows.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
        val typeIndex = rows.getColumnIndexOrThrow(CallLog.Calls.TYPE)
        val dateIndex = rows.getColumnIndexOrThrow(CallLog.Calls.DATE)
        val durationIndex = rows.getColumnIndexOrThrow(CallLog.Calls.DURATION)
        while (rows.moveToNext()) {
          if (entries.size >= MAX_CALL_LOG_ITEMS) {
            truncated = true
            break
          }
          val date = rows.getLong(dateIndex)
          if (date <= 0L) continue
          val cachedName = NotificationContentPolicy.sanitize(
            rows.getString(nameIndex),
            MAX_CALL_LOG_NAME_CHARS,
          )
          val number = NotificationContentPolicy.sanitize(
            rows.getString(numberIndex),
            MAX_CALL_LOG_NUMBER_CHARS,
          )
          val callType = when (rows.getInt(typeIndex)) {
            CallLog.Calls.INCOMING_TYPE -> "INCOMING"
            CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
            CallLog.Calls.MISSED_TYPE -> "MISSED"
            CallLog.Calls.REJECTED_TYPE -> "REJECTED"
            CallLog.Calls.BLOCKED_TYPE -> "BLOCKED"
            CallLog.Calls.VOICEMAIL_TYPE -> "VOICEMAIL"
            else -> "UNKNOWN"
          }
          entries += NativeCallLogEntry(
            cachedName = cachedName,
            number = number,
            callType = callType,
            dateMs = date,
            durationSeconds = rows.getLong(durationIndex).coerceAtLeast(0L),
          )
        }
        NativeCallLogSnapshot(entries = entries, truncated = truncated)
      }
    }
  }

  override suspend fun readOtherNotifications(): NativeOtherNotificationList {
    val manager = appContext.getSystemService(NotificationManager::class.java)
      ?: throw IllegalStateException("Android NotificationManager is unavailable.")
    val component = ComponentName(appContext, GalleryMediaSessionListenerService::class.java)
    if (manager.isNotificationListenerAccessGranted(component).not()) {
      throw SecurityException("Gallery notification-listener special access is not enabled.")
    }
    if (!authorizer.authorize(
        AndroidCapability.NOTIFICATION_READ,
        "Read the title and visible text of up to $MAX_OTHER_NOTIFICATION_ITEMS currently active notifications from other apps. Notification content may contain private messages or sensitive information. No history is read and no notification content is stored.",
      )
    ) {
      throw SecurityException("Reading other apps' notifications was not approved.")
    }
    val active = GalleryMediaSessionListenerService.activeNotificationsSnapshot()
      ?: throw IllegalStateException("Gallery's notification listener is not connected.")
    val eligible = active.asSequence()
      .filter { it.packageName != appContext.packageName }
      .filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
      .sortedByDescending { it.postTime }
      .toList()
    val items = eligible.take(MAX_OTHER_NOTIFICATION_ITEMS).mapNotNull { status ->
      val extras = status.notification.extras
      val title = NotificationContentPolicy.sanitize(
        extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        MAX_OTHER_NOTIFICATION_TITLE_CHARS,
      )
      val text = NotificationContentPolicy.sanitize(
        (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
          ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString(),
        MAX_OTHER_NOTIFICATION_TEXT_CHARS,
      )
      if (title == null && text == null) return@mapNotNull null
      val label = runCatching {
        val info = appContext.packageManager.getApplicationInfo(status.packageName, 0)
        appContext.packageManager.getApplicationLabel(info).toString()
      }.getOrNull()
      NativeOtherNotification(
        appLabel = NotificationContentPolicy.sanitize(label, MAX_OTHER_NOTIFICATION_APP_CHARS) ?: "App",
        title = title,
        text = text,
        postedAtMs = status.postTime,
      )
    }
    return NativeOtherNotificationList(
      notifications = items,
      truncated = eligible.size > MAX_OTHER_NOTIFICATION_ITEMS,
    )
  }

  override fun listPostedNotifications(): List<NativePostedNotification> {
    val manager = appContext.getSystemService(NotificationManager::class.java)
      ?: return emptyList()
    return manager.activeNotifications
      .take(MAX_ACTIVE_NOTIFICATIONS)
      .map { active ->
        val extras = active.notification.extras
        NativePostedNotification(
          id = active.id.toString(),
          title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(MAX_NOTIFICATION_TITLE_CHARS),
          message = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
              ?: extras.getCharSequence(Notification.EXTRA_TEXT)
          )?.toString().orEmpty().take(MAX_NOTIFICATION_MESSAGE_CHARS),
          postedAtMs = active.postTime,
        )
      }
  }

  override suspend fun cancelPostedNotification(id: String): NativeCapabilityActionResult {
    val numericId = id.toIntOrNull()
      ?: throw IllegalArgumentException("notification_id must be the numeric ID returned by list_posted_notifications.")
    val existing = listPostedNotifications().firstOrNull { it.id == id }
      ?: return NativeCapabilityActionResult(false, "No active Gallery notification exists with ID '$id'.")
    if (!authorizer.authorize(
        AndroidCapability.NOTIFICATION_POST,
        "Dismiss the active Gallery notification '${existing.title}' with ID $id.",
      )
    ) {
      throw SecurityException("Notification dismissal was not approved.")
    }
    val manager = appContext.getSystemService(NotificationManager::class.java)
      ?: throw IllegalStateException("NotificationManager is unavailable.")
    manager.cancel(numericId)
    return NativeCapabilityActionResult(true, "Active notification '$id' dismissed.")
  }

  override suspend fun scheduleNotification(
    title: String,
    message: String,
    hour: Int,
    minute: Int,
    year: Int?,
    month: Int?,
    day: Int?,
    repeatDaily: Boolean,
  ): NativeCapabilityActionResult {
    validateNotificationText(title, message)
    require(hour in 0..23 && minute in 0..59) { "Notification time must use hour 0–23 and minute 0–59." }
    val dateParts = listOf(year, month, day)
    require(dateParts.all { it == null } || dateParts.all { it != null }) {
      "year, month, and day must either all be supplied or all be omitted."
    }
    val zone = ZoneId.systemDefault()
    val now = LocalDateTime.now(zone)
    val scheduledDate = if (year != null && month != null && day != null) {
      runCatching { LocalDate.of(year, month, day) }
        .getOrElse { throw IllegalArgumentException("The requested notification date is invalid.") }
    } else {
      val todayAtRequestedTime = now.toLocalDate().atTime(hour, minute)
      if (todayAtRequestedTime.isAfter(now)) now.toLocalDate() else now.toLocalDate().plusDays(1)
    }
    val target = LocalDateTime.of(scheduledDate, java.time.LocalTime.of(hour, minute))
      .atZone(zone).toInstant().toEpochMilli()
    require(target > System.currentTimeMillis()) { "The requested notification date and time must be in the future." }
    val clockTime = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    val dateText = scheduledDate.toString()
    val whenText = if (repeatDaily) "daily starting $dateText at $clockTime" else "$dateText at $clockTime"
    if (!authorizer.authorize(
        AndroidCapability.BACKGROUND_SCHEDULE,
        "Schedule ${if (repeatDaily) "a repeating notification" else "one notification"} $whenText. Title: $title. Message: $message",
      )
    ) {
      throw SecurityException("Notification scheduling was not approved.")
    }
    ensureNotificationsAllowed()
    val manager = appContext.getSystemService(NotificationManager::class.java)
      ?: throw IllegalStateException("NotificationManager is unavailable.")
    ensureChannel(manager, DEFAULT_NOTIFICATION_CHANNEL_ID, DEFAULT_NOTIFICATION_CHANNEL_NAME)
    if (manager.getNotificationChannel(DEFAULT_NOTIFICATION_CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
      throw IllegalStateException("The Agent Skill Task notification channel is blocked in Android settings.")
    }
    val deeplink = Uri.Builder()
      .scheme("com.google.ai.edge.gallery")
      .authority("llm_agent_chat")
      .appendQueryParameter("query", message)
      .build()
      .toString()
    val notification = ScheduledNotification.newBuilder()
      .setId(UUID.randomUUID().toString())
      .setTitle(title)
      .setMessage(message)
      .setHour(hour)
      .setMinute(minute)
      .setChannelId(DEFAULT_NOTIFICATION_CHANNEL_ID)
      .setChannelName(DEFAULT_NOTIFICATION_CHANNEL_NAME)
      .setDeeplink(deeplink)
      .setRepeatDaily(repeatDaily)
      .setYear(scheduledDate.year)
      .setMonth(scheduledDate.monthValue)
      .setDay(scheduledDate.dayOfMonth)
      .build()
    if (!notificationScheduleManager.scheduleNotification(notification)) {
      throw IllegalStateException("Android could not register the notification alarm; the 50-reminder limit may have been reached.")
    }
    return NativeCapabilityActionResult(
      true,
      "Notification scheduled with ID ${notification.id} for $whenText. Android may delay delivery because these alarms are not exact.",
    )
  }

  override suspend fun listScheduledNotifications(): List<NativeScheduledNotification> =
    notificationScheduleManager.getScheduledNotifications().map { item ->
      NativeScheduledNotification(
        id = item.id,
        title = item.title,
        message = item.message,
        hour = item.hour,
        minute = item.minute,
        year = if (item.hasYear()) item.year else null,
        month = if (item.hasMonth()) item.month else null,
        day = if (item.hasDay()) item.day else null,
        repeatDaily = item.repeatDaily,
      )
    }

  override suspend fun cancelScheduledNotification(id: String): NativeCapabilityActionResult {
    require(id.isNotBlank() && id.length <= 64) { "A valid scheduled notification ID is required." }
    val existing = notificationScheduleManager.getScheduledNotifications().firstOrNull { it.id == id }
      ?: return NativeCapabilityActionResult(false, "No scheduled notification exists with ID '$id'.")
    if (!authorizer.authorize(
        AndroidCapability.BACKGROUND_SCHEDULE,
        "Cancel scheduled notification '${existing.title}' with ID $id.",
      )
    ) {
      throw SecurityException("Notification cancellation was not approved.")
    }
    val removed = notificationScheduleManager.cancelScheduledNotification(id)
    return NativeCapabilityActionResult(
      removed,
      if (removed) "Scheduled notification '$id' cancelled." else "Scheduled notification '$id' was not found.",
    )
  }

  override fun listNotificationChannels(): List<NativeNotificationChannel> {
    val manager = appContext.getSystemService(NotificationManager::class.java)
      ?: return emptyList()
    return manager.notificationChannels
      .take(MAX_NOTIFICATION_CHANNELS)
      .map { NativeNotificationChannel(it.id, it.name.toString(), it.importance) }
  }

  override suspend fun createNotificationChannel(
    id: String,
    name: String,
    importance: Int,
  ): NativeCapabilityActionResult {
    validateChannel(id, name)
    require(importance in NotificationManager.IMPORTANCE_LOW..NotificationManager.IMPORTANCE_HIGH) {
      "Channel importance must be LOW (2), DEFAULT (3), or HIGH (4)."
    }
    if (!authorizer.authorize(
        AndroidCapability.NOTIFICATION_CHANNEL_MANAGEMENT,
        "Create notification channel '$name' with ID '$id' and importance $importance.",
      )
    ) {
      throw SecurityException("Notification channel creation was not approved.")
    }
    val manager = appContext.getSystemService(NotificationManager::class.java)
      ?: throw IllegalStateException("NotificationManager is unavailable.")
    val existing = manager.getNotificationChannel(id)
    if (existing != null) {
      return NativeCapabilityActionResult(false, "Notification channel '$id' already exists; Android channel settings cannot be overwritten by the app.")
    }
    manager.createNotificationChannel(NotificationChannel(id, name, importance))
    return NativeCapabilityActionResult(true, "Notification channel '$name' created.")
  }

  private suspend fun ensureNotificationsAllowed() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      ensureRuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
      throw SecurityException("Notifications are disabled for Gallery in Android settings.")
    }
  }

  private fun ensureChannel(manager: NotificationManager, id: String, name: String) {
    if (manager.getNotificationChannel(id) == null) {
      manager.createNotificationChannel(
        NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH),
      )
    }
  }

  private fun validateNotificationText(title: String, message: String) {
    require(title.isNotBlank() && title.length <= MAX_NOTIFICATION_TITLE_CHARS) {
      "Notification title must contain 1–$MAX_NOTIFICATION_TITLE_CHARS characters."
    }
    require(message.isNotBlank() && message.length <= MAX_NOTIFICATION_MESSAGE_CHARS) {
      "Notification message must contain 1–$MAX_NOTIFICATION_MESSAGE_CHARS characters."
    }
  }

  private fun validateChannelId(id: String) {
    require(id.matches(Regex("^[A-Za-z0-9_.-]{1,64}$"))) {
      "Notification channel ID must contain 1–64 letters, digits, dots, underscores, or hyphens."
    }
  }

  private fun validateChannel(id: String, name: String) {
    require(id.matches(Regex("^[A-Za-z0-9_.-]{1,64}$"))) {
      "Notification channel ID must contain 1–64 letters, digits, dots, underscores, or hyphens."
    }
    require(name.isNotBlank() && name.length <= MAX_NOTIFICATION_CHANNEL_NAME_CHARS) {
      "Notification channel name must contain 1–$MAX_NOTIFICATION_CHANNEL_NAME_CHARS characters."
    }
  }

  private fun isAppForeground(): Boolean =
    ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

  private suspend fun ensureRuntimePermission(permission: String) {
    if (hasRuntimePermission(permission)) return
    if (!permissionRequester.request(permission) || !hasRuntimePermission(permission)) {
      throw SecurityException("Android permission $permission was denied or unavailable.")
    }
  }

  private fun hasRuntimePermission(permission: String): Boolean =
    appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

  @SuppressLint("MissingPermission")
  private fun BluetoothDevice.toNativeBluetoothDevice(
    rssi: Int? = null,
    serviceUuids: List<String> = emptyList(),
    advertisedName: String? = null,
  ) = NativeBluetoothDevice(
    address = address.orEmpty(),
    name = (advertisedName ?: name).orEmpty().take(80),
    type = when (type) {
      BluetoothDevice.DEVICE_TYPE_CLASSIC -> "CLASSIC"
      BluetoothDevice.DEVICE_TYPE_LE -> "LE"
      BluetoothDevice.DEVICE_TYPE_DUAL -> "DUAL"
      else -> "UNKNOWN"
    },
    bondState = when (bondState) {
      BluetoothDevice.BOND_BONDED -> "BONDED"
      BluetoothDevice.BOND_BONDING -> "BONDING"
      else -> "NOT_BONDED"
    },
    rssi = rssi,
    serviceUuids = serviceUuids,
  )

  private fun UsbDevice.toNativeUsbDevice() = NativeUsbDevice(
    deviceId = deviceId,
    vendorId = vendorId,
    productId = productId,
    classCode = deviceClass,
    subclassCode = deviceSubclass,
    protocolCode = deviceProtocol,
    interfaces = (0 until interfaceCount).take(MAX_USB_INTERFACES).map { index ->
      val usbInterface = getInterface(index)
      NativeUsbInterface(
        id = usbInterface.id,
        classCode = usbInterface.interfaceClass,
        subclassCode = usbInterface.interfaceSubclass,
        protocolCode = usbInterface.interfaceProtocol,
        endpointCount = usbInterface.endpointCount,
      )
    },
  )

  override suspend fun recordAudio(durationMs: Long): String {
    require(durationMs in MIN_AUDIO_RECORD_MS..MAX_AUDIO_RECORD_MS) {
      "Audio recording duration must be between $MIN_AUDIO_RECORD_MS and $MAX_AUDIO_RECORD_MS milliseconds."
    }
    if (!authorizer.authorize(
        AndroidCapability.MICROPHONE_RECORD,
        "Record audio locally for $durationMs milliseconds and save it under Music/EdgeGallery.",
      )
    ) {
      throw SecurityException("Microphone recording was not approved.")
    }
    ensureRuntimePermission(Manifest.permission.RECORD_AUDIO)
    val values = ContentValues().apply {
      put(MediaStore.Audio.Media.DISPLAY_NAME, "EdgeGallery_Audio_${System.currentTimeMillis()}.m4a")
      put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
      put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/EdgeGallery")
      put(MediaStore.Audio.Media.IS_PENDING, 1)
    }
    val uri = appContext.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
      ?: throw IllegalStateException("Could not create a Gallery-owned audio destination.")
    var descriptor: ParcelFileDescriptor? = null
    var recorder: MediaRecorder? = null
    var recordingStarted = false
    var published = false
    try {
      withContext(Dispatchers.IO) {
        descriptor = appContext.contentResolver.openFileDescriptor(uri, "w")
          ?: throw IllegalStateException("Could not open the audio destination.")
        recorder = MediaRecorder(appContext).apply {
          setAudioSource(MediaRecorder.AudioSource.MIC)
          setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
          setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
          setAudioEncodingBitRate(96_000)
          setAudioSamplingRate(44_100)
          setOutputFile(descriptor!!.fileDescriptor)
          prepare()
          start()
        }
        recordingStarted = true
        delay(durationMs)
        recorder?.stop()
        recordingStarted = false
      }
      withContext(Dispatchers.IO) {
        recorder?.release()
        recorder = null
        descriptor?.close()
        descriptor = null
        val updated = appContext.contentResolver.update(
          uri,
          ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
          null,
          null,
        )
        if (updated <= 0) throw IllegalStateException("Recorded audio could not be published.")
      }
      published = true
      return uri.toString()
    } finally {
      withContext(NonCancellable + Dispatchers.IO) {
        if (recordingStarted) runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        runCatching { descriptor?.close() }
        if (!published) runCatching { appContext.contentResolver.delete(uri, null, null) }
      }
    }
  }

  override suspend fun capturePhoto(): String {
    if (!authorizer.authorize(
        AndroidCapability.CAMERA_CAPTURE,
        "Capture one photo using the device camera and save it to Pictures/EdgeGallery.",
      )
    ) {
      throw SecurityException("Photo capture was not approved.")
    }
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, "EdgeGallery_${System.currentTimeMillis()}.jpg")
      put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
      put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/EdgeGallery")
      put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = appContext.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
      ?: throw IllegalStateException("Could not create a Gallery-owned photo destination.")
    try {
      if (!photoCaptureRequester.capture(uri.toString())) {
        throw IllegalStateException("Photo capture was cancelled or no camera result was returned.")
      }
      val published = appContext.contentResolver.update(
        uri,
        ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
        null,
        null,
      )
      if (published <= 0) throw IllegalStateException("Captured photo could not be published.")
      return uri.toString()
    } catch (error: Throwable) {
      runCatching { appContext.contentResolver.delete(uri, null, null) }
      throw error
    }
  }

  override suspend fun setWallpaper(
    contentUri: String,
    target: String,
  ): NativeCapabilityActionResult {
    val uri = runCatching { Uri.parse(contentUri) }.getOrNull()
      ?: return NativeCapabilityActionResult(false, "Wallpaper content URI is invalid.")
    if (uri.scheme != "content") {
      return NativeCapabilityActionResult(false, "Wallpaper source must be a content URI granted to Gallery.")
    }
    val mimeType = appContext.contentResolver.getType(uri)
    if (mimeType?.startsWith("image/") != true) {
      return NativeCapabilityActionResult(false, "Wallpaper source must be an image content URI.")
    }
    val which = when (target.uppercase()) {
      "SYSTEM" -> WallpaperManager.FLAG_SYSTEM
      "LOCK" -> WallpaperManager.FLAG_LOCK
      "BOTH" -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
      else -> return NativeCapabilityActionResult(false, "target must be SYSTEM, LOCK, or BOTH.")
    }
    val manager = appContext.getSystemService(WallpaperManager::class.java)
      ?: return NativeCapabilityActionResult(false, "WallpaperManager is unavailable.")
    if (!manager.isWallpaperSupported || !manager.isSetWallpaperAllowed) {
      return NativeCapabilityActionResult(false, "Android does not currently allow wallpaper changes.")
    }
    if (!authorizer.authorize(
        AndroidCapability.WALLPAPER_CONTROL,
        "Set the ${target} wallpaper using image URI ${contentUri}.",
      )
    ) {
      return NativeCapabilityActionResult(false, "Wallpaper change was not approved.")
    }
    return try {
      val stream = appContext.contentResolver.openInputStream(uri)
        ?: return NativeCapabilityActionResult(false, "Could not open the wallpaper image URI.")
      stream.use { manager.setStream(it, null, true, which) }
      NativeCapabilityActionResult(true, "Wallpaper updated for ${target}.")
    } catch (error: Exception) {
      NativeCapabilityActionResult(false, "Wallpaper update failed: ${error.message}")
    }
  }

  override fun getDeviceInfo(): NativeDeviceInfo =
    NativeDeviceInfo(
      manufacturer = Build.MANUFACTURER.orEmpty(),
      brand = Build.BRAND.orEmpty(),
      model = Build.MODEL.orEmpty(),
      device = Build.DEVICE.orEmpty(),
      androidRelease = Build.VERSION.RELEASE.orEmpty(),
      sdkInt = Build.VERSION.SDK_INT,
      securityPatch = Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() },
    )

  override fun getTelephonyInfo(): NativeTelephonyInfo {
    val manager = appContext.getSystemService(TelephonyManager::class.java)
      ?: throw IllegalStateException("TelephonyManager is unavailable.")
    val hasFeature = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    val phoneType = when (manager.phoneType) {
      TelephonyManager.PHONE_TYPE_GSM -> "GSM"
      TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
      TelephonyManager.PHONE_TYPE_SIP -> "SIP"
      else -> "NONE_OR_UNKNOWN"
    }
    val simState = when (manager.simState) {
      TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
      TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
      TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
      TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
      TelephonyManager.SIM_STATE_READY -> "READY"
      TelephonyManager.SIM_STATE_NOT_READY -> "NOT_READY"
      TelephonyManager.SIM_STATE_PERM_DISABLED -> "PERMANENTLY_DISABLED"
      TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "CARD_IO_ERROR"
      TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "CARD_RESTRICTED"
      else -> "UNKNOWN"
    }
    return NativeTelephonyInfo(hasFeature, phoneType, simState)
  }

  override fun getBatteryStatus(): NativeBatteryStatus {
    val batteryManager = appContext.getSystemService(BatteryManager::class.java)
    val capacity = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
      ?.takeIf { it in 0..100 }
    val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    // The sticky ACTION_BATTERY_CHANGED snapshot is the authoritative source for the
    // level/scale pair used alongside status, temperature, and voltage. BatteryManager's
    // capacity property is a fallback only when that snapshot is absent or invalid.
    val percent = resolveBatteryPercent(level, scale, capacity)
    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val state = when (status) {
      BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
      BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
      BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
      BatteryManager.BATTERY_STATUS_FULL -> "FULL"
      else -> "UNKNOWN"
    }
    val charging = when (state) {
      "CHARGING", "FULL" -> true
      "DISCHARGING", "NOT_CHARGING" -> false
      else -> null
    }
    val pluggedValue = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
    val temperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
    val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
    return NativeBatteryStatus(
      percent = percent,
      state = state,
      charging = charging,
      plugged = describePluggedSource(pluggedValue),
      temperatureCelsius = temperature.takeIf { it >= 0 }?.div(10f),
      voltageMillivolts = voltage.takeIf { it >= 0 },
    )
  }

  private fun describePluggedSource(value: Int): String =
    when {
      value < 0 -> "UNKNOWN"
      value == 0 -> "NOT_PLUGGED"
      else -> buildList {
        if ((value and BatteryManager.BATTERY_PLUGGED_AC) != 0) add("AC")
        if ((value and BatteryManager.BATTERY_PLUGGED_USB) != 0) add("USB")
        if ((value and BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) add("WIRELESS")
      }.ifEmpty { listOf("OTHER") }.joinToString("|")
    }

  override fun getAudioDevices(): List<NativeAudioDevice> {
    val audio = appContext.getSystemService(AudioManager::class.java)
      ?: return emptyList()
    return audio.getDevices(AudioManager.GET_DEVICES_ALL)
      .map { device ->
        NativeAudioDevice(
          id = device.id,
          type = audioDeviceTypeName(device.type),
          productName = device.productName?.toString().orEmpty().take(80),
          source = device.isSource,
          sink = device.isSink,
        )
      }
      .sortedWith(compareBy<NativeAudioDevice> { it.type }.thenBy { it.id })
  }

  private fun audioDeviceTypeName(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
    AudioDeviceInfo.TYPE_HDMI -> "HDMI"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
    AudioDeviceInfo.TYPE_DOCK -> "DOCK"
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
    AudioDeviceInfo.TYPE_FM -> "FM"
    AudioDeviceInfo.TYPE_AUX_LINE -> "AUX_LINE"
    AudioDeviceInfo.TYPE_IP -> "IP"
    AudioDeviceInfo.TYPE_BUS -> "BUS"
    AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
    AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
    AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
    AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI_EARC"
    AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
    AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
    AudioDeviceInfo.TYPE_UNKNOWN -> "UNKNOWN"
    else -> "OTHER_$type"
  }

  override fun getDisplayBrightness(): NativeDisplayBrightness {
    val resolver = appContext.contentResolver
    val level = runCatching {
      Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrNull()?.takeIf { it in 0..255 }
    val mode = runCatching {
      Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
    }.getOrNull()
    return NativeDisplayBrightness(
      level = level,
      mode = when (mode) {
        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC -> "AUTOMATIC"
        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL -> "MANUAL"
        else -> "UNKNOWN"
      },
      canModifySystemSettings = Settings.System.canWrite(appContext),
    )
  }

  override suspend fun setDisplayBrightness(level: Int): NativeCapabilityActionResult {
    if (level !in 0..255) {
      return NativeCapabilityActionResult(false, "Brightness must be between 0 and 255.")
    }
    if (!Settings.System.canWrite(appContext)) {
      return NativeCapabilityActionResult(
        false,
        "Modify system settings access is required. Enable it in Android Settings > Apps > Special app access > Modify system settings, then retry.",
      )
    }
    if (!authorizer.authorize(
        AndroidCapability.DISPLAY_BRIGHTNESS,
        "Set display brightness to $level of 255 and use manual brightness mode",
      )
    ) {
      return NativeCapabilityActionResult(false, "Brightness change was not approved.")
    }
    return try {
      val resolver = appContext.contentResolver
      val previousLevel = runCatching {
        Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
      }.getOrNull()
      val previousMode = runCatching {
        Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
      }.getOrNull()
      val levelChanged = Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, level)
      val modeChanged = Settings.System.putInt(
        resolver,
        Settings.System.SCREEN_BRIGHTNESS_MODE,
        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
      )
      if (modeChanged && levelChanged) {
        NativeCapabilityActionResult(true, "Display brightness set to $level of 255 (manual mode).")
      } else {
        if (previousLevel != null) {
          Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, previousLevel)
        }
        if (previousMode != null) {
          Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, previousMode)
        }
        NativeCapabilityActionResult(false, "Android rejected the brightness change; previous settings were restored where possible.")
      }
    } catch (error: SecurityException) {
      NativeCapabilityActionResult(false, "Android denied brightness control: ${error.message}")
    }
  }

  override suspend fun vibrate(durationMs: Long): NativeCapabilityActionResult {
    if (durationMs !in 1L..MAX_VIBRATION_DURATION_MS) {
      return NativeCapabilityActionResult(
        false,
        "Vibration duration must be between 1 and $MAX_VIBRATION_DURATION_MS milliseconds.",
      )
    }
    val vibrator = appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
      ?: return NativeCapabilityActionResult(false, "Vibrator service is unavailable.")
    if (!vibrator.hasVibrator()) {
      return NativeCapabilityActionResult(false, "This device has no vibrator.")
    }
    if (!authorizer.authorize(
        AndroidCapability.VIBRATION_CONTROL,
        "Vibrate for $durationMs milliseconds",
      )
    ) {
      return NativeCapabilityActionResult(false, "Vibration was not approved.")
    }
    return try {
      vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
      NativeCapabilityActionResult(true, "Vibrated for $durationMs milliseconds.")
    } catch (error: SecurityException) {
      NativeCapabilityActionResult(false, "Android denied vibration: ${error.message}")
    }
  }

  override fun isWakeLockHeld(): Boolean = synchronized(wakeLockGuard) {
    agentWakeLock?.isHeld == true
  }

  override suspend fun setWakeLock(
    held: Boolean,
    durationMs: Long,
  ): NativeCapabilityActionResult {
    if (held && durationMs !in 1L..MAX_WAKE_LOCK_DURATION_MS) {
      return NativeCapabilityActionResult(
        false,
        "Wake-lock duration must be between 1 and $MAX_WAKE_LOCK_DURATION_MS milliseconds.",
      )
    }
    if (!authorizer.authorize(
        AndroidCapability.WAKE_LOCK,
        if (held) "Keep the CPU awake for at most $durationMs milliseconds"
        else "Release the agent CPU wake lock",
      )
    ) {
      return NativeCapabilityActionResult(false, "Wake-lock change was not approved.")
    }
    val power = appContext.getSystemService(PowerManager::class.java)
      ?: return NativeCapabilityActionResult(false, "PowerManager is unavailable.")
    return synchronized(wakeLockGuard) {
      try {
        if (held) {
          val lock = agentWakeLock ?: power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EdgeGallery:AgentCapability",
          ).apply { setReferenceCounted(false) }.also { agentWakeLock = it }
          lock.acquire(durationMs)
          NativeCapabilityActionResult(
            true,
            "CPU wake lock acquired for at most $durationMs milliseconds; Android will release it automatically at timeout.",
          )
        } else {
          agentWakeLock?.takeIf { it.isHeld }?.release()
          NativeCapabilityActionResult(true, "Agent CPU wake lock released.")
        }
      } catch (error: SecurityException) {
        NativeCapabilityActionResult(false, "Android denied wake-lock access: ${error.message}")
      } catch (error: RuntimeException) {
        NativeCapabilityActionResult(false, "Wake-lock operation failed: ${error.message}")
      }
    }
  }

  override fun getVolumeStatus(stream: NativeAudioStream): NativeVolumeStatus {
    val audio = requireNotNull(appContext.getSystemService(AudioManager::class.java)) {
      "AudioManager is unavailable."
    }
    return NativeVolumeStatus(
      stream = stream,
      current = audio.getStreamVolume(stream.androidValue),
      maximum = audio.getStreamMaxVolume(stream.androidValue),
    )
  }

  override suspend fun setVolume(
    stream: NativeAudioStream,
    volume: Int,
  ): NativeCapabilityActionResult {
    val audio = appContext.getSystemService(AudioManager::class.java)
      ?: return NativeCapabilityActionResult(false, "AudioManager is unavailable.")
    val maximum = audio.getStreamMaxVolume(stream.androidValue)
    if (volume !in 0..maximum) {
      return NativeCapabilityActionResult(false, "Volume must be between 0 and $maximum.")
    }
    if (!authorizer.authorize(
        AndroidCapability.VOLUME_CONTROL,
        "Set ${stream.name.lowercase()} volume to $volume of $maximum",
      )
    ) {
      return NativeCapabilityActionResult(false, "Volume change was not approved.")
    }
    return try {
      audio.setStreamVolume(stream.androidValue, volume, 0)
      NativeCapabilityActionResult(true, "${stream.name} volume set to $volume of $maximum.")
    } catch (error: SecurityException) {
      NativeCapabilityActionResult(false, "Android denied volume control: ${error.message}")
    }
  }

  override suspend fun setFlashlight(enabled: Boolean): NativeCapabilityActionResult {
    val availability = flashlightAvailability()
    if (availability == ProviderAvailability.PERMISSION_REQUIRED) {
      return NativeCapabilityActionResult(false, "Camera permission is required.")
    }
    if (availability != ProviderAvailability.AVAILABLE) {
      return NativeCapabilityActionResult(false, "This device has no available camera flash.")
    }
    val cameraId = findFlashCameraId()
      ?: return NativeCapabilityActionResult(false, "No controllable camera flash was found.")
    if (!authorizer.authorize(
        AndroidCapability.FLASHLIGHT_CONTROL,
        if (enabled) "Turn the flashlight on" else "Turn the flashlight off",
      )
    ) {
      return NativeCapabilityActionResult(false, "Flashlight change was not approved.")
    }
    val cameraManager = appContext.getSystemService(CameraManager::class.java)
      ?: return NativeCapabilityActionResult(false, "Camera service is unavailable.")
    return try {
      cameraManager.setTorchMode(cameraId, enabled)
      NativeCapabilityActionResult(true, "Flashlight turned ${if (enabled) "on" else "off"}.")
    } catch (error: CameraAccessException) {
      NativeCapabilityActionResult(false, "Camera service could not change the flashlight: ${error.message}")
    } catch (error: SecurityException) {
      NativeCapabilityActionResult(false, "Android denied flashlight control: ${error.message}")
    }
  }

  override fun listLaunchableApps(): List<NativeLaunchableApp> =
    queryLaunchableApps().sortedBy { it.label.lowercase() }

  override suspend fun launchApp(packageName: String): NativeCapabilityActionResult {
    if (packageName.isBlank()) {
      return NativeCapabilityActionResult(false, "A package name is required.")
    }
    val app = queryLaunchableApps().firstOrNull { it.packageName == packageName }
      ?: return NativeCapabilityActionResult(
        false,
        "Package is not a visible launchable app: $packageName",
      )
    if (!authorizer.authorize(AndroidCapability.APP_LAUNCH, "Launch ${app.label} ($packageName)")) {
      return NativeCapabilityActionResult(false, "App launch was not approved.")
    }
    val launchIntent = appContext.packageManager.getLaunchIntentForPackage(packageName)
      ?: return NativeCapabilityActionResult(false, "No launch intent exists for $packageName.")
    return try {
      launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      appContext.startActivity(launchIntent)
      NativeCapabilityActionResult(true, "Launched ${app.label}.")
    } catch (error: ActivityNotFoundException) {
      NativeCapabilityActionResult(false, "Launch activity was not found: ${error.message}")
    } catch (error: SecurityException) {
      NativeCapabilityActionResult(false, "Android denied app launch: ${error.message}")
    }
  }

  override suspend fun openUrl(url: String): NativeCapabilityActionResult {
    val uri = runCatching { Uri.parse(url) }.getOrNull()
      ?: return NativeCapabilityActionResult(false, "The URL is invalid.")
    val scheme = uri.scheme?.lowercase()
    if (scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
      return NativeCapabilityActionResult(false, "Only absolute HTTP and HTTPS URLs are allowed.")
    }
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (intent.resolveActivity(appContext.packageManager) == null) {
      return NativeCapabilityActionResult(false, "No application can open this URL.")
    }
    if (!authorizer.authorize(AndroidCapability.OPEN_URL, "Open $url")) {
      return NativeCapabilityActionResult(false, "Opening the URL was not approved.")
    }
    return try {
      appContext.startActivity(intent)
      NativeCapabilityActionResult(true, "Opened $url.")
    } catch (error: ActivityNotFoundException) {
      NativeCapabilityActionResult(false, "No application can open this URL: ${error.message}")
    } catch (error: SecurityException) {
      NativeCapabilityActionResult(false, "Android denied opening this URL: ${error.message}")
    }
  }

  override suspend fun dialPhoneNumber(phoneNumber: String): NativeCapabilityActionResult {
    val normalized = normalizePhoneNumber(phoneNumber)
      ?: return NativeCapabilityActionResult(false, "phone_number must contain 3–20 digits and only common phone-number punctuation.")
    val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", normalized, null))
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (intent.resolveActivity(appContext.packageManager) == null) {
      return NativeCapabilityActionResult(false, "No dialer activity can handle this phone number.")
    }
    if (!authorizer.authorize(AndroidCapability.PHONE_CALL_DIAL, "Open the dialer with phone number $normalized. No call will be placed automatically.")) {
      return NativeCapabilityActionResult(false, "Opening the dialer was not approved.")
    }
    return try {
      appContext.startActivity(intent)
      NativeCapabilityActionResult(true, "Opened the dialer with $normalized. Review the number and place the call yourself.")
    } catch (error: ActivityNotFoundException) {
      NativeCapabilityActionResult(false, "No dialer activity is available: ${error.message}")
    } catch (error: SecurityException) {
      NativeCapabilityActionResult(false, "Android denied opening the dialer: ${error.message}")
    }
  }

  override suspend fun composeSms(
    phoneNumber: String,
    message: String,
  ): NativeCapabilityActionResult {
    val normalized = normalizePhoneNumber(phoneNumber)
      ?: return NativeCapabilityActionResult(false, "phone_number must contain 3–20 digits and only common phone-number punctuation.")
    require(message.isNotBlank() && message.length <= MAX_SMS_MESSAGE_CHARS) {
      "SMS message must contain 1–$MAX_SMS_MESSAGE_CHARS non-whitespace characters."
    }
    val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", normalized, null))
      .putExtra("sms_body", message)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (intent.resolveActivity(appContext.packageManager) == null) {
      return NativeCapabilityActionResult(false, "No SMS composer can handle this recipient.")
    }
    if (!authorizer.authorize(
        AndroidCapability.SMS_COMPOSE,
        "Open the SMS composer addressed to $normalized with this exact message: $message. The message will not be sent automatically.",
      )
    ) {
      return NativeCapabilityActionResult(false, "Opening the SMS composer was not approved.")
    }
    return try {
      appContext.startActivity(intent)
      NativeCapabilityActionResult(true, "Opened the SMS composer for $normalized. Review and send the message yourself.")
    } catch (error: ActivityNotFoundException) {
      NativeCapabilityActionResult(false, "No SMS composer is available: ${error.message}")
    } catch (error: SecurityException) {
      NativeCapabilityActionResult(false, "Android denied opening the SMS composer: ${error.message}")
    }
  }

  override suspend fun authenticateBiometric(reason: String): Boolean {
    val safeReason = reason.trim().take(MAX_BIOMETRIC_REASON_CHARS)
      .takeIf { it.isNotBlank() } ?: return false
    if (BiometricManager.from(appContext).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) !=
      BiometricManager.BIOMETRIC_SUCCESS) return false
    if (!authorizer.authorize(AndroidCapability.BIOMETRIC_AUTH, "Authenticate with a strong enrolled biometric. Purpose: $safeReason")) return false
    return biometricAuthenticator.authenticate("Confirm your identity", safeReason, null) != null
  }

  override suspend fun createKeystoreKey(alias: String): NativeCapabilityActionResult {
    val safeAlias = normalizeKeystoreAlias(alias)
      ?: return NativeCapabilityActionResult(false, "alias must be 1–32 lowercase letters, digits, underscores, or hyphens.")
    if (BiometricManager.from(appContext).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) !=
      BiometricManager.BIOMETRIC_SUCCESS) {
      return NativeCapabilityActionResult(false, "Enroll a strong biometric in Android settings before creating a biometric-protected key.")
    }
    if (!authorizer.authorize(AndroidCapability.KEYSTORE_OPERATION,
        "Create a non-exportable AES-256 Android Keystore key named $safeAlias. Using it later requires strong biometric authentication.")) {
      return NativeCapabilityActionResult(false, "Keystore key creation was not approved.")
    }
    return try {
      val store = androidKeyStore()
      if (store.containsAlias(fullKeystoreAlias(safeAlias))) {
        NativeCapabilityActionResult(false, "Keystore key $safeAlias already exists.")
      } else {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(KeyGenParameterSpec.Builder(
          fullKeystoreAlias(safeAlias),
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
          .setKeySize(256)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setRandomizedEncryptionRequired(true)
          .setUserAuthenticationRequired(true)
          .setUserAuthenticationParameters(0, BiometricManager.Authenticators.BIOMETRIC_STRONG)
          .setInvalidatedByBiometricEnrollment(true)
          .build())
        generator.generateKey()
        NativeCapabilityActionResult(true, "Created non-exportable biometric-protected AES-256 key $safeAlias.")
      }
    } catch (error: Exception) {
      NativeCapabilityActionResult(false, "Could not create Android Keystore key: " + (error.message ?: error.javaClass.simpleName))
    }
  }

  override suspend fun listKeystoreKeys(): List<String> {
    check(authorizer.authorize(AndroidCapability.KEYSTORE_OPERATION,
      "List Gallery Agent Keystore key aliases. Key material and encrypted contents will not be read.")) {
      "Keystore key listing was not approved."
    }
    return androidKeyStore().aliases().toList()
      .filter { it.startsWith(KEYSTORE_ALIAS_PREFIX) }
      .map { it.removePrefix(KEYSTORE_ALIAS_PREFIX) }
      .sorted()
      .take(MAX_KEYSTORE_KEYS)
  }

  override suspend fun deleteKeystoreKey(alias: String): NativeCapabilityActionResult {
    val safeAlias = normalizeKeystoreAlias(alias)
      ?: return NativeCapabilityActionResult(false, "alias must be 1–32 lowercase letters, digits, underscores, or hyphens.")
    if (!authorizer.authorize(AndroidCapability.KEYSTORE_OPERATION,
        "Permanently delete Gallery Agent Keystore key $safeAlias. Existing ciphertext encrypted with this key will become unrecoverable.")) {
      return NativeCapabilityActionResult(false, "Keystore key deletion was not approved.")
    }
    return try {
      val store = androidKeyStore()
      val fullAlias = fullKeystoreAlias(safeAlias)
      if (!store.containsAlias(fullAlias)) NativeCapabilityActionResult(false, "Keystore key $safeAlias does not exist.")
      else {
        store.deleteEntry(fullAlias)
        NativeCapabilityActionResult(true, "Deleted Keystore key $safeAlias. Ciphertext encrypted with it can no longer be decrypted.")
      }
    } catch (error: Exception) {
      NativeCapabilityActionResult(false, "Could not delete Android Keystore key: " + (error.message ?: error.javaClass.simpleName))
    }
  }

  override suspend fun encryptKeystoreData(alias: String, plaintext: String): NativeKeystoreCiphertext {
    val safeAlias = normalizeKeystoreAlias(alias) ?: error("Invalid Keystore alias.")
    requireStrongBiometricAvailable()
    require(plaintext.isNotEmpty() && plaintext.length <= MAX_KEYSTORE_PLAINTEXT_CHARS) {
      "plaintext must contain 1–$MAX_KEYSTORE_PLAINTEXT_CHARS characters."
    }
    check(authorizer.authorize(AndroidCapability.KEYSTORE_OPERATION,
      "Encrypt " + plaintext.length + " characters using biometric-protected Keystore key $safeAlias. Plaintext is processed in memory only.")) {
      "Keystore encryption was not approved."
    }
    val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, getKeystoreSecretKey(safeAlias))
    val authentication = biometricAuthenticator.authenticate(
      "Encrypt protected data", "Authenticate to use Keystore key $safeAlias.", BiometricPrompt.CryptoObject(cipher),
    ) ?: error("Strong biometric authentication was cancelled or failed.")
    val authenticatedCipher = authentication.cryptoObject?.cipher ?: error("Biometric authentication did not return the Keystore cipher.")
    val encrypted = authenticatedCipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    val iv = authenticatedCipher.iv ?: error("Android Keystore did not return a GCM IV.")
    return NativeKeystoreCiphertext(
      alias = safeAlias,
      ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP),
      ciphertextBase64 = Base64.encodeToString(encrypted, Base64.NO_WRAP),
    )
  }

  override suspend fun decryptKeystoreData(alias: String, ivBase64: String, ciphertextBase64: String): String {
    val safeAlias = normalizeKeystoreAlias(alias) ?: error("Invalid Keystore alias.")
    requireStrongBiometricAvailable()
    val iv = decodeKeystoreBase64(ivBase64, "iv_base64", MIN_GCM_IV_BYTES, MAX_GCM_IV_BYTES)
    val ciphertext = decodeKeystoreBase64(ciphertextBase64, "ciphertext_base64", GCM_TAG_BYTES, MAX_KEYSTORE_CIPHERTEXT_BYTES)
    check(authorizer.authorize(AndroidCapability.KEYSTORE_OPERATION,
      "Decrypt " + ciphertext.size + " encrypted bytes using biometric-protected Keystore key $safeAlias. Plaintext is returned to the local agent only.")) {
      "Keystore decryption was not approved."
    }
    val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, getKeystoreSecretKey(safeAlias), GCMParameterSpec(GCM_TAG_BITS, iv))
    val authentication = biometricAuthenticator.authenticate(
      "Decrypt protected data", "Authenticate to use Keystore key $safeAlias.", BiometricPrompt.CryptoObject(cipher),
    ) ?: error("Strong biometric authentication was cancelled or failed.")
    val authenticatedCipher = authentication.cryptoObject?.cipher ?: error("Biometric authentication did not return the Keystore cipher.")
    return authenticatedCipher.doFinal(ciphertext).toString(Charsets.UTF_8)
  }

  private fun requireStrongBiometricAvailable() {
    check(
      BiometricManager.from(appContext).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
        BiometricManager.BIOMETRIC_SUCCESS
    ) { "Enroll a strong biometric and make sure it is available before using this Keystore key." }
  }

  private fun androidKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

  private fun getKeystoreSecretKey(alias: String): SecretKey =
    androidKeyStore().getKey(fullKeystoreAlias(alias), null) as? SecretKey
      ?: error("Keystore key $alias does not exist.")

  private fun normalizeKeystoreAlias(value: String): String? =
    value.trim().lowercase().takeIf { it.matches(Regex("^[a-z0-9_-]{1,32}$")) }

  private fun fullKeystoreAlias(alias: String) = KEYSTORE_ALIAS_PREFIX + alias

  private fun decodeKeystoreBase64(value: String, name: String, minBytes: Int, maxBytes: Int): ByteArray {
    require(value.length <= ((maxBytes + 2) / 3) * 4 + 4) { "$name exceeds the encoded size limit." }
    val decoded = runCatching { Base64.decode(value, Base64.NO_WRAP) }.getOrNull()
      ?: error("$name must be valid unwrapped Base64.")
    require(decoded.size in minBytes..maxBytes) { "$name must decode to $minBytes–$maxBytes bytes." }
    return decoded
  }

  private fun normalizePhoneNumber(value: String): String? {
    val normalized = value.trim()
    if (normalized.isEmpty() || normalized.length > MAX_PHONE_NUMBER_CHARS) return null
    if (!normalized.matches(Regex("^\\+?[0-9][0-9 .()-]*$"))) return null
    val digits = normalized.count { it.isDigit() }
    return normalized.takeIf { digits in 3..20 }
  }

  private fun flashlightAvailability(): ProviderAvailability {
    if (appContext.checkSelfPermission(Manifest.permission.CAMERA) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      return ProviderAvailability.PERMISSION_REQUIRED
    }
    return if (findFlashCameraId() != null) ProviderAvailability.AVAILABLE
    else ProviderAvailability.UNSUPPORTED
  }

  private fun findFlashCameraId(): String? {
    val cameraManager = appContext.getSystemService(CameraManager::class.java) ?: return null
    return try {
      val cameraIds = cameraManager.cameraIdList
      cameraIds.firstOrNull { id ->
        val characteristics = cameraManager.getCameraCharacteristics(id)
        characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
          characteristics.get(CameraCharacteristics.LENS_FACING) ==
          CameraCharacteristics.LENS_FACING_BACK
      } ?: cameraIds.firstOrNull { id ->
        cameraManager.getCameraCharacteristics(id)
          .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
      }
    } catch (_: CameraAccessException) {
      null
    } catch (_: SecurityException) {
      null
    }
  }

  private fun queryLaunchableApps(): List<NativeLaunchableApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    val activities = appContext.packageManager.queryIntentActivities(
      intent,
      0,
    )
    return activities.mapNotNull { resolveInfo ->
      val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
      val packageName = activityInfo.packageName
      val label = resolveInfo.loadLabel(appContext.packageManager).toString()
        .takeIf { it.isNotBlank() } ?: packageName
      NativeLaunchableApp(packageName, label)
    }.distinctBy { it.packageName }
  }

  private companion object {
    const val MAX_VIBRATION_DURATION_MS = 1_000L
    const val MIN_AUDIO_RECORD_MS = 1_000L
    const val MAX_AUDIO_RECORD_MS = 30_000L
    const val MAX_WAKE_LOCK_DURATION_MS = 5 * 60 * 1_000L
    const val MIN_BLUETOOTH_SCAN_MS = 1_000L
    const val MAX_BLUETOOTH_SCAN_MS = 10_000L
    const val MAX_BLUETOOTH_RESULTS = 32
    const val MAX_BLUETOOTH_SERVICE_UUIDS = 16
    const val MIN_GATT_TIMEOUT_MS = 5_000L
    const val MAX_GATT_TIMEOUT_MS = 20_000L
    const val MAX_GATT_SERVICES = 32
    const val MAX_GATT_CHARACTERISTICS = 128
    const val MAX_GATT_DESCRIPTORS_PER_CHARACTERISTIC = 16
    const val MAX_GATT_VALUE_BYTES = 512
    const val MAX_GATT_BASE64_CHARS = 684
    const val MAX_CONTACT_QUERY_CHARS = 80
    const val MAX_CONTACT_RESULTS = 20
    const val MAX_CONTACT_NAME_CHARS = 100
    const val MAX_CONTACT_VALUES_PER_TYPE = 3
    const val MAX_CONTACT_VALUE_CHARS = 120
    const val MAX_PHONE_NUMBER_CHARS = 32
    const val MAX_SMS_MESSAGE_CHARS = 1_600
    const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val KEYSTORE_ALIAS_PREFIX = "gallery_agent_"
    const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    const val GCM_TAG_BITS = 128
    const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    const val MIN_GCM_IV_BYTES = 12
    const val MAX_GCM_IV_BYTES = 16
    const val MAX_KEYSTORE_PLAINTEXT_CHARS = 4_096
    const val MAX_KEYSTORE_CIPHERTEXT_BYTES = 16_400
    const val MAX_KEYSTORE_KEYS = 50
    const val MAX_BIOMETRIC_REASON_CHARS = 160
    const val MAX_CLIPBOARD_ITEMS = 5
    const val MAX_CLIPBOARD_CHARS = 8_192
    const val MAX_CLIPBOARD_WRITE_CHARS = 2_048
    const val MAX_NOTIFICATION_TITLE_CHARS = 100
    const val MAX_NOTIFICATION_MESSAGE_CHARS = 1_000
    const val MAX_NOTIFICATION_CHANNEL_NAME_CHARS = 80
    const val MAX_NOTIFICATION_CHANNELS = 20
    const val MAX_ACTIVE_NOTIFICATIONS = 50
    const val MAX_SMS_INBOX_ITEMS = 20
    const val MAX_SMS_SENDER_CHARS = 64
    const val MAX_SMS_BODY_PREVIEW_CHARS = 240
    const val MAX_CALL_LOG_ITEMS = 20
    const val MAX_CALL_LOG_NAME_CHARS = 80
    const val MAX_CALL_LOG_NUMBER_CHARS = 32
    const val MAX_OTHER_NOTIFICATION_ITEMS = 20
    const val MAX_OTHER_NOTIFICATION_APP_CHARS = 60
    const val MAX_OTHER_NOTIFICATION_TITLE_CHARS = 120
    const val MAX_OTHER_NOTIFICATION_TEXT_CHARS = 240
    const val DEFAULT_NOTIFICATION_CHANNEL_ID = "agent_skill_tasks_channel"
    const val DEFAULT_NOTIFICATION_CHANNEL_NAME = "Agent Skill Task"
    const val MAX_USB_DEVICES = 32
    const val MAX_USB_INTERFACES = 16
  }

  private fun canResolveWebUrl(): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid"))
    return intent.resolveActivity(appContext.packageManager) != null
  }
}
