/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.google.ai.edge.gallery.agent.capability

/** Model-independent capability identifiers, not model-facing tool names. */
enum class AndroidCapability {
  DEVICE_INFO, BATTERY_STATUS, FLASHLIGHT_CONTROL, VOLUME_CONTROL, AUDIO_DEVICE_INFO,
  DISPLAY_BRIGHTNESS, VIBRATION_CONTROL, WAKE_LOCK, SENSOR_READ, SENSOR_STREAM,
  LOCATION_READ, LOCATION_UPDATES, WIFI_CONNECTION_INFO, WIFI_SCAN, WIFI_CONTROL,
  BLUETOOTH_DISCOVERY, BLUETOOTH_GATT, NFC_READ, NFC_WRITE, INFRARED_CONTROL,
  USB_DEVICE_DISCOVERY, USB_DEVICE_IO, APP_DISCOVERY, APP_LAUNCH, OPEN_URL,
  CONTACT_READ, CALL_LOG_READ, TELEPHONY_INFO, SMS_READ, SMS_COMPOSE, PHONE_CALL_DIAL,
  CLIPBOARD_READ, CLIPBOARD_WRITE, NOTIFICATION_POST, NOTIFICATION_READ,
  NOTIFICATION_CHANNEL_MANAGEMENT, CAMERA_INFO, CAMERA_CAPTURE, MICROPHONE_RECORD,
  SPEECH_RECOGNITION, TEXT_TO_SPEECH, BIOMETRIC_AUTH, KEYSTORE_OPERATION,
  DOWNLOAD_MANAGEMENT, MEDIA_SCAN, MEDIA_PLAYBACK, MEDIA_SESSION_CONTROL, SHARE_CONTENT,
  WALLPAPER_CONTROL, BACKGROUND_SCHEDULE, FILE_LIST, FILE_READ, FILE_CREATE, FILE_WRITE,
  FILE_COPY, FILE_MOVE, FILE_RENAME, FILE_DELETE, FILE_SEARCH, FILE_METADATA_READ,
  FILE_METADATA_WRITE, DOCUMENT_TREE_LIST, DOCUMENT_TREE_READ, DOCUMENT_TREE_CREATE,
  DOCUMENT_TREE_WRITE, DOCUMENT_TREE_COPY, DOCUMENT_TREE_MOVE, DOCUMENT_TREE_RENAME,
  DOCUMENT_TREE_DELETE, DOCUMENT_TREE_SEARCH, DOCUMENT_TREE_METADATA_READ,
  DOCUMENT_TREE_METADATA_WRITE, SCREEN_CAPTURE, UI_TREE_READ, UI_GESTURE, UI_TEXT_INPUT,
}

/** Capability owners, not execution transports. */
enum class CapabilityProviderId {
  UNIFIED_SHELL, ANDROID_NATIVE, ANDROID_ACCESSIBILITY, ANDROID_DOCUMENTS,
}

enum class ProviderExecutionIdentity {
  UNIFIED_SHELL_ROUTER, GALLERY_APP_UID, ACCESSIBILITY_SERVICE, USER_GRANTED_DOCUMENT_URI,
}

enum class ProviderAvailability {
  AVAILABLE, PERMISSION_REQUIRED, NOT_INSTALLED, SERVICE_STOPPED, UNSUPPORTED, UNKNOWN,
}

data class CapabilityProviderDescriptor(
  val id: CapabilityProviderId,
  val displayName: String,
  val executionIdentity: ProviderExecutionIdentity,
  val capabilities: Set<AndroidCapability>,
) {
  init {
    require(displayName.isNotBlank()) { "Provider display name must not be blank." }
    require(capabilities.isNotEmpty()) { "A provider must declare at least one capability." }
  }
}

/** Exactly one selected owner is registered for each capability. */
data class CapabilityRoutePreference(
  val capability: AndroidCapability,
  val providerId: CapabilityProviderId,
  val priority: Int = 1,
) {
  init {
    require(priority == 1) { "A capability has one owner; its ownership priority must be 1." }
  }
}

data class CapabilityProviderObservation(
  val providerId: CapabilityProviderId,
  val availability: ProviderAvailability,
  val detail: String? = null,
  val capabilityAvailability: Map<AndroidCapability, ProviderAvailability> = emptyMap(),
  val capabilityDetails: Map<AndroidCapability, String> = emptyMap(),
)

data class CapabilityCandidate(
  val providerId: CapabilityProviderId,
  val availability: ProviderAvailability,
  val priority: Int,
  val detail: String? = null,
)

data class CapabilityResolution(
  val capability: AndroidCapability,
  val selectedProviderId: CapabilityProviderId?,
  val candidates: List<CapabilityCandidate>,
) {
  val isAvailable: Boolean get() = selectedProviderId != null
}

/** Probes are observational only: they must not request permissions or mutate device state. */
interface CapabilityProviderProbe {
  val descriptor: CapabilityProviderDescriptor
  suspend fun inspect(): CapabilityProviderObservation
}

/**
 * Pure Kotlin ownership and availability policy. Every capability has exactly one declared
 * provider and one owner entry. Shell backend selection remains inside the unified shell router.
 */
