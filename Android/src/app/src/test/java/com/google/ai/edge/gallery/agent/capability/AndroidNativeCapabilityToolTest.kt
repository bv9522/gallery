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

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidNativeCapabilityToolTest {
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

    assertTrue(volume.isSuccessful)
    assertTrue(flashlight.isSuccessful)
    assertTrue(launch.isSuccessful)
    assertTrue(url.isSuccessful)
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
  }
}
