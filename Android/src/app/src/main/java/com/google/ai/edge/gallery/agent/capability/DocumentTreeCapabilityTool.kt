/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.google.ai.edge.gallery.agent.capability

import android.content.Context
import com.google.ai.edge.gallery.agent.coordinator.AgentTool
import com.google.ai.edge.gallery.agent.coordinator.AgentToolResult
import com.google.ai.edge.gallery.tools.ToolAction
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel

/** User-granted Storage Access Framework document-tree operations. */
class DocumentTreeCapabilityTool internal constructor(
  private val operations: DocumentTreeOperations,
) : AgentTool {
  constructor(context: Context, actionChannel: SendChannel<ToolAction>) : this(
    AndroidDocumentTreeAdapter(
      context = context.applicationContext,
      authorizer = ChannelNativeCapabilityAuthorizer(actionChannel),
      pickerRequester = ChannelDocumentTreePickerRequester(actionChannel),
    ),
  )

  override val name: String = "document_tree"
  override val description: String =
    "Access folders and attached USB storage only through user-granted Android Storage Access Framework tree URIs. " +
      "Use grants to list existing Gallery grants, request_access to open the system folder picker, list to inspect " +
      "children, read_text for bounded text files, create_file/create_directory to create items, write_text to write " +
      "text, rename/delete for explicit file management, and metadata for document details. The user must select " +
      "the folder or USB drive; no raw filesystem paths, arbitrary URIs, or shell commands are accepted. " +
      "Read and write actions require scoped approval. Copy, move, recursive search, and general metadata writes " +
      "are not implemented yet."

  override val parametersSchema: JsonObject
    get() = JsonObject().apply {
      addProperty("type", "object")
      add("properties", JsonObject().apply {
        add("operation", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply {
            listOf(
              "status", "grants", "request_access", "list", "metadata", "read_text",
              "create_file", "create_directory", "write_text", "rename", "delete",
            ).forEach { add(it) }
          })
        })
        add("tree_uri", JsonObject().apply { addProperty("type", "string") })
        add("parent_document_id", JsonObject().apply { addProperty("type", "string") })
        add("document_id", JsonObject().apply { addProperty("type", "string") })
        add("name", JsonObject().apply { addProperty("type", "string") })
        add("new_name", JsonObject().apply { addProperty("type", "string") })
        add("mime_type", JsonObject().apply { addProperty("type", "string") })
        add("text", JsonObject().apply {
          addProperty("type", "string")
          addProperty("maxLength", 262144)
        })
      })
      add("required", JsonArray().apply { add("operation") })
      addProperty("additionalProperties", false)
    }

  override suspend fun execute(arguments: JsonObject): AgentToolResult {
    val operation = arguments.string("operation")
      ?: return failure("document_tree requires a string operation.")
    val allowed = ALLOWED_ARGUMENTS[operation]
      ?: return failure("Unsupported document_tree operation '" + operation + "'.")
    val unknown = arguments.keySet() - allowed
    if (unknown.isNotEmpty()) {
      return failure("Unexpected arguments for " + operation + ": " + unknown.sorted().joinToString() + ".")
    }
    val missing = REQUIRED_ARGUMENTS[operation].orEmpty()
      .filterNot { arguments.has(it) && !arguments.get(it).isJsonNull }
    if (missing.isNotEmpty()) {
      return failure("Missing required arguments for " + operation + ": " + missing.joinToString() + ".")
    }

    return try {
      when (operation) {
        "status" -> AgentToolResult(Gson().toJson(operations.inspect()))
        "grants" -> AgentToolResult(Gson().toJson(operations.listGrants()))
        "request_access" -> {
          val grant = operations.requestAccess()
            ?: return failure("The user cancelled the document-tree picker.")
          AgentToolResult(Gson().toJson(grant))
        }
        "list" -> AgentToolResult(
          Gson().toJson(operations.list(arguments.requiredString("tree_uri"), arguments.string("parent_document_id"))),
        )
        "metadata" -> AgentToolResult(
          Gson().toJson(operations.metadata(arguments.requiredString("tree_uri"), arguments.requiredString("document_id"))),
        )
        "read_text" -> AgentToolResult(
          Gson().toJson(operations.readText(arguments.requiredString("tree_uri"), arguments.requiredString("document_id"))),
        )
        "create_file" -> AgentToolResult(
          operations.createFile(
            arguments.requiredString("tree_uri"),
            arguments.string("parent_document_id"),
            arguments.requiredString("name"),
            arguments.string("mime_type") ?: "text/plain",
          ),
        )
        "create_directory" -> AgentToolResult(
          operations.createDirectory(
            arguments.requiredString("tree_uri"),
            arguments.string("parent_document_id"),
            arguments.requiredString("name"),
          ),
        )
        "write_text" -> AgentToolResult(
          "Wrote " + operations.writeText(
            arguments.requiredString("tree_uri"),
            arguments.requiredString("document_id"),
            arguments.requiredString("text"),
          ) + " UTF-8 bytes.",
        )
        "rename" -> AgentToolResult(
          operations.rename(
            arguments.requiredString("tree_uri"),
            arguments.requiredString("document_id"),
            arguments.requiredString("new_name"),
          ),
        )
        "delete" -> {
          val deleted = operations.delete(
            arguments.requiredString("tree_uri"),
            arguments.requiredString("document_id"),
          )
          AgentToolResult(
            if (deleted) "Document deleted." else "The document provider did not delete the document.",
            deleted,
          )
        }
        else -> failure("Unsupported document_tree operation '" + operation + "'.")
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      failure(error.message ?: "The document-tree operation failed.")
    }
  }

  private fun failure(message: String) = AgentToolResult(message, false)

  private fun JsonObject.string(key: String): String? {
    val value = get(key) ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
    return value.asString
  }

  private fun JsonObject.requiredString(key: String): String =
    string(key) ?: throw IllegalArgumentException(key + " must be a string.")

  private companion object {
    val ALLOWED_ARGUMENTS = mapOf(
      "status" to setOf("operation"),
      "grants" to setOf("operation"),
      "request_access" to setOf("operation"),
      "list" to setOf("operation", "tree_uri", "parent_document_id"),
      "metadata" to setOf("operation", "tree_uri", "document_id"),
      "read_text" to setOf("operation", "tree_uri", "document_id"),
      "create_file" to setOf("operation", "tree_uri", "parent_document_id", "name", "mime_type"),
      "create_directory" to setOf("operation", "tree_uri", "parent_document_id", "name"),
      "write_text" to setOf("operation", "tree_uri", "document_id", "text"),
      "rename" to setOf("operation", "tree_uri", "document_id", "new_name"),
      "delete" to setOf("operation", "tree_uri", "document_id"),
    )
    val REQUIRED_ARGUMENTS = mapOf(
      "list" to setOf("tree_uri"),
      "metadata" to setOf("tree_uri", "document_id"),
      "read_text" to setOf("tree_uri", "document_id"),
      "create_file" to setOf("tree_uri", "name"),
      "create_directory" to setOf("tree_uri", "name"),
      "write_text" to setOf("tree_uri", "document_id", "text"),
      "rename" to setOf("tree_uri", "document_id", "new_name"),
      "delete" to setOf("tree_uri", "document_id"),
    )
  }
}
