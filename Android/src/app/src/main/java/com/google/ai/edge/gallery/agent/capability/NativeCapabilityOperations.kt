/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file defines the model-independent contract for native Android capability operations.
 */

package com.google.ai.edge.gallery.agent.capability

/**
 * Typed operations consumed by [AndroidNativeCapabilityTool].
 *
 * Keeping the tool behind this contract makes operation dispatch and provider gating testable
 * without Android framework services. Implementations remain responsible for enforcing the
 * injected authorization boundary before every mutation.
 */
interface NativeCapabilityOperations : CapabilityProviderProbe {
  fun getDeviceInfo(): NativeDeviceInfo
  fun getBatteryStatus(): NativeBatteryStatus
  fun getVolumeStatus(stream: NativeAudioStream): NativeVolumeStatus
  suspend fun setVolume(stream: NativeAudioStream, volume: Int): NativeCapabilityActionResult
  suspend fun setFlashlight(enabled: Boolean): NativeCapabilityActionResult
  fun listLaunchableApps(): List<NativeLaunchableApp>
  suspend fun launchApp(packageName: String): NativeCapabilityActionResult
  suspend fun openUrl(url: String): NativeCapabilityActionResult
}
