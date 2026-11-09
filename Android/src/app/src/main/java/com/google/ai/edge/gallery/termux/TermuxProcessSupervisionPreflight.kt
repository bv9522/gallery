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

/** Builds and explains the Termux utility check without hiding which prerequisite is missing. */
internal object TermuxProcessSupervisionPreflight {
  val requiredUtilities = listOf("setsid", "timeout", "head", "wc")

  fun command(): String = buildString {
    append("missing='' ; ")
    append("for utility in setsid timeout head wc; do ")
    append("if ! command -v \"\$utility\" >/dev/null 2>&1; then ")
    append("if [ -n \"\$missing\" ]; then missing=\"\$missing \"; fi; ")
    append("missing=\"\$missing\$utility\"; fi; done; ")
    append("if [ -n \"\$missing\" ]; then printf '%s\\\\n' \"\$missing\"; exit 127; fi")
  }

  fun failureMessage(result: TermuxShellResult): String {
    val reportedMissing = result.stdout
      .lineSequence()
      .flatMap { it.split(Regex("\\s+")).asSequence() }
      .filter { it in requiredUtilities }
      .distinct()
      .toList()

    if (reportedMissing.isNotEmpty()) {
      return buildString {
        append("Termux is missing required process-supervision utilities: ")
        append(reportedMissing.joinToString(", "))
        append(". Install them with: pkg install -y util-linux coreutils.")
        result.stderr.trim().takeIf { it.isNotEmpty() }?.let {
          append("\nTermux stderr: ")
          append(it)
        }
      }
    }

    return buildString {
      append("Termux process-supervision preflight failed before dependency detection")
      append(" (exit code \${result.exitCode}")
      result.errorCode?.let { append(", Termux error code \$it") }
      append(").")
      result.error?.takeIf { it.isNotBlank() }?.let {
        append(" ")
        append(it)
      }
      result.stderr.trim().takeIf { it.isNotEmpty() }?.let {
        append(" STDERR: ")
        append(it)
      }
      if (result.stdout.isNotBlank()) {
        append(" STDOUT: ")
        append(result.stdout.trim())
      }
    }
  }
}
