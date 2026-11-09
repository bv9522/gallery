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

package com.google.ai.edge.gallery.agent.coordinator

import com.google.ai.edge.gallery.shizuku.ShizukuShellExecutor
import com.google.ai.edge.gallery.shizuku.ShizukuShellClient
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException

/** Details presented to the user before a model-requested shell command is executed. */
data class ShellCommandApprovalRequest(
  val command: String,
  val timeoutMs: Long,
  val maxOutputChars: Int,
)

/**
 * UI-owned approval boundary for arbitrary shell execution.
 *
 * Implementations must present the exact command and its limits, and return true only after an
 * explicit user approval. There is intentionally no auto-approve implementation.
 */
fun interface ShellCommandApproval {
  suspend fun requestApproval(request: ShellCommandApprovalRequest): Boolean
}

/**
 * Model-callable adapter for the native Shizuku Android shell.
 *
 * Every invocation requires an explicit approval decision before the shell client is touched.
 * The approval implementation belongs to the UI layer; this tool has no implicit allow path.
 */
class ShizukuShellTool(
  private val client: ShizukuShellExecutor,
  private val approval: ShellCommandApproval,
) : AgentTool {

  override val name: String = "android_shell"

  override val description: String =
    "Execute a command using Android's /system/bin/sh with Shizuku shell privileges (uid 2000, not root). " +
      "Supports Android/Toybox commands and persistent shell state. Every command requires explicit user approval. " +
      "Arguments: command (required string, maximum 8192 characters), " +
      "timeout_ms (optional integer, default 15000, range 100-120000), " +
      "max_output_chars (optional integer, default 32768, range 1024-131072)."

  override val parametersSchema: JsonObject
    get() = JsonObject().apply {
      addProperty("type", "object")
      add("properties", JsonObject().apply {
        add("command", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Shell command to execute; maximum 8192 characters.")
        })
        add("timeout_ms", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("description", "Execution timeout in milliseconds (100-120000).")
        })
        add("max_output_chars", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("description", "Maximum captured output characters (1024-131072).")
        })
      })
      add("required", com.google.gson.JsonArray().apply { add("command") })
      addProperty("additionalProperties", false)
    }

  override suspend fun execute(arguments: JsonObject): AgentToolResult {
    val allowedKeys = setOf("command", "timeout_ms", "max_output_chars")
    val unexpectedKeys = arguments.keySet() - allowedKeys
    if (unexpectedKeys.isNotEmpty()) {
      return AgentToolResult(
        "Rejected: unsupported argument(s): " + unexpectedKeys.sorted().joinToString(", "),
        false,
      )
    }

    val command = arguments.get("command")
      ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
      ?.asString
      ?.takeIf { it.isNotBlank() }
      ?: return AgentToolResult("Missing required non-empty string argument: command.", false)

    if (command.length > MAX_COMMAND_LENGTH) {
      return AgentToolResult("Rejected: command exceeds the $MAX_COMMAND_LENGTH character limit.", false)
    }
    if ('\u0000' in command) {
      return AgentToolResult("Rejected: command contains a NUL character.", false)
    }

    val timeoutMs = parseInteger(arguments, "timeout_ms")
      ?: if (arguments.has("timeout_ms")) {
        return AgentToolResult("timeout_ms must be an integer.", false)
      } else {
        ShizukuShellClient.DEFAULT_TIMEOUT_MS
      }
    val maxOutputChars: Long = parseInteger(arguments, "max_output_chars")
      ?: if (arguments.has("max_output_chars")) {
        return AgentToolResult("max_output_chars must be an integer.", false)
      } else {
        ShizukuShellClient.DEFAULT_OUTPUT_LIMIT.toLong()
      }

    if (timeoutMs !in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
      return AgentToolResult("timeout_ms must be between $MIN_TIMEOUT_MS and $MAX_TIMEOUT_MS.", false)
    }
    if (maxOutputChars !in MIN_OUTPUT_CHARS..MAX_OUTPUT_CHARS) {
      return AgentToolResult(
        "max_output_chars must be between $MIN_OUTPUT_CHARS and $MAX_OUTPUT_CHARS; received $maxOutputChars.",
        false,
      )
    }

    val approved = try {
      approval.requestApproval(ShellCommandApprovalRequest(command, timeoutMs, maxOutputChars.toInt()))
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      return AgentToolResult(
        "Shell approval could not be obtained: " + (error.message ?: error.javaClass.simpleName),
        false,
      )
    }
    if (!approved) {
      return AgentToolResult("Execution denied: the user did not approve this shell command.", false)
    }

    val result = try {
      client.execute(
        command,
        timeoutMs = timeoutMs,
        maxOutputChars = maxOutputChars.toInt(),
      )
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: IllegalArgumentException) {
      return AgentToolResult(error.message ?: "Invalid shell arguments.", false)
    } catch (error: IllegalStateException) {
      return AgentToolResult(error.message ?: "Shizuku shell is unavailable.", false)
    } catch (error: Exception) {
      return AgentToolResult(
        "Shizuku shell execution failed: " + (error.message ?: error.javaClass.simpleName),
        false,
      )
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

  private fun parseInteger(arguments: JsonObject, key: String): Long? {
    val value = arguments.get(key) ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
    return runCatching {
      val number = value.asDouble
      val integer = value.asLong
      integer.takeIf { number.isFinite() && number == integer.toDouble() }
    }.getOrNull()
  }

  companion object {
    const val MAX_COMMAND_LENGTH = 8_192
    private const val MIN_TIMEOUT_MS = 100L
    private const val MAX_TIMEOUT_MS = 120_000L
    private const val MIN_OUTPUT_CHARS = 1_024L
    private const val MAX_OUTPUT_CHARS = 128L * 1_024L
  }
}
