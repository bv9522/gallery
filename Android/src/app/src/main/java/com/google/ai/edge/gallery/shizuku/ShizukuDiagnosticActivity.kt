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

package com.google.ai.edge.gallery.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.ai.edge.gallery.agent.coordinator.AgentCoordinator
import com.google.ai.edge.gallery.agent.coordinator.AgentMessage
import com.google.ai.edge.gallery.agent.coordinator.AgentModel
import com.google.ai.edge.gallery.agent.coordinator.AgentModelResponse
import com.google.ai.edge.gallery.agent.coordinator.AgentToolRegistry
import com.google.ai.edge.gallery.agent.coordinator.IdentityDiagnosticShellTool
import com.google.ai.edge.gallery.agent.coordinator.ShellExecutionApprovalPolicy
import com.google.ai.edge.gallery.agent.coordinator.ShellExecutionBackend
import com.google.ai.edge.gallery.agent.coordinator.ShellExecutionRouter
import com.google.ai.edge.gallery.agent.coordinator.TermuxRishShellTransport
import com.google.ai.edge.gallery.agent.coordinator.UnifiedShellTool
import com.google.ai.edge.gallery.termux.TermuxRunCommandClient
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * On-device acceptance test for persistent Shizuku shell sessions.
 *
 * Verifies shell identity, state persistence (cwd and environment), separate stderr, and exit
 * status. This diagnostic surface is not the model-driven agent or its approval layer.
 */
class ShizukuDiagnosticActivity : ComponentActivity() {
  private lateinit var statusView: TextView
  private lateinit var runButton: Button
  private lateinit var agentRunButton: Button
  private lateinit var termuxRunButton: Button
  private lateinit var termuxRishRunButton: Button
  private var pendingAgentTest = false
  private var shellService: IShizukuShellService? = null
  private var sessionId: String? = null
  private var bound = false

  private val userServiceArgs by lazy {
    Shizuku.UserServiceArgs(
      ComponentName(packageName, ShizukuShellUserService::class.java.name)
    )
      .daemon(false)
      .processNameSuffix("shell")
  }

  private val serviceConnection =
    object : ServiceConnection {
      override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        shellService = IShizukuShellService.Stub.asInterface(binder)
        statusView.text = "Connected to Shizuku. Tap to test one persistent shell session."
        runButton.isEnabled = true
      }

