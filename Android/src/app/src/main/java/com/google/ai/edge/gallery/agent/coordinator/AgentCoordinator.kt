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

import kotlinx.coroutines.CancellationException

/** Terminal status for one coordinator run. */
enum class AgentRunStatus { COMPLETED, FAILED, TURN_LIMIT_REACHED }

/** A complete, inspectable record of one agent run. */
data class AgentRunResult(
  val status: AgentRunStatus,
  val finalText: String,
  val modelTurns: Int,
  val toolCalls: Int,
  val messages: List<AgentMessage>,
) {
  val isSuccessful: Boolean get() = status == AgentRunStatus.COMPLETED
}

/** Model-neutral tool-use loop. The coordinator is the only component that executes tools. */
class AgentCoordinator(
  private val model: AgentModel,
  private val tools: AgentToolRegistry,
  private val maxModelTurns: Int = DEFAULT_MAX_MODEL_TURNS,
) {
  init {
    require(maxModelTurns > 0) { "maxModelTurns must be greater than zero." }
  }

  suspend fun run(userInput: String): AgentRunResult {
    require(userInput.isNotBlank()) { "User input must not be blank." }
    val messages = mutableListOf(AgentMessage(AgentMessage.Role.USER, content = userInput))
    var modelTurns = 0
    var toolCalls = 0

    while (modelTurns < maxModelTurns) {
      val response = model.generate(messages.toList())
      modelTurns++
      when (response) {
        is AgentModelResponse.FinalAnswer -> {
          messages += AgentMessage(AgentMessage.Role.ASSISTANT, content = response.text)
          return AgentRunResult(
            AgentRunStatus.COMPLETED, response.text, modelTurns, toolCalls, messages.toList()
          )
        }
        is AgentModelResponse.ToolCall -> {
          executeToolCalls(listOf(response), messages)
          toolCalls++
        }
        is AgentModelResponse.ToolCalls -> {
          executeToolCalls(response.calls, messages)
          toolCalls += response.calls.size
        }
      }
    }

    val limitMessage =
      "Agent stopped after reaching the configured limit of " + maxModelTurns + " model turns."
    return AgentRunResult(
      AgentRunStatus.TURN_LIMIT_REACHED, limitMessage, modelTurns, toolCalls, messages.toList()
    )
  }

  private suspend fun executeToolCalls(
    calls: List<AgentModelResponse.ToolCall>,
    messages: MutableList<AgentMessage>,
  ) {
    // Preserve the model's complete batch before executing any member of it.
    calls.forEach { call ->
      messages += AgentMessage(
        role = AgentMessage.Role.ASSISTANT,
        toolCallId = call.callId,
        toolName = call.name,
        toolArguments = call.arguments.deepCopy(),
      )
    }
    // Execute sequentially: shell sessions are stateful and ordering must be deterministic.
    calls.forEach { call ->
      val result = executeToolCall(call)
      messages += AgentMessage(
        role = AgentMessage.Role.TOOL,
        content = result.content,
        toolCallId = call.callId,
        toolName = call.name,
        toolSucceeded = result.isSuccessful,
      )
    }
  }

  private suspend fun executeToolCall(call: AgentModelResponse.ToolCall): AgentToolResult {
    val tool = tools.find(call.name)
      ?: return AgentToolResult(
        content = "Unknown tool '" + call.name + "'. Available tools: " +
          tools.names().sorted().joinToString(", "),
        isSuccessful = false,
      )
    return try {
      tool.execute(call.arguments.deepCopy())
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      AgentToolResult(
        content = "Tool '" + call.name + "' failed: " +
          (error.message ?: error.javaClass.simpleName),
        isSuccessful = false,
      )
    }
  }

  companion object {
    const val DEFAULT_MAX_MODEL_TURNS = 8
  }
}