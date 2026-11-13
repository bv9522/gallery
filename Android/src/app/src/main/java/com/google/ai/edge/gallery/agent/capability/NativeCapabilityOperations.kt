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

data class NativeBluetoothGattCharacteristic(
  val uuid: String,
  val properties: Int,
  val canRead: Boolean,
  val canWrite: Boolean,
  val canWriteWithoutResponse: Boolean,
  val canNotify: Boolean,
  val canIndicate: Boolean,
  val descriptorCount: Int,
)

data class NativeBluetoothGattService(
  val uuid: String,
  val type: String,
  val characteristics: List<NativeBluetoothGattCharacteristic>,
)

data class NativeBluetoothGattInspection(
  val deviceAddress: String,
  val services: List<NativeBluetoothGattService>,
  val totalCharacteristics: Int,
  val truncated: Boolean,
)

data class NativeBluetoothGattValue(
  val deviceAddress: String,
  val serviceUuid: String,
  val characteristicUuid: String,
  val valueBase64: String,
  val byteCount: Int,
)

/**
 * Typed operations consumed by [AndroidNativeCapabilityTool].
 *
 * Keeping the tool behind this contract makes operation dispatch and provider gating testable
 * without Android framework services. Implementations remain responsible for enforcing the
 * injected authorization boundary before every mutation and privacy-sensitive discovery operation.
 */
interface NativeCapabilityOperations : CapabilityProviderProbe {
  fun getDeviceInfo(): NativeDeviceInfo
  fun getBatteryStatus(): NativeBatteryStatus
  fun getVolumeStatus(stream: NativeAudioStream): NativeVolumeStatus
  fun getAudioDevices(): List<NativeAudioDevice>
  fun getDisplayBrightness(): NativeDisplayBrightness
  suspend fun setDisplayBrightness(level: Int): NativeCapabilityActionResult
  suspend fun vibrate(durationMs: Long): NativeCapabilityActionResult
  fun isWakeLockHeld(): Boolean
  suspend fun setWakeLock(held: Boolean, durationMs: Long): NativeCapabilityActionResult
  suspend fun setVolume(stream: NativeAudioStream, volume: Int): NativeCapabilityActionResult
  suspend fun setFlashlight(enabled: Boolean): NativeCapabilityActionResult
  fun listLaunchableApps(): List<NativeLaunchableApp>
  suspend fun launchApp(packageName: String): NativeCapabilityActionResult
  suspend fun openUrl(url: String): NativeCapabilityActionResult
  suspend fun getBluetoothBondedDevices(): List<NativeBluetoothDevice>
  suspend fun scanBluetoothLe(durationMs: Long): List<NativeBluetoothDevice>
  suspend fun inspectBluetoothGatt(deviceAddress: String, timeoutMs: Long): NativeBluetoothGattInspection
  suspend fun readBluetoothGattCharacteristic(
    deviceAddress: String,
    serviceUuid: String,
    characteristicUuid: String,
    timeoutMs: Long,
  ): NativeBluetoothGattValue
  suspend fun writeBluetoothGattCharacteristic(
    deviceAddress: String,
    serviceUuid: String,
    characteristicUuid: String,
    valueBase64: String,
    timeoutMs: Long,
  ): NativeCapabilityActionResult
  suspend fun getUsbDevices(): List<NativeUsbDevice>
  suspend fun recordAudio(durationMs: Long): String
  suspend fun capturePhoto(): String
  suspend fun setWallpaper(contentUri: String, target: String): NativeCapabilityActionResult
}
