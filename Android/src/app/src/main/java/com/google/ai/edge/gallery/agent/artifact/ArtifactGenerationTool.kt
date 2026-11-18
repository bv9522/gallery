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
) : AgentTool {
  override val name: String = "artifact_create"
  override val description: String =
    "Create one bounded PDF, DOCX, XLSX, PPTX, ZIP, or processed image artifact. " +
      "Choose exactly one destination: a persisted Gallery SAF tree grant or an explicit TERMUX, SHIZUKU, or TERMUX_RISH path. " +
      "Use only tree URIs/document IDs returned by document_tree grants/list operations. Shell paths must be absolute and accessible to the selected backend. " +
      "The exact filename, format, destination, and overwrite behavior are shown for one-time approval. " +
      "Generated content remains local; remote image generation is a separate opt-in provider flow."

  override val parametersSchema: JsonObject
    get() = JsonObject().apply {
      addProperty("type", "object")
      add("properties", JsonObject().apply {
        add("format", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply {
            ArtifactFormat.entries.forEach { add(it.name.lowercase()) }
          })
        })
        add("file_name", JsonObject().apply { addProperty("type", "string"); addProperty("maxLength", ArtifactCreateArguments.MAX_FILE_NAME_CHARS) })
        add("destination_type", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply { add("saf"); add("shell") })
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

  override suspend fun execute(arguments: JsonObject): AgentToolResult =
    when (val parsed = ArtifactCreateArguments.parse(arguments)) {
      is ArtifactArgumentsParseResult.Invalid -> AgentToolResult(parsed.message, false)
      is ArtifactArgumentsParseResult.Valid -> {
        val result = engine.create(parsed.request)
        AgentToolResult(Gson().toJson(result), result.isSuccessful)
      }
    }

  private companion object {
    val ShellBackendNames = listOf("TERMUX", "SHIZUKU", "TERMUX_RISH")
  }
}
