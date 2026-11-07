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
import com.google.ai.edge.gallery.termux.TermuxShellExecutor
import com.google.ai.edge.gallery.tools.AskShellCommandPermissionAction
import com.google.ai.edge.gallery.tools.ToolAction
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.SendChannel
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException

/** Execution identities are intentionally explicit; none of these backends is root. */
enum class ShellExecutionBackend {
  TERMUX,
  SHIZUKU,
  TERMUX_RISH,
}

/** Immutable command and resource limits shown to the user before any backend is invoked. */
data class ShellExecutionRequest(
  val backend: ShellExecutionBackend,
  val command: String,
  val workingDirectory: String = "/",
  val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
  val maxOutputChars: Int = DEFAULT_OUTPUT_CHARS,
) {
  init {
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
  }

  companion object {
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val DEFAULT_OUTPUT_CHARS = 32 * 1_024
    const val MAX_COMMAND_LENGTH = 8_192
    const val MIN_TIMEOUT_MS = 100L
    const val MAX_TIMEOUT_MS = 120_000L
    const val MIN_OUTPUT_CHARS = 1_024
    const val MAX_OUTPUT_CHARS = 96 * 1_024
  }
}

/** Backend-neutral result; process supervision limitations remain explicit to the caller. */
data class ShellExecutionResult(
  val backend: ShellExecutionBackend,
  val stdout: String,
  val stderr: String,
  val exitCode: Int,
  val timedOut: Boolean = false,
  val outputTruncated: Boolean = false,
  val error: String? = null,
  val processMayStillBeRunning: Boolean = false,
) {
  val succeeded: Boolean
    get() = error == null && !timedOut && exitCode == 0 && !processMayStillBeRunning
}

/** A transport implementation. It must not perform approval itself. */
fun interface ShellExecutionTransport {
  suspend fun execute(request: ShellExecutionRequest): ShellExecutionResult
}

/** One policy owns approval for every execution identity. */
fun interface ShellExecutionApprovalPolicy {
  suspend fun requestApproval(request: ShellExecutionRequest): Boolean
}

/**
 * Bridges the shared approval policy to Agent Chat's existing action channel. Missing UI
 * collectors, closed channels, dismissal, and the approval deadline all fail closed.
 */
class ChannelShellExecutionApprovalPolicy(
  private val actionChannel: SendChannel<ToolAction>,
  private val approvalTimeoutMs: Long = 120_000L,
) : ShellExecutionApprovalPolicy {
  override suspend fun requestApproval(request: ShellExecutionRequest): Boolean {
    val action = AskShellCommandPermissionAction(
      backend = request.backend.name,
      command = request.command,
      workingDirectory = request.workingDirectory,
      timeoutMs = request.timeoutMs,
      maxOutputChars = request.maxOutputChars,
    )
    return try {
      actionChannel.send(action)
      withTimeoutOrNull(approvalTimeoutMs) { action.result.await() } ?: false
    } finally {
      if (!action.result.isCompleted) action.result.complete(false)
    }
  }
}

/**
 * Routes only to explicitly registered backends. There is deliberately no fallback: a requested
 * Shizuku command must never silently run as Termux (or vice versa).
 */
class ShellExecutionRouter(transports: Map<ShellExecutionBackend, ShellExecutionTransport>) {
  private val backends = transports.toMap()

  init {
    require(backends.isNotEmpty()) { "At least one shell execution backend is required." }
  }

  suspend fun execute(request: ShellExecutionRequest): ShellExecutionResult {
    val transport = backends[request.backend]
      ?: return ShellExecutionResult(
        backend = request.backend,
        stdout = "",
        stderr = "",
        exitCode = -1,
        error = "Execution backend ${request.backend} is not configured.",
      )
    return transport.execute(request)
  }

  fun availableBackends(): Set<ShellExecutionBackend> = backends.keys
}