      override fun onServiceDisconnected(name: ComponentName?) {
        shellService = null
        sessionId = null
        statusView.text = "Shizuku UserService disconnected."
        runButton.isEnabled = true
      }
    }

  private val permissionListener =
    Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
      if (requestCode != REQUEST_CODE) return@OnRequestPermissionResultListener
      if (grantResult == PackageManager.PERMISSION_GRANTED) {
        if (pendingAgentTest) {
          pendingAgentTest = false
          runAgentShellRoundTrip()
        } else {
          bindUserService()
        }
      } else {
        pendingAgentTest = false
        statusView.text = "Shizuku permission was denied. Grant it in the Shizuku prompt and retry."
        runButton.isEnabled = true
        agentRunButton.isEnabled = true
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    statusView =
      TextView(this).apply {
        text = "Shizuku persistent-shell diagnostic ready. Tap below to request permission and connect."
        textSize = 16f
      }
    runButton =
      Button(this).apply {
        text = "Test persistent shell"
        setOnClickListener { connectOrExecute() }
      }
    agentRunButton =
      Button(this).apply {
        text = "Test mock agent → real Shizuku shell"
        setOnClickListener { requestAgentShellRoundTrip() }
      }
    termuxRunButton =
      Button(this).apply {
        text = "Test Termux local execution (no rish)"
        setOnClickListener { connectTermuxOrRequestPermission() }
      }
    termuxRishRunButton =
      Button(this).apply {
        text = "Test mock agent → Termux rish → Shizuku shell"
        setOnClickListener { connectTermuxRishOrRequestPermission() }
      }

    val layout =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 48, 48, 48)
        addView(statusView)
        addView(runButton)
        addView(agentRunButton)
        addView(termuxRunButton)
        addView(termuxRishRunButton)
      }
    setContentView(layout)

    Shizuku.addRequestPermissionResultListener(permissionListener)
  }

  private fun connectOrExecute() {
    runButton.isEnabled = false
    if (!Shizuku.pingBinder()) {
      statusView.text = "Shizuku is not running. Start Shizuku and try again."
      runButton.isEnabled = true
      return
    }

    try {
      if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
        statusView.text = "Requesting Shizuku permission…"
        Shizuku.requestPermission(REQUEST_CODE)
        return
      }
      if (shellService == null) {
        bindUserService()
      } else {
        runPersistentSessionTest()
      }
    } catch (error: Exception) {
      statusView.text = "Shizuku error: " + error.message
      runButton.isEnabled = true
    }
  }

  /**
   * Runs a deterministic mock model through the real coordinator and Shizuku shell.
   * The only command issued by this diagnostic is the harmless identity command: id.
   */
  private fun requestAgentShellRoundTrip() {
    if (!Shizuku.pingBinder()) {
      statusView.text = "Shizuku is not running. Start Shizuku and try again."
      return
    }
    try {
      if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
        pendingAgentTest = true
        agentRunButton.isEnabled = false
        statusView.text = "Requesting Shizuku permission for the agent-shell diagnostic…"
        Shizuku.requestPermission(REQUEST_CODE)
        return
      }
      runAgentShellRoundTrip()
    } catch (error: Exception) {
      statusView.text = "Could not start agent-shell diagnostic: " + error.message
      agentRunButton.isEnabled = true
    }
  }

  private fun runAgentShellRoundTrip() {
    agentRunButton.isEnabled = false
    statusView.text = "Mock model → coordinator → Shizuku shell → result → mock model…"
    lifecycleScope.launch {
      val client = ShizukuShellClient(this@ShizukuDiagnosticActivity)
      try {
        val mockModel = AgentModel { history ->
          val toolResult = history.lastOrNull { it.role == AgentMessage.Role.TOOL }
          if (toolResult == null) {
            AgentModelResponse.ToolCall(
              name = "android_shell",
              arguments = JsonObject().apply { addProperty("command", "id") },
              callId = "diagnostic-id",
            )
          } else {
            AgentModelResponse.FinalAnswer(
              "The shell returned this identity:\\n" + toolResult.content
            )
          }
        }
        val coordinator = AgentCoordinator(
          model = mockModel,
          tools = AgentToolRegistry(listOf(IdentityDiagnosticShellTool(client))),
        )
        val result = coordinator.run("Run the Android shell identity command and report the result.")
        val toolResult = result.messages.lastOrNull { it.role == AgentMessage.Role.TOOL }
        statusView.text = buildString {
          appendLine("Agent run status: " + result.status)
          appendLine("Model turns: " + result.modelTurns + " | Tool calls: " + result.toolCalls)
          appendLine("Tool succeeded: " + (toolResult?.toolSucceeded == true))
          appendLine()
          appendLine("Mock model's final answer:")
          appendLine(result.finalText)
        }
      } catch (error: Exception) {
        statusView.text = "Agent-to-Shizuku round trip failed: " +
          (error.message ?: error.javaClass.simpleName)
      } finally {
        runCatching { client.close() }
        agentRunButton.isEnabled = true
      }
    }
  }


  /**
   * Verifies Termux RUN_COMMAND independently of Shizuku/rish. This diagnostic only prints
   * Termux's own identity and environment; it does not invoke rish or Android shell commands.
   */
  private fun connectTermuxOrRequestPermission() {
    try {
      @Suppress("DEPRECATION")
      packageManager.getPackageInfo("com.termux", 0)
    } catch (error: PackageManager.NameNotFoundException) {
      statusView.text = "Termux is not installed. Install and initialize Termux, then retry."
      return
    }

    if (checkSelfPermission("com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED) {
      statusView.text = "Requesting permission to run commands in Termux…"
      requestPermissions(
        arrayOf("com.termux.permission.RUN_COMMAND"),
        TERMUX_PERMISSION_REQUEST_CODE,
      )
      return
    }
    runTermuxLocalDiagnostic()
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    grantResults: IntArray,
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode != TERMUX_PERMISSION_REQUEST_CODE &&
      requestCode != TERMUX_RISH_PERMISSION_REQUEST_CODE
    ) return
    if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
      if (requestCode == TERMUX_RISH_PERMISSION_REQUEST_CODE) {
        runTermuxRishAgentRoundTrip()
      } else {
        runTermuxLocalDiagnostic()
      }
    } else {
      statusView.text =
        "Termux RUN_COMMAND permission was denied. Grant it in Android Settings to continue."
    }
  }


  /**
   * Exercises the shared model-facing shell tool through Termux's rish client. The diagnostic
   * policy approves only the fixed, harmless identity command initiated by this button.
   */
  private fun connectTermuxRishOrRequestPermission() {
    try {
      @Suppress("DEPRECATION")
      packageManager.getPackageInfo("com.termux", 0)
    } catch (error: PackageManager.NameNotFoundException) {
      statusView.text = "Termux is not installed. Install and initialize Termux, then retry."
      return
    }

    if (checkSelfPermission("com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED) {
      statusView.text = "Requesting permission to run the rish diagnostic through Termux…"
      requestPermissions(
        arrayOf("com.termux.permission.RUN_COMMAND"),
        TERMUX_RISH_PERMISSION_REQUEST_CODE,
      )
      return
    }
    runTermuxRishAgentRoundTrip()
  }

  private fun runTermuxRishAgentRoundTrip() {
    termuxRishRunButton.isEnabled = false
    statusView.text = "Mock agent → unified shell tool → Termux RUN_COMMAND → rish → Shizuku shell…"
    lifecycleScope.launch {
      try {
        val router = ShellExecutionRouter(
          mapOf(
            ShellExecutionBackend.TERMUX_RISH to
              TermuxRishShellTransport(TermuxRunCommandClient(this@ShizukuDiagnosticActivity))
          )
        )
        val shellTool = UnifiedShellTool(
          router = router,
          approval = ShellExecutionApprovalPolicy { request ->
            request.backend == ShellExecutionBackend.TERMUX_RISH && request.command == "id"
          },
        )
        val mockModel = AgentModel { history ->
          val toolResult = history.lastOrNull { it.role == AgentMessage.Role.TOOL }
          if (toolResult == null) {
            AgentModelResponse.ToolCall(
              name = "execute_shell",
              arguments = JsonObject().apply {
                addProperty("backend", ShellExecutionBackend.TERMUX_RISH.name)
                addProperty("command", "id")
                addProperty("working_directory", "/")
                addProperty("timeout_ms", COMMAND_TIMEOUT_MS)
              },
              callId = "diagnostic-rish-id",
            )
          } else {
            AgentModelResponse.FinalAnswer(
              "Termux rish returned this Android shell identity:\n" + toolResult.content
            )
          }
        }
        val coordinator = AgentCoordinator(
          model = mockModel,
          tools = AgentToolRegistry(listOf(shellTool)),
        )
        val result = coordinator.run(
          "Run the fixed Android shell identity diagnostic through Termux rish and report the result."
        )
        val toolResult = result.messages.lastOrNull { it.role == AgentMessage.Role.TOOL }
        statusView.text = buildString {
          appendLine("Agent run status: " + result.status)
          appendLine("Model turns: " + result.modelTurns + " | Tool calls: " + result.toolCalls)
          appendLine("Tool succeeded: " + (toolResult?.toolSucceeded == true))
          appendLine("Expected identity: uid=2000(shell), not root")
          appendLine()
          appendLine("Tool result:")
          appendLine(toolResult?.content.orEmpty())
          appendLine()
          appendLine("Mock model final answer:")
          appendLine(result.finalText)
        }
      } catch (error: Exception) {
        statusView.text = "Termux rish agent diagnostic failed: " +
          (error.message ?: error.javaClass.simpleName)
      } finally {
        termuxRishRunButton.isEnabled = true
      }
    }
  }

  private fun runTermuxLocalDiagnostic() {
    termuxRunButton.isEnabled = false
    statusView.text = "Launching a local command in Termux (rish is not used)…"
    lifecycleScope.launch {
      try {
        val result = TermuxRunCommandClient(this@ShizukuDiagnosticActivity).execute(
          command = "printf 'identity='; id; printf 'HOME=%s\\n' \"\$HOME\"; printf 'PREFIX=%s\\n' \"\$PREFIX\"; command -v bash",
          workingDirectory = "~/",
          timeoutMs = COMMAND_TIMEOUT_MS,
          maxOutputChars = OUTPUT_LIMIT,
        )
        statusView.text = buildString {
          appendLine("Termux local execution (rish not used):")
          appendLine("Exit code: " + result.exitCode)
          result.errorCode?.let { appendLine("Termux API error code: " + it) }
          result.error?.let { appendLine("Error: " + it) }
          if (result.stdout.isNotEmpty()) {
            appendLine("STDOUT:")
            appendLine(result.stdout)
          }
          if (result.stderr.isNotEmpty()) {
            appendLine("STDERR:")
            appendLine(result.stderr)
          }
          if (result.outputTruncated) appendLine("Output was truncated.")
          if (result.processMayStillBeRunning) {
            appendLine("Warning: the command may still be running inside Termux.")
          }
        }
      } catch (error: Exception) {
        statusView.text = "Termux execution failed: " +
          (error.message ?: error.javaClass.simpleName)
      } finally {
        termuxRunButton.isEnabled = true
      }
    }
  }

  private fun bindUserService() {
    try {
      statusView.text = "Binding Shizuku UserService…"
      Shizuku.bindUserService(userServiceArgs, serviceConnection)
      bound = true
    } catch (error: Exception) {
      statusView.text = "Could not bind Shizuku UserService: " + error.message
      runButton.isEnabled = true
    }
  }

  private fun runPersistentSessionTest() {
    val service = shellService
    if (service == null) {
      statusView.text = "Shizuku shell service is not connected."
      runButton.isEnabled = true
      return
    }

    statusView.text = "Creating persistent Android shell session…"
    lifecycleScope.launch {
      try {
        val report = withContext(Dispatchers.IO) {
          val created = service.createSession("/")
          val id = created.getString(ShizukuShellUserService.KEY_SESSION_ID)
            ?: error(created.getString(ShizukuShellUserService.KEY_ERROR) ?: "Session creation failed.")
          sessionId = id

          val identity = service.execute(id, "id", COMMAND_TIMEOUT_MS, OUTPUT_LIMIT)
          val setup = service.execute(
            id,
            "cd /storage/emulated/0 && export WINTAINIUM_SESSION_TEST=persists",
            COMMAND_TIMEOUT_MS,
            OUTPUT_LIMIT,
          )
          val verification = service.execute(
            id,
            "printf 'cwd='; pwd; printf 'env=%s\\n' \"\$WINTAINIUM_SESSION_TEST\"; printf 'stderr-check\\n' >&2; false",
            COMMAND_TIMEOUT_MS,
            OUTPUT_LIMIT,
          )

          listOf(identity, setup, verification)
        }

        val identity = report[0]
        val setup = report[1]
        val verification = report[2]
        val error = listOf(identity, setup, verification)
          .firstNotNullOfOrNull { it.getString(ShizukuShellUserService.KEY_ERROR) }
        statusView.text = buildString {
          if (error != null) appendLine("ERROR: " + error)
          appendLine("Identity (uid 2000 expected):")
          appendLine(identity.getString(ShizukuShellUserService.KEY_STDOUT).orEmpty())
          appendLine("Persistent state (cwd should be /storage/emulated/0):")
          appendLine(verification.getString(ShizukuShellUserService.KEY_STDOUT).orEmpty())
          appendLine("Separate stderr:")
          appendLine(verification.getString(ShizukuShellUserService.KEY_STDERR).orEmpty())
          appendLine("Exit code: " + verification.getInt(ShizukuShellUserService.KEY_EXIT_CODE, -1) + " (1 expected)")
          appendLine("Setup exit code: " + setup.getInt(ShizukuShellUserService.KEY_EXIT_CODE, -1) + " (0 expected)")
          if (verification.getBoolean(ShizukuShellUserService.KEY_OUTPUT_TRUNCATED)) {
            appendLine("Output was truncated.")
          }
        }
      } catch (error: Exception) {
        statusView.text = "Persistent shell test failed: " + error.message
      } finally {
        runButton.isEnabled = true
      }
    }
  }

  override fun onDestroy() {
    Shizuku.removeRequestPermissionResultListener(permissionListener)
    sessionId?.let { id ->
      shellService?.let { service -> runCatching { service.closeSession(id) } }
    }
    if (bound) {
      runCatching { Shizuku.unbindUserService(userServiceArgs, serviceConnection, true) }
      bound = false
    }
    shellService = null
    super.onDestroy()
  }

  private companion object {
    const val REQUEST_CODE = 4812
    const val TERMUX_PERMISSION_REQUEST_CODE = 4813
    const val TERMUX_RISH_PERMISSION_REQUEST_CODE = 4814
    const val COMMAND_TIMEOUT_MS = 15_000L
    const val OUTPUT_LIMIT = 32 * 1_024
  }
}
