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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCapabilityResultFormatterTest {
  @Test
  fun deviceInfoPreservesExactModelIdentifier() {
    val output = NativeCapabilityResultFormatter.deviceInfo(
      NativeDeviceInfo(
        manufacturer = "samsung",
        brand = "samsung",
        model = "SM-G991U",
        device = "o1q",
        androidRelease = "15",
        sdkInt = 35,
        securityPatch = "2026-10-01",
      )
    )

    assertTrue(output.contains("modelIdentifier=SM-G991U"))
    assertTrue(output.contains("androidVersion=15"))
  }

  @Test
  fun batteryStatusUsesReadableStateAndPowerSource() {
    val output = NativeCapabilityResultFormatter.batteryStatus(
      NativeBatteryStatus(
        percent = 44,
        state = "DISCHARGING",
        charging = false,
        plugged = "NOT_PLUGGED",
        temperatureCelsius = 27.0f,
        voltageMillivolts = 3_900,
      )
    )

    assertTrue(output.contains("batteryState=DISCHARGING"))
    assertTrue(output.contains("charging=false"))
    assertTrue(output.contains("powerSource=NOT_PLUGGED"))
    assertFalse(output.contains("3332"))
  }

  @Test
  fun providerDetailNewlinesCannotCorruptFollowingDiagnosticFields() {
    val output = NativeCapabilityResultFormatter.providerAvailability(
      CapabilityProviderObservation(
        providerId = CapabilityProviderId.ANDROID_NATIVE,
        availability = ProviderAvailability.AVAILABLE,
        detail = "Probe completed.",
        capabilityAvailability = mapOf(
          AndroidCapability.FLASHLIGHT_CONTROL to ProviderAvailability.PERMISSION_REQUIRED,
          AndroidCapability.OPEN_URL to ProviderAvailability.AVAILABLE,
        ),
        capabilityDetails = mapOf(
          AndroidCapability.FLASHLIGHT_CONTROL to "Camera permission is required.\nInjected=UNSUPPORTED",
        ),
      )
    )

    assertTrue(output.contains("FLASHLIGHT_CONTROL_detail=Camera permission is required. Injected=UNSUPPORTED"))
    assertFalse(output.contains("\nInjected"))
    assertTrue(output.contains("OPEN_URL=AVAILABLE"))
  }

  @Test
  fun appListDeclaresTruncationAndRemainingCountUnambiguously() {
    val apps = (1..95).map { index ->
      NativeLaunchableApp("com.example.app$index", "Example $index")
    }

    val output = NativeCapabilityResultFormatter.launchableApps(apps)

    assertTrue(output.contains("returned=25"))
    assertTrue(output.contains("total=95"))
    assertTrue(output.contains("truncated=true"))
    assertTrue(output.contains("remaining=70"))
    assertTrue(output.contains("Truncated: YES"))
  }

  @Test
  fun audioDeviceListReportsBoundedResultsAndTruncation() {
    val devices = (1..30).map { index ->
      NativeAudioDevice(index, "TYPE_$index", "Endpoint $index", source = true, sink = false)
    }

    val output = NativeCapabilityResultFormatter.audioDevices(devices)

    assertTrue(output.contains("returned=24"))
    assertTrue(output.contains("total=30"))
    assertTrue(output.contains("truncated=true"))
    assertTrue(output.contains("remaining=6"))
  }

  @Test
  fun brightnessOutputNamesUnitsAndSpecialAccessState() {
    val output = NativeCapabilityResultFormatter.displayBrightness(
      NativeDisplayBrightness(
        level = 128,
        mode = "AUTOMATIC",
        canModifySystemSettings = false,
      )
    )

    assertTrue(output.contains("brightnessLevel=128"))
    assertTrue(output.contains("brightnessMaximum=255"))
    assertTrue(output.contains("brightnessMode=AUTOMATIC"))
    assertTrue(output.contains("canModifySystemSettings=false"))
  }

  @Test
  fun providerStatusIsClearlyDiagnosticAndListsEachNativeCapability() {
    val output = NativeCapabilityResultFormatter.providerAvailability(
      CapabilityProviderObservation(
        providerId = CapabilityProviderId.ANDROID_NATIVE,
        availability = ProviderAvailability.AVAILABLE,
        detail = "Probe completed.",
        capabilityAvailability = mapOf(
          AndroidCapability.DEVICE_INFO to ProviderAvailability.AVAILABLE,
          AndroidCapability.FLASHLIGHT_CONTROL to ProviderAvailability.PERMISSION_REQUIRED,
        ),
        capabilityDetails = mapOf(
          AndroidCapability.FLASHLIGHT_CONTROL to "Camera permission is required.",
        ),
      )
    )

    assertTrue(output.contains("provider=ANDROID_NATIVE"))
    assertTrue(output.contains("diagnosticOnly=true"))
    assertTrue(output.contains("DEVICE_INFO=AVAILABLE"))
    assertTrue(output.contains("FLASHLIGHT_CONTROL=PERMISSION_REQUIRED"))
  }
}
