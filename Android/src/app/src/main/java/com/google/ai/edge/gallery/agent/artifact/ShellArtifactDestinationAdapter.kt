/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.artifact

import android.os.ParcelFileDescriptor
import com.google.ai.edge.gallery.agent.coordinator.ShellExecutionBackend
import com.google.ai.edge.gallery.agent.coordinator.ShellExecutionRequest
import com.google.ai.edge.gallery.agent.coordinator.ShellExecutionRouter
import com.google.ai.edge.gallery.shizuku.ShizukuShellClient
import com.google.ai.edge.gallery.shizuku.ShizukuShellUserService
import com.google.ai.edge.gallery.termux.TermuxRunCommandClient
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

interface ShellBinaryArtifactTransport {
  suspend fun write(
    request: ArtifactCreateRequest,
    producer: ArtifactOutputProducer,
  ): ArtifactDestinationWriteResult
}

/** Binary shell destination with an explicit execution identity and no backend fallback. */
class ShellArtifactDestinationAdapter(
  private val router: ShellExecutionRouter,
  private val transports: Map<ShellExecutionBackend, ShellBinaryArtifactTransport>,
) : ArtifactDestinationAdapter {
  override val destinationType = DestinationType.SHELL

  override suspend fun describe(request: ArtifactCreateRequest): String {
    val destination = request.destination as? ArtifactDestinationSpec.ShellPath
      ?: throw IllegalArgumentException("Shell adapter received a non-shell destination.")
    require(!request.overwrite) {
      "Shell artifact overwrite is not supported yet; existing files are never replaced."
    }
    require(destination.backend in transports) {
      "Binary artifact output is not enabled for ${destination.backend}; no backend substitution is allowed."
    }
    require(destination.backend in router.availableBackends()) {
      "Selected shell backend ${destination.backend} is not currently available."
    }
    val parent = destination.absolutePath.substringBeforeLast('/', "").ifBlank { "/" }
    val command = "test -d ${shellQuote(parent)} && test -w ${shellQuote(parent)} && " +
      "test ! -e ${shellQuote(destination.absolutePath)} && printf 'READY'"
    val preflight = router.execute(
      ShellExecutionRequest(
        backend = destination.backend,
        command = command,
        workingDirectory = parent,
        timeoutMs = PREFLIGHT_TIMEOUT_MS,
        maxOutputChars = 1_024,
      ),
    )
    require(preflight.succeeded && preflight.stdout.trim() == "READY") {
      preflight.error ?: preflight.stderr.ifBlank {
        "Selected shell destination is not writable or the target already exists."
      }
    }
    return "${destination.backend.name} shell path '${destination.absolutePath}'"
  }

  override suspend fun write(
    request: ArtifactCreateRequest,
    producer: ArtifactOutputProducer,
  ): ArtifactDestinationWriteResult {
    val destination = request.destination as? ArtifactDestinationSpec.ShellPath
      ?: throw IllegalArgumentException("Shell adapter received a non-shell destination.")
    require(!request.overwrite) { "Shell artifact overwrite is not supported; refusing to replace an existing file." }
    val transport = transports[destination.backend]
      ?: throw IllegalStateException("Binary output for ${destination.backend} is not configured.")
    return transport.write(request, producer)
  }

  private companion object {
    const val PREFLIGHT_TIMEOUT_MS = 5_000L
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
  }
}

/**
 * Termux's RUN_COMMAND stdin extra is a String, so raw bytes are base64-encoded into bounded
 * chunks. Every chunk is a separate one-shot RUN_COMMAND invocation; the final size and SHA-256
 * are verified before a no-clobber rename publishes the temporary file.
 */
