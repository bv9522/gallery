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

package com.google.ai.edge.gallery.agent.coordinator

import com.google.ai.edge.gallery.shizuku.ShizukuShellClient
import com.google.gson.JsonObject

/**
 * Model-callable adapter for the native Shizuku Android shell.
 *
 * This tool deliberately accepts shell commands rather than mapping natural language to a fixed
 * menu of Android actions. Execution approval is not implemented here; callers must keep this
 * capability behind a trusted UI/approval boundary until the Phase 5 policy layer is in place.
 */
class ShizukuShellTool(
  private val client: ShizukuShellClient,
) : AgentTool {

  override val name: String = "android_shell"

  override val description: String =
    "Execute a command using Android's /system/bin/sh with Shizuku shell privileges (uid 2000, not root). " +
      "Supports Android/Toybox commands and persistent shell state. Arguments: command (required string), " +
      "timeout_ms (optional integer, default 15000, range 100-120000), " +
      "max_output_chars (optional integer, default 32768, range 1024-131072)."

  override suspend fun execute(arguments: JsonObject): AgentToolResult {
    val command = arguments.get("command")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
      ?.asString
      ?.takeIf { it.isNotBlank() }
      ?: return AgentToolResult("Missing required non-empty string argument: command.", false)

    val timeoutMs = arguments.get("timeout_ms")?.let {
      if (!it.isJsonPrimitive || !it.asJsonPrimitive.isNumber) {
        return AgentToolResult("timeout_ms must be an integer.", false)
      }
      runCatching { it.asLong }.getOrElse {
        return AgentToolResult("timeout_ms must be an integer.", false)
      }
    } ?: ShizukuShellClient.DEFAULT_TIMEOUT_MS

    val maxOutputChars = arguments.get("max_output_chars")?.let {
      if (!it.isJsonPrimitive || !it.asJsonPrimitive.isNumber) {
        return AgentToolResult("max_output_chars must be an integer.", false)
      }
      runCatching { it.asInt }.getOrElse {
        return AgentToolResult("max_output_chars must be an integer.", false)
      }
    } ?: ShizukuShellClient.DEFAULT_OUTPUT_LIMIT

    val result = try {
      client.execute(command, timeoutMs = timeoutMs, maxOutputChars = maxOutputChars)
    } catch (error: IllegalArgumentException) {
      return AgentToolResult(error.message ?: "Invalid shell arguments.", false)
    } catch (error: IllegalStateException) {
      return AgentToolResult(error.message ?: "Shizuku shell is unavailable.", false)
    }

    val response = buildString {
      result.error?.let { appendLine("Shell error: $it") }
      if (result.timedOut) appendLine("Command timed out.")
      appendLine("Exit code: ${result.exitCode}")
      if (result.stdout.isNotEmpty()) {
        appendLine("STDOUT:")
        appendLine(result.stdout.trimEnd())
      }
      if (result.stderr.isNotEmpty()) {
        appendLine("STDERR:")
        appendLine(result.stderr.trimEnd())
      }
      if (result.outputTruncated) appendLine("Warning: output was truncated by the configured limit.")
    }.trim()

    return AgentToolResult(response, result.succeeded)
  }
}
