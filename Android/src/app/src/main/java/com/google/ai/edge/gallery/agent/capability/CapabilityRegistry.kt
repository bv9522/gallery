/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.google.ai.edge.gallery.agent.capability

/** Model-independent capabilities, not model-facing tool names. */
enum class AndroidCapability {
  DEVICE_INFO, BATTERY_STATUS, FLASHLIGHT_CONTROL, VOLUME_CONTROL,
  APP_DISCOVERY, APP_LAUNCH, OPEN_URL, FILE_LIST, FILE_READ, FILE_WRITE,
  FILE_DELETE, SCREEN_CAPTURE, UI_TREE_READ, UI_GESTURE, UI_TEXT_INPUT,
}

enum class CapabilityProviderId {
  TERMUX_SHELL, SHIZUKU_SHELL, TERMUX_RISH, TERMUX_API,
  ANDROID_NATIVE, ANDROID_ACCESSIBILITY, ANDROID_DOCUMENTS,
}

enum class ProviderExecutionIdentity {
  TERMUX_APP_UID, ANDROID_SHELL_UID, TERMUX_API_COMPANION,
  GALLERY_APP_UID, ACCESSIBILITY_SERVICE, USER_GRANTED_DOCUMENT_URI,
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

data class CapabilityRoutePreference(
  val capability: AndroidCapability,
  val providerId: CapabilityProviderId,
  /** Lower values are preferred. Priority is capability-specific. */
  val priority: Int,
) {
  init {
    require(priority > 0) { "Route priority must be greater than zero." }
  }
}

data class CapabilityProviderObservation(
  val providerId: CapabilityProviderId,
  val availability: ProviderAvailability,
  val detail: String? = null,
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

/**
 * Android-specific adapters implement this probe contract in later Phase 3 work.
 * Probes are observational only: they must not request permissions or mutate device state.
 */
interface CapabilityProviderProbe {
  val descriptor: CapabilityProviderDescriptor
  suspend fun inspect(): CapabilityProviderObservation
}

/**
 * Pure Kotlin routing policy. Missing observations are UNKNOWN, never AVAILABLE.
 * Explicit backend choices remain the responsibility of the existing execution router.
 */
class CapabilityRegistry(
  providers: List<CapabilityProviderDescriptor>,
  preferences: List<CapabilityRoutePreference>,
) {
  private val providersById: Map<CapabilityProviderId, CapabilityProviderDescriptor>
  private val preferencesByCapability: Map<AndroidCapability, List<CapabilityRoutePreference>>

  init {
    val duplicateProviders = providers.groupBy { it.id }.filterValues { it.size > 1 }.keys
    require(duplicateProviders.isEmpty()) {
      "Duplicate capability providers: " + duplicateProviders.joinToString(", ")
    }
    providersById = providers.associateBy { it.id }

    preferences.forEach { preference ->
      val provider = providersById[preference.providerId]
        ?: throw IllegalArgumentException(
          "Route references unknown provider '${preference.providerId}'."
        )
      require(preference.capability in provider.capabilities) {
        "Provider '${preference.providerId}' does not declare '${preference.capability}'."
      }
    }

    val duplicatePriorities = preferences
      .groupBy { it.capability }
      .values
      .flatMap { routes ->
        routes.groupBy { it.priority }.filterValues { it.size > 1 }.keys
      }
    require(duplicatePriorities.isEmpty()) {
      "Route priorities must be unique within each capability."
    }

    preferencesByCapability = preferences
      .groupBy { it.capability }
      .mapValues { (_, routes) -> routes.sortedBy { it.priority } }
  }

  fun providers(): List<CapabilityProviderDescriptor> = providersById.values.toList()

  /**
   * Inspects the supplied providers in a deterministic order.
   *
   * The caller supplies only probes it can construct; omitted providers remain UNKNOWN when
   * the returned observations are passed to resolve(). Probe identity must match the catalog.
   */
  suspend fun discover(
    probes: Collection<CapabilityProviderProbe>,
  ): List<CapabilityProviderObservation> {
    val duplicateProbes = probes.groupBy { it.descriptor.id }.filterValues { it.size > 1 }.keys
    require(duplicateProbes.isEmpty()) {
      "Duplicate capability probes: " + duplicateProbes.joinToString(", ")
    }

    return probes.map { probe ->
      require(providersById[probe.descriptor.id] == probe.descriptor) {
        "Probe descriptor '${probe.descriptor.id}' does not match the capability catalog."
      }
      val observation = probe.inspect()
      require(observation.providerId == probe.descriptor.id) {
        "Probe '${probe.descriptor.id}' returned an observation for '${observation.providerId}'."
      }
      observation
    }
  }

  fun resolve(
    capability: AndroidCapability,
    observations: Collection<CapabilityProviderObservation>,
  ): CapabilityResolution {
    val duplicateObservations = observations
      .groupBy { it.providerId }
      .filterValues { it.size > 1 }
      .keys
    require(duplicateObservations.isEmpty()) {
      "Duplicate provider observations: " + duplicateObservations.joinToString(", ")
    }
    val observationsByProvider = observations.associateBy { it.providerId }

    val candidates = preferencesByCapability[capability].orEmpty().map { route ->
      val observation = observationsByProvider[route.providerId]
      CapabilityCandidate(
        providerId = route.providerId,
        availability = observation?.availability ?: ProviderAvailability.UNKNOWN,
        priority = route.priority,
        detail = observation?.detail,
      )
    }
    val selected = candidates.firstOrNull {
      it.availability == ProviderAvailability.AVAILABLE
    }?.providerId

    return CapabilityResolution(capability, selected, candidates)
  }

  fun resolveAll(
    observations: Collection<CapabilityProviderObservation>,
  ): Map<AndroidCapability, CapabilityResolution> =
    AndroidCapability.values().associateWith { resolve(it, observations) }
}
