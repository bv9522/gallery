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

/** Converts Android background-service start restrictions into an actionable transport error. */
internal object TermuxRunCommandStartFailure {
  private const val BACKGROUND_START_REJECTION = "Not allowed to start service"

  fun describe(platformMessage: String?): String? {
    if (platformMessage.isNullOrBlank()) return null
    if (!platformMessage.contains(BACKGROUND_START_REJECTION, ignoreCase = true) &&
      !platformMessage.contains("ForegroundServiceStartNotAllowedException", ignoreCase = true)
    ) {
      return null
    }

    return "Android blocked Termux's foreground RUN_COMMAND service from starting. " +
      "Bring Android MCP to the foreground and retry. No command was executed."
  }
}
