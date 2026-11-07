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

import kotlinx.coroutines.CancellationException

/** Execution identity is explicit and is included in every approval prompt. */
enum class ShellExecutionBackendId(val wireName: String) {
  TERMUX("termux"),
  SHIZUKU("shizuku"),
  TERMUX_RISH("termux_rish"),
}

/** Immutable, fully reviewable request shared by every shell backend. */
data class ShellExecutionRequest(
  val backend: ShellExecutionBackendId,
  val command: String,
  val workingDirectory: String,
  val timeoutMs: Long,
  val maxOutputChars: Int,
) {
  init {
    require(command.isNotBlank()) { "Command must not be blank." }
    require(command.length <= MAX_COMMAND_LENGTH) {
      "Command exceeds $MAX_COMMAND_LENGTH characters."
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
    const val MAX_COMMAND_LENGTH = 8_192
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val DEFAULT_OUTPUT_CHARS = 32 * 1_024
    const val MIN_TIMEOUT_MS = 100L
    const val MAX_TIMEOUT_MS = 120_000L
    const val MIN_OUTPUT_CHARS = 1_024
    const val MAX_OUTPUT_CHARS = 128 * 1_024
  }
}

/** Backend-neutral execution result. */
data class ShellExecutionResult(
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

/** Every backend implements the same contract and receives the exact approved request. */
fun interface ShellExecutionBackend {
  suspend fun execute(request: ShellExecutionRequest): ShellExecutionResult
}

/**
 * Central policy. There is deliberately no permissive/default implementation: callers must supply
 * an explicit approval function, and cancellation is never converted into approval.
 */
fun interface ShellExecutionApprovalPolicy {
  suspend fun approve(request: ShellExecutionRequest): Boolean
}

/** The only component allowed to select and invoke a shell backend. */
class ShellBackendRouter(
  backends: Map<ShellExecutionBackendId, ShellExecutionBackend>,
  private val approvalPolicy: ShellExecutionApprovalPolicy,
) {
  private val backends = backends.toMap()

  init {
    require(this.backends.isNotEmpty()) { "At least one shell backend must be registered." }
  }

  suspend fun execute(request: ShellExecutionRequest): ShellExecutionResult {
    val backend = backends[request.backend]
      ?: return ShellExecutionResult(
        stdout = "",
        stderr = "",
        exitCode = -1,
        error = "Execution backend '${request.backend.wireName}' is unavailable.",
      )

    val approved = try {
      approvalPolicy.approve(request)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      return ShellExecutionResult(
        stdout = "",
        stderr = "",
        exitCode = -1,
        error = "Approval failed: ${error.message ?: error.javaClass.simpleName}",
      )
    }
    if (!approved) {
      return ShellExecutionResult(
        stdout = "",
        stderr = "",
        exitCode = -1,
        error = "Execution denied: the user did not approve this exact request.",
      )
    }

    return try {
      backend.execute(request)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      ShellExecutionResult(
        stdout = "",
        stderr = "",
        exitCode = -1,
        error = "Backend execution failed: ${error.message ?: error.javaClass.simpleName}",
      )
    }
  }
}
