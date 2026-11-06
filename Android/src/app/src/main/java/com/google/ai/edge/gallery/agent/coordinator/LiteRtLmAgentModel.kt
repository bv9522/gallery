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

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolCall as LiteRtToolCall
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Model-neutral coordinator adapter for a manually-driven LiteRT-LM conversation.
 *
 * The conversation must be created with automaticToolCalling=false and the providers returned by
 * [toolProviders]. This adapter never executes a tool; it only translates structured requests and
 * tool responses. The owning caller closes this adapter when the agent run ends. Closing it closes
 * the Conversation, not its shared Engine.
 *
 * Create one adapter per agent run because LiteRT-LM Conversation instances retain their own
 * message/KV-cache history.
 */
class LiteRtLmAgentModel(
  private val conversation: Conversation,
  private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : AgentModel, Closeable {
  private val mutex = Mutex()
  private var consumedMessageCount = 0
  private var modelTurn = 0
  private var closed = false

  init {
    require(!conversation.automaticToolCalling) {
      "Agent conversations must disable automatic tool calling."
    }
  }

  override suspend fun generate(messages: List<AgentMessage>): AgentModelResponse =
    mutex.withLock {
      check(!closed) { "Agent conversation is closed." }
      require(messages.size >= consumedMessageCount) {
        "Agent message history cannot move backwards."
      }
      val pending = messages.drop(consumedMessageCount)
      consumedMessageCount = messages.size
      modelTurn++

      val userMessages = pending.filter { it.role == AgentMessage.Role.USER }
      val toolMessages = pending.filter { it.role == AgentMessage.Role.TOOL }
      require(userMessages.size + toolMessages.size == 1) {
        "Expected exactly one new user message or a non-empty tool-result batch; got " +
          "${userMessages.size} user and ${toolMessages.size} tool messages."
      }

      val response = withContext(ioDispatcher) {
        if (userMessages.isNotEmpty()) {
          conversation.sendMessage(Message.user(userMessages.single().content))
        } else {
          val toolResponses = toolMessages.map { message ->
            val name = message.toolName
              ?: error("Tool result is missing its tool name.")
            Content.ToolResponse(name, message.content)
          }
          conversation.sendMessage(Message.tool(Contents.of(toolResponses)))
        }
      }

      if (response.toolCalls.isNotEmpty()) {
        val calls = response.toolCalls.mapIndexed { index, call ->
          AgentModelResponse.ToolCall(
            name = call.name,
            arguments = Gson().toJsonTree(call.arguments).asJsonObject,
            callId = "litert-${modelTurn}-${index + 1}",
          )
        }
        AgentModelResponse.ToolCalls(calls)
      } else {
        AgentModelResponse.FinalAnswer(response.toString())
      }
    }

  override fun close() {
    if (closed) return
    closed = true
    conversation.close()
  }

  companion object {
    /**
     * Builds description-only LiteRT-LM providers. Their execute methods deliberately fail closed:
     * automatic execution is forbidden and the AgentCoordinator is the only tool dispatcher.
     */
    fun toolProviders(registry: AgentToolRegistry): List<ToolProvider> =
      registry.all().map { agentTool ->
        val definition = JsonObject().apply {
          addProperty("name", agentTool.name)
          addProperty("description", agentTool.description)
          add("parameters", agentTool.parametersSchema.deepCopy())
        }
        tool(
          object : OpenApiTool {
            override fun getToolDescriptionJsonString(): String = definition.toString()

            override fun execute(paramsJsonString: String): String =
              throw UnsupportedOperationException(
                "LiteRT-LM automatic tool execution is disabled; use AgentCoordinator."
              )
          }
        )
      }
  }
}
