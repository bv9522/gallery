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
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
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
import android.media.AudioDeviceInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.MediaStore
import android.provider.Settings
import kotlinx.coroutines.delay
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
      !hasBluetoothPermission(Manifest.permission.BLUETOOTH_CONNECT) -> {
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
      !hasBluetoothPermission(Manifest.permission.BLUETOOTH_SCAN) ||
        !hasBluetoothPermission(Manifest.permission.BLUETOOTH_CONNECT) -> {
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

    capabilities[AndroidCapability.BLUETOOTH_GATT] = ProviderAvailability.UNSUPPORTED
    details[AndroidCapability.BLUETOOTH_GATT] =
      "Bluetooth GATT sessions are not implemented in this onboarding phase."

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
    ensureBluetoothPermission(Manifest.permission.BLUETOOTH_CONNECT)
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
    ensureBluetoothPermission(Manifest.permission.BLUETOOTH_SCAN)
    ensureBluetoothPermission(Manifest.permission.BLUETOOTH_CONNECT)
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

  private suspend fun ensureBluetoothPermission(permission: String) {
    if (hasBluetoothPermission(permission)) return
    if (!permissionRequester.request(permission) || !hasBluetoothPermission(permission)) {
      throw SecurityException("Android permission $permission was denied or unavailable.")
    }
  }

  private fun hasBluetoothPermission(permission: String): Boolean =
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
    const val MAX_WAKE_LOCK_DURATION_MS = 5 * 60 * 1_000L
    const val MIN_BLUETOOTH_SCAN_MS = 1_000L
    const val MAX_BLUETOOTH_SCAN_MS = 10_000L
    const val MAX_BLUETOOTH_RESULTS = 32
    const val MAX_BLUETOOTH_SERVICE_UUIDS = 16
    const val MAX_USB_DEVICES = 32
    const val MAX_USB_INTERFACES = 16
  }

  private fun canResolveWebUrl(): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid"))
    return intent.resolveActivity(appContext.packageManager) != null
  }
}
