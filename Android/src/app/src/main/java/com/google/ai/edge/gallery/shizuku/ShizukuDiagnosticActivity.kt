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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** Small on-device acceptance test for the app-to-Shizuku binder path. */
class ShizukuDiagnosticActivity : ComponentActivity() {
  private lateinit var statusView: TextView
  private lateinit var runButton: Button
  private var shellService: IShizukuShellService? = null
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
        statusView.text = "Connected to Shizuku UserService. Tap the button to run id."
        runButton.isEnabled = true
      }

      override fun onServiceDisconnected(name: ComponentName?) {
        shellService = null
        statusView.text = "Shizuku UserService disconnected."
        runButton.isEnabled = true
      }
    }

  private val permissionListener =
    Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
      if (requestCode != REQUEST_CODE) return@OnRequestPermissionResultListener
      if (grantResult == PackageManager.PERMISSION_GRANTED) {
        bindUserService()
      } else {
        statusView.text = "Shizuku permission was denied. Grant it in the Shizuku prompt and retry."
        runButton.isEnabled = true
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    statusView =
      TextView(this).apply {
        text = "Shizuku diagnostic ready. Tap below to request permission and connect."
        textSize = 16f
      }
    runButton =
      Button(this).apply {
        text = "Run id through Shizuku"
        setOnClickListener { connectOrExecute() }
      }

    val layout =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 48, 48, 48)
        addView(statusView)
        addView(runButton)
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
        executeId()
      }
    } catch (error: Exception) {
      statusView.text = "Shizuku error: ${error.message}"
      runButton.isEnabled = true
    }
  }

  private fun bindUserService() {
    try {
      statusView.text = "Binding Shizuku UserService…"
      Shizuku.bindUserService(userServiceArgs, serviceConnection)
      bound = true
    } catch (error: Exception) {
      statusView.text = "Could not bind Shizuku UserService: ${error.message}"
      runButton.isEnabled = true
    }
  }

  private fun executeId() {
    val service = shellService
    if (service == null) {
      statusView.text = "Shizuku shell service is not connected."
      runButton.isEnabled = true
      return
    }

    statusView.text = "Executing id as Android shell…"
    lifecycleScope.launch {
      try {
        val result = withContext(Dispatchers.IO) { service.execute("id") }
        val output = result.getString(ShizukuShellUserService.KEY_OUTPUT).orEmpty()
        val exitCode = result.getInt(ShizukuShellUserService.KEY_EXIT_CODE, -1)
        statusView.text = "Exit code: $exitCode\n\n$output"
      } catch (error: Exception) {
        statusView.text = "Command failed: ${error.message}"
      } finally {
        runButton.isEnabled = true
      }
    }
  }

  override fun onDestroy() {
    Shizuku.removeRequestPermissionResultListener(permissionListener)
    if (bound) {
      runCatching { Shizuku.unbindUserService(userServiceArgs, serviceConnection, true) }
      bound = false
    }
    shellService = null
    super.onDestroy()
  }

  private companion object {
    const val REQUEST_CODE = 4812
  }
}
