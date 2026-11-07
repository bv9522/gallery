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
import com.google.ai.edge.gallery.shizuku.ShizukuShellResult
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuShellToolTest {

  @Test
  fun deniedCommandNeverReachesShellExecutor() = runBlocking {
    val shell = RecordingShell()
    var requested: ShellCommandApprovalRequest? = null
    val tool = ShizukuShellTool(shell, ShellCommandApproval {
      requested = it
      false
    })

    val result = tool.execute(commandArguments("id"))

    assertFalse(result.isSuccessful)
    assertTrue("Unexpected denial result: '${result.content}'", result.content.contains("user did not approve"))
    assertEquals("id", requested?.command)
    assertEquals(0, shell.commands.size)
  }

  @Test
  fun approvedCommandExecutesWithTheExactApprovedLimits() = runBlocking {
    val shell = RecordingShell()
    var requested: ShellCommandApprovalRequest? = null
    val tool = ShizukuShellTool(shell, ShellCommandApproval {
      requested = it
      true
    })

    val result = tool.execute(
      commandArguments("id").apply {
        addProperty("timeout_ms", 5_000)
        addProperty("max_output_chars", 4_096)
      }
    )

    assertTrue(result.isSuccessful)
    assertEquals("id", shell.commands.single())
    assertEquals(5_000L, requested?.timeoutMs)
    assertEquals(4_096, requested?.maxOutputChars)
    assertEquals(5_000L, shell.lastTimeoutMs)
    assertEquals(4_096, shell.lastMaxOutputChars)
    assertTrue(result.content.contains("uid=2000(shell)"))
  }

  @Test
  fun malformedOrOutOfRangeArgumentsAreRejectedBeforeApproval() = runBlocking {
    val shell = RecordingShell()
    var approvalRequests = 0
    val tool = ShizukuShellTool(shell, ShellCommandApproval {
      approvalRequests++
      true
    })

    val fractionalTimeout = tool.execute(
      commandArguments("id").apply { addProperty("timeout_ms", 1_000.5) }
    )
    val excessiveOutput = tool.execute(
      commandArguments("id").apply { addProperty("max_output_chars", 999_999) }
    )
    val unknownArgument = tool.execute(
      commandArguments("id").apply { addProperty("force", true) }
    )
    val oversizedCommand = tool.execute(
      commandArguments("x".repeat(ShizukuShellTool.MAX_COMMAND_LENGTH + 1))
    )

    assertFalse(fractionalTimeout.isSuccessful)
    assertFalse(excessiveOutput.isSuccessful)
    assertFalse(unknownArgument.isSuccessful)
    assertFalse(oversizedCommand.isSuccessful)
    assertEquals(0, approvalRequests)
    assertTrue(shell.commands.isEmpty())
  }

  @Test
  fun approvalCancellationPropagatesWithoutExecutingCommand() = runBlocking {
    val shell = RecordingShell()
    val tool = ShizukuShellTool(shell, ShellCommandApproval {
      throw CancellationException("approval screen dismissed")
    })

    try {
      val result = tool.execute(commandArguments("id"))
      throw AssertionError("Expected approval cancellation, but tool returned: $result")
    } catch (_: CancellationException) {
      assertTrue(shell.commands.isEmpty())
    }
  }

  private fun commandArguments(command: String) =
    JsonObject().apply { addProperty("command", command) }

  private class RecordingShell : ShizukuShellExecutor {
    val commands = mutableListOf<String>()
    var lastTimeoutMs: Long? = null
    var lastMaxOutputChars: Int? = null

    override suspend fun execute(
      command: String,
      workingDirectory: String,
      timeoutMs: Long,
      maxOutputChars: Int,
    ): ShizukuShellResult {
      commands += command
      lastTimeoutMs = timeoutMs
      lastMaxOutputChars = maxOutputChars
      return ShizukuShellResult(
        stdout = "uid=2000(shell) gid=2000(shell)",
        stderr = "",
        exitCode = 0,
        timedOut = false,
        outputTruncated = false,
      )
    }
  }
}
