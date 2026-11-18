/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.shizuku

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

/**
 * Shizuku-backed shell host. Each session owns one long-lived /system/bin/sh process, preserving
 * working directory and exported environment variables between commands.
 *
 * This is Android's shell environment, not root or a bundled GNU/Linux environment. Streams are
 * pipes rather than a PTY, so full-screen terminal applications, interactive command stdin, and
 * terminal job-control signals are not supported yet. Session count, command duration, and
 * captured output are bounded.
 */
class ShizukuShellUserService : IShizukuShellService.Stub() {

  override fun createSession(workingDirectory: String): Bundle {
    if (sessions.size >= MAX_SESSIONS) {
      return errorBundle("The maximum of " + MAX_SESSIONS + " shell sessions is already open.")
    }

    return try {
      val directory = if (workingDirectory.isBlank()) File("/") else File(workingDirectory)
      require(directory.isDirectory) { "Working directory does not exist: " + workingDirectory }

      val setsid = File("/system/bin/setsid")
      check(setsid.isFile && setsid.canExecute()) {
        "Android shell process supervision requires /system/bin/setsid; refusing an unsupervised shell session."
      }
      val id = UUID.randomUUID().toString().replace("-", "")
      // Isolate the persistent shell and every command it launches in one process group. Closing
      // or cancelling the session can then terminate the complete process tree, not just /system/bin/sh.
      // Ask the shell to report its own PID before replacing itself with the persistent shell.
      // Because setsid created a new session, this PID is also the process-group ID. Reading the
      // handshake from the raw stream avoids relying on Process.pid(), which is not in Android's
      // java.lang.Process API surface.
      val process = ProcessBuilder(
        setsid.absolutePath,
        "/system/bin/sh",
        "-c",
        "printf '%s\\n' \"\$\$\"; exec /system/bin/sh",
      )
        .directory(directory)
        .start()
      try {
        val processGroupId = readProcessGroupId(process)
        val session = ShellSession(id, process, processGroupId)
        sessions[id] = session
        Bundle().apply { putString(KEY_SESSION_ID, id) }
      } catch (error: Exception) {
        runCatching { process.destroyForcibly() }
        runCatching { process.waitFor(250, TimeUnit.MILLISECONDS) }
        throw error
      }
    } catch (error: Exception) {
      errorBundle(error.message ?: "Could not create shell session.")
    }
  }

  override fun execute(
    sessionId: String,
    command: String,
    timeoutMs: Long,
    maxOutputChars: Int,
  ): Bundle {
    if (command.isBlank()) return errorBundle("Command must not be blank.")
    if (timeoutMs !in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
      return errorBundle("Timeout must be between " + MIN_TIMEOUT_MS + " and " + MAX_TIMEOUT_MS + " ms.")
    }
    if (maxOutputChars !in MIN_OUTPUT_CHARS..MAX_OUTPUT_CHARS) {
      return errorBundle("Output limit must be between " + MIN_OUTPUT_CHARS + " and " + MAX_OUTPUT_CHARS + " characters.")
    }

    val session = sessions[sessionId] ?: return errorBundle("Shell session not found.")
    val result = session.execute(command, timeoutMs, maxOutputChars)
    if (result.getBoolean(KEY_TIMED_OUT) || !session.isAlive()) {
      sessions.remove(sessionId, session)
      session.close()
    }
    return result
  }

