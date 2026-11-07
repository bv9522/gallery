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

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException

/** One model-visible shell capability; the backend is an explicit, validated argument. */
class UnifiedShellTool(
  private val router: ShellBackendRouter,
  private val defaultBackend: ShellExecutionBackendId = ShellExecutionBackendId.TERMUX,
) : AgentTool {
  override val name: String = "shell_execute"

  override val description: String =
    "Execute a user-approved command using Termux, Android Shizuku shell, or Termux+rish. " +
      "Choose backend explicitly: termux (Termux app UID), shizuku (Android shell UID 2000), " +
      "or termux_rish (Termux command bridged through the existing Shizuku service). " +
      "Every invocation requires one-time user approval."

  override val parametersSchema: JsonObject
    get() = JsonObject().apply {
      addProperty("type", "object")
      add("properties", JsonObject().apply {
        add("backend", JsonObject().apply {
          addProperty("type", "string")
          addProperty("enum", ShellExecutionBackendId.entries.joinToString(",") { it.wireName })
          addProperty("description", "Execution identity: termux, shizuku, or termux_rish.")
        })
        add("command", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Command to execute; maximum 8192 characters.")
        })
        add("working_directory", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Working directory for the command.")
        })
        add("timeout_ms", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("description", "Timeout in milliseconds, 100-120000.")
        })
        add("max_output_chars", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("description", "Combined stdout/stderr limit, 1024-131072.")
        })
      })
      add("required", JsonArray().apply { add("backend"); add("command") })
      addProperty("additionalProperties", false)
    }

  override suspend fun execute(arguments: JsonObject): AgentToolResult {
    val allowed = setOf("backend", "command", "working_directory", "timeout_ms", "max_output_chars")
    val unexpected = arguments.keySet() - allowed
    if (unexpected.isNotEmpty()) {
      return AgentToolResult("Rejected unsupported argument(s): ${unexpected.sorted().joinToString()}.", false)
    }

    val backendName = stringArgument(arguments, "backend")
      ?: return AgentToolResult("backend must be one of: termux, shizuku, termux_rish.", false)
    val backend = ShellExecutionBackendId.entries.firstOrNull { it.wireName == backendName }
      ?: return AgentToolResult("Unsupported backend '$backendName'.", false)
    val command = stringArgument(arguments, "command")
      ?: return AgentToolResult("command must be a non-empty string.", false)
    val workingDirectory = stringArgument(arguments, "working_directory")
      ?: when (backend) {
        ShellExecutionBackendId.SHIZUKU -> "/"
        ShellExecutionBackendId.TERMUX, ShellExecutionBackendId.TERMUX_RISH -> "~/"
      }
    val timeout = integerArgument(arguments, "timeout_ms")
      ?: if (arguments.has("timeout_ms")) {
        return AgentToolResult("timeout_ms must be an integer.", false)
      } else ShellExecutionRequest.DEFAULT_TIMEOUT_MS
    val outputLimit = integerArgument(arguments, "max_output_chars")
      ?: if (arguments.has("max_output_chars")) {
        return AgentToolResult("max_output_chars must be an integer.", false)
      } else ShellExecutionRequest.DEFAULT_OUTPUT_CHARS.toLong()

    val request = try {
      ShellExecutionRequest(backend, command, workingDirectory, timeout, outputLimit.toInt().also {
        require(outputLimit in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
      })
    } catch (error: IllegalArgumentException) {
      return AgentToolResult("Rejected: ${error.message}", false)
    }

    val result = try {
      router.execute(request)
    } catch (cancelled: CancellationException) {
      throw cancelled
    }
    val content = buildString {
      result.error?.let { appendLine("Error: $it") }
      if (result.timedOut) appendLine("Command timed out.")
      appendLine("Backend: ${backend.wireName}")
      appendLine("Exit code: ${result.exitCode}")
      if (result.stdout.isNotEmpty()) {
        appendLine("STDOUT:")
        appendLine(result.stdout.trimEnd())
      }
      if (result.stderr.isNotEmpty()) {
        appendLine("STDERR:")
        appendLine(result.stderr.trimEnd())
      }
      if (result.outputTruncated) appendLine("Warning: output was truncated.")
      if (result.processMayStillBeRunning) {
        appendLine("Warning: the backend could not confirm process termination.")
      }
    }.trim()
    return AgentToolResult(content, result.succeeded)
  }

  private fun stringArgument(arguments: JsonObject, key: String): String? =
    arguments.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
      ?.asString?.takeIf { it.isNotBlank() }

  private fun integerArgument(arguments: JsonObject, key: String): Long? {
    val value = arguments.get(key) ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
    return runCatching {
      val number = value.asDouble
      val integer = value.asLong
      integer.takeIf { number.isFinite() && number == integer.toDouble() }
    }.getOrNull()
  }
}
