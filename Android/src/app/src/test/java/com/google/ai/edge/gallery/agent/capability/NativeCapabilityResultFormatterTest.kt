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

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCapabilityResultFormatterTest {
  @Test
  fun deviceInfoPreservesExactModelIdentifierAndNumericSdkType() {
    val output = JsonParser.parseString(
      NativeCapabilityResultFormatter.deviceInfo(
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
    ).asJsonObject

    assertEquals("SM-G991U", output.get("model_identifier").asString)
    assertEquals("15", output.get("android_version").asString)
    assertTrue(output.get("sdk_int").isJsonPrimitive)
    assertTrue(output.get("sdk_int").asJsonPrimitive.isNumber)
    assertEquals(35, output.get("sdk_int").asInt)
  }

  @Test
  fun batteryStatusPreservesNumericTypesAndExplicitUnits() {
    val output = JsonParser.parseString(
      NativeCapabilityResultFormatter.batteryStatus(
        NativeBatteryStatus(
          percent = 55,
          state = "DISCHARGING",
          charging = false,
          plugged = "NOT_PLUGGED",
          temperatureCelsius = 26.0f,
          voltageMillivolts = 3_900,
        )
      )
    ).asJsonObject

    assertTrue(output.get("battery_percent").asJsonPrimitive.isNumber)
    assertEquals(55, output.get("battery_percent").asInt)
    assertTrue(output.get("temperature_celsius").asJsonPrimitive.isNumber)
    assertEquals(26.0, output.get("temperature_celsius").asDouble, 0.0001)
    assertTrue(output.get("charging").asJsonPrimitive.isBoolean)
    assertFalse(output.get("charging").asBoolean)
    assertEquals("%", output.getAsJsonObject("units").get("battery_percent").asString)
    assertEquals("°C", output.getAsJsonObject("units").get("temperature_celsius").asString)
    assertEquals("mV", output.getAsJsonObject("units").get("voltage_millivolts").asString)
  }

  @Test
  fun missingBatteryValuesRemainJsonNullRatherThanZero() {
    val output = JsonParser.parseString(
      NativeCapabilityResultFormatter.batteryStatus(
        NativeBatteryStatus(
          percent = null,
          state = "UNKNOWN",
          charging = null,
          plugged = null,
          temperatureCelsius = null,
          voltageMillivolts = null,
        )
      )
    ).asJsonObject

    assertTrue(output.get("battery_percent").isJsonNull)
    assertTrue(output.get("charging").isJsonNull)
    assertTrue(output.get("temperature_celsius").isJsonNull)
    assertTrue(output.get("voltage_millivolts").isJsonNull)
    assertEquals("unknown", output.get("power_source").asString)
  }

  @Test
  fun providerDetailsCannotInjectSiblingJsonFields() {
    val output = JsonParser.parseString(
      NativeCapabilityResultFormatter.providerAvailability(
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
    ).asJsonObject

    assertEquals("AVAILABLE", output.get("provider_availability").asString)
    assertTrue(output.get("diagnostic_only").asBoolean)
    assertEquals(
      "Camera permission is required. Injected=UNSUPPORTED",
      output.getAsJsonObject("details").get("FLASHLIGHT_CONTROL").asString,
    )
    assertFalse(output.getAsJsonObject("details").has("Injected"))
    assertEquals(
      "AVAILABLE",
      output.getAsJsonObject("capabilities").get("OPEN_URL").asString,
    )
  }

  @Test
  fun appListDeclaresTruncationAndRemainingCountAsTypedValues() {
    val apps = (1..95).map { index ->
      NativeLaunchableApp("com.example.app$index", "Example $index")
    }

    val output = JsonParser.parseString(NativeCapabilityResultFormatter.launchableApps(apps)).asJsonObject

    assertEquals(25, output.get("returned").asInt)
    assertEquals(95, output.get("total").asInt)
    assertTrue(output.get("truncated").asBoolean)
    assertEquals(70, output.get("remaining").asInt)
    assertEquals(25, output.getAsJsonArray("apps").size())
  }

  @Test
  fun audioDeviceListReportsBoundedTypedResultsAndTruncation() {
    val devices = (1..30).map { index ->
      NativeAudioDevice(index, "TYPE_$index", "Endpoint $index", source = true, sink = false)
    }

    val output = JsonParser.parseString(NativeCapabilityResultFormatter.audioDevices(devices)).asJsonObject

    assertEquals(24, output.get("returned").asInt)
    assertEquals(30, output.get("total").asInt)
    assertTrue(output.get("truncated").asBoolean)
    assertEquals(6, output.get("remaining").asInt)
    assertTrue(output.getAsJsonArray("devices")[0].asJsonObject.get("id").asJsonPrimitive.isNumber)
  }

  @Test
  fun brightnessOutputNamesRangeAndSpecialAccessState() {
    val output = JsonParser.parseString(
      NativeCapabilityResultFormatter.displayBrightness(
        NativeDisplayBrightness(
          level = 128,
          mode = "AUTOMATIC",
          canModifySystemSettings = false,
        )
      )
    ).asJsonObject

    assertEquals(128, output.get("brightness_level").asInt)
    assertEquals(255, output.get("brightness_maximum").asInt)
    assertEquals("AUTOMATIC", output.get("brightness_mode").asString)
    assertFalse(output.get("can_modify_system_settings").asBoolean)
    assertEquals("0-255", output.get("units").asString)
  }
}
