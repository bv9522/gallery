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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Calls Termux's documented RUN_COMMAND service without a network server or extra plugin app.
 *
 * This first adapter intentionally uses background one-shot commands so stdout and stderr are
 * returned separately. Persistent interactive sessions and reliable remote process termination
 * require a later session-bridge layer; RUN_COMMAND alone does not provide those semantics.
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
      putExtra(EXTRA_COMMAND_LABEL, "Android MCP command")
      putExtra(EXTRA_COMMAND_DESCRIPTION, "Command requested by the local AI assistant.")
      putExtra(EXTRA_PENDING_INTENT, callback)
    }

    try {
      withContext(Dispatchers.IO) {
        appContext.startService(runIntent)
      }
      val bundle = try {
        withTimeout(timeoutMs) { deferred.await() }
      } catch (timeout: TimeoutCancellationException) {
        return TermuxShellResult(
          stdout = "",
          stderr = "",
          exitCode = -1,
          timedOut = true,
          error = "Timed out waiting for Termux. The dispatched process may still be running; " +
            "RUN_COMMAND does not provide reliable cancellation of an in-flight command.",
          processMayStillBeRunning = true,
        )
      }
      return parseResult(bundle, maxOutputChars)
    } catch (error: SecurityException) {
      return TermuxShellResult(
        stdout = "",
        stderr = "",
        exitCode = -1,
        error = "Termux rejected the request. Check that this app has the Termux " +
          "RUN_COMMAND permission and that allow-external-apps=true is configured in Termux.",
      )
    } catch (error: Exception) {
      return TermuxShellResult(
        stdout = "",
        stderr = "",
        exitCode = -1,
        error = "Could not start or communicate with Termux: " +
          (error.message ?: error.javaClass.simpleName),
      )
    } finally {
      callback.cancel()
      TermuxCommandResultReceiver.remove(requestId)
    }
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

  private fun parseResult(bundle: Bundle, maxOutputChars: Int): TermuxShellResult {
    val rawStdout = bundle.getString(EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT).orEmpty()
    val rawStderr = bundle.getString(EXTRA_PLUGIN_RESULT_BUNDLE_STDERR).orEmpty()
    val originalStdoutLength = bundle.getString(EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT_ORIGINAL_LENGTH)
      ?.toLongOrNull()
    val originalStderrLength = bundle.getString(EXTRA_PLUGIN_RESULT_BUNDLE_STDERR_ORIGINAL_LENGTH)
      ?.toLongOrNull()
    val totalAllowed = maxOutputChars
    val stdout = rawStdout.take(totalAllowed)
    val stderr = rawStderr.take((totalAllowed - stdout.length).coerceAtLeast(0))
    val truncated = stdout.length < rawStdout.length ||
      stderr.length < rawStderr.length ||
      (originalStdoutLength != null && originalStdoutLength > rawStdout.length) ||
      (originalStderrLength != null && originalStderrLength > rawStderr.length)
    val errorCode = bundle.getInt(EXTRA_PLUGIN_RESULT_BUNDLE_ERR, RESULT_OK)
    val errorMessage = bundle.getString(EXTRA_PLUGIN_RESULT_BUNDLE_ERRMSG)
      ?.takeIf { it.isNotBlank() }

    return TermuxShellResult(
      stdout = stdout,
      stderr = stderr,
      exitCode = bundle.getInt(EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE, -1),
      outputTruncated = truncated,
      errorCode = errorCode.takeIf { it != RESULT_OK },
      error = errorMessage,
    )
  }

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
  }
}
