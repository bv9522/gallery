/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.agent.capability

import com.google.ai.edge.gallery.termux.TermuxShellExecutor
import com.google.ai.edge.gallery.termux.TermuxShellResult
import com.google.ai.edge.gallery.tools.AskNativeCapabilityPermissionAction
import com.google.ai.edge.gallery.tools.ToolAction
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxApiCapabilityToolTest {
  @Test
  fun sensorListDispatchesFixedCommandWithoutRequestingApproval() = runBlocking {
    val actions = Channel<ToolAction>(Channel.UNLIMITED)
    val executor = FakeExecutor { TermuxShellResult("Accelerometer\n", "", 0) }
    val tool = tool(executor, actions)

    val result = tool.execute(arguments("sensor_list"))

    assertTrue(result.isSuccessful)
    assertEquals(listOf("termux-sensor -l"), executor.calls.map { it.command })
    assertFalse(actions.tryReceive().isSuccess)
  }

  @Test
  fun sensorReadUsesLatestListingAndFiniteBoundedCommand() = runBlocking {
    val actions = Channel<ToolAction>(Channel.UNLIMITED)
    val executor = FakeExecutor { call ->
      if (call.command == "termux-sensor -l") {
        TermuxShellResult("Accelerometer\nGyroscope\n", "", 0)
      } else {
        TermuxShellResult("{\"samples\":2}", "", 0)
      }
    }
    val tool = tool(executor, actions)

    val result = tool.execute(arguments("sensor_read").apply {
      add("sensors", com.google.gson.JsonArray().apply { add("Accelerometer") })
      addProperty("count", 2)
      addProperty("delay_ms", 100)
    })

    assertTrue(result.isSuccessful)
    assertEquals(
      listOf(
        "termux-sensor -l",
        "termux-sensor -s 'Accelerometer' -d 100 -n 2",
      ),
      executor.calls.map { it.command },
    )
    assertEquals(10_200L, executor.calls.last().timeoutMs)
    assertEquals(8_192, executor.calls.last().maxOutputChars)
    assertFalse(actions.tryReceive().isSuccess)
  }

  @Test
  fun malformedAndOperationUnrelatedArgumentsAreRejectedBeforeDispatch() = runBlocking {
    val actions = Channel<ToolAction>(Channel.UNLIMITED)
    val executor = FakeExecutor()
    val tool = tool(executor, actions)

    val invalidCountType = tool.execute(arguments("sensor_read").apply {
      add("sensors", com.google.gson.JsonArray().apply { add("Accelerometer") })
      addProperty("count", "2")
    })
    val unrelatedLocationArgument = tool.execute(arguments("location").apply {
      addProperty("count", 2)
    })
    val invalidProviderType = tool.execute(arguments("location").apply {
      addProperty("provider", 7)
    })

    assertFalse(invalidCountType.isSuccessful)
    assertFalse(unrelatedLocationArgument.isSuccessful)
    assertFalse(invalidProviderType.isSuccessful)
    assertTrue(executor.calls.isEmpty())
    assertFalse(actions.tryReceive().isSuccess)
  }

  @Test
  fun locationRequiresScopedApprovalAndUsesFixedCommand() = runBlocking {
    val actions = Channel<ToolAction>(Channel.UNLIMITED)
    val executor = FakeExecutor { TermuxShellResult("{\"latitude\":1.0}", "", 0) }
    val tool = tool(executor, actions)
    val responder = launch {
      val action = actions.receive() as AskNativeCapabilityPermissionAction
      assertEquals(AndroidCapability.LOCATION_READ.name, action.capability)
      assertTrue(action.summary.contains("location"))
      action.result.complete(true)
    }

    val result = tool.execute(arguments("location").apply {
      addProperty("provider", "network")
      addProperty("request", "last")
    })
    responder.join()

    assertTrue(result.isSuccessful)
    assertEquals(listOf("termux-location -p network -r last"), executor.calls.map { it.command })
    assertEquals(45_000L, executor.calls.single().timeoutMs)
  }

  @Test
  fun locationDenialPreventsCommandDispatch() = runBlocking {
    val actions = Channel<ToolAction>(Channel.UNLIMITED)
    val executor = FakeExecutor()
    val tool = tool(executor, actions)
    val responder = launch {
      val action = actions.receive() as AskNativeCapabilityPermissionAction
      assertEquals(AndroidCapability.LOCATION_READ.name, action.capability)
      action.result.complete(false)
    }

    val result = tool.execute(arguments("location"))
    responder.join()

    assertFalse(result.isSuccessful)
    assertTrue(result.content.contains("denied"))
    assertTrue(executor.calls.isEmpty())
  }

  @Test
  fun wifiDenialPreventsCommandDispatch() = runBlocking {
    val actions = Channel<ToolAction>(Channel.UNLIMITED)
    val executor = FakeExecutor()
    val tool = tool(executor, actions)
    val responder = launch {
      val action = actions.receive() as AskNativeCapabilityPermissionAction
      assertEquals(AndroidCapability.WIFI_CONNECTION_INFO.name, action.capability)
      action.result.complete(false)
    }

    val result = tool.execute(arguments("wifi_connection_info"))
    responder.join()

    assertFalse(result.isSuccessful)
    assertTrue(executor.calls.isEmpty())
  }

  @Test
  fun infraredTransmitChecksCurrentFrequencyAndRequiresApproval() = runBlocking {
    val actions = Channel<ToolAction>(Channel.UNLIMITED)
    val executor = FakeExecutor { call ->
      if (call.command == "termux-infrared-frequencies") {
        TermuxShellResult("""{"frequencies":[38000]}""", "", 0)
      } else TermuxShellResult("", "", 0)
    }
    val tool = tool(executor, actions)
    val responder = launch {
      val action = actions.receive() as AskNativeCapabilityPermissionAction
      assertEquals(AndroidCapability.INFRARED_CONTROL.name, action.capability)
      action.result.complete(true)
    }
    val result = tool.execute(arguments("infrared_transmit").apply {
      addProperty("frequency", 38_000)
      add("pattern", com.google.gson.JsonArray().apply { listOf(500, 500).forEach { add(it) } })
    })
    responder.join()
    assertTrue(result.isSuccessful)
    assertEquals(
      listOf("termux-infrared-frequencies", "termux-infrared-transmit -f 38000 '500,500'"),
      executor.calls.map { it.command },
    )
  }

  @Test
  fun infraredDenialAndUnsupportedFrequencyPreventTransmission() = runBlocking {
    val deniedActions = Channel<ToolAction>(Channel.UNLIMITED)
    val deniedExecutor = FakeExecutor { TermuxShellResult("[38000]", "", 0) }
    val deniedTool = tool(deniedExecutor, deniedActions)
    val responder = launch {
      (deniedActions.receive() as AskNativeCapabilityPermissionAction).result.complete(false)
    }
    val denied = deniedTool.execute(arguments("infrared_transmit").apply {
      addProperty("frequency", 38_000)
      add("pattern", com.google.gson.JsonArray().apply { add(500); add(500) })
    })
    responder.join()
    assertFalse(denied.isSuccessful)
    assertEquals(listOf("termux-infrared-frequencies"), deniedExecutor.calls.map { it.command })

    val unsupportedExecutor = FakeExecutor { TermuxShellResult("[30000]", "", 0) }
    val unsupported = tool(unsupportedExecutor, Channel<ToolAction>(Channel.UNLIMITED))
      .execute(arguments("infrared_transmit").apply {
        addProperty("frequency", 38_000)
        add("pattern", com.google.gson.JsonArray().apply { add(500); add(500) })
      })
    assertFalse(unsupported.isSuccessful)
    assertEquals(listOf("termux-infrared-frequencies"), unsupportedExecutor.calls.map { it.command })
  }

  @Test
  fun ttsSpeakRequiresApprovalAndUsesBoundedQuotedText() = runBlocking {
    val actions = Channel<ToolAction>(Channel.UNLIMITED)
    val executor = FakeExecutor { TermuxShellResult("", "", 0) }
    val tool = tool(executor, actions)
    val responder = launch {
      val action = actions.receive() as AskNativeCapabilityPermissionAction
      assertEquals(AndroidCapability.TEXT_TO_SPEECH.name, action.capability)
      action.result.complete(true)
    }
    val result = tool.execute(arguments("tts_speak").apply { addProperty("text", "Hello Coco's world") })
    responder.join()
    assertTrue(result.isSuccessful)
    assertEquals("termux-tts-speak 'Hello Coco'\\''s world'", executor.calls.single().command)
  }

  @Test
  fun ttsDenialPreventsSpeechCommand() = runBlocking {
    val actions = Channel<ToolAction>(Channel.UNLIMITED)
    val executor = FakeExecutor()
    val tool = tool(executor, actions)
    val responder = launch {
      (actions.receive() as AskNativeCapabilityPermissionAction).result.complete(false)
    }
    val result = tool.execute(arguments("tts_speak").apply { addProperty("text", "hello") })
    responder.join()
    assertFalse(result.isSuccessful)
    assertTrue(executor.calls.isEmpty())
  }

  @Test
  fun malformedNewOperationArgumentsAreRejectedBeforeDispatch() = runBlocking {
    val actions = Channel<ToolAction>(Channel.UNLIMITED)
    val executor = FakeExecutor()
    val tool = tool(executor, actions)
    val badPattern = tool.execute(arguments("infrared_transmit").apply {
      addProperty("frequency", 38_000)
      add("pattern", com.google.gson.JsonArray().apply { add("500"); add(500) })
    })
    val extraText = tool.execute(arguments("tts_speak").apply {
      addProperty("text", "hello")
      addProperty("frequency", 38_000)
    })
    assertFalse(badPattern.isSuccessful)
    assertFalse(extraText.isSuccessful)
    assertTrue(executor.calls.isEmpty())
    assertFalse(actions.tryReceive().isSuccess)
  }

  @Test
  fun closedApprovalChannelFailsClosed() = runBlocking {
    val actions = Channel<ToolAction>(Channel.UNLIMITED).apply { close() }
    val executor = FakeExecutor()
    val tool = tool(executor, actions)

    val result = tool.execute(arguments("location"))

    assertFalse(result.isSuccessful)
    assertTrue(executor.calls.isEmpty())
  }

  @Test
  fun truncatedTimeoutAndUnconfirmedCleanupResultsFailClosed() = runBlocking {
    val truncated = tool(
      FakeExecutor { TermuxShellResult("partial", "", 0, outputTruncated = true) },
      Channel<ToolAction>(Channel.UNLIMITED),
    ).execute(arguments("sensor_list"))
    val timedOut = tool(
      FakeExecutor {
        TermuxShellResult("", "", -1, timedOut = true, error = "deadline exceeded")
      },
      Channel<ToolAction>(Channel.UNLIMITED),
    ).execute(arguments("sensor_list"))
    val cleanupUnconfirmed = tool(
      FakeExecutor {
        TermuxShellResult("", "", 0, processMayStillBeRunning = true, error = "cleanup failed")
      },
      Channel<ToolAction>(Channel.UNLIMITED),
    ).execute(arguments("sensor_list"))

    assertFalse(truncated.isSuccessful)
    assertTrue(truncated.content.contains("OUTPUT_TRUNCATED"))
    assertFalse(timedOut.isSuccessful)
    assertTrue(timedOut.content.contains("TIMEOUT"))
    assertFalse(cleanupUnconfirmed.isSuccessful)
    assertTrue(cleanupUnconfirmed.content.contains("PROCESS_CLEANUP_UNCONFIRMED"))
  }

  @Test
  fun cancellationPropagatesAndExecutorCleanupFinallyRuns() = runBlocking {
    val actions = Channel<ToolAction>(Channel.UNLIMITED)
    val started = CompletableDeferred<Unit>()
    var cleanupCompleted = false
    val executor = object : TermuxShellExecutor {
      override suspend fun execute(
        command: String,
        workingDirectory: String,
        timeoutMs: Long,
        maxOutputChars: Int,
      ): TermuxShellResult {
        started.complete(Unit)
        try {
          awaitCancellation()
        } finally {
          withContext(NonCancellable) {
            cleanupCompleted = true
          }
        }
      }
    }
    val tool = tool(executor, actions)
    val job = launch { tool.execute(arguments("sensor_list")) }

    started.await()
    job.cancelAndJoin()

    assertTrue(cleanupCompleted)
  }

  @Test
  fun statusReportsCompanionAndCommandProbeSeparatelyWithoutClaimingPermission() = runBlocking {
    val actions = Channel<ToolAction>(Channel.UNLIMITED)
    val executor = FakeExecutor {
      TermuxShellResult("termux-sensor=AVAILABLE\n", "", 0)
    }
    val tool = tool(executor, actions, companionInstalled = false)

    val result = tool.execute(arguments("status"))

    assertFalse(result.isSuccessful)
    assertTrue(result.content.contains("companionAppInstalled=false"))
    assertTrue(result.content.contains("commandProbeSucceeded=true"))
    assertTrue(result.content.contains("permissionState=UNKNOWN"))
    assertTrue(executor.calls.single().command.contains("command -v"))
  }

  private fun tool(
    executor: TermuxShellExecutor,
    actions: Channel<ToolAction>,
    companionInstalled: Boolean = true,
  ) = TermuxApiCapabilityTool(
    actionChannel = actions,
    client = executor,
    companionInstalledProbe = { companionInstalled },
  )

  private fun arguments(operation: String) = JsonObject().apply {
    addProperty("operation", operation)
  }

  private data class CommandCall(
    val command: String,
    val workingDirectory: String,
    val timeoutMs: Long,
    val maxOutputChars: Int,
  )

  private class FakeExecutor(
    private val respond: suspend (CommandCall) -> TermuxShellResult = {
      TermuxShellResult("ok", "", 0)
    },
  ) : TermuxShellExecutor {
    val calls = mutableListOf<CommandCall>()

    override suspend fun execute(
      command: String,
      workingDirectory: String,
      timeoutMs: Long,
      maxOutputChars: Int,
    ): TermuxShellResult {
      val call = CommandCall(command, workingDirectory, timeoutMs, maxOutputChars)
      calls += call
      return respond(call)
    }
  }
}
