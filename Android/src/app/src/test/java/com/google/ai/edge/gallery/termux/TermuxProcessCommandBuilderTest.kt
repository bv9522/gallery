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

package com.google.ai.edge.gallery.termux

import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxProcessCommandBuilderTest {
  @Test
  fun launchUsesIsolatedProcessGroupAndQuotesUntrustedValues() {
    val command = "printf '%s' \"hello world\"; echo done"
    val pidFile = ".cache/android-mcp-42's.pid"

    val script = TermuxProcessCommandBuilder.buildLaunchCommand(command, pidFile)

    assertTrue(script.contains("command -v setsid"))
    assertTrue(script.contains("setsid bash -lc"))
    assertTrue(script.contains("printf '%s' \"hello world\"; echo done"))
    assertTrue(script.contains("'\$HOME/.cache/android-mcp-42'\\''s.pid'"))
    assertTrue(script.contains("wait \"\$child_pid\""))
    assertTrue(script.contains("rm -f \"\$pid_file\""))
  }

  @Test
  fun cancellationEscalatesFromTermToKillAndVerifiesProcessGroup() {
    val script = TermuxProcessCommandBuilder.buildTerminateCommand(
      ".cache/android-mcp-9.pid"
    )

    assertTrue(script.contains("kill -TERM -- \"-\$pid\""))
    assertTrue(script.contains("kill -KILL -- \"-\$pid\""))
    assertTrue(script.contains("kill -0 -- \"-\$pid\""))
    assertTrue(script.contains("[ -s \"\$pid_file\" ]"))
  }

  @Test(expected = IllegalArgumentException::class)
  fun launchRejectsNulInCommand() {
    TermuxProcessCommandBuilder.buildLaunchCommand("id\u0000; whoami", "/tmp/job.pid")
  }

  @Test(expected = IllegalArgumentException::class)
  fun cleanupRejectsNulInPidFile() {
    TermuxProcessCommandBuilder.buildTerminateCommand("/tmp/job\u0000.pid")
  }
}