  /**
   * Streams a binary artifact from the app through a Binder file descriptor into a shell-owned
   * temporary sibling file. It verifies size, digest, MIME/signature, then atomically publishes
   * with a no-clobber hard link. Existing targets are never replaced.
   */
  override fun writeBinaryFile(
    targetPath: String,
    input: ParcelFileDescriptor,
    maxBytes: Long,
    mimeType: String,
    timeoutMs: Long,
  ): Bundle {
    var temporary: File? = null
    var target: File? = null
    var targetLinked = false
    val startedAt = System.nanoTime()
    try {
      require(maxBytes in 1L..MAX_BINARY_WRITE_BYTES) {
        "Binary write limit must be between 1 and $MAX_BINARY_WRITE_BYTES bytes."
      }
      require(timeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
        "Binary write timeout must be between $MIN_TIMEOUT_MS and $MAX_TIMEOUT_MS ms."
      }
      require(mimeType in SUPPORTED_BINARY_MIME_TYPES) { "Unsupported binary artifact MIME type." }
      target = resolveSafeBinaryTarget(targetPath)
      require(!target.exists() && !android.system.OsConstants.S_ISLNK(android.system.Os.lstat(target.absolutePath).st_mode)) {
        "The target already exists or is a symbolic link; overwrite is not supported."
      }
      val parent = target.parentFile ?: throw IllegalArgumentException("Target has no parent directory.")
      temporary = File.createTempFile(".android-mcp-artifact-", ".part", parent)
      val digest = MessageDigest.getInstance("SHA-256")
      var bytesWritten = 0L
      ParcelFileDescriptor.AutoCloseInputStream(input).use { source ->
        FileOutputStream(temporary).use { destination ->
          val buffer = ByteArray(BINARY_BUFFER_BYTES)
          while (true) {
            if (System.nanoTime() - startedAt > TimeUnit.MILLISECONDS.toNanos(timeoutMs)) {
              throw IllegalStateException("Shizuku binary transfer exceeded its time limit.")
            }
            val count = source.read(buffer)
            if (count < 0) break
            bytesWritten += count
            require(bytesWritten <= maxBytes) { "Binary artifact exceeds the $maxBytes byte limit." }
            digest.update(buffer, 0, count)
            destination.write(buffer, 0, count)
          }
          destination.flush()
          destination.fd.sync()
        }
      }
      require(bytesWritten > 0L) { "Binary artifact is empty." }
      require(hasExpectedSignature(temporary, mimeType)) {
        "Binary artifact signature does not match the declared MIME type."
      }
      require(!target.exists()) { "The target appeared during transfer; refusing to overwrite it." }
      // link(2) is atomic and fails with EEXIST instead of replacing a concurrently-created target.
      // Some document filesystems do not support hard links; those paths fail closed.
      Os.link(temporary.absolutePath, target.absolutePath)
      targetLinked = true
      Os.unlink(temporary.absolutePath)
      temporary = null
      return Bundle().apply {
        putBoolean(KEY_BINARY_SUCCESS, true)
        putLong(KEY_BINARY_BYTES, bytesWritten)
        putString(KEY_BINARY_SHA256, digest.digest().toHexString())
        putString(KEY_BINARY_MIME_TYPE, mimeType)
        putString(KEY_BINARY_PATH, target.absolutePath)
      }
    } catch (error: Exception) {
      if (targetLinked) target?.let { runCatching { Os.unlink(it.absolutePath) } }
      temporary?.let { runCatching { it.delete() } }
      return errorBundle(error.message ?: error.javaClass.simpleName)
    } finally {
      runCatching { input.close() }
    }
  }

  private fun resolveSafeBinaryTarget(path: String): File {
    require(path.startsWith('/') && path.length <= MAX_BINARY_PATH_CHARS && '\\u0000' !in path) {
      "Binary target must be a bounded absolute path."
    }
    val segments = path.split('/')
    require(segments.drop(1).all { it.isNotBlank() && it != "." && it != ".." && it.none(Char::isISOControl) }) {
      "Binary target contains an unsafe path segment."
    }
    val raw = File(path)
    require(raw.name.isNotBlank() && raw.name != "." && raw.name != "..") {
      "Binary target filename is invalid."
    }
    val parent = raw.parentFile?.canonicalFile
      ?: throw IllegalArgumentException("Binary target parent is invalid.")
    require(parent.isDirectory && parent.canWrite()) {
      "Binary target parent must already exist and be writable by the selected shell identity."
    }
    return File(parent, raw.name)
  }

