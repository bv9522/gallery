package com.google.ai.edge.gallery.agent.artifact

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.gallery.shizuku.ShizukuShellClient
import com.google.ai.edge.gallery.termux.TermuxRunCommandClient
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku

@RunWith(AndroidJUnit4::class)
class ShellBinaryTransportInstrumentedTest {
  @Test
  fun termuxAppShellStdinTransfersMultipleChunksAndVerifiesHash() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Suppress("DEPRECATION")
    val installed = runCatching {
      context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
    }.isSuccess
    assumeTrue("Termux is not installed; skipping its device transport test.", installed)
    assumeTrue(
      "RUN_COMMAND permission is not granted; skipping its device transport test.",
      context.checkSelfPermission(TERMUX_RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED,
    )

    val client = TermuxRunCommandClient(context)
    val bytes = ByteArray(384 * 1_024 + 37) { (it % 251).toByte() }
    val path = "/data/data/com.termux/files/home/.android-mcp-stage10i-" +
      UUID.randomUUID().toString().replace("-", "") + ".bin"
    try {
      var offset = 0
      var first = true
      while (offset < bytes.size) {
        val count = minOf(TERMUX_RAW_CHUNK_BYTES, bytes.size - offset)
        val encoded = Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, offset + count))
        client.appendBinaryFileChunk(path, encoded, first, CHUNK_TIMEOUT_MS)
        first = false
        offset += count
      }
      assertEquals(bytes.size.toString(), client.runBinaryTransferCommand(
        "wc -c < '$path' | tr -d '[:space:]'",
      ))
      assertEquals(sha256(bytes), client.runBinaryTransferCommand(
        "sha256sum '$path' | cut -d ' ' -f1",
      ))
    } finally {
      runCatching { client.runBinaryTransferCommand("rm -f -- '$path'") }
    }
  }

  @Test
  fun shizukuPipeTransfersToShellIdentityAndVerifiesHash() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    assumeTrue("Shizuku service is not running; skipping its device transport test.", Shizuku.pingBinder())
    assumeTrue(
      "Shizuku permission is not granted; skipping its device transport test.",
      Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED,
    )

    val client = ShizukuShellClient(context)
    val bytes = ByteArray(384 * 1_024 + 37) { (it % 239).toByte() }
    val staged = File.createTempFile("stage10i-", ".bin", context.cacheDir)
    val destination = "/data/local/tmp/android-mcp-stage10i-" +
      UUID.randomUUID().toString().replace("-", "") + ".bin"
    try {
      staged.writeBytes(bytes)
      val result = client.writeBinaryFile(
        sourceFile = staged,
        destinationPath = destination,
        overwrite = false,
        maxBytes = bytes.size.toLong(),
        timeoutMs = 120_000L,
        expectedSha256 = sha256(bytes),
      )
      assertEquals(bytes.size.toLong(), result.bytesWritten)
      assertEquals(sha256(bytes), result.sha256)
    } finally {
      runCatching {
        client.execute(
          "rm -f -- '$destination'",
          workingDirectory = "/data/local/tmp",
          timeoutMs = 5_000L,
          maxOutputChars = 1_024,
        )
      }
      runCatching { client.close() }
      staged.delete()
    }
  }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
      .joinToString("") { "%02x".format(it.toInt() and 0xff) }

  private companion object {
    const val TERMUX_PACKAGE = "com.termux"
    const val TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val TERMUX_RAW_CHUNK_BYTES = 96 * 1_024
    const val CHUNK_TIMEOUT_MS = 15_000L
  }
}
