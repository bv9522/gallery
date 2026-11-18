/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.artifact

import com.google.ai.edge.gallery.agent.coordinator.ShellExecutionBackend
import com.google.ai.edge.gallery.shizuku.ShizukuBinaryArtifactWriter
import com.google.ai.edge.gallery.termux.TermuxBinaryArtifactWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Binary shell destination adapter. Each backend keeps its own identity and file namespace:
 * Termux receives bounded RUN_COMMAND stdin chunks; Shizuku receives a one-shot PFD pipe.
 * TERMUX_RISH is deliberately unavailable until end-to-end stdin forwarding is independently proven.
 */
class ShellArtifactDestinationAdapter(
  private val stagingDirectory: File,
  private val termux: TermuxBinaryArtifactWriter,
  private val shizuku: ShizukuBinaryArtifactWriter,
) : ArtifactDestinationAdapter {
  override val destinationType = DestinationType.SHELL

  override suspend fun describe(request: ArtifactCreateRequest): String {
    val destination = request.destination as? ArtifactDestinationSpec.ShellPath
      ?: throw IllegalArgumentException("Shell adapter received a non-shell destination.")
    require(ShellArtifactTransferPolicy.supportsBinaryOutput(destination.backend)) {
    require(destination.absolutePath.substringAfterLast('/') == request.fileName) {
      "Shell destination leaf must exactly match the approved filename."
    }
      "TERMUX_RISH binary artifact output is unavailable until remote stdin forwarding is verified."
    }
    return destination.backend.name + " path " + destination.absolutePath
  }

  override suspend fun write(
    request: ArtifactCreateRequest,
    producer: ArtifactOutputProducer,
  ): ArtifactDestinationWriteResult = stagingMutex.withLock {
    val destination = request.destination as? ArtifactDestinationSpec.ShellPath
      ?: throw IllegalArgumentException("Shell adapter received a non-shell destination.")
    require(ShellArtifactTransferPolicy.supportsBinaryOutput(destination.backend)) {
      "TERMUX_RISH binary artifact output is unavailable until remote stdin forwarding is verified."
    }
    require(destination.absolutePath.substringAfterLast('/') == request.fileName) {
      "Shell destination leaf must exactly match the approved filename."
    }
    require(stagingDirectory.exists() || stagingDirectory.mkdirs()) {
      "The private artifact staging directory is unavailable."
    }
    val staleStagingFiles = stagingDirectory.listFiles()
      ?: throw IllegalStateException("Could not inspect the private artifact staging quota.")
    staleStagingFiles
      .filter { it.isFile && it.name.startsWith("gallery-artifact-") }
      .forEach { stale ->
        check(stale.delete() || !stale.exists()) {
          "A previous private artifact staging file could not be cleaned."
        }
      }
    val staged = File.createTempFile("gallery-artifact-", ".stage", stagingDirectory)
    try {
      val metadata = withContext(Dispatchers.IO) {
        FileOutputStream(staged).use { output ->
          val result = producer.write(output)
          output.flush()
          output.fd.sync()
          result
        }
      }
      require(staged.length() == metadata.bytesWritten && staged.length() in 1L..ShellArtifactTransferPolicy.MAX_STAGED_ARTIFACT_BYTES) {
        "Staged artifact length does not match the verified output metadata."
      }
      withTimeout(TRANSFER_TIMEOUT_MS) {
        when (destination.backend) {
          ShellExecutionBackend.TERMUX -> transferToTermux(staged, destination.absolutePath, request.overwrite, metadata)
          ShellExecutionBackend.SHIZUKU -> {
            val result = shizuku.writeBinaryFile(
              sourceFile = staged,
              destinationPath = destination.absolutePath,
              overwrite = request.overwrite,
              maxBytes = metadata.bytesWritten,
              timeoutMs = TRANSFER_TIMEOUT_MS,
              expectedSha256 = metadata.sha256,
            )
            require(result.bytesWritten == metadata.bytesWritten && result.sha256 == metadata.sha256) {
              "Shizuku destination verification did not match the generated artifact."
            }
          }
          ShellExecutionBackend.TERMUX_RISH -> error("TERMUX_RISH binary artifact output is unavailable.")
        }
      }
      ArtifactDestinationWriteResult(
        destination.backend.name + " path " + destination.absolutePath,
        metadata,
      )
    } catch (cancelled: CancellationException) {
      throw cancelled
    } finally {
      withContext(NonCancellable + Dispatchers.IO) {
        runCatching { staged.delete() }
      }
    }
  }

  private suspend fun transferToTermux(
    staged: File,
    destinationPath: String,
    overwrite: Boolean,
    metadata: ArtifactOutputMetadata,
  ) {
    val temporaryPath = siblingTemporaryPath(destinationPath)
    var finalized = false
    try {
      val quotedTemp = shellQuote(temporaryPath)
      val quotedTarget = shellQuote(destinationPath)
      val targetPolicy = if (overwrite) {
        "[ ! -e $quotedTarget ] || [ -f $quotedTarget ]"
      } else {
        "[ ! -e $quotedTarget ]"
      }
      val preflight =
        "for utility in base64 sha256sum dirname wc tr cut mv rm; do command -v \"\$utility\" >/dev/null 2>&1 || exit 26; done && " +
          "parent=\$(dirname -- $quotedTarget) && [ -d \"\$parent\" ] && " +
          "[ ! -L $quotedTarget ] && { " + targetPolicy + "; } && " +
          "umask 077 && : > $quotedTemp && printf READY"
      check(termux.runBinaryTransferCommand(preflight) == "READY") {
        "Termux destination preflight failed; no artifact was committed."
      }

      FileInputStream(staged).use { input ->
        val buffer = ByteArray(ShellArtifactTransferPolicy.TERMUX_RAW_CHUNK_BYTES)
        var first = true
        while (true) {
          val count = input.read(buffer)
          if (count < 0) break
          val encoded = ShellArtifactTransferPolicy.encodeTermuxChunk(buffer, count)
          termux.appendBinaryFileChunk(temporaryPath, encoded, first)
          first = false
        }
        require(!first) { "Generated artifact is empty." }
      }

      val expectedBytes = metadata.bytesWritten.toString()
      val expectedHash = metadata.sha256
      val movePolicy = if (overwrite) {
        "[ ! -L $quotedTarget ] && { [ ! -e $quotedTarget ] || [ -f $quotedTarget ]; } && " +
          "mv -f -- $quotedTemp $quotedTarget || exit 24; "
      } else {
        "[ ! -e $quotedTarget ] && [ ! -L $quotedTarget ] && " +
          "mv -n -- $quotedTemp $quotedTarget && [ ! -e $quotedTemp ] || exit 25; "
      }
      val finalize =
        "actual_size=\$(wc -c < $quotedTemp | tr -d '[:space:]'); " +
          "actual_hash=\$(sha256sum $quotedTemp | cut -d ' ' -f1); " +
          "[ \"\$actual_size\" = " + shellQuote(expectedBytes) + " ] && " +
          "[ \"\$actual_hash\" = " + shellQuote(expectedHash) + " ] || exit 23; " +
          movePolicy +
          "printf '%s:%s' \"\$actual_size\" \"\$actual_hash\""
      val result = termux.runBinaryTransferCommand(finalize)
      require(result == expectedBytes + ":" + expectedHash) {
        "Termux finalization returned unexpected length or SHA-256."
      }
      finalized = true
    } finally {
      if (!finalized) {
        withContext(NonCancellable) {
          runCatching { termux.runBinaryTransferCommand("rm -f -- $quotedTemp", CLEANUP_TIMEOUT_MS) }
        }
      }
    }
  }

  private fun siblingTemporaryPath(destinationPath: String): String {
    val parent = destinationPath.substringBeforeLast('/', "")
    val name = destinationPath.substringAfterLast('/')
    require(parent.isNotBlank() && name.isNotBlank()) {
      "Shell artifact destination must name a file inside an existing folder."
    }
    return parent + "/." + name + "." + UUID.randomUUID().toString().replace("-", "") + ".part"
  }

  private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

  private companion object {
    val stagingMutex = Mutex()
    const val TRANSFER_TIMEOUT_MS = 120_000L
    const val CLEANUP_TIMEOUT_MS = 5_000L
  }
}
