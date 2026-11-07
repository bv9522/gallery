/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.termux

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Executes Termux commands through a detached, process-group-supervised Termux-side runner.
 *
 * RUN_COMMAND has no cancellation handle. The runner therefore creates a dedicated session with
 * setsid, records its process-group ID, captures output in files, and exposes a status file.
 * Cancellation and timeout send TERM followed by KILL to that process group from a second
 * RUN_COMMAND invocation. GNU timeout is a final safety net if Android MCP is killed before it can
 * issue cleanup.
 */
class TermuxRunCommandClient(context: Context) : TermuxShellExecutor {
  private val appContext = context.applicationContext

  override suspend fun execute(
    command: String,
    workingDirectory: String,
    timeoutMs: Long,
    maxOutputChars: Int,
  ): TermuxShellResult {
    require(command.isNotBlank()) { "Command must not be blank." }
    require(command.length <= MAX_COMMAND_LENGTH) {
      "Command exceeds the $MAX_COMMAND_LENGTH character limit."
    }
    require('\u0000' !in command) { "Command contains a NUL character." }
    require(workingDirectory.isNotBlank() && '\u0000' !in workingDirectory) {
      "Working directory must be a non-empty path without NUL characters."
    }
    require(timeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
      "Timeout must be between $MIN_TIMEOUT_MS and $MAX_TIMEOUT_MS ms."
    }
    require(maxOutputChars in MIN_OUTPUT_CHARS..MAX_OUTPUT_CHARS) {
      "Output limit must be between $MIN_OUTPUT_CHARS and $MAX_OUTPUT_CHARS characters."
    }

    ensureTermuxReady()
    val utilities = invokeTermux(
      "command -v setsid >/dev/null 2>&1 && command -v timeout >/dev/null 2>&1 && " +
        "command -v head >/dev/null 2>&1 && command -v wc >/dev/null 2>&1",
      DEFAULT_TERMUX_WORKDIR,
      PREFLIGHT_TIMEOUT_MS,
      MIN_OUTPUT_CHARS,
    )
    if (!utilities.succeeded) {
      return TermuxShellResult(
        stdout = "",
        stderr = utilities.stderr,
        exitCode = -1,
        error = "Termux process supervision requires setsid and GNU timeout. " +
          "Install them with: pkg install -y util-linux coreutils",
      )
    }

    var runDirectory: String? = null
    try {
      return withTimeout(timeoutMs) {
        val id = UUID.randomUUID().toString().replace("-", "")
        val start = invokeTermux(
          buildStartCommand(
            id = id,
            command = command,
            workingDirectory = workingDirectory,
            timeoutSeconds = ((timeoutMs + 999L) / 1_000L).coerceAtLeast(1L),
            maxOutputChars = maxOutputChars,
          ),
          DEFAULT_TERMUX_WORKDIR,
          START_TIMEOUT_MS,
          MIN_OUTPUT_CHARS,
        )
        if (!start.succeeded) return@withTimeout start

        val directory = start.stdout.trim()
        check(directory.startsWith(TERMUX_RUN_DIRECTORY_PREFIX) && directory.endsWith("/$id")) {
          "Termux returned an invalid process supervisor identifier."
        }
        runDirectory = directory

        val state = awaitCompletion(directory)
        val stdoutLimit = minOf(state.stdoutBytes, maxOutputChars.toLong()).toInt()
        val stderrLimit = minOf(
          state.stderrBytes,
          (maxOutputChars - stdoutLimit).coerceAtLeast(0).toLong(),
        ).toInt()
        val stdout = readCaptured(directory, "stdout", stdoutLimit)
        val stderr = readCaptured(directory, "stderr", stderrLimit)

        TermuxShellResult(
          stdout = stdout,
          stderr = stderr,
          exitCode = state.exitCode,
          outputTruncated = state.outputTruncated ||
            state.stdoutBytes + state.stderrBytes > maxOutputChars ||
            stdout.length < stdoutLimit || stderr.length < stderrLimit,
        )
      }
    } catch (timeout: TimeoutCancellationException) {
      val stopped = runDirectory?.let { directory ->
        withContext(NonCancellable) { terminateSupervisor(directory) }
      } ?: true
      return TermuxShellResult(
        stdout = "",
        stderr = "",
        exitCode = -1,
        timedOut = true,
        error = "Termux command exceeded the ${timeoutMs}ms deadline. " +
          if (stopped) "The supervised process group was terminated."
          else "Process termination could not be confirmed.",
        processMayStillBeRunning = !stopped,
      )
    } catch (cancelled: CancellationException) {
      runDirectory?.let { directory ->
        withContext(NonCancellable) { terminateSupervisor(directory) }
      }
      throw cancelled
    } catch (error: Exception) {
      val stopped = runDirectory?.let { directory ->
        withContext(NonCancellable) { terminateSupervisor(directory) }
      } ?: true
      return TermuxShellResult(
        stdout = "",
        stderr = "",
        exitCode = -1,
        error = error.message ?: error.javaClass.simpleName,
        processMayStillBeRunning = !stopped,
      )
    } finally {
      runDirectory?.let { directory ->
        withContext(NonCancellable) { cleanupSupervisor(directory) }
      }
    }
  }

