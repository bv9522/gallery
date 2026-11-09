/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package com.google.ai.edge.gallery.agent.capability

/** Initial provider inventory for Brahma-inspired Android capabilities. */
object CapabilityCatalog {
  private val shellCapabilities = setOf(
    AndroidCapability.DEVICE_INFO,
    AndroidCapability.BATTERY_STATUS,
    AndroidCapability.FLASHLIGHT_CONTROL,
    AndroidCapability.VOLUME_CONTROL,
    AndroidCapability.APP_DISCOVERY,
    AndroidCapability.APP_LAUNCH,
    AndroidCapability.OPEN_URL,
    AndroidCapability.FILE_LIST,
    AndroidCapability.FILE_READ,
    AndroidCapability.FILE_WRITE,
    AndroidCapability.FILE_DELETE,
    AndroidCapability.SCREEN_CAPTURE,
    AndroidCapability.UI_GESTURE,
    AndroidCapability.UI_TEXT_INPUT,
  )

  val providers: List<CapabilityProviderDescriptor> = listOf(
    CapabilityProviderDescriptor(
      CapabilityProviderId.TERMUX_SHELL, "Termux shell",
      ProviderExecutionIdentity.TERMUX_APP_UID, shellCapabilities,
    ),
    CapabilityProviderDescriptor(
      CapabilityProviderId.SHIZUKU_SHELL, "Shizuku shell",
      ProviderExecutionIdentity.ANDROID_SHELL_UID, shellCapabilities,
    ),
    CapabilityProviderDescriptor(
      CapabilityProviderId.TERMUX_RISH, "Termux Rish",
      ProviderExecutionIdentity.ANDROID_SHELL_UID, shellCapabilities,
    ),
    CapabilityProviderDescriptor(
      CapabilityProviderId.TERMUX_API, "Termux:API",
      ProviderExecutionIdentity.TERMUX_API_COMPANION,
      setOf(
        AndroidCapability.BATTERY_STATUS,
        AndroidCapability.FLASHLIGHT_CONTROL,
        AndroidCapability.VOLUME_CONTROL,
      ),
    ),
    CapabilityProviderDescriptor(
      CapabilityProviderId.ANDROID_NATIVE, "Native Android APIs",
      ProviderExecutionIdentity.GALLERY_APP_UID,
      setOf(
        AndroidCapability.DEVICE_INFO,
        AndroidCapability.BATTERY_STATUS,
        AndroidCapability.FLASHLIGHT_CONTROL,
        AndroidCapability.VOLUME_CONTROL,
        AndroidCapability.APP_DISCOVERY,
        AndroidCapability.APP_LAUNCH,
        AndroidCapability.OPEN_URL,
      ),
    ),
    CapabilityProviderDescriptor(
      CapabilityProviderId.ANDROID_ACCESSIBILITY, "Android AccessibilityService",
      ProviderExecutionIdentity.ACCESSIBILITY_SERVICE,
      setOf(
        AndroidCapability.SCREEN_CAPTURE,
        AndroidCapability.UI_TREE_READ,
        AndroidCapability.UI_GESTURE,
        AndroidCapability.UI_TEXT_INPUT,
      ),
    ),
    CapabilityProviderDescriptor(
      CapabilityProviderId.ANDROID_DOCUMENTS, "Android Storage Access Framework",
      ProviderExecutionIdentity.USER_GRANTED_DOCUMENT_URI,
      setOf(
        AndroidCapability.FILE_LIST,
        AndroidCapability.FILE_READ,
        AndroidCapability.FILE_WRITE,
        AndroidCapability.FILE_DELETE,
      ),
    ),
  )

  private fun routes(
    capability: AndroidCapability,
    vararg providers: CapabilityProviderId,
  ): List<CapabilityRoutePreference> =
    providers.mapIndexed { index, providerId ->
      CapabilityRoutePreference(capability, providerId, index + 1)
    }