/** Direct Termux execution under the Termux application's UID. */
class TermuxShellTransport(private val executor: TermuxShellExecutor) : ShellExecutionTransport {
  override suspend fun execute(request: ShellExecutionRequest): ShellExecutionResult {
    require(request.backend == ShellExecutionBackend.TERMUX)
    val result = executor.execute(
      command = request.command,
      workingDirectory = request.workingDirectory,
      timeoutMs = request.timeoutMs,
      maxOutputChars = request.maxOutputChars,
    )
    return ShellExecutionResult(
      backend = request.backend,
      stdout = result.stdout,
      stderr = result.stderr,
      exitCode = result.exitCode,
      timedOut = result.timedOut,
      outputTruncated = result.outputTruncated,
      error = result.error ?: result.errorCode?.let { "Termux returned error code $it." },
      processMayStillBeRunning = result.processMayStillBeRunning,
    )
  }
}

/** Direct Android shell execution under Shizuku's shell UID (uid 2000, not root). */
class ShizukuShellTransport(private val executor: ShizukuShellExecutor) : ShellExecutionTransport {
  override suspend fun execute(request: ShellExecutionRequest): ShellExecutionResult {
    require(request.backend == ShellExecutionBackend.SHIZUKU)
    val result = executor.execute(
      command = request.command,
      workingDirectory = request.workingDirectory,
      timeoutMs = request.timeoutMs,
      maxOutputChars = request.maxOutputChars,
    )
    return ShellExecutionResult(
      backend = request.backend,
      stdout = result.stdout,
      stderr = result.stderr,
      exitCode = result.exitCode,
      timedOut = result.timedOut,
      outputTruncated = result.outputTruncated,
      error = result.error,
    )
  }
}

/**
 * Runs the command through Termux's installed rish client, which connects to the already-running
 * Shizuku UserService. This does not create or bind a second Shizuku service.
 *
 * rish is an optional Termux-side prerequisite. A missing or stale rish installation fails
 * explicitly; this transport never falls back to Termux's own UID.
 */
class TermuxRishShellTransport(private val executor: TermuxShellExecutor) : ShellExecutionTransport {
  override suspend fun execute(request: ShellExecutionRequest): ShellExecutionResult {
    require(request.backend == ShellExecutionBackend.TERMUX_RISH)
    val remoteCommand = "cd ${shellQuote(request.workingDirectory)} && ${request.command}"
    val wrappedCommand = "rish -c ${shellQuote(remoteCommand)}"
    val result = executor.execute(
      command = wrappedCommand,
      workingDirectory = "~/",
      timeoutMs = request.timeoutMs,
      maxOutputChars = request.maxOutputChars,
    )
    return ShellExecutionResult(
      backend = request.backend,
      stdout = result.stdout,
      stderr = result.stderr,
      exitCode = result.exitCode,
      timedOut = result.timedOut,
      outputTruncated = result.outputTruncated,
      error = result.error ?: result.errorCode?.let { "Termux returned error code $it." },
      processMayStillBeRunning = result.processMayStillBeRunning,
    )
  }

