package com.google.ai.edge.gallery.agent.artifact

import com.google.ai.edge.gallery.agent.coordinator.ShellExecutionBackend
import com.google.ai.edge.gallery.shizuku.ShizukuBinaryArtifactWriter
import com.google.ai.edge.gallery.shizuku.ShizukuBinaryFileResult
import com.google.ai.edge.gallery.termux.TermuxBinaryArtifactWriter
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellArtifactDestinationAdapterTest {
  @Test fun termuxStreamsMultipleBoundedChunksAndVerifiesTheDestination() = runBlocking {
    val directory = Files.createTempDirectory("artifact-termux-test").toFile()
    val bytes = ByteArray(500_000) { (it % 239).toByte() }
    val termux = FakeTermuxBinaryWriter(bytes)
    val adapter = adapter(directory, termux = termux)

    try {
      val result = adapter.write(request(ShellExecutionBackend.TERMUX), producer(bytes))

      assertEquals(bytes.size.toLong(), result.metadata.bytesWritten)
      assertEquals(bytes.toList(), termux.received.toByteArray().toList())
      assertTrue(termux.chunkCount > 1)
      assertTrue(termux.commands.any { it.contains("mv -n") })
      assertTrue(directory.listFiles().orEmpty().isEmpty())
    } finally {
      directory.deleteRecursively()
    }
  }

  @Test fun shizukuUsesTheSelectedIdentityAndVerifiesTheStagedBytes() = runBlocking {
    val directory = Files.createTempDirectory("artifact-shizuku-test").toFile()
    val bytes = ByteArray(64_000) { (it % 197).toByte() }
    val shizuku = FakeShizukuBinaryWriter()
    val adapter = adapter(directory, shizuku = shizuku)

    try {
      val result = adapter.write(request(ShellExecutionBackend.SHIZUKU), producer(bytes))

      assertEquals(bytes.toList(), shizuku.received.toList())
      assertEquals("/storage/emulated/0/Documents/report.pdf", shizuku.destinationPath)
      assertFalse(shizuku.overwrite)
      assertEquals(bytes.size.toLong(), result.metadata.bytesWritten)
      assertTrue(directory.listFiles().orEmpty().isEmpty())
    } finally {
      directory.deleteRecursively()
    }
  }

  @Test fun failedTermuxChunkAttemptsSameIdentityCleanupAndRemovesPrivateStage() = runBlocking {
    val directory = Files.createTempDirectory("artifact-cleanup-test").toFile()
    val bytes = ByteArray(400_000) { it.toByte() }
    val termux = FakeTermuxBinaryWriter(bytes, failAfterFirstChunk = true)
    val adapter = adapter(directory, termux = termux)

    try {
      try {
        adapter.write(request(ShellExecutionBackend.TERMUX), producer(bytes))
        throw AssertionError("Expected the injected transfer failure.")
      } catch (_: IllegalStateException) {
        // Expected.
      }
      assertTrue(termux.cleanupAttempted)
      assertTrue(directory.listFiles().orEmpty().isEmpty())
    } finally {
      directory.deleteRecursively()
    }
  }

  @Test fun rishIsRejectedBeforeAnyBinaryTransportIsInvoked() = runBlocking {
    val directory = Files.createTempDirectory("artifact-rish-test").toFile()
    val termux = FakeTermuxBinaryWriter(byteArrayOf(1, 2, 3))
    val adapter = adapter(directory, termux = termux)
    try {
      try {
        adapter.describe(request(ShellExecutionBackend.TERMUX_RISH))
        throw AssertionError("Expected TERMUX_RISH binary output to be rejected.")
      } catch (_: IllegalArgumentException) {
        // Expected.
      }
      assertEquals(0, termux.chunkCount)
      assertTrue(termux.commands.isEmpty())
    } finally {
      directory.deleteRecursively()
    }
  }

  private fun adapter(
    directory: File,
    termux: FakeTermuxBinaryWriter = FakeTermuxBinaryWriter(byteArrayOf()),
    shizuku: FakeShizukuBinaryWriter = FakeShizukuBinaryWriter(),
  ) = ShellArtifactDestinationAdapter(directory, termux, shizuku)

  private fun request(backend: ShellExecutionBackend) = ArtifactCreateRequest(
    format = ArtifactFormat.PDF,
    fileName = "report.pdf",
    destination = ArtifactDestinationSpec.ShellPath(
      backend,
      "/storage/emulated/0/Documents/report.pdf",
    ),
    overwrite = false,
    payload = JsonObject(),
  )

  private fun producer(bytes: ByteArray) = ArtifactOutputProducer { output ->
    output.write(bytes)
    ArtifactOutputMetadata("application/pdf", bytes.size.toLong(), sha256(bytes))
  }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
      .joinToString("") { "%02x".format(it.toInt() and 0xff) }

  private class FakeTermuxBinaryWriter(
    private val expected: ByteArray,
    private val failAfterFirstChunk: Boolean = false,
  ) : TermuxBinaryArtifactWriter {
    val received = ByteArrayOutputStream()
    val commands = mutableListOf<String>()
    var chunkCount = 0
    var cleanupAttempted = false

    override suspend fun appendBinaryFileChunk(
      temporaryPath: String,
      encodedChunk: String,
      firstChunk: Boolean,
      timeoutMs: Long,
    ) {
      assertTrue(encodedChunk.length <= ShellArtifactTransferPolicy.MAX_TERMUX_STDIN_CHARS)
      if (firstChunk) received.reset()
      received.write(Base64.getDecoder().decode(encodedChunk))
      chunkCount++
      if (failAfterFirstChunk && chunkCount == 1) {
        throw IllegalStateException("Injected chunk failure.")
      }
    }

    override suspend fun runBinaryTransferCommand(command: String, timeoutMs: Long): String {
      commands += command
      return when {
        command.contains("printf READY") -> "READY"
        command.startsWith("rm -f") -> {
          cleanupAttempted = true
          ""
        }
        command.contains("actual_hash") -> received.size().toString() + ":" + sha256(received.toByteArray())
        else -> ""
      }
    }
  }

  private class FakeShizukuBinaryWriter : ShizukuBinaryArtifactWriter {
    var received = byteArrayOf()
    var destinationPath = ""
    var overwrite = false

    override suspend fun writeBinaryFile(
      sourceFile: File,
      destinationPath: String,
      overwrite: Boolean,
      maxBytes: Long,
      timeoutMs: Long,
      expectedSha256: String,
    ): ShizukuBinaryFileResult {
      received = sourceFile.readBytes()
      this.destinationPath = destinationPath
      this.overwrite = overwrite
      check(received.size.toLong() <= maxBytes)
      val hash = sha256(received)
      check(hash == expectedSha256)
      return ShizukuBinaryFileResult(received.size.toLong(), hash)
    }
  }
}
