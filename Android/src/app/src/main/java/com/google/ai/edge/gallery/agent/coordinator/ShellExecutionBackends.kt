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

import com.google.ai.edge.gallery.shizuku.ShizukuShellExecutor
import com.google.ai.edge.gallery.termux.TermuxShellExecutor
import com.google.ai.edge.gallery.tools.AskShellCommandPermissionAction
import com.google.ai.edge.gallery.tools.ToolAction
import kotlinx.coroutines.channels.SendChannel

/** Adapts the existing Termux RUN_COMMAND transport to the shared execution contract. */
class TermuxShellBackend(
  private val executor: TermuxShellExecutor,
) : ShellExecutionBackend {
  override suspend fun execute(request: ShellExecutionRequest): ShellExecutionResult {
    require(request.backend == ShellExecutionBackendId.TERMUX)
    val result = executor.execute(
      command = request.command,
      workingDirectory = request.workingDirectory,
      timeoutMs = request.timeoutMs,
      maxOutputChars = request.maxOutputChars,
    )
    return ShellExecutionResult(
      stdout = result.stdout,
      stderr = result.stderr,
      exitCode = result.exitCode,
      timedOut = result.timedOut,
      outputTruncated = result.outputTruncated,
      error = result.error,
      processMayStillBeRunning = result.processMayStillBeRunning,
    )
  }
}

/** Adapts the persistent Shizuku UserService shell (uid 2000, not root). */
class ShizukuShellBackend(
  private val executor: ShizukuShellExecutor,
) : ShellExecutionBackend {
  override suspend fun execute(request: ShellExecutionRequest): ShellExecutionResult {
    require(request.backend == ShellExecutionBackendId.SHIZUKU)
    val result = executor.execute(
      command = request.command,
      workingDirectory = request.workingDirectory,
      timeoutMs = request.timeoutMs,
      maxOutputChars = request.maxOutputChars,
    )
    return ShellExecutionResult(
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
 * Executes through Termux's rish client, which connects to the existing Shizuku service. It does
 * not start or bind a second Shizuku service. The rish binary must already be installed in Termux.
 */
class TermuxRishShellBackend(
  private val executor: TermuxShellExecutor,
) : ShellExecutionBackend {
  override suspend fun execute(request: ShellExecutionRequest): ShellExecutionResult {
    require(request.backend == ShellExecutionBackendId.TERMUX_RISH)
    val remoteCommand =
      "cd -- ${quote(request.workingDirectory)} && ${request.command}"
    val result = executor.execute(
      command = "rish -c ${quote(remoteCommand)}",
      workingDirectory = "~/",
      timeoutMs = request.timeoutMs,
      maxOutputChars = request.maxOutputChars,
    )
    return ShellExecutionResult(
      stdout = result.stdout,
      stderr = result.stderr,
      exitCode = result.exitCode,
      timedOut = result.timedOut,
      outputTruncated = result.outputTruncated,
      error = result.error,
      processMayStillBeRunning = result.processMayStillBeRunning,
    )
  }

  private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

/** Bridges the existing Agent Chat permission dialog into the shared backend-independent policy. */
class ActionChannelShellApprovalPolicy(
  private val actionChannel: SendChannel<ToolAction>,
) : ShellExecutionApprovalPolicy {
  override suspend fun approve(request: ShellExecutionRequest): Boolean {
    val action = AskShellCommandPermissionAction(
      backend = request.backend.wireName,
      command = request.command,
      workingDirectory = request.workingDirectory,
      timeoutMs = request.timeoutMs,
      maxOutputChars = request.maxOutputChars,
    )
    actionChannel.send(action)
    return action.result.await()
  }
}
