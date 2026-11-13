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

import com.google.ai.edge.gallery.tools.CapturePhotoToolAction
import com.google.ai.edge.gallery.tools.RequestPermissionToolAction
import com.google.ai.edge.gallery.tools.ToolAction
import com.google.gson.JsonObject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidNativeCapabilityToolTest {
  @Test
  fun runtimePermissionRequesterReturnsOnlyTheSystemPermissionResult() = runBlocking {
    val actions = Channel<ToolAction>(Channel.UNLIMITED)
    val requester = ChannelNativeRuntimePermissionRequester(actions, timeoutMs = 1_000L)
    val responder = launch {
      val action = actions.receive() as RequestPermissionToolAction
      assertEquals("android.permission.BLUETOOTH_SCAN", action.permission)
      action.result.complete(true)
    }
    assertTrue(requester.request("android.permission.BLUETOOTH_SCAN"))
    responder.join()
  }

  @Test
  fun runtimePermissionRequesterFailsClosedWhenActionChannelIsClosed() = runBlocking {
    val actions = Channel<ToolAction>(Channel.UNLIMITED).apply { close() }
    val requester = ChannelNativeRuntimePermissionRequester(actions, timeoutMs = 1_000L)
    assertFalse(requester.request("android.permission.BLUETOOTH_CONNECT"))
  }

  @Test
  fun runtimePermissionRequesterTimesOutIfNoUiConsumerCanReceiveTheAction() = runBlocking {
    val actions = Channel<ToolAction>(capacity = 0)
    val requester = ChannelNativeRuntimePermissionRequester(actions, timeoutMs = 10L)
    assertFalse(requester.request("android.permission.BLUETOOTH_CONNECT"))
  }

  @Test
  fun photoCaptureRequesterPassesTheGalleryOwnedUriAndReturnsTheCameraResult() = runBlocking {
    val actions = Channel<ToolAction>(Channel.UNLIMITED)
    val requester = ChannelNativePhotoCaptureRequester(actions, timeoutMs = 1_000L)
    val uri = "content://media/external/images/media/42"
    val responder = launch {
      val action = actions.receive() as CapturePhotoToolAction
      assertEquals(uri, action.contentUri)
      action.result.complete(true)
    }
    assertTrue(requester.capture(uri))
    responder.join()
  }

  @Test
  fun photoCaptureRequesterTimesOutWhenNoUiConsumerReceivesTheAction() = runBlocking {
    val actions = Channel<ToolAction>(capacity = 0)
    val requester = ChannelNativePhotoCaptureRequester(actions, timeoutMs = 10L)
    assertFalse(requester.capture("content://media/external/images/media/42"))
  }

  @Test
  fun mutationOperationsReachTheirMatchingNativeAdapterMethods() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    val tool = AndroidNativeCapabilityTool(adapter)

    val volume = tool.execute(arguments("set_volume").apply {
      addProperty("stream", "MUSIC")
      addProperty("volume", 7)
    })
    val flashlight = tool.execute(arguments("set_flashlight").apply {
      addProperty("enabled", true)
    })
    val launch = tool.execute(arguments("launch_app").apply {
      addProperty("package_name", "org.example.app")
    })
    val url = tool.execute(arguments("open_url").apply {
      addProperty("url", "https://example.com")
    })
    val bonded = tool.execute(arguments("bluetooth_bonded_devices"))
    val bleScan = tool.execute(arguments("bluetooth_le_scan").apply { addProperty("duration_ms", 5_000) })
    val usb = tool.execute(arguments("usb_devices"))
    val audio = tool.execute(arguments("record_audio").apply { addProperty("duration_ms", 5_000) })
    val photo = tool.execute(arguments("capture_photo"))
    val wallpaper = tool.execute(arguments("set_wallpaper").apply {
      addProperty("content_uri", "content://media/external/images/media/42")
      addProperty("target", "SYSTEM")
    })

    assertTrue(volume.isSuccessful)
    assertTrue(flashlight.isSuccessful)
    assertTrue(launch.isSuccessful)
    assertTrue(url.isSuccessful)
    assertTrue(bonded.isSuccessful)
    assertTrue(bleScan.isSuccessful)
    assertTrue(usb.isSuccessful)
    assertTrue(audio.isSuccessful)
    assertTrue(photo.isSuccessful)
    assertTrue(wallpaper.isSuccessful)
    assertEquals("content://media/external/audio/media/84", audio.content)
    assertEquals("content://media/external/images/media/42", photo.content)
    assertEquals("set_wallpaper:content://media/external/images/media/42:SYSTEM", adapter.discoveryCalls.last())
    assertEquals(listOf("bluetooth_bonded_devices", "bluetooth_le_scan:5000", "usb_devices"), adapter.discoveryCalls.take(3))
    val invalidScan = tool.execute(arguments("bluetooth_le_scan").apply { addProperty("duration_ms", 10_001) })
    val invalidAudio = tool.execute(arguments("record_audio").apply { addProperty("duration_ms", 30_001) })
    assertFalse(invalidScan.isSuccessful)
    assertFalse(invalidAudio.isSuccessful)
    assertEquals(5, adapter.discoveryCalls.size)
    assertEquals(
      listOf(
        "set_volume:MUSIC:7",
        "set_flashlight:true",
        "launch_app:org.example.app",
        "open_url:https://example.com",
      ),
      adapter.mutations,
    )
  }

  @Test
  fun bluetoothGattInspectionDispatchesOnlyWithAddressAndBoundedTimeout() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    val tool = AndroidNativeCapabilityTool(adapter)

    val result = tool.execute(arguments("bluetooth_gatt_services").apply {
      addProperty("device_address", "AA:BB:CC:DD:EE:FF")
      addProperty("timeout_ms", 10_000)
    })
    val missingAddress = tool.execute(arguments("bluetooth_gatt_services").apply {
      addProperty("timeout_ms", 10_000)
    })
    val invalidTimeout = tool.execute(arguments("bluetooth_gatt_services").apply {
      addProperty("device_address", "AA:BB:CC:DD:EE:FF")
      addProperty("timeout_ms", 20_001)
    })
    val invalidAddress = tool.execute(arguments("bluetooth_gatt_services").apply {
      addProperty("device_address", "not-a-mac")
    })

    assertTrue(result.isSuccessful)
    assertTrue(result.content.contains("AA:BB:CC:DD:EE:FF"))
    assertFalse(missingAddress.isSuccessful)
    assertFalse(invalidTimeout.isSuccessful)
    assertFalse(invalidAddress.isSuccessful)
    assertEquals(listOf("bluetooth_gatt_services:AA:BB:CC:DD:EE:FF:10000"), adapter.discoveryCalls)
  }

  @Test
  fun bluetoothGattReadWriteDispatchBoundedUuidAddressAndPayloadArguments() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    val tool = AndroidNativeCapabilityTool(adapter)
    val address = "AA:BB:CC:DD:EE:FF"
    val service = "0000180f-0000-1000-8000-00805f9b34fb"
    val characteristic = "00002a19-0000-1000-8000-00805f9b34fb"

    val read = tool.execute(arguments("bluetooth_gatt_read").apply {
      addProperty("device_address", address)
      addProperty("service_uuid", service)
      addProperty("characteristic_uuid", characteristic)
      addProperty("timeout_ms", 10_000)
    })
    val write = tool.execute(arguments("bluetooth_gatt_write").apply {
      addProperty("device_address", address)
      addProperty("service_uuid", service)
      addProperty("characteristic_uuid", characteristic)
      addProperty("value_base64", "AQID")
    })
    val invalidUuid = tool.execute(arguments("bluetooth_gatt_read").apply {
      addProperty("device_address", address)
      addProperty("service_uuid", "not-a-uuid")
      addProperty("characteristic_uuid", characteristic)
    })
    val invalidPayload = tool.execute(arguments("bluetooth_gatt_write").apply {
      addProperty("device_address", address)
      addProperty("service_uuid", service)
      addProperty("characteristic_uuid", characteristic)
      addProperty("value_base64", "not-base64!")
    })

    assertTrue(read.isSuccessful)
    assertTrue(read.content.contains("AQID"))
    assertTrue(write.isSuccessful)
    assertFalse(invalidUuid.isSuccessful)
    assertFalse(invalidPayload.isSuccessful)
    assertEquals(
      listOf(
        "bluetooth_gatt_read:$address:$service:$characteristic:15000",
        "bluetooth_gatt_write:$address:$service:$characteristic:AQID:15000",
      ),
      adapter.discoveryCalls,
    )
  }

  @Test
  fun systemCapabilityOperationsReachTheirMatchingAdapterMethods() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    val tool = AndroidNativeCapabilityTool(adapter)

    val devices = tool.execute(arguments("audio_devices"))
    val brightness = tool.execute(arguments("display_brightness"))
    val setBrightness = tool.execute(arguments("set_display_brightness").apply {
      addProperty("level", 120)
    })
    val vibration = tool.execute(arguments("vibrate").apply {
      addProperty("duration_ms", 250)
    })
    val wakeLock = tool.execute(arguments("set_wake_lock").apply {
      addProperty("held", true)
      addProperty("duration_ms", 30_000)
    })
    val wakeLockStatus = tool.execute(arguments("wake_lock_status"))
    val releaseWakeLock = tool.execute(arguments("set_wake_lock").apply {
      addProperty("held", false)
    })

    assertTrue(devices.isSuccessful)
    assertTrue(brightness.isSuccessful)
    assertTrue(setBrightness.isSuccessful)
    assertTrue(vibration.isSuccessful)
    assertTrue(wakeLock.isSuccessful)
    assertTrue(wakeLockStatus.isSuccessful)
    assertTrue(releaseWakeLock.isSuccessful)
    assertEquals(
      listOf(
        "set_display_brightness:120",
        "vibrate:250",
        "set_wake_lock:true:30000",
        "set_wake_lock:false:0",
      ),
      adapter.mutations,
    )
  }

  @Test
  fun invalidSystemMutationArgumentsAreRejectedBeforeAdapterDispatch() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    val tool = AndroidNativeCapabilityTool(adapter)

    val missingBrightness = tool.execute(arguments("set_display_brightness"))
    val missingVibration = tool.execute(arguments("vibrate"))
    val missingWakeLockState = tool.execute(arguments("set_wake_lock"))
    val missingWakeLockDuration = tool.execute(arguments("set_wake_lock").apply {
      addProperty("held", true)
    })

    assertFalse(missingBrightness.isSuccessful)
    assertFalse(missingVibration.isSuccessful)
    assertFalse(missingWakeLockState.isSuccessful)
    assertFalse(missingWakeLockDuration.isSuccessful)
    assertTrue(adapter.mutations.isEmpty())
  }

  @Test
  fun unavailableMutationCapabilityNeverReachesAdapter() = runBlocking {
    val adapter = FakeNativeCapabilityOperations().apply {
      availability[AndroidCapability.FLASHLIGHT_CONTROL] = ProviderAvailability.PERMISSION_REQUIRED
    }
    val tool = AndroidNativeCapabilityTool(adapter)

    val result = tool.execute(arguments("set_flashlight").apply {
      addProperty("enabled", true)
    })

    assertFalse(result.isSuccessful)
    assertFalse(result.content.isBlank())
    assertTrue(adapter.mutations.isEmpty())
  }

  @Test
  fun invalidMutationArgumentsAreRejectedBeforeAdapterDispatch() = runBlocking {
    val adapter = FakeNativeCapabilityOperations()
    val tool = AndroidNativeCapabilityTool(adapter)

    val missingVolume = tool.execute(arguments("set_volume").apply {
      addProperty("stream", "MUSIC")
    })
    val invalidStream = tool.execute(arguments("set_volume").apply {
      addProperty("stream", "NOT_A_STREAM")
      addProperty("volume", 4)
    })
    val missingFlashlightState = tool.execute(arguments("set_flashlight"))

    assertFalse(missingVolume.isSuccessful)
    assertFalse(invalidStream.isSuccessful)
    assertFalse(missingFlashlightState.isSuccessful)
    assertTrue(adapter.mutations.isEmpty())
  }

  @Test
  fun adapterFailureIsPreservedAsFailedToolResult() = runBlocking {
    val adapter = FakeNativeCapabilityOperations().apply {
      actionResult = NativeCapabilityActionResult(false, "Android denied the operation.")
    }
    val tool = AndroidNativeCapabilityTool(adapter)

    val result = tool.execute(arguments("set_volume").apply {
      addProperty("volume", 5)
    })

    assertFalse(result.isSuccessful)
    assertEquals("Android denied the operation.", result.content)
  }

  private fun arguments(operation: String) = JsonObject().apply {
    addProperty("operation", operation)
  }

  private class FakeNativeCapabilityOperations : NativeCapabilityOperations {
    override val descriptor =
      CapabilityCatalog.providers.first { it.id == CapabilityProviderId.ANDROID_NATIVE }
    val availability = descriptor.capabilities.associateWith {
      ProviderAvailability.AVAILABLE
    }.toMutableMap()
    val mutations = mutableListOf<String>()
    val discoveryCalls = mutableListOf<String>()
    var actionResult = NativeCapabilityActionResult(true, "Action completed.")

    override suspend fun inspect() = CapabilityProviderObservation(
      providerId = descriptor.id,
      availability = ProviderAvailability.AVAILABLE,
      detail = "Fake native provider.",
      capabilityAvailability = availability.toMap(),
    )

    override fun getDeviceInfo() = NativeDeviceInfo(
      manufacturer = "test",
      brand = "test",
      model = "test",
      device = "test",
      androidRelease = "test",
      sdkInt = 1,
      securityPatch = null,
    )

    override fun getBatteryStatus() = NativeBatteryStatus(
      percent = 50,
      state = "DISCHARGING",
      charging = false,
      plugged = "NOT_PLUGGED",
      temperatureCelsius = 25f,
      voltageMillivolts = 3800,
    )

    override fun getVolumeStatus(stream: NativeAudioStream) =
      NativeVolumeStatus(stream, 5, 15)

    override fun getAudioDevices() = listOf(
      NativeAudioDevice(1, "BUILTIN_SPEAKER", "Speaker", source = false, sink = true)
    )

    override fun getDisplayBrightness() =
      NativeDisplayBrightness(level = 120, mode = "MANUAL", canModifySystemSettings = true)

    override suspend fun setDisplayBrightness(level: Int): NativeCapabilityActionResult {
      mutations += "set_display_brightness:$level"
      return actionResult
    }

    override suspend fun vibrate(durationMs: Long): NativeCapabilityActionResult {
      mutations += "vibrate:$durationMs"
      return actionResult
    }

    override fun isWakeLockHeld() = false

    override suspend fun setWakeLock(
      held: Boolean,
      durationMs: Long,
    ): NativeCapabilityActionResult {
      mutations += "set_wake_lock:$held:$durationMs"
      return actionResult
    }

    override suspend fun setVolume(
      stream: NativeAudioStream,
      volume: Int,
    ): NativeCapabilityActionResult {
      mutations += "set_volume:${stream.name}:$volume"
      return actionResult
    }

    override suspend fun setFlashlight(enabled: Boolean): NativeCapabilityActionResult {
      mutations += "set_flashlight:$enabled"
      return actionResult
    }

    override fun listLaunchableApps() = emptyList<NativeLaunchableApp>()

    override suspend fun launchApp(packageName: String): NativeCapabilityActionResult {
      mutations += "launch_app:$packageName"
      return actionResult
    }

    override suspend fun openUrl(url: String): NativeCapabilityActionResult {
      mutations += "open_url:$url"
      return actionResult
    }
    override suspend fun getBluetoothBondedDevices(): List<NativeBluetoothDevice> {
      discoveryCalls += "bluetooth_bonded_devices"
      return emptyList()
    }
    override suspend fun scanBluetoothLe(durationMs: Long): List<NativeBluetoothDevice> {
      discoveryCalls += "bluetooth_le_scan:$durationMs"
      return emptyList()
    }
    override suspend fun inspectBluetoothGatt(
      deviceAddress: String,
      timeoutMs: Long,
    ): NativeBluetoothGattInspection {
      discoveryCalls += "bluetooth_gatt_services:$deviceAddress:$timeoutMs"
      return NativeBluetoothGattInspection(
        deviceAddress = deviceAddress,
        services = listOf(NativeBluetoothGattService("180F", "PRIMARY", emptyList())),
        totalCharacteristics = 0,
        truncated = false,
      )
    }

    override suspend fun readBluetoothGattCharacteristic(
      deviceAddress: String,
      serviceUuid: String,
      characteristicUuid: String,
      timeoutMs: Long,
    ): NativeBluetoothGattValue {
      discoveryCalls += "bluetooth_gatt_read:$deviceAddress:$serviceUuid:$characteristicUuid:$timeoutMs"
      return NativeBluetoothGattValue(deviceAddress, serviceUuid, characteristicUuid, "AQID", 3)
    }

    override suspend fun writeBluetoothGattCharacteristic(
      deviceAddress: String,
      serviceUuid: String,
      characteristicUuid: String,
      valueBase64: String,
      timeoutMs: Long,
    ): NativeCapabilityActionResult {
      discoveryCalls += "bluetooth_gatt_write:$deviceAddress:$serviceUuid:$characteristicUuid:$valueBase64:$timeoutMs"
      return actionResult
    }

    override suspend fun getUsbDevices(): List<NativeUsbDevice> {
      discoveryCalls += "usb_devices"
      return emptyList()
    }
    override suspend fun recordAudio(durationMs: Long): String {
      discoveryCalls += "record_audio:$durationMs"
      return "content://media/external/audio/media/84"
    }
    override suspend fun capturePhoto(): String = "content://media/external/images/media/42"
    override suspend fun setWallpaper(contentUri: String, target: String): NativeCapabilityActionResult {
      discoveryCalls += "set_wallpaper:$contentUri:$target"
      return actionResult
    }
  }
}
