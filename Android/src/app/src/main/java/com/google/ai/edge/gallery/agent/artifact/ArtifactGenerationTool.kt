/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.artifact

import com.google.ai.edge.gallery.agent.coordinator.AgentTool
import com.google.ai.edge.gallery.agent.coordinator.AgentToolResult
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Single model-facing entry point for all generated artifact formats. */
class ArtifactGenerationTool(
  private val engine: DefaultArtifactGenerationEngine,
  private val supportedFormats: Set<ArtifactFormat>,
  private val supportedDestinations: Set<DestinationType>,
  private val zipExtractionHandler: SafZipExtractionHandler? = null,
) : AgentTool {
  init {
    require(supportedFormats.isNotEmpty()) { "At least one artifact format must be exposed." }
    require(supportedDestinations.isNotEmpty()) { "At least one artifact destination must be exposed." }
  }
  override val name: String = "artifact_create"
  override val description: String =
    "Create bounded local artifacts in supported formats and destinations. SAF requires an existing persisted Gallery tree grant and returned document IDs. Shell output requires an explicit TERMUX or SHIZUKU backend; TERMUX_RISH binary output is unavailable. Approval shows exact format, filename, destination, and overwrite behavior. Remote image generation is available only when configured and requires separate disclosure/consent before prompt or source upload; no on-device generative provider is registered. Embedded images must come from a persisted Gallery read grant."

  override val parametersSchema: JsonObject
    get() = JsonObject().apply {
      addProperty("type", "object")
      add("properties", JsonObject().apply {
        add("format", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply {
            supportedFormats.sortedBy { it.name }.forEach { add(it.name.lowercase()) }
          })
        })
        add("file_name", JsonObject().apply { addProperty("type", "string"); addProperty("maxLength", ArtifactCreateArguments.MAX_FILE_NAME_CHARS) })
        add("destination_type", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply { supportedDestinations.sortedBy { it.name }.forEach { add(it.name.lowercase()) } })
        })
        add("tree_uri", JsonObject().apply { addProperty("type", "string") })
        add("parent_document_id", JsonObject().apply { addProperty("type", "string") })
        add("backend", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply { ShellBackendNames.forEach { add(it) } })
        })
        add("path", JsonObject().apply { addProperty("type", "string") })
        add("overwrite", JsonObject().apply { addProperty("type", "boolean") })
        add("payload", JsonObject().apply { addProperty("type", "object") })
      })
      add("required", JsonArray().apply {
        add("format"); add("file_name"); add("destination_type"); add("payload")
      })
      addProperty("additionalProperties", false)
    }

  override suspend fun execute(arguments: JsonObject): AgentToolResult {
    return when (val parsed = ArtifactCreateArguments.parse(arguments)) {
      is ArtifactArgumentsParseResult.Invalid -> AgentToolResult(parsed.message, false)
      is ArtifactArgumentsParseResult.Valid -> {
        val request = parsed.request
        val operation = request.payload.get("operation")
          ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: "create"
        val result = if (request.format == ArtifactFormat.ZIP && operation == "extract") {
          zipExtractionHandler?.execute(request)
            ?: return AgentToolResult("ZIP extraction is not available in this runtime.", false)
        } else {
          engine.create(request)
        }
        AgentToolResult(Gson().toJson(result), result.isSuccessful)
      }
    }
  }

  private companion object {
    val ShellBackendNames = listOf("TERMUX", "SHIZUKU")
  }
}