  val preferences: List<CapabilityRoutePreference> = buildList {
    addAll(routes(AndroidCapability.DEVICE_INFO,
      CapabilityProviderId.ANDROID_NATIVE, CapabilityProviderId.SHIZUKU_SHELL,
      CapabilityProviderId.TERMUX_RISH, CapabilityProviderId.TERMUX_SHELL))
    addAll(routes(AndroidCapability.BATTERY_STATUS,
      CapabilityProviderId.ANDROID_NATIVE, CapabilityProviderId.TERMUX_API,
      CapabilityProviderId.SHIZUKU_SHELL, CapabilityProviderId.TERMUX_RISH,
      CapabilityProviderId.TERMUX_SHELL))
    addAll(routes(AndroidCapability.FLASHLIGHT_CONTROL,
      CapabilityProviderId.ANDROID_NATIVE, CapabilityProviderId.TERMUX_API,
      CapabilityProviderId.SHIZUKU_SHELL, CapabilityProviderId.TERMUX_RISH,
      CapabilityProviderId.TERMUX_SHELL))
    addAll(routes(AndroidCapability.VOLUME_CONTROL,
      CapabilityProviderId.ANDROID_NATIVE, CapabilityProviderId.TERMUX_API,
      CapabilityProviderId.SHIZUKU_SHELL, CapabilityProviderId.TERMUX_RISH,
      CapabilityProviderId.TERMUX_SHELL))
    addAll(routes(AndroidCapability.APP_DISCOVERY,
      CapabilityProviderId.ANDROID_NATIVE, CapabilityProviderId.SHIZUKU_SHELL,
      CapabilityProviderId.TERMUX_RISH, CapabilityProviderId.TERMUX_SHELL))
    addAll(routes(AndroidCapability.APP_LAUNCH,
      CapabilityProviderId.ANDROID_NATIVE, CapabilityProviderId.SHIZUKU_SHELL,
      CapabilityProviderId.TERMUX_RISH, CapabilityProviderId.TERMUX_SHELL))
    addAll(routes(AndroidCapability.OPEN_URL,
      CapabilityProviderId.ANDROID_NATIVE, CapabilityProviderId.SHIZUKU_SHELL,
      CapabilityProviderId.TERMUX_RISH, CapabilityProviderId.TERMUX_SHELL))
    addAll(routes(AndroidCapability.FILE_LIST,
      CapabilityProviderId.SHIZUKU_SHELL, CapabilityProviderId.TERMUX_RISH,
      CapabilityProviderId.TERMUX_SHELL, CapabilityProviderId.ANDROID_DOCUMENTS))
    addAll(routes(AndroidCapability.FILE_READ,
      CapabilityProviderId.SHIZUKU_SHELL, CapabilityProviderId.TERMUX_RISH,
      CapabilityProviderId.TERMUX_SHELL, CapabilityProviderId.ANDROID_DOCUMENTS))
    addAll(routes(AndroidCapability.FILE_WRITE,
      CapabilityProviderId.SHIZUKU_SHELL, CapabilityProviderId.TERMUX_RISH,
      CapabilityProviderId.TERMUX_SHELL, CapabilityProviderId.ANDROID_DOCUMENTS))
    addAll(routes(AndroidCapability.FILE_DELETE,
      CapabilityProviderId.SHIZUKU_SHELL, CapabilityProviderId.TERMUX_RISH,
      CapabilityProviderId.TERMUX_SHELL, CapabilityProviderId.ANDROID_DOCUMENTS))
    addAll(routes(AndroidCapability.SCREEN_CAPTURE,
      CapabilityProviderId.ANDROID_ACCESSIBILITY, CapabilityProviderId.SHIZUKU_SHELL,
      CapabilityProviderId.TERMUX_RISH, CapabilityProviderId.TERMUX_SHELL))
    addAll(routes(AndroidCapability.UI_TREE_READ, CapabilityProviderId.ANDROID_ACCESSIBILITY))
    addAll(routes(AndroidCapability.UI_GESTURE,
      CapabilityProviderId.ANDROID_ACCESSIBILITY, CapabilityProviderId.SHIZUKU_SHELL,
      CapabilityProviderId.TERMUX_RISH, CapabilityProviderId.TERMUX_SHELL))
    addAll(routes(AndroidCapability.UI_TEXT_INPUT,
      CapabilityProviderId.ANDROID_ACCESSIBILITY, CapabilityProviderId.SHIZUKU_SHELL,
      CapabilityProviderId.TERMUX_RISH, CapabilityProviderId.TERMUX_SHELL))
  }

  fun createRegistry(): CapabilityRegistry =
    CapabilityRegistry(providers = providers, preferences = preferences)
}