class TermuxShellBinaryArtifactTransport(
  private val executor: TermuxRunCommandClient,
) : ShellBinaryArtifactTransport {
  override suspend fun write(
    request: ArtifactCreateRequest,
    producer: ArtifactOutputProducer,
  ): ArtifactDestinationWriteResult = coroutineScope {
    val destination = request.destination as? ArtifactDestinationSpec.ShellPath
      ?: throw IllegalArgumentException("Termux binary transport received a non-shell destination.")
    require(destination.backend == ShellExecutionBackend.TERMUX) { "Termux transport cannot execute another backend." }
    require(!request.overwrite) { "Termux binary transport never overwrites existing files." }
    val target = destination.absolutePath
    val parent = target.substringBeforeLast('/', "").ifBlank { "/" }
    val temporary = "$parent/.${request.fileName}.android-mcp-${UUID.randomUUID().toString().replace("-", "")}.part"
    var temporaryCreated = false
    try {
      val setup = executor.execute(
        command =
          "command -v base64 >/dev/null 2>&1 && command -v sha256sum >/dev/null 2>&1 && " +
            "command -v wc >/dev/null 2>&1 && command -v mv >/dev/null 2>&1 && " +
            "command -v timeout >/dev/null 2>&1 && test -d ${shellQuote(parent)} && " +
            "test -w ${shellQuote(parent)} && test ! -e ${shellQuote(target)} && " +
            "(umask 077; set -C; : > ${shellQuote(temporary)}) && printf READY",
        workingDirectory = parent,
        timeoutMs = CONTROL_TIMEOUT_MS,
        maxOutputChars = 1_024,
      )
      require(setup.succeeded && setup.stdout.trim() == "READY") {
        setup.error ?: setup.stderr.ifBlank {
          "Termux cannot prepare the selected destination or lacks a required binary-transfer utility."
        }
      }
      temporaryCreated = true

      val metadata = coroutineScope {
        val input = PipedInputStream(PIPE_BUFFER_BYTES)
        val output = PipedOutputStream(input)
        val transfer = async(Dispatchers.IO) {
          input.use { stream ->
            val buffer = ByteArray(BINARY_CHUNK_BYTES)
            while (true) {
              currentCoroutineContext().ensureActive()
              val count = stream.read(buffer)
              if (count < 0) break
              val encoded = Base64.getEncoder().encodeToString(buffer.copyOf(count))
              require(encoded.length <= MAX_STDIN_CHARS) {
                "Encoded binary chunk exceeds the Termux RUN_COMMAND stdin bound."
              }
              val result = executor.executeWithStdin(
                command = "timeout --signal=TERM --kill-after=1s 10s base64 -d >> ${shellQuote(temporary)}",
                stdin = encoded,
                workingDirectory = "~/",
                timeoutMs = CHUNK_TIMEOUT_MS,
                maxOutputChars = 1_024,
              )
              require(result.succeeded) {
                result.error ?: result.stderr.ifBlank { "Termux rejected or truncated a binary stdin chunk." }
              }
            }
          }
        }
        try {
          val produced = withContext(Dispatchers.IO) { producer.write(output) }
          output.close()
          transfer.await()
          produced
        } catch (error: Throwable) {
          runCatching { output.close() }
          transfer.cancel()
          throw error
        }
      }

      val finalizeCommand =
        "size=\$(wc -c < ${shellQuote(temporary)} | tr -d '[:space:]'); " +
          "digest=\$(sha256sum ${shellQuote(temporary)} | cut -d ' ' -f1); " +
          "if [ \"\$size\" != ${shellQuote(metadata.bytesWritten.toString())} ] || " +
          "[ \"\$digest\" != ${shellQuote(metadata.sha256)} ]; then rm -f -- ${shellQuote(temporary)}; exit 91; fi; " +
          "if [ -e ${shellQuote(target)} ]; then rm -f -- ${shellQuote(temporary)}; exit 92; fi; " +
          "mv -n -- ${shellQuote(temporary)} ${shellQuote(target)}; " +
          "if [ -e ${shellQuote(temporary)} ] || [ ! -f ${shellQuote(target)} ]; then " +
          "rm -f -- ${shellQuote(temporary)}; exit 93; fi; " +
          "printf '%s %s\\n' \"\$size\" \"\$digest\""
      val finalized = executor.execute(
        command = finalizeCommand,
        workingDirectory = parent,
        timeoutMs = CONTROL_TIMEOUT_MS,
        maxOutputChars = 1_024,
      )
      val fields = finalized.stdout.trim().split(Regex("\\s+"))
      require(finalized.succeeded && fields.size == 2 &&
        fields[0].toLongOrNull() == metadata.bytesWritten && fields[1] == metadata.sha256) {
        finalized.error ?: finalized.stderr.ifBlank {
          "Termux could not confirm atomic publication of the binary artifact."
        }
      }
      temporaryCreated = false
      ArtifactDestinationWriteResult(
        "${destination.backend.name} shell path '$target'",
        metadata,
      )
    } catch (cancelled: CancellationException) {
      if (temporaryCreated) cleanupTemporary(executor, temporary)
      throw cancelled
    } catch (error: Exception) {
      if (temporaryCreated) cleanupTemporary(executor, temporary)
      throw error
    }
  }

  private suspend fun cleanupTemporary(executor: TermuxRunCommandClient, path: String) {
    withContext(NonCancellable + Dispatchers.IO) {
      runCatching {
        executor.execute(
          command = "rm -f -- ${shellQuote(path)}",
          workingDirectory = "~/",
          timeoutMs = CONTROL_TIMEOUT_MS,
          maxOutputChars = 1_024,
        )
      }
    }
  }

  private companion object {
    const val BINARY_CHUNK_BYTES = 128 * 1_024
    const val PIPE_BUFFER_BYTES = 128 * 1_024
    const val MAX_STDIN_CHARS = 180_000
    const val CONTROL_TIMEOUT_MS = 15_000L
    const val CHUNK_TIMEOUT_MS = 15_000L
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
  }
}

