/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.google.ai.edge.gallery.agent.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class CapabilityRegistryTest {
  private val registry = CapabilityCatalog.createRegistry()

  @Test
  fun nativeAndroidIsPreferredForSystemControls() {
    val result = registry.resolve(
      AndroidCapability.VOLUME_CONTROL,
      listOf(
        observation(CapabilityProviderId.ANDROID_NATIVE, ProviderAvailability.AVAILABLE),
        observation(CapabilityProviderId.TERMUX_API, ProviderAvailability.AVAILABLE),
        observation(CapabilityProviderId.SHIZUKU_SHELL, ProviderAvailability.AVAILABLE),
      ),
    )
    assertEquals(CapabilityProviderId.ANDROID_NATIVE, result.selectedProviderId)
    assertEquals(
      listOf(
        CapabilityProviderId.ANDROID_NATIVE,
        CapabilityProviderId.TERMUX_API,
        CapabilityProviderId.SHIZUKU_SHELL,
      ),
      result.candidates.take(3).map { it.providerId },
    )
  }

  @Test
  fun shellProviderIsSelectedWhenPreferredProviderIsUnavailable() {
    val result = registry.resolve(
      AndroidCapability.FILE_READ,
      listOf(
        observation(CapabilityProviderId.SHIZUKU_SHELL, ProviderAvailability.SERVICE_STOPPED),
        observation(CapabilityProviderId.TERMUX_RISH, ProviderAvailability.AVAILABLE),
        observation(CapabilityProviderId.TERMUX_SHELL, ProviderAvailability.AVAILABLE),
      ),
    )
    assertEquals(CapabilityProviderId.TERMUX_RISH, result.selectedProviderId)
  }

  @Test
  fun accessibilityIsRequiredForSemanticUiTreeReads() {
    val result = registry.resolve(
      AndroidCapability.UI_TREE_READ,
      listOf(
        observation(CapabilityProviderId.SHIZUKU_SHELL, ProviderAvailability.AVAILABLE),
        observation(
          CapabilityProviderId.ANDROID_ACCESSIBILITY,
          ProviderAvailability.PERMISSION_REQUIRED,
        ),
      ),
    )
    assertNull(result.selectedProviderId)
    assertEquals(ProviderAvailability.PERMISSION_REQUIRED, result.candidates.single().availability)
  }

  @Test
  fun missingObservationIsUnknownAndNeverSelected() {
    val result = registry.resolve(AndroidCapability.DEVICE_INFO, emptyList())
    assertFalse(result.isAvailable)
    assertNull(result.selectedProviderId)
    assertTrue(result.candidates.all { it.availability == ProviderAvailability.UNKNOWN })
  }

  @Test
  fun unavailablePreferredProviderCanFallBack() {
    val result = registry.resolve(
      AndroidCapability.FLASHLIGHT_CONTROL,
      listOf(
        observation(
          CapabilityProviderId.ANDROID_NATIVE,
          ProviderAvailability.PERMISSION_REQUIRED,
          "Camera permission is required.",
        ),
        observation(CapabilityProviderId.TERMUX_API, ProviderAvailability.AVAILABLE),
      ),
    )
    assertEquals(CapabilityProviderId.TERMUX_API, result.selectedProviderId)
    assertEquals("Camera permission is required.", result.candidates.first().detail)
  }

  @Test
  fun duplicateProviderObservationsAreRejected() {
    try {
      registry.resolve(
        AndroidCapability.DEVICE_INFO,
        listOf(
          observation(CapabilityProviderId.ANDROID_NATIVE, ProviderAvailability.AVAILABLE),
          observation(CapabilityProviderId.ANDROID_NATIVE, ProviderAvailability.SERVICE_STOPPED),
        ),
      )
      throw AssertionError("Expected duplicate observations to be rejected.")
    } catch (expected: IllegalArgumentException) {
      assertTrue(expected.message.orEmpty().contains("Duplicate provider observations"))
    }
  }


  @Test
  fun discoveryReturnsValidatedProviderObservations() = runBlocking {
    val descriptor = registry.providers().first {
      it.id == CapabilityProviderId.ANDROID_NATIVE
    }
    val probe = object : CapabilityProviderProbe {
      override val descriptor = descriptor
      override suspend fun inspect() = CapabilityProviderObservation(
        descriptor.id,
        ProviderAvailability.AVAILABLE,
        "Native framework services are ready.",
      )
    }

    val observations = registry.discover(listOf(probe))

    assertEquals(1, observations.size)
    assertEquals(CapabilityProviderId.ANDROID_NATIVE, observations.single().providerId)
    assertEquals(ProviderAvailability.AVAILABLE, observations.single().availability)
  }

  private fun observation(
    providerId: CapabilityProviderId,
    availability: ProviderAvailability,
    detail: String? = null,
  ) = CapabilityProviderObservation(providerId, availability, detail)
}