  private fun hasExpectedSignature(file: File, mimeType: String): Boolean {
    val prefix = ByteArray(12)
    val count = FileInputStream(file).use { it.read(prefix) }
    if (count < 4) return false
    return when (mimeType) {
      "application/pdf" -> count >= 5 && prefix.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray())
      "image/png" -> count >= 8 && prefix.copyOfRange(0, 8).contentEquals(
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
      )
      "image/jpeg" -> count >= 3 && prefix[0] == 0xff.toByte() && prefix[1] == 0xd8.toByte() && prefix[2] == 0xff.toByte()
      "image/webp" -> count >= 12 &&
        String(prefix, 0, 4, StandardCharsets.US_ASCII) == "RIFF" &&
        String(prefix, 8, 4, StandardCharsets.US_ASCII) == "WEBP"
      else -> count >= 4 && prefix[0] == 0x50.toByte() && prefix[1] == 0x4b.toByte() &&
        prefix[2] == 0x03.toByte() && prefix[3] == 0x04.toByte()
    }
  }

  private fun ByteArray.toHexString(): String =
    joinToString("") { "%02x".format(it.toInt() and 0xff) }

  override fun closeSession(sessionId: String) {
    sessions.remove(sessionId)?.close()
  }

  /** Interrupts an in-flight command by destroying its shell process and all attached streams. */
  override fun cancelSession(sessionId: String) {
    sessions.remove(sessionId)?.close()
  }

  override fun closeAllSessions() {
    sessions.values.toList().forEach { session ->
      sessions.remove(session.id, session)
      session.close()
    }
  }

  /**
   * Reads the PID emitted by the setsid-owned shell before starting the normal stdout reader.
   * The shell then execs the persistent interpreter, preserving that PID and process group.
   */
  private fun readProcessGroupId(process: Process): Int {
    val processGroupId = AtomicReference<Int?>()
    val failure = AtomicReference<Throwable?>()
    val completed = CountDownLatch(1)
    Thread({
      try {
        val line = buildString {
          while (length <= 16) {
            val next = process.inputStream.read()
            if (next < 0 || next == '\n'.code) break
            if (next != '\r'.code) append(next.toChar())
          }
        }
        processGroupId.set(
          line.toIntOrNull()?.takeIf { it > 1 }
            ?: throw IllegalStateException("The shell did not provide a valid process-group ID."),
        )
      } catch (error: Throwable) {
        failure.set(error)
      } finally {
        completed.countDown()
      }
    }, "ShizukuShellHandshake").apply {
      isDaemon = true
      start()
    }

    if (!completed.await(PROCESS_GROUP_HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
      throw IllegalStateException("Timed out while initializing the supervised shell process.")
    }
    failure.get()?.let {
      throw IllegalStateException("Could not initialize the supervised shell process.", it)
    }
    return processGroupId.get()
      ?: throw IllegalStateException("The shell process-group handshake returned no ID.")
  }

  private inner class ShellSession(
    val id: String,
    private val process: Process,
    private val processGroupId: Int,
  ) {
    private val writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
    private val writerLock = Any()
    private val executionLock = Any()
    private val activeCapture = AtomicReference<CommandCapture?>(null)

    init {
      startReader(process.inputStream, isStdout = true)
      startReader(process.errorStream, isStdout = false)
    }

    fun isAlive(): Boolean = process.isAlive

    fun execute(command: String, timeoutMs: Long, maxOutputChars: Int): Bundle =
      synchronized(executionLock) {
        if (!process.isAlive) return@synchronized errorBundle("Shell process is no longer running.")

        val token = "__WINTAINIUM_DONE_" + UUID.randomUUID().toString().replace("-", "") + "__"
        val capture = CommandCapture(token, maxOutputChars)
        activeCapture.set(capture)

        try {
          synchronized(writerLock) {
            writer.write(command)
            writer.newLine()
            val statusVariable = "__wintainium_status_" + token
              .removePrefix("__WINTAINIUM_DONE_")
              .removeSuffix("__")
            writer.write(statusVariable + "=\$?; printf '\\n%s:%s\\n' '" + token +
              "' \"\$" + statusVariable + "\"; printf '\\n%s\\n' '" + token + "' >&2")
            writer.newLine()
            writer.flush()
          }
        } catch (error: Exception) {
          activeCapture.compareAndSet(capture, null)
          return@synchronized errorBundle(error.message ?: "Could not write command to shell.")
        }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var completed = false
        while (System.nanoTime() < deadline && process.isAlive) {
          val remaining = deadline - System.nanoTime()
          if (remaining <= 0L) break
          capture.await(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS)
          if (capture.isComplete()) {
            completed = true
            break
          }
          if (capture.hasFailed()) break
        }
        activeCapture.compareAndSet(capture, null)

        if (!completed) {
          val timedOut = process.isAlive
          val processStopped = close()
          return@synchronized Bundle().apply {
            putString(KEY_ERROR, if (timedOut) "Command timed out; the shell session was closed." else "Shell process exited before command completion.")
            putBoolean(KEY_PROCESS_MAY_STILL_BE_RUNNING, !processStopped)
            putBoolean(KEY_TIMED_OUT, timedOut)
            putInt(KEY_EXIT_CODE, -1)
            putString(KEY_STDOUT, capture.stdout())
            putString(KEY_STDERR, capture.stderr())
            putBoolean(KEY_OUTPUT_TRUNCATED, capture.outputTruncated())
          }
        }

        capture.toBundle()
      }

    fun close(): Boolean {
      activeCapture.getAndSet(null)?.signalFailure()
      runCatching { writer.close() }
      // setsid made this session's PID its process-group ID. Signal the whole group so commands
      // and ordinary descendants cannot survive a timeout, cancellation, or session teardown.
      runCatching { Os.kill(-processGroupId, OsConstants.SIGTERM) }
      runCatching { process.waitFor(250, TimeUnit.MILLISECONDS) }
      runCatching { Os.kill(-processGroupId, OsConstants.SIGKILL) }
      if (process.isAlive) {
        runCatching { process.destroyForcibly() }
        runCatching { process.waitFor(250, TimeUnit.MILLISECONDS) }
      }
      val groupStopped = try {
        Os.kill(-processGroupId, 0)
        false
      } catch (error: ErrnoException) {
        error.errno == OsConstants.ESRCH
      }
      return !process.isAlive && groupStopped
    }

    private fun startReader(stream: InputStream, isStdout: Boolean) {
      Thread({
        val reader = InputStreamReader(stream, StandardCharsets.UTF_8)
        val buffer = CharArray(4096)
        try {
          while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            activeCapture.get()?.accept(isStdout, String(buffer, 0, count))
          }
        } catch (_: Exception) {
          // Closing a session closes both streams; reader termination is expected.
        } finally {
          activeCapture.get()?.signalFailure()
        }
      }, if (isStdout) "ShizukuShellStdout-" + id else "ShizukuShellStderr-" + id).apply {
        isDaemon = true
        start()
      }
    }
  }

  internal class CommandCapture(
    private val token: String,
    private val maxOutputChars: Int,
  ) {
    private val lock = Any()
    private val completed = CountDownLatch(1)
    private val stdoutBuffer = StringBuilder()
    private val stderrBuffer = StringBuilder()
    private var stdoutTail = ""
    private var stderrTail = ""
    private var stdoutLength = 0L
    private var stderrLength = 0L
    private var stdoutMarkerOffset = -1L
    private var stdoutDone = false
    private var stderrDone = false
    private var failed = false
    private var exitCode = -1
    private var truncated = false
    private var capturedChars = 0
    private val stdoutMarker = Pattern.compile("(^|\\n)" + Pattern.quote(token) + ":(-?\\d+)\\n")
    private val stderrMarker = Pattern.compile("(^|\\n)" + Pattern.quote(token) + "\\n")

    fun accept(isStdout: Boolean, chunk: String) {
      synchronized(lock) {
        val buffer = if (isStdout) stdoutBuffer else stderrBuffer
        val remaining = (maxOutputChars - capturedChars).coerceAtLeast(0)
        val accepted = minOf(remaining, chunk.length)
        if (accepted > 0) {
          buffer.append(chunk, 0, accepted)
          capturedChars += accepted
        }
        if (chunk.length > accepted) truncated = true

        if (isStdout) {
          stdoutLength += chunk.length
          stdoutTail = (stdoutTail + chunk).takeLast(512)
          val matcher = stdoutMarker.matcher(stdoutTail)
          if (!stdoutDone && matcher.find()) {
            stdoutMarkerOffset = stdoutLength - stdoutTail.length + matcher.start()
            exitCode = matcher.group(2).toIntOrNull() ?: -1
            stdoutDone = true
          }
        } else {
          stderrLength += chunk.length
          stderrTail = (stderrTail + chunk).takeLast(512)
          if (!stderrDone && stderrMarker.matcher(stderrTail).find()) stderrDone = true
        }

        if (stdoutDone && stderrDone) completed.countDown()
      }
    }

    fun await(timeout: Long, unit: TimeUnit) {
      completed.await(timeout, unit)
    }

    fun isComplete(): Boolean = synchronized(lock) { stdoutDone && stderrDone }

    fun hasFailed(): Boolean = synchronized(lock) { failed }

    fun signalFailure() {
      synchronized(lock) {
        failed = true
        completed.countDown()
      }
    }

    fun stdout(): String = synchronized(lock) {
      val markerOffset = stdoutMarkerOffset
      if (markerOffset >= 0 && markerOffset <= stdoutBuffer.length) {
        stdoutBuffer.substring(0, markerOffset.toInt())
      } else {
        stdoutBuffer.toString()
      }
    }

    fun stderr(): String = synchronized(lock) {
      val markerPattern = Pattern.compile("(?s)(?:^|\\n)" + Pattern.quote(token) + "\\n.*$")
      markerPattern.matcher(stderrBuffer.toString()).replaceFirst("")
    }

    fun outputTruncated(): Boolean = synchronized(lock) { truncated }

    fun toBundle(): Bundle = Bundle().apply {
      putString(KEY_STDOUT, stdout())
      putString(KEY_STDERR, stderr())
      putInt(KEY_EXIT_CODE, exitCode)
      putBoolean(KEY_TIMED_OUT, false)
      putBoolean(KEY_OUTPUT_TRUNCATED, outputTruncated())
    }
  }

  private fun errorBundle(message: String): Bundle =
    Bundle().apply {
      putString(KEY_ERROR, message)
      putInt(KEY_EXIT_CODE, -1)
      putBoolean(KEY_TIMED_OUT, false)
      putBoolean(KEY_OUTPUT_TRUNCATED, false)
    }

  companion object {
    const val KEY_SESSION_ID = "session_id"
    const val KEY_STDOUT = "stdout"
    const val KEY_STDERR = "stderr"
    const val KEY_EXIT_CODE = "exit_code"
    const val KEY_TIMED_OUT = "timed_out"
    const val KEY_OUTPUT_TRUNCATED = "output_truncated"
    const val KEY_ERROR = "error"
    const val KEY_PROCESS_MAY_STILL_BE_RUNNING = "process_may_still_be_running"
    const val KEY_BINARY_SUCCESS = "binary_success"
    const val KEY_BINARY_BYTES = "binary_bytes"
    const val KEY_BINARY_SHA256 = "binary_sha256"
    const val KEY_BINARY_MIME_TYPE = "binary_mime_type"
    const val KEY_BINARY_PATH = "binary_path"

    private const val PROCESS_GROUP_HANDSHAKE_TIMEOUT_MS = 2_000L
    private const val MAX_SESSIONS = 3
    private const val MIN_TIMEOUT_MS = 100L
    private const val MAX_TIMEOUT_MS = 120_000L
    private const val MIN_OUTPUT_CHARS = 1_024
    private const val MAX_OUTPUT_CHARS = 128 * 1_024
    private const val MAX_BINARY_WRITE_BYTES = 100L * 1024L * 1024L
    private const val MAX_BINARY_PATH_CHARS = 1_024
    private const val BINARY_BUFFER_BYTES = 32 * 1_024
    private val SUPPORTED_BINARY_MIME_TYPES = setOf(
      "application/pdf",
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      "application/vnd.openxmlformats-officedocument.presentationml.presentation",
      "application/zip",
      "image/png",
      "image/jpeg",
      "image/webp",
    )
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, ShellSession>()
  }
}