  private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

/**
 * The only model-callable shell tool. Validation happens before approval; approval happens before
 * routing; the exact immutable request approved is the one executed.
 */
class UnifiedShellTool(
  private val router: ShellExecutionRouter,
  private val approval: ShellExecutionApprovalPolicy,
) : AgentTool {
  override val name: String = "execute_shell"
  override val description: String =
    "Execute a shell command using an explicitly selected backend: TERMUX (Termux app UID), " +
      "SHIZUKU (Android shell UID 2000, not root), or TERMUX_RISH (Termux invoking the existing " +
      "Shizuku service). Every command requires explicit user approval."

  override val parametersSchema: JsonObject
    get() = JsonObject().apply {
      addProperty("type", "object")
      add("properties", JsonObject().apply {
        add("backend", JsonObject().apply {
          addProperty("type", "string")
          add("enum", com.google.gson.JsonArray().apply {
            ShellExecutionBackend.entries.forEach { add(it.name) }
          })
          addProperty("description", "Execution identity. No implicit backend fallback.")
        })
        add("command", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Command to execute; maximum 8192 characters.")
        })
        add("working_directory", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Working directory. Defaults to the backend's home directory.")
        })
        add("timeout_ms", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("description", "Timeout in milliseconds (100-120000).")
        })
        add("max_output_chars", JsonObject().apply {
          addProperty("type", "integer")
          addProperty("description", "Combined stdout/stderr limit (1024-98304).")
        })
      })
      add("required", com.google.gson.JsonArray().apply {
        add("backend")
        add("command")
      })
      addProperty("additionalProperties", false)
    }

  override suspend fun execute(arguments: JsonObject): AgentToolResult {
    val allowed = setOf("backend", "command", "working_directory", "timeout_ms", "max_output_chars")
    val unexpected = arguments.keySet() - allowed
    if (unexpected.isNotEmpty()) {
      return AgentToolResult("Rejected: unsupported argument(s): ${unexpected.sorted().joinToString(", ")}.", false)
    }

    val backendText = arguments.string("backend")
      ?: return AgentToolResult("Missing required string argument: backend.", false)
    val backend = runCatching { ShellExecutionBackend.valueOf(backendText) }.getOrNull()
      ?: return AgentToolResult("Rejected: unsupported backend '$backendText'.", false)
    val command = arguments.string("command")
      ?: return AgentToolResult("Missing required non-empty string argument: command.", false)
    val workingDirectory = arguments.string("working_directory") ?: when (backend) {
      ShellExecutionBackend.TERMUX, ShellExecutionBackend.TERMUX_RISH -> "~/"
      ShellExecutionBackend.SHIZUKU -> "/"
    }
    val timeout = arguments.integer("timeout_ms") ?: if (arguments.has("timeout_ms")) {
      return AgentToolResult("timeout_ms must be an integer.", false)
    } else ShellExecutionRequest.DEFAULT_TIMEOUT_MS
    val outputLimit = arguments.integer("max_output_chars") ?: if (arguments.has("max_output_chars")) {
      return AgentToolResult("max_output_chars must be an integer.", false)
    } else ShellExecutionRequest.DEFAULT_OUTPUT_CHARS

    if (outputLimit !in ShellExecutionRequest.MIN_OUTPUT_CHARS.toLong()..ShellExecutionRequest.MAX_OUTPUT_CHARS.toLong()) {
      return AgentToolResult("max_output_chars must be between ${ShellExecutionRequest.MIN_OUTPUT_CHARS} and ${ShellExecutionRequest.MAX_OUTPUT_CHARS}.", false)
    }

    val request = try {
      ShellExecutionRequest(backend, command, workingDirectory, timeout, outputLimit.toInt())
    } catch (error: IllegalArgumentException) {
      return AgentToolResult("Rejected: ${error.message}", false)
    }

    val approved = try {
      approval.requestApproval(request)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      return AgentToolResult("Approval failed: ${error.message ?: error.javaClass.simpleName}", false)
    }
    if (!approved) return AgentToolResult("Execution denied: user approval was not granted.", false)

    val result = try {
      router.execute(request)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      return AgentToolResult("Execution failed: ${error.message ?: error.javaClass.simpleName}", false)
    }

    val response = buildString {
      appendLine("Backend: ${result.backend}")
      result.error?.let { appendLine("Execution error: $it") }
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
      if (result.outputTruncated) appendLine("Warning: output was truncated.")
      if (result.processMayStillBeRunning) {
        appendLine("Warning: Termux may still be running this command; reliable process cancellation is not available through this transport yet.")
      }
    }.trim()
    return AgentToolResult(response, result.succeeded)
  }

  private fun JsonObject.string(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
      ?.asString?.takeIf { it.isNotBlank() }

  private fun JsonObject.integer(key: String): Long? {
    val value = get(key) ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
    return runCatching {
      val number = value.asDouble
      val integer = value.asLong
      integer.takeIf { number.isFinite() && number == integer.toDouble() }
    }.getOrNull()
  }
}
