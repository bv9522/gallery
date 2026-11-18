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

data class NativeContactSummary(
  val displayName: String,
  val phoneNumbers: List<String>,
  val emailAddresses: List<String>,
)

/** Deliberately excludes subscriber IDs, phone numbers, cell IDs, and carrier identifiers. */
data class NativeTelephonyInfo(
  val hasTelephonyFeature: Boolean,
  val phoneType: String,
  val simState: String,
)

data class NativeCallLogEntry(
  val cachedName: String?,
  val number: String?,
  val callType: String,
  val dateMs: Long,
  val durationSeconds: Long,
)

data class NativeCallLogSnapshot(
  val entries: List<NativeCallLogEntry>,
  val truncated: Boolean,
)

data class NativeSmsInboxEntry(
  val sender: String?,
  val bodyPreview: String?,
  val dateMs: Long,
  val isRead: Boolean,
)

data class NativeSmsInboxSnapshot(
  val entries: List<NativeSmsInboxEntry>,
  val truncated: Boolean,
)

data class NativeUsbDescriptor(
  val length: Int,
  val descriptorType: Int,
  val interfaceNumber: Int? = null,
  val alternateSetting: Int? = null,
  val endpointAddress: Int? = null,
  val endpointAttributes: Int? = null,
  val maxPacketSize: Int? = null,
  val interval: Int? = null,
)

data class NativeUsbDescriptorSnapshot(
  val vendorId: Int,
  val productId: Int,
  val descriptorBytes: Int,
  val descriptors: List<NativeUsbDescriptor>,
  val truncated: Boolean,
)

data class NativeKeystoreCiphertext(
  val alias: String,
  val ivBase64: String,
  val ciphertextBase64: String,
)

data class NativeClipboardContent(
  val text: String,
  val hasText: Boolean,
  val itemCount: Int,
  val truncated: Boolean,
)

data class NativeScheduledNotification(
  val id: String,
  val title: String,
  val message: String,
  val hour: Int,
  val minute: Int,
  val year: Int?,
  val month: Int?,
  val day: Int?,
  val repeatDaily: Boolean,
)

data class NativeNotificationChannel(
  val id: String,
  val name: String,
  val importance: Int,
)

data class NativePostedNotification(
  val id: String,
  val title: String,
  val message: String,
  val postedAtMs: Long,
)

data class NativeOtherNotification(
  val appLabel: String,
  val title: String?,
  val text: String?,
  val postedAtMs: Long,
)

data class NativeOtherNotificationList(
  val notifications: List<NativeOtherNotification>,
  val truncated: Boolean,
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
  fun getTelephonyInfo(): NativeTelephonyInfo
  suspend fun readCallLog(): NativeCallLogSnapshot =
    throw UnsupportedOperationException("Call-log reading is unavailable.")
  suspend fun readSmsInbox(): NativeSmsInboxSnapshot =
    throw UnsupportedOperationException("SMS inbox reading is unavailable.")
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
  suspend fun dialPhoneNumber(phoneNumber: String): NativeCapabilityActionResult
  suspend fun composeSms(phoneNumber: String, message: String): NativeCapabilityActionResult
  suspend fun authenticateBiometric(reason: String): Boolean
  suspend fun createKeystoreKey(alias: String): NativeCapabilityActionResult
  suspend fun listKeystoreKeys(): List<String>
  suspend fun deleteKeystoreKey(alias: String): NativeCapabilityActionResult
  suspend fun encryptKeystoreData(alias: String, plaintext: String): NativeKeystoreCiphertext
  suspend fun decryptKeystoreData(alias: String, ivBase64: String, ciphertextBase64: String): String
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
  suspend fun searchContacts(query: String, limit: Int): List<NativeContactSummary>
  suspend fun readClipboard(): NativeClipboardContent
  suspend fun writeClipboard(text: String): NativeCapabilityActionResult
  suspend fun postNotification(
    title: String,
    message: String,
    channelId: String,
  ): NativeCapabilityActionResult
  fun listPostedNotifications(): List<NativePostedNotification>
  suspend fun readOtherNotifications(): NativeOtherNotificationList =
    throw UnsupportedOperationException("Reading other apps' notifications is unavailable.")
  suspend fun cancelPostedNotification(id: String): NativeCapabilityActionResult
  suspend fun scheduleNotification(
    title: String,
    message: String,
    hour: Int,
    minute: Int,
    year: Int?,
    month: Int?,
    day: Int?,
    repeatDaily: Boolean,
  ): NativeCapabilityActionResult
  suspend fun listScheduledNotifications(): List<NativeScheduledNotification>
  suspend fun cancelScheduledNotification(id: String): NativeCapabilityActionResult
  fun listNotificationChannels(): List<NativeNotificationChannel>
  suspend fun createNotificationChannel(
    id: String,
    name: String,
    importance: Int,
  ): NativeCapabilityActionResult
  suspend fun getUsbDevices(): List<NativeUsbDevice>
  suspend fun readUsbDescriptors(handle: String): NativeUsbDescriptorSnapshot =
    throw UnsupportedOperationException("USB descriptor inspection is unavailable.")
  suspend fun recordAudio(durationMs: Long): String
  suspend fun capturePhoto(): String
  suspend fun setWallpaper(contentUri: String, target: String): NativeCapabilityActionResult
}
