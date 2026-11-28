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

import com.google.ai.edge.gallery.termux.TermuxShellExecutor
import com.google.ai.edge.gallery.termux.TermuxShellResult
import com.google.ai.edge.gallery.shizuku.ShizukuShellResult
import com.google.ai.edge.gallery.shizuku.ShizukuShellExecutor
import com.google.ai.edge.gallery.tools.AskShellCommandPermissionAction
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedShellExecutionTest {

  @Test
  fun readOnlyPrintfRunsWithoutRequestingApproval() = runBlocking {
    var approvals = 0
    var executions = 0
    val router = ShellExecutionRouter(
      mapOf(ShellExecutionBackend.TERMUX to ShellExecutionTransport { request ->
        executions++
        ShellExecutionResult(request.backend, "555", "", 0)
      })
    )
    val tool = UnifiedShellTool(router, ShellExecutionApprovalPolicy {
      approvals++
      false
    })

    val result = tool.execute(arguments("TERMUX", "printf '555'"))

    assertTrue(result.isSuccessful)
    assertTrue(result.content.contains("555"))
    assertEquals(0, approvals)
    assertEquals(1, executions)
  }

  @Test
  fun mutatingAndUnclassifiedCommandsStillRequireApproval() {
    listOf(
      "touch /tmp/file",
      "rm -f /tmp/file",
      "settings put system screen_brightness 100",
      "printf 'hello' > /tmp/file",
      "printf 'hello' | wc -c",
      "echo " + '$' + "HOME",
      "date --set='2026-01-01'",
      "sort -o /tmp/output",
      "dumpsys battery set level 5",
    ).forEach { command ->
      assertTrue("Expected approval for: $command", ShellCommandApprovalClassifier.requiresApproval(command))
    }
  }

  @Test
  fun commonReadOnlyCommandsDoNotRequireApproval() {
    listOf(
      "printf '555'",
      "printf 'Hello Cocochat'",
      "id",
      "pwd",
      "getprop ro.product.model",
      "ls -la",
      "cat /proc/version",
    ).forEach { command ->
      assertFalse("Unexpected approval for: $command", ShellCommandApprovalClassifier.requiresApproval(command))
    }
  }

  @Test
  fun deniedRequestNeverReachesAnyBackend() = runBlocking {
    val calls = mutableListOf<ShellExecutionRequest>()
    val router = ShellExecutionRouter(
      ShellExecutionBackend.entries.associateWith { backend ->
        ShellExecutionTransport { request ->
          calls += request
          success(request.backend)
        }
      }
    )
    var approvedRequest: ShellExecutionRequest? = null
    val tool = UnifiedShellTool(router, ShellExecutionApprovalPolicy {
      approvedRequest = it
      false
    })

    val result = tool.execute(arguments("TERMUX", "touch /tmp/denied"))

    assertFalse(result.isSuccessful)
    assertEquals(AgentToolOutcome.DENIED, result.outcome)
    assertTrue("Unexpected tool result: ${result.content}", approvedRequest != null)
    assertEquals(ShellExecutionBackend.TERMUX, approvedRequest?.backend)
    assertTrue(calls.isEmpty())
  }

  @Test
  fun approvedRequestRoutesOnlyToTheSelectedBackend() = runBlocking {
    val calls = mutableListOf<ShellExecutionBackend>()
    val router = ShellExecutionRouter(
      ShellExecutionBackend.entries.associateWith { backend ->
        ShellExecutionTransport { request ->
          calls += backend
          success(request.backend)
        }
      }
    )
    var approvedRequest: ShellExecutionRequest? = null
    val tool = UnifiedShellTool(router, ShellExecutionApprovalPolicy {
      approvedRequest = it
      true
    })

    val result = tool.execute(
      arguments("SHIZUKU", "touch /tmp/approved").apply {
        addProperty("working_directory", "/")
        addProperty("timeout_ms", 5_000)
        addProperty("max_output_chars", 4_096)
      }
    )

    assertTrue(result.isSuccessful)
    assertEquals(listOf(ShellExecutionBackend.SHIZUKU), calls)
    assertEquals(approvedRequest, ShellExecutionRequest(
      backend = ShellExecutionBackend.SHIZUKU,
      command = "touch /tmp/approved",
      workingDirectory = "/",
      timeoutMs = 5_000,
      maxOutputChars = 4_096,
    ))
  }

  @Test
  fun shellToolReportsBackendTruncationExplicitly() = runBlocking {
    val router = ShellExecutionRouter(
      mapOf(ShellExecutionBackend.TERMUX to ShellExecutionTransport { request ->
        ShellExecutionResult(
          backend = request.backend,
          stdout = "first line\nlast retained line",
          stderr = "",
          exitCode = 0,
          outputTruncated = true,
        )
      })
    )
    val tool = UnifiedShellTool(router, ShellExecutionApprovalPolicy { true })

    val result = tool.execute(arguments("TERMUX", "generate-output"))

    assertTrue(result.isSuccessful)
    assertTrue(result.content.contains("Backend output truncated: true"))
    assertTrue(result.content.contains("Warning: output was truncated."))
  }

  @Test
  fun shellResultRetainsExactStreamsAndTypedOutcome() = runBlocking {
    val stdout = "BEGIN|VALUE|END\n\n  "
    val stderr = "warning\r\n  "
    val router = ShellExecutionRouter(
      mapOf(ShellExecutionBackend.TERMUX to ShellExecutionTransport { request ->
        ShellExecutionResult(
          backend = request.backend,
          stdout = stdout,
          stderr = stderr,
          exitCode = 7,
        )
      })
    )
    val tool = UnifiedShellTool(router, ShellExecutionApprovalPolicy { true })

    val result = tool.execute(arguments("TERMUX", "id"))

    assertFalse(result.isSuccessful)
    assertEquals(AgentToolOutcome.FAILED, result.outcome)
    assertEquals(stdout, result.shellDetails?.stdout)
    assertEquals(stderr, result.shellDetails?.stderr)
    assertTrue(result.content.contains(stdout))
    assertTrue(result.content.contains(stderr))
    assertEquals(7, result.shellDetails?.exitCode)
  }

  @Test
  fun missingBackendIsPresentedAsUnavailableWithoutFallback() = runBlocking {
    val router = ShellExecutionRouter(
      mapOf(ShellExecutionBackend.TERMUX to ShellExecutionTransport { success(it.backend) })
    )
    val tool = UnifiedShellTool(router, ShellExecutionApprovalPolicy { true })

    val result = tool.execute(arguments("SHIZUKU", "id"))

    assertFalse(result.isSuccessful)
    assertEquals(AgentToolOutcome.UNAVAILABLE, result.outcome)
    assertTrue(result.shellDetails?.unavailable == true)
  }

  @Test
  fun everyBackendUsesTheSameApprovalBoundaryAndExactIdentity() = runBlocking {
    val executions = mutableListOf<ShellExecutionBackend>()
    val approvals = mutableListOf<ShellExecutionRequest>()
    val router = ShellExecutionRouter(
      ShellExecutionBackend.entries.associateWith { backend ->
        ShellExecutionTransport { request ->
          executions += backend
          success(request.backend)
        }
      }
    )
    val tool = UnifiedShellTool(router, ShellExecutionApprovalPolicy {
      approvals += it
      true
    })

    ShellExecutionBackend.entries.forEach { backend ->
      val workingDirectory = if (backend == ShellExecutionBackend.TERMUX) "~/" else "/"
      val result = tool.execute(arguments(backend.name, "touch /tmp/approved").apply {
        addProperty("working_directory", workingDirectory)
      })
      assertTrue("Backend $backend should execute after approval. Result: ${result.content}", result.isSuccessful)
    }

    assertEquals(ShellExecutionBackend.entries, approvals.map { it.backend })
    assertEquals(ShellExecutionBackend.entries, executions)
    assertEquals(listOf("~/", "/", "/"), approvals.map { it.workingDirectory })
  }

  @Test
  fun unconfirmedProcessTerminationIsNeverReportedAsSuccess() = runBlocking {
    val router = ShellExecutionRouter(
      mapOf(ShellExecutionBackend.TERMUX to ShellExecutionTransport {
        ShellExecutionResult(
          backend = it.backend,
          stdout = "",
          stderr = "",
          exitCode = 0,
          processMayStillBeRunning = true,
        )
      })
    )
    val result = router.execute(ShellExecutionRequest(ShellExecutionBackend.TERMUX, "sleep 30", "~/"))

    assertFalse(result.succeeded)
    assertTrue(result.processMayStillBeRunning)
  }

  @Test
  fun shizukuCleanupUncertaintyPropagatesThroughSharedResult() = runBlocking {
    val executor = object : ShizukuShellExecutor {
      override suspend fun execute(
        command: String,
        workingDirectory: String,
        timeoutMs: Long,
        maxOutputChars: Int,
      ) = ShizukuShellResult(
        stdout = "",
        stderr = "",
        exitCode = -1,
        timedOut = true,
        outputTruncated = false,
        error = "Could not confirm process-group teardown.",
        processMayStillBeRunning = true,
      )
    }
    val router = ShellExecutionRouter(
      mapOf(ShellExecutionBackend.SHIZUKU to ShizukuShellTransport(executor))
    )

    val result = router.execute(
      ShellExecutionRequest(ShellExecutionBackend.SHIZUKU, "sleep 30", "/")
    )

    assertFalse(result.succeeded)
    assertTrue(result.timedOut)
    assertTrue(result.processMayStillBeRunning)
  }

  @Test
  fun transportCancellationPropagatesWithoutTryingAnotherBackend() = runBlocking {
    val calls = mutableListOf<ShellExecutionBackend>()
    val router = ShellExecutionRouter(
      ShellExecutionBackend.entries.associateWith { backend ->
        ShellExecutionTransport {
          calls += backend
          if (backend == ShellExecutionBackend.SHIZUKU) {
            throw CancellationException("cancelled")
          }
          success(backend)
        }
      }
    )

    try {
      router.execute(ShellExecutionRequest(ShellExecutionBackend.SHIZUKU, "sleep 30", "/"))
      throw AssertionError("Expected cancellation.")
    } catch (_: CancellationException) {
      assertEquals(listOf(ShellExecutionBackend.SHIZUKU), calls)
    }
  }

  @Test
  fun missingBackendDoesNotFallBackToAnotherIdentity() = runBlocking {
    val calls = mutableListOf<ShellExecutionBackend>()
    val router = ShellExecutionRouter(
      mapOf(ShellExecutionBackend.TERMUX to ShellExecutionTransport { request ->
        calls += request.backend
        success(request.backend)
      })
    )

    val result = router.execute(
      ShellExecutionRequest(ShellExecutionBackend.SHIZUKU, "id", "/")
    )

    assertFalse(result.succeeded)
    assertTrue(result.error.orEmpty().contains("not configured"))
    assertTrue(calls.isEmpty())
  }

  @Test
  fun malformedArgumentsAreRejectedBeforeApproval() = runBlocking {
    var approvals = 0
    val router = ShellExecutionRouter(
      mapOf(ShellExecutionBackend.TERMUX to ShellExecutionTransport { success(it.backend) })
    )
    val tool = UnifiedShellTool(router, ShellExecutionApprovalPolicy {
      approvals++
      true
    })

    val badBackend = tool.execute(arguments("root", "id"))
    val fractionalTimeout = tool.execute(arguments("TERMUX", "id").apply {
      addProperty("timeout_ms", 100.5)
    })
    val excessiveOutput = tool.execute(arguments("TERMUX", "id").apply {
      addProperty("max_output_chars", 999_999)
    })
    val unknownArgument = tool.execute(arguments("TERMUX", "id").apply {
      addProperty("force", true)
    })

    assertFalse(badBackend.isSuccessful)
    assertFalse(fractionalTimeout.isSuccessful)
    assertFalse(excessiveOutput.isSuccessful)
    assertFalse(unknownArgument.isSuccessful)
    assertEquals(0, approvals)
  }

  @Test
  fun rishTransportQuotesCommandAndUsesExistingRishClient() = runBlocking {
    val termux = RecordingTermux()
    val transport = TermuxRishShellTransport(termux) { "org.example.shizuku.manager" }

    val result = transport.execute(
      ShellExecutionRequest(
        backend = ShellExecutionBackend.TERMUX_RISH,
        command = "printf '%s' \"hello world\"",
        workingDirectory = "/sdcard/My Files",
      )
    )

    assertTrue(result.succeeded)
    assertEquals("~/", termux.workingDirectory)
    assertTrue(termux.command.startsWith(
      "export RISH_APPLICATION_ID='com.termux' && " +
        "export MANAGER_APPLICATION_ID='org.example.shizuku.manager' && rish -c "
    ))
    assertTrue(termux.command.contains("cd "))
    assertTrue(termux.command.contains("My Files"))
    assertTrue(termux.command.contains("printf"))
    assertTrue(termux.command.contains("hello world"))
  }

  @Test
  fun rishManagerResolutionFailureDoesNotInvokeTermux() = runBlocking {
    val termux = RecordingTermux()
    val transport = TermuxRishShellTransport(termux) {
      throw IllegalStateException("Multiple Shizuku managers were discovered.")
    }

    val result = transport.execute(
      ShellExecutionRequest(
        backend = ShellExecutionBackend.TERMUX_RISH,
        command = "id",
        workingDirectory = "/",
      )
    )

    assertFalse(result.succeeded)
    assertTrue(result.error.orEmpty().contains("Multiple Shizuku managers"))
    assertEquals("", termux.command)
  }

  @Test
  fun channelApprovalShowsExactRequestAndWaitsForOneTimeDecision(): Unit = runBlocking {
    val channel = Channel<com.google.ai.edge.gallery.tools.ToolAction>(capacity = 1)
    val policy = ChannelShellExecutionApprovalPolicy(channel)
    val request = ShellExecutionRequest(
      backend = ShellExecutionBackend.TERMUX_RISH,
      command = "touch /tmp/approved",
      workingDirectory = "/sdcard",
      timeoutMs = 4_000,
      maxOutputChars = 2_048,
    )
    val pending = async { policy.requestApproval(request) }
    val action = channel.receive() as AskShellCommandPermissionAction

    assertEquals("TERMUX_RISH", action.backend)
    assertEquals("touch /tmp/approved", action.command)
    assertEquals("/sdcard", action.workingDirectory)
    assertEquals(4_000L, action.timeoutMs)
    assertEquals(2_048, action.maxOutputChars)
    assertTrue(action.result.complete(true))
    assertTrue(pending.await())
    channel.close()
  }

  @Test
  fun missingApprovalResponseFailsClosed(): Unit = runBlocking {
    val channel = Channel<com.google.ai.edge.gallery.tools.ToolAction>(capacity = 1)
    val policy = ChannelShellExecutionApprovalPolicy(channel, approvalTimeoutMs = 10)
    val approved = policy.requestApproval(
      ShellExecutionRequest(ShellExecutionBackend.TERMUX, "touch /tmp/pending", "~/")
    )

    assertFalse(approved)
    channel.close()
  }

  @Test
  fun approvalCancellationPropagatesAndDoesNotRoute() = runBlocking {
    var executions = 0
    val router = ShellExecutionRouter(
      mapOf(ShellExecutionBackend.TERMUX to ShellExecutionTransport {
        executions++
        success(it.backend)
      })
    )
    val tool = UnifiedShellTool(router, ShellExecutionApprovalPolicy {
      throw CancellationException("approval dismissed")
    })

    try {
      val result = tool.execute(arguments("TERMUX", "touch /tmp/cancelled"))
      throw AssertionError("Expected cancellation, but tool returned: ${result.content}")
    } catch (_: CancellationException) {
      assertEquals(0, executions)
    }
  }


  @Test
  fun channelApprovalCancellationPropagatesAndCompletesPendingRequestAsDenied() = runBlocking {
    val channel = Channel<com.google.ai.edge.gallery.tools.ToolAction>(capacity = 1)
    val policy = ChannelShellExecutionApprovalPolicy(channel, approvalTimeoutMs = 10_000)
    val request = ShellExecutionRequest(ShellExecutionBackend.TERMUX, "touch /tmp/cancelled", "~/")
    val pending = async { policy.requestApproval(request) }
    val action = channel.receive() as AskShellCommandPermissionAction

    pending.cancel()
    try {
      pending.await()
      throw AssertionError("Expected approval cancellation to propagate.")
    } catch (_: CancellationException) {
      assertTrue(action.result.isCompleted)
      assertFalse(action.result.await())
    }
    channel.close()
    Unit
  }

  @Test
  fun channelApprovalDismissalFailsClosed() = runBlocking {
    val channel = Channel<com.google.ai.edge.gallery.tools.ToolAction>(capacity = 1)
    val policy = ChannelShellExecutionApprovalPolicy(channel)
    val request = async {
      policy.requestApproval(
        ShellExecutionRequest(ShellExecutionBackend.TERMUX, "touch /tmp/denied", "~/")
      )
    }
    val action = channel.receive() as AskShellCommandPermissionAction
    action.result.complete(false)

    assertFalse(request.await())
    channel.close()
    Unit
  }


  @Test
  fun shellApprovalTimeoutAlsoBoundsActionChannelDelivery(): Unit = runBlocking {
    val channel = Channel<com.google.ai.edge.gallery.tools.ToolAction>(capacity = 0)
    val policy = ChannelShellExecutionApprovalPolicy(channel, approvalTimeoutMs = 10)
    val approved = policy.requestApproval(
      ShellExecutionRequest(ShellExecutionBackend.TERMUX, "touch /tmp/pending", "~/")
    )

    assertFalse(approved)
    channel.close()
  }

  private fun arguments(backend: String, command: String) =
    JsonObject().apply {
      addProperty("backend", backend)
      addProperty("command", command)
    }

  private fun success(backend: ShellExecutionBackend) = ShellExecutionResult(
    backend = backend,
    stdout = "uid=2000(shell)",
    stderr = "",
    exitCode = 0,
  )

  private class RecordingTermux : TermuxShellExecutor {
    var command = ""
    var workingDirectory = ""

    override suspend fun execute(
      command: String,
      workingDirectory: String,
      timeoutMs: Long,
      maxOutputChars: Int,
    ): TermuxShellResult {
      this.command = command
      this.workingDirectory = workingDirectory
      return TermuxShellResult("uid=2000(shell)", "", 0)
    }
  }
}
