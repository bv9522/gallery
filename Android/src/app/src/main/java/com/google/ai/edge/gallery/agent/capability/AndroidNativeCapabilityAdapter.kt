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
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings

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

/**
 * The caller must connect this to the app's existing user-approval boundary. There is deliberately
 * no permissive default: device mutations fail closed unless the caller supplies authorization.
 */
fun interface NativeCapabilityAuthorizer {
  suspend fun authorize(capability: AndroidCapability, summary: String): Boolean
}

/**
 * Typed Android framework operations. Discovery is observational; mutating methods require an
 * injected authorizer and validate their inputs before touching Android system services.
 *
 * This adapter never requests permissions. The UI must request any required permission explicitly.
 */
class AndroidNativeCapabilityAdapter(
  context: Context,
  private val authorizer: NativeCapabilityAuthorizer,
) : NativeCapabilityOperations {
  private val appContext = context.applicationContext
  private val wakeLockGuard = Any()
  private var agentWakeLock: PowerManager.WakeLock? = null

  override val descriptor: CapabilityProviderDescriptor =
    CapabilityCatalog.providers.first { it.id == CapabilityProviderId.ANDROID_NATIVE }

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

    return CapabilityProviderObservation(
      providerId = descriptor.id,
      availability = ProviderAvailability.AVAILABLE,
      detail = "Native Android framework probe completed.",
      capabilityAvailability = capabilities,
      capabilityDetails = details,
    )
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
      .take(MAX_AUDIO_DEVICES)
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
      mode = if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) "AUTOMATIC" else "MANUAL",
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
      val modeChanged = Settings.System.putInt(
        resolver,
        Settings.System.SCREEN_BRIGHTNESS_MODE,
        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
      )
      val levelChanged = Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, level)
      if (modeChanged && levelChanged) {
        NativeCapabilityActionResult(true, "Display brightness set to $level of 255 (manual mode).")
      } else {
        NativeCapabilityActionResult(false, "Android did not accept the brightness setting.")
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
    const val MAX_AUDIO_DEVICES = 24
    const val MAX_VIBRATION_DURATION_MS = 1_000L
    const val MAX_WAKE_LOCK_DURATION_MS = 5 * 60 * 1_000L
  }

  private fun canResolveWebUrl(): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid"))
    return intent.resolveActivity(appContext.packageManager) != null
  }
}
