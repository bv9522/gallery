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
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
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
 * pipes rather than a PTY, so full-screen terminal applications and terminal job-control signals
 * are not supported. Session count, command duration, and captured output are bounded.
 */
class ShizukuShellUserService : IShizukuShellService.Stub() {

  override fun createSession(workingDirectory: String): Bundle {
    if (sessions.size >= MAX_SESSIONS) {
      return errorBundle("The maximum of " + MAX_SESSIONS + " shell sessions is already open.")
    }

    return try {
      val directory = if (workingDirectory.isBlank()) File("/") else File(workingDirectory)
      require(directory.isDirectory) { "Working directory does not exist: " + workingDirectory }

      val id = UUID.randomUUID().toString().replace("-", "")
      val process = ProcessBuilder("/system/bin/sh").directory(directory).start()
      val session = ShellSession(id, process)
      sessions[id] = session
      Bundle().apply { putString(KEY_SESSION_ID, id) }
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

  override fun writeStdin(sessionId: String, input: String): Bundle {
    val session = sessions[sessionId] ?: return errorBundle("Shell session not found.")
    return try {
      session.writeStdin(input)
      Bundle().apply { putBoolean(KEY_SUCCESS, true) }
    } catch (error: Exception) {
      errorBundle(error.message ?: "Could not write to shell stdin.")
    }
  }

  override fun closeSession(sessionId: String) {
    sessions.remove(sessionId)?.close()
  }

  override fun closeAllSessions() {
    sessions.values.toList().forEach { session ->
      sessions.remove(session.id, session)
      session.close()
    }
  }

  private inner class ShellSession(
    val id: String,
    private val process: Process,
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
          close()
          return@synchronized Bundle().apply {
            putString(KEY_ERROR, if (timedOut) "Command timed out; the shell session was closed." else "Shell process exited before command completion.")
            putBoolean(KEY_TIMED_OUT, timedOut)
            putInt(KEY_EXIT_CODE, -1)
            putString(KEY_STDOUT, capture.stdout())
            putString(KEY_STDERR, capture.stderr())
            putBoolean(KEY_OUTPUT_TRUNCATED, capture.outputTruncated())
          }
        }

        capture.toBundle()
      }

    fun writeStdin(input: String) {
      synchronized(writerLock) {
        check(process.isAlive) { "Shell process is no longer running." }
        writer.write(input)
        writer.flush()
      }
    }

    fun close() {
      activeCapture.getAndSet(null)?.signalFailure()
      runCatching { writer.close() }
      if (process.isAlive) {
        process.destroy()
        if (!process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly()
      }
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

  private class CommandCapture(
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
    private val stdoutMarker = Pattern.compile("(^|\\n)" + Pattern.quote(token) + ":(-?\\d+)\\n")
    private val stderrMarker = Pattern.compile("(^|\\n)" + Pattern.quote(token) + "\\n")

    fun accept(isStdout: Boolean, chunk: String) {
      synchronized(lock) {
        val buffer = if (isStdout) stdoutBuffer else stderrBuffer
        val remaining = (maxOutputChars - buffer.length).coerceAtLeast(0)
        if (remaining > 0) buffer.append(chunk, 0, minOf(remaining, chunk.length))
        if (chunk.length > remaining) truncated = true

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
    const val KEY_SUCCESS = "success"

    private const val MAX_SESSIONS = 3
    private const val MIN_TIMEOUT_MS = 100L
    private const val MAX_TIMEOUT_MS = 120_000L
    private const val MIN_OUTPUT_CHARS = 1_024
    private const val MAX_OUTPUT_CHARS = 128 * 1_024
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, ShellSession>()
  }
}