class CapabilityRegistry(
  providers: List<CapabilityProviderDescriptor>,
  preferences: List<CapabilityRoutePreference>,
) {
  private val providersById: Map<CapabilityProviderId, CapabilityProviderDescriptor>
  private val preferencesByCapability: Map<AndroidCapability, CapabilityRoutePreference>

  init {
    val duplicateProviders = providers.groupBy { it.id }.filterValues { it.size > 1 }.keys
    require(duplicateProviders.isEmpty()) {
      "Duplicate capability providers: " + duplicateProviders.joinToString(", ")
    }
    providersById = providers.associateBy { it.id }

    val duplicateDeclarations = providers
      .flatMap { provider -> provider.capabilities.map { it to provider.id } }
      .groupBy { it.first }
      .filterValues { it.size > 1 }
      .keys
    require(duplicateDeclarations.isEmpty()) {
      "A capability cannot be declared by multiple providers: " +
        duplicateDeclarations.joinToString(", ")
    }

    preferences.forEach { ownership ->
      val provider = providersById[ownership.providerId]
        ?: throw IllegalArgumentException(
          "Ownership references unknown provider '${ownership.providerId}'."
        )
      require(ownership.capability in provider.capabilities) {
        "Provider '${ownership.providerId}' does not declare '${ownership.capability}'."
      }
    }

    val duplicateOwners = preferences.groupBy { it.capability }
      .filterValues { it.size > 1 }.keys
    require(duplicateOwners.isEmpty()) {
      "Each capability must have exactly one owner: " + duplicateOwners.joinToString(", ")
    }

    val declaredCapabilities = providers.flatMap { it.capabilities }.toSet()
    val ownedCapabilities = preferences.map { it.capability }.toSet()
    val missingOwnership = declaredCapabilities - ownedCapabilities
    require(missingOwnership.isEmpty()) {
      "Provider capabilities missing ownership entries: " + missingOwnership.joinToString(", ")
    }
    val unownedCapabilities = AndroidCapability.values().toSet() - ownedCapabilities
    require(unownedCapabilities.isEmpty()) {
      "Capabilities missing an owner: " + unownedCapabilities.joinToString(", ")
    }

    preferencesByCapability = preferences.associateBy { it.capability }
  }

  fun providers(): List<CapabilityProviderDescriptor> = providersById.values.toList()

  /** Inspects supplied providers and validates every observation against the catalog. */
  suspend fun discover(
    probes: Collection<CapabilityProviderProbe>,
  ): List<CapabilityProviderObservation> {
    val duplicateProbes = probes.groupBy { it.descriptor.id }.filterValues { it.size > 1 }.keys
    require(duplicateProbes.isEmpty()) {
      "Duplicate capability probes: " + duplicateProbes.joinToString(", ")
    }

    return probes.map { probe ->
      val descriptor = providersById[probe.descriptor.id]
      require(descriptor == probe.descriptor) {
        "Probe descriptor '${probe.descriptor.id}' does not match the capability catalog."
      }
      val observation = probe.inspect()
      require(observation.providerId == probe.descriptor.id) {
        "Probe '${probe.descriptor.id}' returned an observation for '${observation.providerId}'."
      }
      val undeclared = observation.capabilityAvailability.keys - probe.descriptor.capabilities
      require(undeclared.isEmpty()) {
        "Probe '${probe.descriptor.id}' reported undeclared capabilities: " +
          undeclared.joinToString(", ")
      }
      val undeclaredDetails = observation.capabilityDetails.keys - probe.descriptor.capabilities
      require(undeclaredDetails.isEmpty()) {
        "Probe '${probe.descriptor.id}' detailed undeclared capabilities: " +
          undeclaredDetails.joinToString(", ")
      }
      observation
    }
  }

  fun resolve(
    capability: AndroidCapability,
    observations: Collection<CapabilityProviderObservation>,
  ): CapabilityResolution {
    val duplicateObservations = observations.groupBy { it.providerId }
      .filterValues { it.size > 1 }.keys
    require(duplicateObservations.isEmpty()) {
      "Duplicate provider observations: " + duplicateObservations.joinToString(", ")
    }
    val owner = preferencesByCapability.getValue(capability)
    val observation = observations.firstOrNull { it.providerId == owner.providerId }
    val candidate = CapabilityCandidate(
      providerId = owner.providerId,
      availability = observation?.capabilityAvailability?.get(capability)
        ?: observation?.availability
        ?: ProviderAvailability.UNKNOWN,
      priority = owner.priority,
      detail = observation?.capabilityDetails?.get(capability) ?: observation?.detail,
    )
    val selected = candidate.providerId.takeIf {
      candidate.availability == ProviderAvailability.AVAILABLE
    }
    return CapabilityResolution(capability, selected, listOf(candidate))
  }

  fun resolveAll(
    observations: Collection<CapabilityProviderObservation>,
  ): Map<AndroidCapability, CapabilityResolution> =
    AndroidCapability.values().associateWith { resolve(it, observations) }
}
