/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.capability

/** Parses only standard USB descriptor metadata; raw vendor payloads are never returned. */
internal object UsbDescriptorParser {
  private const val MAX_DESCRIPTOR_BYTES = 4_096
  private const val MAX_DESCRIPTORS = 64
  private const val INTERFACE_DESCRIPTOR = 4
  private const val ENDPOINT_DESCRIPTOR = 5

  fun parse(raw: ByteArray, vendorId: Int, productId: Int): NativeUsbDescriptorSnapshot {
    require(raw.size in 2..MAX_DESCRIPTOR_BYTES) {
      "USB raw descriptor size is outside the supported bound."
    }
    val descriptors = mutableListOf<NativeUsbDescriptor>()
    var offset = 0
    var truncated = false
    while (offset + 2 <= raw.size) {
      if (descriptors.size >= MAX_DESCRIPTORS) {
        truncated = true
        break
      }
      val length = raw[offset].toInt() and 0xff
      val type = raw[offset + 1].toInt() and 0xff
      if (length < 2 || offset + length > raw.size) {
        throw IllegalStateException("USB device returned a malformed descriptor sequence.")
      }
      val descriptor = when (type) {
        INTERFACE_DESCRIPTOR -> {
          if (length < 9) throw IllegalStateException("USB interface descriptor is incomplete.")
          NativeUsbDescriptor(
            length = length,
            descriptorType = type,
            interfaceNumber = raw[offset + 2].toInt() and 0xff,
            alternateSetting = raw[offset + 3].toInt() and 0xff,
          )
        }
        ENDPOINT_DESCRIPTOR -> {
          if (length < 7) throw IllegalStateException("USB endpoint descriptor is incomplete.")
          NativeUsbDescriptor(
            length = length,
            descriptorType = type,
            endpointAddress = raw[offset + 2].toInt() and 0xff,
            endpointAttributes = raw[offset + 3].toInt() and 0xff,
            maxPacketSize = (raw[offset + 4].toInt() and 0xff) or
              ((raw[offset + 5].toInt() and 0xff) shl 8),
            interval = raw[offset + 6].toInt() and 0xff,
          )
        }
        else -> NativeUsbDescriptor(length = length, descriptorType = type)
      }
      descriptors += descriptor
      offset += length
    }
    if (offset != raw.size && !truncated) {
      throw IllegalStateException("USB descriptor sequence ended unexpectedly.")
    }
    return NativeUsbDescriptorSnapshot(
      vendorId = vendorId,
      productId = productId,
      descriptorBytes = raw.size,
      descriptors = descriptors,
      truncated = truncated,
    )
  }
}
