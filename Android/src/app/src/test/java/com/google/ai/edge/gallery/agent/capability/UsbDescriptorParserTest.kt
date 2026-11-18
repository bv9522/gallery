/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDescriptorParserTest {
  @Test
  fun parsesInterfaceAndEndpointMetadataWithoutReturningRawBytes() {
    val raw = byteArrayOf(
      9, 4, 2, 1, 1, 0, 0, 0, 0,
      7, 5, 0x81.toByte(), 2, 64, 0, 1,
    )

    val result = UsbDescriptorParser.parse(raw, vendorId = 0x1234, productId = 0x5678)

    assertEquals(0x1234, result.vendorId)
    assertEquals(0x5678, result.productId)
    assertEquals(raw.size, result.descriptorBytes)
    assertEquals(2, result.descriptors.size)
    assertEquals(2, result.descriptors[0].interfaceNumber)
    assertEquals(1, result.descriptors[0].alternateSetting)
    assertEquals(0x81, result.descriptors[1].endpointAddress)
    assertEquals(2, result.descriptors[1].endpointAttributes)
    assertEquals(64, result.descriptors[1].maxPacketSize)
    assertEquals(1, result.descriptors[1].interval)
    assertFalse(result.truncated)
  }

  @Test
  fun rejectsMalformedDescriptorLengths() {
    try {
      UsbDescriptorParser.parse(byteArrayOf(0, 4), 1, 2)
      throw AssertionError("Expected malformed descriptor to be rejected.")
    } catch (expected: IllegalStateException) {
      assertTrue(expected.message.orEmpty().contains("malformed"))
    }
  }

  @Test
  fun rejectsOversizedRawDescriptorPayload() {
    try {
      UsbDescriptorParser.parse(ByteArray(4_097) { 2 }, 1, 2)
      throw AssertionError("Expected oversized descriptor data to be rejected.")
    } catch (expected: IllegalArgumentException) {
      assertTrue(expected.message.orEmpty().contains("supported bound"))
    }
  }

  @Test
  fun capsDescriptorCountAndMarksTruncation() {
    val raw = ByteArray(130) { index -> if (index % 2 == 0) 2.toByte() else 0x30.toByte() }

    val result = UsbDescriptorParser.parse(raw, 1, 2)

    assertEquals(64, result.descriptors.size)
    assertTrue(result.truncated)
  }
}
