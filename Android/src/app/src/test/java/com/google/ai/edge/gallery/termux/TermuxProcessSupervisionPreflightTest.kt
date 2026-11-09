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

import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxProcessSupervisionPreflightTest {
  @Test
  fun commandChecksEveryRequiredUtilityAndReturnsFailureWhenAnyIsMissing() {
    val command = TermuxProcessSupervisionPreflight.command()

    TermuxProcessSupervisionPreflight.requiredUtilities.forEach {
      assertTrue("Missing check for $it", command.contains(it))
    }
    assertTrue(command.contains("exit 127"))
  }

  @Test
  fun missingUtilitiesAreReportedPrecisely() {
    val message = TermuxProcessSupervisionPreflight.failureMessage(
      TermuxShellResult(
        stdout = "setsid wc",
        stderr = "",
        exitCode = 127,
      )
    )

    assertTrue(message.contains("setsid, wc"))
    assertTrue(message.contains("util-linux coreutils"))
  }

  @Test
  fun transportFailureIsNotMisreportedAsMissingDependencies() {
    val message = TermuxProcessSupervisionPreflight.failureMessage(
      TermuxShellResult(
        stdout = "",
        stderr = "RUN_COMMAND permission denied",
        exitCode = -1,
        error = "Termux rejected the request.",
      )
    )

    assertTrue(message.contains("preflight failed"))
    assertTrue(message.contains("RUN_COMMAND permission denied"))
    assertTrue(!message.contains("missing required process-supervision utilities"))
  }
}