  private suspend fun awaitCompletion(directory: String): SupervisorState {
    while (true) {
      val command =
        "if [ -f ${shellQuote("$directory/status")} ]; then " +
          "printf 'DONE:'; cat ${shellQuote("$directory/status")}; printf ':'; " +
          "wc -c < ${shellQuote("$directory/stdout")} | tr -d '[:space:]'; printf ':'; " +
          "wc -c < ${shellQuote("$directory/stderr")} | tr -d '[:space:]'; " +
          "if [ -f ${shellQuote("$directory/truncated")} ]; then printf ':1'; " +
          "else printf ':0'; fi; else printf 'RUNNING'; fi"
      val result = invokeTermux(command, DEFAULT_TERMUX_WORKDIR, POLL_TIMEOUT_MS, MIN_OUTPUT_CHARS)
      if (!result.succeeded) {
        throw IllegalStateException(
          result.error ?: result.stderr.ifBlank { "Could not query Termux process status." }
        )
      }
      val value = result.stdout.trim()
      if (value.startsWith("DONE:")) {
        val fields = value.removePrefix("DONE:").split(':')
        require(fields.size == 4) { "Termux returned malformed process status." }
        return SupervisorState(
          exitCode = fields[0].toIntOrNull() ?: -1,
          stdoutBytes = fields[1].toLongOrNull() ?: 0L,
          stderrBytes = fields[2].toLongOrNull() ?: 0L,
          outputTruncated = fields[3] == "1",
        )
      }
      delay(POLL_INTERVAL_MS)
    }
  }

  private suspend fun readCaptured(directory: String, stream: String, limit: Int): String {
    if (limit <= 0) return ""
    val path = shellQuote("$directory/$stream")
    val result = invokeTermux(
      "head -c $limit $path",
      DEFAULT_TERMUX_WORKDIR,
      POLL_TIMEOUT_MS,
      maxOf(MIN_OUTPUT_CHARS, limit),
    )
    if (!result.succeeded) {
      throw IllegalStateException(result.error ?: "Could not read Termux $stream output.")
    }
    return result.stdout.take(limit)
  }

