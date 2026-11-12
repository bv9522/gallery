/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.google.ai.edge.gallery.agent.capability

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityRegistryTest {
  private val registry = CapabilityCatalog.createRegistry()

  @Test
  fun everyCapabilityHasExactlyOneDeclaredOwner() {
    val allCapabilities = AndroidCapability.values().toSet()
    val groupedOwnership = CapabilityCatalog.preferences.groupBy { it.capability }

    assertEquals(allCapabilities, groupedOwnership.keys)
    assertTrue(groupedOwnership.values.all { it.size == 1 })

    groupedOwnership.forEach { (capability, ownership) ->
      val provider = CapabilityCatalog.providers.single { it.id == ownership.single().providerId }
      assertTrue("${provider.id} must declare $capability", capability in provider.capabilities)
    }
  }

  @Test
  fun nativeAndroidOwnsTypedSystemControls() {
    listOf(
      AndroidCapability.DEVICE_INFO,
      AndroidCapability.BATTERY_STATUS,
      AndroidCapability.FLASHLIGHT_CONTROL,
      AndroidCapability.VOLUME_CONTROL,
      AndroidCapability.APP_DISCOVERY,
      AndroidCapability.APP_LAUNCH,
      AndroidCapability.OPEN_URL,
    ).forEach { capability ->
      val ownership = CapabilityCatalog.preferences.single { it.capability == capability }
      assertEquals(CapabilityProviderId.ANDROID_NATIVE, ownership.providerId)
    }
  }

  @Test
  fun pathBasedFileOperationsBelongToUnifiedShellRouter() {
    listOf(
      AndroidCapability.FILE_LIST,
      AndroidCapability.FILE_READ,
      AndroidCapability.FILE_CREATE,
      AndroidCapability.FILE_WRITE,
      AndroidCapability.FILE_COPY,
      AndroidCapability.FILE_MOVE,
      AndroidCapability.FILE_RENAME,
      AndroidCapability.FILE_DELETE,
      AndroidCapability.FILE_SEARCH,
      AndroidCapability.FILE_METADATA_READ,
      AndroidCapability.FILE_METADATA_WRITE,
    ).forEach { capability ->
      val ownership = CapabilityCatalog.preferences.single { it.capability == capability }
      assertEquals(CapabilityProviderId.UNIFIED_SHELL, ownership.providerId)
    }
  }

  @Test
  fun semanticUiCapabilitiesBelongOnlyToAccessibility() {
    listOf(
      AndroidCapability.SCREEN_CAPTURE,
      AndroidCapability.UI_TREE_READ,
      AndroidCapability.UI_GESTURE,
      AndroidCapability.UI_TEXT_INPUT,
    ).forEach { capability ->
      val ownership = CapabilityCatalog.preferences.single { it.capability == capability }
      assertEquals(CapabilityProviderId.ANDROID_ACCESSIBILITY, ownership.providerId)
    }
  }

  @Test
  fun documentTreeCapabilitiesBelongOnlyToSaf() {
    listOf(
      AndroidCapability.DOCUMENT_TREE_LIST,
      AndroidCapability.DOCUMENT_TREE_READ,
      AndroidCapability.DOCUMENT_TREE_CREATE,
      AndroidCapability.DOCUMENT_TREE_WRITE,
      AndroidCapability.DOCUMENT_TREE_COPY,
      AndroidCapability.DOCUMENT_TREE_MOVE,
      AndroidCapability.DOCUMENT_TREE_RENAME,
      AndroidCapability.DOCUMENT_TREE_DELETE,
      AndroidCapability.DOCUMENT_TREE_SEARCH,
      AndroidCapability.DOCUMENT_TREE_METADATA_READ,
      AndroidCapability.DOCUMENT_TREE_METADATA_WRITE,
    ).forEach { capability ->
      val ownership = CapabilityCatalog.preferences.single { it.capability == capability }
      assertEquals(CapabilityProviderId.ANDROID_DOCUMENTS, ownership.providerId)
    }
  }

  @Test
  fun missingOwnerObservationIsUnknownAndNeverSelected() {
    val result = registry.resolve(AndroidCapability.DEVICE_INFO, emptyList())

    assertFalse(result.isAvailable)
    assertNull(result.selectedProviderId)
    assertEquals(ProviderAvailability.UNKNOWN, result.candidates.single().availability)
  }

  @Test
  fun unavailableOwnerDoesNotFallBackToAnotherProvider() {
    val result = registry.resolve(
      AndroidCapability.VOLUME_CONTROL,
      listOf(
        observation(
          CapabilityProviderId.ANDROID_NATIVE,
          ProviderAvailability.PERMISSION_REQUIRED,
          "Native volume capability requires permission.",
        ),
        // The unified shell is not an alternate owner for volume control.
        observation(CapabilityProviderId.UNIFIED_SHELL, ProviderAvailability.AVAILABLE),
      ),
    )

    assertNull(result.selectedProviderId)
    assertEquals(1, result.candidates.size)
    assertEquals(CapabilityProviderId.ANDROID_NATIVE, result.candidates.single().providerId)
    assertEquals(ProviderAvailability.PERMISSION_REQUIRED, result.candidates.single().availability)
  }

  @Test
  fun duplicateCapabilityDeclarationsAreRejected() {
    val capability = AndroidCapability.BATTERY_STATUS
    val providers = listOf(
      CapabilityProviderDescriptor(
        CapabilityProviderId.ANDROID_NATIVE,
        "Native",
        ProviderExecutionIdentity.GALLERY_APP_UID,
        setOf(capability),
      ),
      CapabilityProviderDescriptor(
        CapabilityProviderId.UNIFIED_SHELL,
        "Shell",
        ProviderExecutionIdentity.UNIFIED_SHELL_ROUTER,
        setOf(capability),
      ),
    )

    try {
      CapabilityRegistry(
        providers,
        listOf(CapabilityRoutePreference(capability, CapabilityProviderId.ANDROID_NATIVE)),
      )
      throw AssertionError("Expected duplicate capability declarations to be rejected.")
    } catch (expected: IllegalArgumentException) {
      assertTrue(expected.message.orEmpty().contains("declared by multiple providers"))
    }
  }

  @Test
  fun missingCapabilityOwnerIsRejected() {
    try {
      CapabilityRegistry(
        CapabilityCatalog.providers,
        CapabilityCatalog.preferences.drop(1),
      )
      throw AssertionError("Expected a missing capability owner to be rejected.")
    } catch (expected: IllegalArgumentException) {
      assertTrue(expected.message.orEmpty().contains("missing an owner"))
    }
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
    val providerDescriptor = registry.providers().single {
      it.id == CapabilityProviderId.ANDROID_NATIVE
    }
    val probe = object : CapabilityProviderProbe {
      override val descriptor = providerDescriptor
      override suspend fun inspect() = CapabilityProviderObservation(
        descriptor.id,
        ProviderAvailability.AVAILABLE,
        "Native framework probe completed.",
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
