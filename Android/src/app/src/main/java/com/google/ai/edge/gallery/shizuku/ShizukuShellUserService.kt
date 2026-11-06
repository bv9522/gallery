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

import android.os.Bundle
import java.io.File

/**
 * Binder endpoint instantiated by Shizuku in its privileged user-service process.
 *
 * This first foundation milestone deliberately exposes only a small synchronous command
 * operation. The app-side diagnostic invokes "id"; persistent sessions and policy enforcement
 * will be layered on after the UID-2000 connection is proven on-device.
 */
class ShizukuShellUserService : IShizukuShellService.Stub() {
  override fun execute(command: String): Bundle {
    require(command.isNotBlank()) { "Command must not be blank" }

    val process =
      ProcessBuilder("/system/bin/sh", "-c", command)
        .directory(File("/"))
        .redirectErrorStream(true)
        .start()

    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()

    return Bundle().apply {
      putString(KEY_OUTPUT, output)
      putInt(KEY_EXIT_CODE, exitCode)
    }
  }

  companion object {
    const val KEY_OUTPUT = "output"
    const val KEY_EXIT_CODE = "exit_code"
  }
}