  private suspend fun terminateSupervisor(directory: String): Boolean {
    val quotedDirectory = shellQuote(directory)
    val command = """
      if [ -f $quotedDirectory/status ]; then printf STOPPED; exit 0; fi
      for file in pid timeout_pid; do
        pid=${'(cat $quotedDirectory/\$file 2>/dev/null || true)
        if [ -n "\$pid" ]; then
          kill -TERM -- "-\$pid" 2>/dev/null || kill -TERM "\$pid" 2>/dev/null || true
        fi
      done
      sleep 0.25
      for file in pid timeout_pid; do
        pid=\$(cat $quotedDirectory/\$file 2>/dev/null || true)
        if [ -n "\$pid" ]; then
          kill -KILL -- "-\$pid" 2>/dev/null || kill -KILL "\$pid" 2>/dev/null || true
          if kill -0 -- "-\$pid" 2>/dev/null || kill -0 "\$pid" 2>/dev/null; then
            printf RUNNING
            exit 1
          fi
        fi
      done
      printf STOPPED
    """.trimIndent()
    val result = invokeTermux(command, DEFAULT_TERMUX_WORKDIR, CLEANUP_TIMEOUT_MS, MIN_OUTPUT_CHARS)
    return result.succeeded && result.stdout.trim() == "STOPPED"
  }

}(cat $quotedDirectory/${'file 2>/dev/null || true)
        if [ -n "\$pid" ]; then
          kill -TERM -- "-\$pid" 2>/dev/null || kill -TERM "\$pid" 2>/dev/null || true
        fi
      done
      sleep 0.25
      for file in pid timeout_pid; do
        pid=\$(cat $quotedDirectory/\$file 2>/dev/null || true)
        if [ -n "\$pid" ]; then
          kill -KILL -- "-\$pid" 2>/dev/null || kill -KILL "\$pid" 2>/dev/null || true
          if kill -0 -- "-\$pid" 2>/dev/null || kill -0 "\$pid" 2>/dev/null; then
            printf RUNNING
            exit 1
          fi
        fi
      done
      printf STOPPED
    """.trimIndent()
    val result = invokeTermux(command, DEFAULT_TERMUX_WORKDIR, CLEANUP_TIMEOUT_MS, MIN_OUTPUT_CHARS)
    return result.succeeded && result.stdout.trim() == "STOPPED"
  }

}file 2>/dev/null || true)
        if [ -n "${'pid" ]; then
          kill -TERM -- "-\$pid" 2>/dev/null || kill -TERM "\$pid" 2>/dev/null || true
        fi
      done
      sleep 0.25
      for file in pid timeout_pid; do
        pid=\$(cat $quotedDirectory/\$file 2>/dev/null || true)
        if [ -n "\$pid" ]; then
          kill -KILL -- "-\$pid" 2>/dev/null || kill -KILL "\$pid" 2>/dev/null || true
          if kill -0 -- "-\$pid" 2>/dev/null || kill -0 "\$pid" 2>/dev/null; then
            printf RUNNING
            exit 1
          fi
        fi
      done
      printf STOPPED
    """.trimIndent()
    val result = invokeTermux(command, DEFAULT_TERMUX_WORKDIR, CLEANUP_TIMEOUT_MS, MIN_OUTPUT_CHARS)
    return result.succeeded && result.stdout.trim() == "STOPPED"
  }

}pid" ]; then
          kill -TERM -- "-${'pid" 2>/dev/null || kill -TERM "\$pid" 2>/dev/null || true
        fi
      done
      sleep 0.25
      for file in pid timeout_pid; do
        pid=\$(cat $quotedDirectory/\$file 2>/dev/null || true)
        if [ -n "\$pid" ]; then
          kill -KILL -- "-\$pid" 2>/dev/null || kill -KILL "\$pid" 2>/dev/null || true
          if kill -0 -- "-\$pid" 2>/dev/null || kill -0 "\$pid" 2>/dev/null; then
            printf RUNNING
            exit 1
          fi
        fi
      done
      printf STOPPED
    """.trimIndent()
    val result = invokeTermux(command, DEFAULT_TERMUX_WORKDIR, CLEANUP_TIMEOUT_MS, MIN_OUTPUT_CHARS)
    return result.succeeded && result.stdout.trim() == "STOPPED"
  }

}pid" 2>/dev/null || kill -TERM "${'pid" 2>/dev/null || true
        fi
      done
      sleep 0.25
      for file in pid timeout_pid; do
        pid=\$(cat $quotedDirectory/\$file 2>/dev/null || true)
        if [ -n "\$pid" ]; then
          kill -KILL -- "-\$pid" 2>/dev/null || kill -KILL "\$pid" 2>/dev/null || true
          if kill -0 -- "-\$pid" 2>/dev/null || kill -0 "\$pid" 2>/dev/null; then
            printf RUNNING
            exit 1
          fi
        fi
      done
      printf STOPPED
    """.trimIndent()
    val result = invokeTermux(command, DEFAULT_TERMUX_WORKDIR, CLEANUP_TIMEOUT_MS, MIN_OUTPUT_CHARS)
    return result.succeeded && result.stdout.trim() == "STOPPED"
  }

}pid" 2>/dev/null || true
        fi
      done
      sleep 0.25
      for file in pid timeout_pid; do
        pid=${'(cat $quotedDirectory/\$file 2>/dev/null || true)
        if [ -n "\$pid" ]; then
          kill -KILL -- "-\$pid" 2>/dev/null || kill -KILL "\$pid" 2>/dev/null || true
          if kill -0 -- "-\$pid" 2>/dev/null || kill -0 "\$pid" 2>/dev/null; then
            printf RUNNING
            exit 1
          fi
        fi
      done
      printf STOPPED
    """.trimIndent()
    val result = invokeTermux(command, DEFAULT_TERMUX_WORKDIR, CLEANUP_TIMEOUT_MS, MIN_OUTPUT_CHARS)
    return result.succeeded && result.stdout.trim() == "STOPPED"
  }

}(cat $quotedDirectory/${'file 2>/dev/null || true)
        if [ -n "\$pid" ]; then
          kill -KILL -- "-\$pid" 2>/dev/null || kill -KILL "\$pid" 2>/dev/null || true
          if kill -0 -- "-\$pid" 2>/dev/null || kill -0 "\$pid" 2>/dev/null; then
            printf RUNNING
            exit 1
          fi
        fi
      done
      printf STOPPED
    """.trimIndent()
    val result = invokeTermux(command, DEFAULT_TERMUX_WORKDIR, CLEANUP_TIMEOUT_MS, MIN_OUTPUT_CHARS)
    return result.succeeded && result.stdout.trim() == "STOPPED"
  }

}file 2>/dev/null || true)
        if [ -n "${'pid" ]; then
          kill -KILL -- "-\$pid" 2>/dev/null || kill -KILL "\$pid" 2>/dev/null || true
          if kill -0 -- "-\$pid" 2>/dev/null || kill -0 "\$pid" 2>/dev/null; then
            printf RUNNING
            exit 1
          fi
        fi
      done
      printf STOPPED
    """.trimIndent()
    val result = invokeTermux(command, DEFAULT_TERMUX_WORKDIR, CLEANUP_TIMEOUT_MS, MIN_OUTPUT_CHARS)
    return result.succeeded && result.stdout.trim() == "STOPPED"
  }

}pid" ]; then
          kill -KILL -- "-${'pid" 2>/dev/null || kill -KILL "\$pid" 2>/dev/null || true
          if kill -0 -- "-\$pid" 2>/dev/null || kill -0 "\$pid" 2>/dev/null; then
            printf RUNNING
            exit 1
          fi
        fi
      done
      printf STOPPED
    """.trimIndent()
    val result = invokeTermux(command, DEFAULT_TERMUX_WORKDIR, CLEANUP_TIMEOUT_MS, MIN_OUTPUT_CHARS)
    return result.succeeded && result.stdout.trim() == "STOPPED"
  }

}pid" 2>/dev/null || kill -KILL "${'pid" 2>/dev/null || true
          if kill -0 -- "-\$pid" 2>/dev/null || kill -0 "\$pid" 2>/dev/null; then
            printf RUNNING
            exit 1
          fi
        fi
      done
      printf STOPPED
    """.trimIndent()
    val result = invokeTermux(command, DEFAULT_TERMUX_WORKDIR, CLEANUP_TIMEOUT_MS, MIN_OUTPUT_CHARS)
    return result.succeeded && result.stdout.trim() == "STOPPED"
  }

}pid" 2>/dev/null || true
          if kill -0 -- "-${'pid" 2>/dev/null || kill -0 "\$pid" 2>/dev/null; then
            printf RUNNING
            exit 1
          fi
        fi
      done
      printf STOPPED
    """.trimIndent()
    val result = invokeTermux(command, DEFAULT_TERMUX_WORKDIR, CLEANUP_TIMEOUT_MS, MIN_OUTPUT_CHARS)
    return result.succeeded && result.stdout.trim() == "STOPPED"
  }

}pid" 2>/dev/null || kill -0 "${'pid" 2>/dev/null; then
            printf RUNNING
            exit 1
          fi
        fi
      done
      printf STOPPED
    """.trimIndent()
    val result = invokeTermux(command, DEFAULT_TERMUX_WORKDIR, CLEANUP_TIMEOUT_MS, MIN_OUTPUT_CHARS)
    return result.succeeded && result.stdout.trim() == "STOPPED"
  }

}pid" 2>/dev/null; then
            printf RUNNING
            exit 1
          fi
        fi
      done
      printf STOPPED
    """.trimIndent()
    val result = invokeTermux(command, DEFAULT_TERMUX_WORKDIR, CLEANUP_TIMEOUT_MS, MIN_OUTPUT_CHARS)
    return result.succeeded && result.stdout.trim() == "STOPPED"
  }

  private suspend fun cleanupSupervisor(directory: String) {
    invokeTermux(
      "rm -rf -- ${shellQuote(directory)}",
      DEFAULT_TERMUX_WORKDIR,
      CLEANUP_TIMEOUT_MS,
      MIN_OUTPUT_CHARS,
    )
  }

  private fun buildStartCommand(
    id: String,
    command: String,
    workingDirectory: String,
    timeoutSeconds: Long,
    maxOutputChars: Int,
  ): String {
    val script = """
      run_dir="${'$'}1"
      work_dir="${'$'}2"
      command_text="${'$'}3"
      timeout_seconds="${'$'}4"
      output_limit="${'$'}5"
      mkdir -p "${'$'}run_dir"
      printf '%s' "${'$'}${'$'}" > "${'$'}run_dir/pid"
      case "${'$'}work_dir" in
        "~") work_dir="${'$'}HOME" ;;
        "~/"*) work_dir="${'$'}HOME/${'$'}{work_dir#~/}" ;;
      esac
      cd "${'$'}work_dir" || { printf '126' > "${'$'}run_dir/status"; exit 126; }
      capture_stream() {
        output_file="${'$'}1"
        marker_file="${'$'}2"
        limit="${'$'}3"
        head -c "${'$'}limit" > "${'$'}output_file"
        extra_bytes=${'$'}(cat | wc -c)
        if [ "${'$'}extra_bytes" -gt 0 ]; then touch "${'$'}marker_file"; fi
      }
      exec 3> >(capture_stream "${'$'}run_dir/stderr" "${'$'}run_dir/truncated" "${'$'}output_limit")
      stderr_capture_pid=${'$'}!
      exec 4> >(capture_stream "${'$'}run_dir/stdout" "${'$'}run_dir/truncated" "${'$'}output_limit")
      stdout_capture_pid=${'$'}!
      timeout --signal=TERM --kill-after=2s "${'$'}{timeout_seconds}s" \
        bash -lc "${'$'}command_text" 2>&3 3>&- >&4 4>&- &
      timeout_pid=${'$'}!
      printf '%s' "${'$'}timeout_pid" > "${'$'}run_dir/timeout_pid"
      wait "${'$'}timeout_pid"
      exit_status=${'$'}?
      exec 3>&- 4>&-
      wait "${'$'}stderr_capture_pid" || true
      wait "${'$'}stdout_capture_pid" || true
      printf '%s' "${'$'}exit_status" > "${'$'}run_dir/status"
    """.trimIndent()
    val directory = "$TERMUX_RUN_DIRECTORY_ROOT/$id"
    return "mkdir -p ${shellQuote(TERMUX_RUN_DIRECTORY_ROOT)} && " +
      "setsid -f bash -c ${shellQuote(script)} _ " +
      "${shellQuote(directory)} ${shellQuote(workingDirectory)} " +
      "${shellQuote(command)} $timeoutSeconds $maxOutputChars && " +
      "for n in 1 2 3 4 5 6 7 8 9 10; do " +
      "[ -f ${shellQuote("$directory/timeout_pid")} ] && break; sleep 0.05; done; " +
      "printf '%s' ${shellQuote(directory)}"
  }

  private fun ensureTermuxReady() {
    try {
      @Suppress("DEPRECATION")
      appContext.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
    } catch (error: PackageManager.NameNotFoundException) {
      throw IllegalStateException("Termux is not installed. Install and initialize Termux first.")
    }
    check(appContext.checkSelfPermission(TERMUX_RUN_COMMAND_PERMISSION) ==
      PackageManager.PERMISSION_GRANTED) {
      "Termux RUN_COMMAND permission is not granted to this app. Grant it in Android Settings."
    }
  }

  /** Dispatches one short control command through the documented RUN_COMMAND callback. */
  private suspend fun invokeTermux(
    command: String,
    workingDirectory: String,
    timeoutMs: Long,
    maxOutputChars: Int,
  ): TermuxShellResult {
    val (requestId, deferred) = TermuxCommandResultReceiver.register()
    val callbackIntent = Intent(appContext, TermuxCommandResultReceiver::class.java)
      .putExtra(TermuxCommandResultReceiver.EXTRA_REQUEST_ID, requestId)
    val pendingIntentFlags = PendingIntent.FLAG_ONE_SHOT or
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    val callback = PendingIntent.getBroadcast(
      appContext,
      requestId,
      callbackIntent,
      pendingIntentFlags,
    )
    val runIntent = Intent().apply {
      setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
      action = ACTION_RUN_COMMAND
      putExtra(EXTRA_COMMAND_PATH, TERMUX_BASH_PATH)
      putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", command))
      putExtra(EXTRA_WORKDIR, workingDirectory)
      putExtra(EXTRA_BACKGROUND, true)
      putExtra(EXTRA_COMMAND_LABEL, "Android MCP process supervisor")
      putExtra(EXTRA_COMMAND_DESCRIPTION, "Supervised command control request.")
      putExtra(EXTRA_PENDING_INTENT, callback)
    }

    try {
      withContext(Dispatchers.IO) { appContext.startService(runIntent) }
      val bundle = try {
        withTimeout(timeoutMs) { deferred.await() }
      } catch (timeout: TimeoutCancellationException) {
        return TermuxShellResult(
          stdout = "",
          stderr = "",
          exitCode = -1,
          timedOut = true,
          error = "Timed out waiting for the Termux command service callback.",
          processMayStillBeRunning = true,
        )
      }
      return parseResult(bundle, maxOutputChars)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: SecurityException) {
      return TermuxShellResult(
        stdout = "",
        stderr = "",
        exitCode = -1,
        error = "Termux rejected the request. Check RUN_COMMAND permission and " +
          "allow-external-apps=true in Termux.",
      )
    } catch (error: Exception) {
      return TermuxShellResult(
        stdout = "",
        stderr = "",
        exitCode = -1,
        error = "Could not communicate with Termux: " +
          (error.message ?: error.javaClass.simpleName),
      )
    } finally {
      callback.cancel()
      TermuxCommandResultReceiver.remove(requestId)
    }
  }

  private fun parseResult(bundle: Bundle, maxOutputChars: Int): TermuxShellResult {
    val rawStdout = bundle.getString(EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT).orEmpty()
    val rawStderr = bundle.getString(EXTRA_PLUGIN_RESULT_BUNDLE_STDERR).orEmpty()
    val originalStdoutLength = bundle.getString(EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT_ORIGINAL_LENGTH)
      ?.toLongOrNull()
    val originalStderrLength = bundle.getString(EXTRA_PLUGIN_RESULT_BUNDLE_STDERR_ORIGINAL_LENGTH)
      ?.toLongOrNull()
    val stdout = rawStdout.take(maxOutputChars)
    val stderr = rawStderr.take((maxOutputChars - stdout.length).coerceAtLeast(0))
    val truncated = stdout.length < rawStdout.length ||
      stderr.length < rawStderr.length ||
      (originalStdoutLength != null && originalStdoutLength > rawStdout.length) ||
      (originalStderrLength != null && originalStderrLength > rawStderr.length)
    val errorCode = bundle.getInt(EXTRA_PLUGIN_RESULT_BUNDLE_ERR, RESULT_OK)
    val errorMessage = bundle.getString(EXTRA_PLUGIN_RESULT_BUNDLE_ERRMSG)
      ?.takeIf { it.isNotBlank() }
      ?: if (errorCode != RESULT_OK) "Termux reported an internal execution error (code $errorCode)." else null
    return TermuxShellResult(
      stdout = stdout,
      stderr = stderr,
      exitCode = bundle.getInt(EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE, -1),
      outputTruncated = truncated,
      errorCode = errorCode.takeIf { it != RESULT_OK },
      error = errorMessage,
    )
  }

  private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

  private data class SupervisorState(
    val exitCode: Int,
    val stdoutBytes: Long,
    val stderrBytes: Long,
    val outputTruncated: Boolean,
  )

  companion object {
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val DEFAULT_OUTPUT_LIMIT = 32 * 1_024
    const val DEFAULT_WORKING_DIRECTORY = "~/"

    private const val TERMUX_PACKAGE = "com.termux"
    private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
    private const val TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    private const val EXTRA_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
    private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    internal const val EXTRA_PLUGIN_RESULT_BUNDLE = "result"
    private const val EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT = "stdout"
    private const val EXTRA_PLUGIN_RESULT_BUNDLE_STDERR = "stderr"
    private const val EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT_ORIGINAL_LENGTH = "stdout_original_length"
    private const val EXTRA_PLUGIN_RESULT_BUNDLE_STDERR_ORIGINAL_LENGTH = "stderr_original_length"
    private const val EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE = "exitCode"
    internal const val EXTRA_PLUGIN_RESULT_BUNDLE_ERR = "err"
    internal const val EXTRA_PLUGIN_RESULT_BUNDLE_ERRMSG = "errmsg"
    private const val RESULT_OK = -1

    private const val MIN_TIMEOUT_MS = 100L
    private const val MAX_TIMEOUT_MS = 120_000L
    private const val MIN_OUTPUT_CHARS = 1_024
    private const val MAX_OUTPUT_CHARS = 96 * 1_024
    private const val MAX_COMMAND_LENGTH = 64 * 1_024

    private const val DEFAULT_TERMUX_WORKDIR = "~/"
    private const val TERMUX_RUN_DIRECTORY_ROOT = "/data/data/com.termux/files/usr/var/run/android-mcp"
    private const val TERMUX_RUN_DIRECTORY_PREFIX = "$TERMUX_RUN_DIRECTORY_ROOT/"
    private const val PREFLIGHT_TIMEOUT_MS = 5_000L
    private const val START_TIMEOUT_MS = 10_000L
    private const val POLL_TIMEOUT_MS = 5_000L
    private const val CLEANUP_TIMEOUT_MS = 5_000L
    private const val POLL_INTERVAL_MS = 150L
  }
}
