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

/** Injectable boundary for commands executed inside the Termux application environment. */
interface TermuxShellExecutor {
  suspend fun execute(
    command: String,
    workingDirectory: String = TermuxRunCommandClient.DEFAULT_WORKING_DIRECTORY,
    timeoutMs: Long = TermuxRunCommandClient.DEFAULT_TIMEOUT_MS,
    maxOutputChars: Int = TermuxRunCommandClient.DEFAULT_OUTPUT_LIMIT,
  ): TermuxShellResult
}

/**
 * Structured result from a Termux RUN_COMMAND invocation.
 *
 * RUN_COMMAND is currently a one-shot process API. A client-side timeout stops waiting for the
 * result; it does not guarantee termination of the process already running inside Termux.
 */
data class TermuxShellResult(
  val stdout: String,
  val stderr: String,
  val exitCode: Int,
  val timedOut: Boolean = false,
  val outputTruncated: Boolean = false,
  val errorCode: Int? = null,
  val error: String? = null,
  val processMayStillBeRunning: Boolean = false,
) {
  val succeeded: Boolean
    get() = error == null && errorCode == null && !timedOut && exitCode == 0
}
