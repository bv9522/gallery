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
 * and Rish remain execution transports inside that router, not duplicate capability providers.
 * Termux:API is not registered as a parallel owner for Android framework operations.
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

  private val nativeCapabilities = AndroidCapability.values().toSet() -
    shellCapabilities - accessibilityCapabilities - documentCapabilities

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
