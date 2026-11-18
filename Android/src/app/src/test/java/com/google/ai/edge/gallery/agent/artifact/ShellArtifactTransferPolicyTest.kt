package com.google.ai.edge.gallery.agent.artifact

import com.google.ai.edge.gallery.agent.coordinator.ShellExecutionBackend
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellArtifactTransferPolicyTest {
  @Test fun termuxChunkStaysBelowIntentStdinBoundAndRoundTrips() {
    val source = ByteArray(ShellArtifactTransferPolicy.TERMUX_RAW_CHUNK_BYTES) { (it % 251).toByte() }
    val encoded = ShellArtifactTransferPolicy.encodeTermuxChunk(source, source.size)

    assertTrue(encoded.length <= ShellArtifactTransferPolicy.MAX_TERMUX_STDIN_CHARS)
    assertEquals(source.toList(), Base64.getDecoder().decode(encoded).toList())
  }

  @Test fun partialTermuxChunkPreservesOnlyTheRequestedBytes() {
    val source = byteArrayOf(1, 2, 3, 4, 5, 6)
    val encoded = ShellArtifactTransferPolicy.encodeTermuxChunk(source, 3)

    assertEquals(listOf<Byte>(1, 2, 3), Base64.getDecoder().decode(encoded).toList())
  }

  @Test fun rishIsNotAdvertisedAsABinaryDestinationUntilForwardingIsProven() {
    assertTrue(ShellArtifactTransferPolicy.supportsBinaryOutput(ShellExecutionBackend.TERMUX))
    assertTrue(ShellArtifactTransferPolicy.supportsBinaryOutput(ShellExecutionBackend.SHIZUKU))
    assertFalse(ShellArtifactTransferPolicy.supportsBinaryOutput(ShellExecutionBackend.TERMUX_RISH))
  }

  @Test fun oversizedRawChunkIsRejected() {
    val source = ByteArray(ShellArtifactTransferPolicy.TERMUX_RAW_CHUNK_BYTES + 1)
    try {
      ShellArtifactTransferPolicy.encodeTermuxChunk(source, source.size)
      throw AssertionError("Expected oversized chunk to be rejected.")
    } catch (_: IllegalArgumentException) {
      // Expected.
    }
  }
}