/** Shizuku transports binary data through a PFD pipe, never through a shell command argument. */
class ShizukuShellBinaryArtifactTransport(
  private val client: ShizukuShellClient,
) : ShellBinaryArtifactTransport {
  override suspend fun write(
    request: ArtifactCreateRequest,
    producer: ArtifactOutputProducer,
  ): ArtifactDestinationWriteResult = coroutineScope {
    val destination = request.destination as? ArtifactDestinationSpec.ShellPath
      ?: throw IllegalArgumentException("Shizuku binary transport received a non-shell destination.")
    require(destination.backend == ShellExecutionBackend.SHIZUKU) {
      "Shizuku transport cannot execute another backend."
    }
    require(!request.overwrite) { "Shizuku binary transport never overwrites existing files." }
    val mimeType = outputMimeType(request)
    val descriptors = ParcelFileDescriptor.createPipe()
    val readEnd = descriptors[0]
    val writeEnd = descriptors[1]
    val remote = async(Dispatchers.IO) {
      client.writeBinaryFile(
        targetPath = destination.absolutePath,
        input = readEnd,
        maxBytes = MAX_SHIZUKU_BINARY_BYTES,
        mimeType = mimeType,
        timeoutMs = SHIZUKU_WRITE_TIMEOUT_MS,
      )
    }
    val output = ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)
    try {
      val metadata = withContext(Dispatchers.IO) { producer.write(output) }
      output.close()
      val bundle = remote.await()
      val error = bundle.getString(ShizukuShellUserService.KEY_ERROR)
      require(error == null && bundle.getBoolean(ShizukuShellUserService.KEY_BINARY_SUCCESS)) {
        error ?: "Shizuku did not confirm binary artifact publication."
      }
      val bytes = bundle.getLong(ShizukuShellUserService.KEY_BINARY_BYTES, -1L)
      val digest = bundle.getString(ShizukuShellUserService.KEY_BINARY_SHA256).orEmpty()
      val actualMime = bundle.getString(ShizukuShellUserService.KEY_BINARY_MIME_TYPE).orEmpty()
      require(bytes == metadata.bytesWritten && digest == metadata.sha256 && actualMime == metadata.mimeType) {
        "Shizuku binary output metadata did not match the generated artifact."
      }
      ArtifactDestinationWriteResult(
        "${destination.backend.name} shell path '${destination.absolutePath}'",
        metadata,
      )
    } catch (error: Throwable) {
      runCatching { output.close() }
      remote.cancel()
      throw error
    } finally {
      runCatching { readEnd.close() }
      runCatching { writeEnd.close() }
    }
  }

  private fun outputMimeType(request: ArtifactCreateRequest): String =
    if (request.format != ArtifactFormat.IMAGE) request.format.mimeType
    else when (request.fileName.substringAfterLast('.', "").lowercase()) {
      "png" -> "image/png"
      "jpg", "jpeg" -> "image/jpeg"
      "webp" -> "image/webp"
      else -> throw IllegalArgumentException("Unsupported image output extension.")
    }

  private companion object {
    const val MAX_SHIZUKU_BINARY_BYTES = 100L * 1024L * 1024L
    const val SHIZUKU_WRITE_TIMEOUT_MS = 120_000L
  }
}
