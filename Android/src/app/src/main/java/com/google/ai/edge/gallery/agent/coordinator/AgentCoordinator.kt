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

import com.google.ai.edge.litertlm.Contents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
  private val resultBudget: AgentToolResultBudget = AgentToolResultBudget(),
) {
  private val runMutex = Mutex()
  private val conversationMessages = mutableListOf<AgentMessage>()

  init {
    require(maxModelTurns > 0) { "maxModelTurns must be greater than zero." }
  }

  /**
   * Runs one user turn while retaining coordinator history for the lifetime of its model
   * conversation. A cancelled turn is rolled back in coordinator memory; the owner must discard
   * the associated native conversation because its KV cache may contain a partial turn.
   */
  suspend fun run(
    userInput: String,
    userContents: Contents? = null,
    extraContext: Map<String, Any> = emptyMap(),
  ): AgentRunResult =
    runMutex.withLock {
      require(userInput.isNotBlank() || userContents != null) {
        "User input or multimodal contents must not be empty."
      }
      val previousSize = conversationMessages.size
      resultBudget.reset()
      conversationMessages += AgentMessage(
        AgentMessage.Role.USER,
        content = userInput,
        userContents = userContents,
        extraContext = extraContext,
      )
      var modelTurns = 0
      var toolCalls = 0

      try {
        while (modelTurns < maxModelTurns) {
          val response = model.generate(conversationMessages.toList())
          modelTurns++
          when (response) {
            is AgentModelResponse.FinalAnswer -> {
              conversationMessages += AgentMessage(
                AgentMessage.Role.ASSISTANT,
                content = response.text,
              )
              return@withLock AgentRunResult(
                AgentRunStatus.COMPLETED,
                response.text,
                modelTurns,
                toolCalls,
                conversationMessages.toList(),
              )
            }
            is AgentModelResponse.ToolCall -> {
              executeToolCalls(listOf(response), conversationMessages)
              toolCalls++
            }
            is AgentModelResponse.ToolCalls -> {
              executeToolCalls(response.calls, conversationMessages)
              toolCalls += response.calls.size
            }
          }
        }

        val limitMessage =
          "Agent stopped after reaching the configured limit of " + maxModelTurns + " model turns."
        AgentRunResult(
          AgentRunStatus.TURN_LIMIT_REACHED,
          limitMessage,
          modelTurns,
          toolCalls,
          conversationMessages.toList(),
        )
      } catch (cancelled: CancellationException) {
        while (conversationMessages.size > previousSize) {
          conversationMessages.removeAt(conversationMessages.lastIndex)
        }
        throw cancelled
      } catch (error: Exception) {
        while (conversationMessages.size > previousSize) {
          conversationMessages.removeAt(conversationMessages.lastIndex)
        }
        throw error
      }
    }

  /** Clears model-side coordinator history when the owning conversation is reset or discarded. */
  suspend fun reset() {
    runMutex.withLock { conversationMessages.clear() }
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
    val result = try {
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
    return result.copy(content = resultBudget.constrain(result.content))
  }

  companion object {
    const val DEFAULT_MAX_MODEL_TURNS = 8
  }
}