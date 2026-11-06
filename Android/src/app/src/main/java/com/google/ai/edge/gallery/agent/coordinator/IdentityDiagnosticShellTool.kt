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

import com.google.ai.edge.gallery.shizuku.ShizukuShellClient
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Strictly constrained diagnostic capability used to validate real model tool calling.
 *
 * Unlike [ShizukuShellTool], this tool can execute only the literal identity command "id".
 */
class IdentityDiagnosticShellTool(
  private val client: ShizukuShellClient,
) : AgentTool {
  override val name: String = "android_shell"

  override val description: String =
    "Run the Android shell identity diagnostic. The only permitted command is exactly id."

  override val parametersSchema: JsonObject
    get() = JsonObject().apply {
      addProperty("type", "object")
      add("properties", JsonObject().apply {
        add("command", JsonObject().apply {
          addProperty("type", "string")
          addProperty("description", "Must be exactly id.")
          add("enum", JsonArray().apply { add("id") })
        })
      })
      add("required", JsonArray().apply { add("command") })
      addProperty("additionalProperties", false)
    }

  override suspend fun execute(arguments: JsonObject): AgentToolResult {
    val command = arguments.get("command")
      ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
      ?.asString
    if (command != "id") {
      return AgentToolResult("Rejected: this diagnostic permits only the exact command id.", false)
    }

    val result = try {
      client.execute("id")
    } catch (error: Exception) {
      return AgentToolResult(
        "Shizuku diagnostic execution failed: " +
          (error.message ?: error.javaClass.simpleName),
        false,
      )
    }

    val output = buildString {
      appendLine("Exit code: ${result.exitCode}")
      if (result.stdout.isNotBlank()) appendLine("STDOUT:").appendLine(result.stdout.trimEnd())
      if (result.stderr.isNotBlank()) appendLine("STDERR:").appendLine(result.stderr.trimEnd())
      result.error?.let { appendLine("ERROR: $it") }
      if (result.timedOut) appendLine("Command timed out.")
    }.trim()

    return AgentToolResult(output, result.succeeded)
  }
}