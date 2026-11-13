/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.google.ai.edge.gallery.agent.capability

/**
 * Canonical capability ownership catalog.
 *
 * Shell backends are intentionally represented by one UNIFIED_SHELL owner. Termux, Shizuku,
 * and Rish remain execution transports inside that router. Termux:API owns only the bounded
 * command capabilities explicitly assigned below; it does not duplicate Gallery-native owners.
 */
object CapabilityCatalog {
  private val shellCapabilities = setOf(
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
  )

  private val accessibilityCapabilities = setOf(
    AndroidCapability.SCREEN_CAPTURE,
    AndroidCapability.UI_TREE_READ,
    AndroidCapability.UI_GESTURE,
    AndroidCapability.UI_TEXT_INPUT,
  )

  private val documentCapabilities = setOf(
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
  )

  private val termuxApiCapabilities = setOf(
    AndroidCapability.SENSOR_READ,
    AndroidCapability.SENSOR_STREAM,
    AndroidCapability.LOCATION_READ,
    AndroidCapability.LOCATION_UPDATES,
    AndroidCapability.WIFI_CONNECTION_INFO,
    AndroidCapability.WIFI_SCAN,
    AndroidCapability.NFC_READ,
    AndroidCapability.NFC_WRITE,
    AndroidCapability.INFRARED_CONTROL,
    AndroidCapability.CAMERA_INFO,
    AndroidCapability.CAMERA_CAPTURE,
    AndroidCapability.MICROPHONE_RECORD,
    AndroidCapability.SPEECH_RECOGNITION,
    AndroidCapability.TEXT_TO_SPEECH,
    AndroidCapability.WALLPAPER_CONTROL,
  )

  private val nativeCapabilities = AndroidCapability.values().toSet() -
    shellCapabilities - termuxApiCapabilities - accessibilityCapabilities - documentCapabilities

  val providers: List<CapabilityProviderDescriptor> = listOf(
    CapabilityProviderDescriptor(
      CapabilityProviderId.UNIFIED_SHELL,
      "Unified shell execution router",
      ProviderExecutionIdentity.UNIFIED_SHELL_ROUTER,
      shellCapabilities,
    ),
    CapabilityProviderDescriptor(
      CapabilityProviderId.ANDROID_NATIVE,
      "Native Android framework APIs",
      ProviderExecutionIdentity.GALLERY_APP_UID,
      nativeCapabilities,
    ),
    CapabilityProviderDescriptor(
      CapabilityProviderId.TERMUX_API,
      "Termux:API command provider",
      ProviderExecutionIdentity.TERMUX_RUN_COMMAND_CLIENT,
      termuxApiCapabilities,
    ),
    CapabilityProviderDescriptor(
      CapabilityProviderId.ANDROID_ACCESSIBILITY,
      "Android AccessibilityService",
      ProviderExecutionIdentity.ACCESSIBILITY_SERVICE,
      accessibilityCapabilities,
    ),
    CapabilityProviderDescriptor(
      CapabilityProviderId.ANDROID_DOCUMENTS,
      "Android Storage Access Framework",
      ProviderExecutionIdentity.USER_GRANTED_DOCUMENT_URI,
      documentCapabilities,
    ),
  )

  /** Exactly one owner is assigned to every capability in the vocabulary. */
  val preferences: List<CapabilityRoutePreference> = providers.flatMap { provider ->
    provider.capabilities.map { capability ->
      CapabilityRoutePreference(capability, provider.id)
    }
  }

  fun createRegistry(): CapabilityRegistry =
    CapabilityRegistry(providers = providers, preferences = preferences)
}
