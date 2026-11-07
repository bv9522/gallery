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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxShellResultTest {
  @Test
  fun successfulResultRequiresZeroExitAndNoTermuxError() {
    assertTrue(TermuxShellResult("", "", exitCode = 0).succeeded)
    assertFalse(TermuxShellResult("", "", exitCode = 1).succeeded)
    assertFalse(TermuxShellResult("", "", exitCode = 0, errorCode = 1).succeeded)
    assertFalse(TermuxShellResult("", "", exitCode = 0, error = "Termux failed").succeeded)
  }

  @Test
  fun timeoutIsNotReportedAsSuccess() {
    val result = TermuxShellResult(
      stdout = "",
      stderr = "",
      exitCode = -1,
      timedOut = true,
      processMayStillBeRunning = true,
    )

    assertFalse(result.succeeded)
    assertTrue(result.processMayStillBeRunning)
  }

  @Test
  fun unconfirmedProcessTerminationIsNeverReportedAsSuccess() {
    val result = TermuxShellResult(
      stdout = "partial",
      stderr = "",
      exitCode = 0,
      processMayStillBeRunning = true,
    )

    assertFalse(result.succeeded)
  }

  @Test
  fun outputTruncationDoesNotChangeExitStatus() {
    val result = TermuxShellResult(
      stdout = "partial output",
      stderr = "",
      exitCode = 0,
      outputTruncated = true,
    )

    assertTrue(result.succeeded)
    assertTrue(result.outputTruncated)
  }
}
