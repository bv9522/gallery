/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.artifact

import com.google.ai.edge.gallery.agent.coordinator.ShellExecutionBackend
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

enum class ArtifactFormat(val mimeType: String) {
  PDF("application/pdf"),
  DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
  XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
  PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation"),
  ZIP("application/zip"),
  IMAGE("image/*");

  fun acceptsMimeType(value: String): Boolean =
    if (this == IMAGE) value in setOf("image/png", "image/jpeg", "image/webp") else value == mimeType

  fun acceptsSignature(prefix: ByteArray): Boolean = when (this) {
    PDF -> prefix.startsWithAscii("%PDF-")
    DOCX, XLSX, PPTX, ZIP -> prefix.startsWithBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
    IMAGE -> prefix.startsWithBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) ||
      prefix.startsWithBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())) ||
      (prefix.size >= 12 && prefix.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
        prefix.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()))
  }

  private fun ByteArray.startsWithAscii(value: String): Boolean =
    size >= value.length && copyOfRange(0, value.length).contentEquals(value.toByteArray())

  private fun ByteArray.startsWithBytes(value: ByteArray): Boolean =
    size >= value.size && copyOfRange(0, value.size).contentEquals(value)

  fun acceptsFileName(name: String): Boolean {
    val extension = name.substringAfterLast('.', "").lowercase()
    return when (this) {
      PDF -> extension == "pdf"
      DOCX -> extension == "docx"
      XLSX -> extension == "xlsx"
      PPTX -> extension == "pptx"
      ZIP -> extension == "zip"
      IMAGE -> extension in setOf("png", "jpg", "jpeg", "webp")
    }
  }
}

sealed interface ArtifactDestinationSpec {
  data class SafTree(val treeUri: String, val parentDocumentId: String?) : ArtifactDestinationSpec
  data class ShellPath(val backend: ShellExecutionBackend, val absolutePath: String) : ArtifactDestinationSpec
}

data class ArtifactCreateRequest(
  val format: ArtifactFormat,
  val fileName: String,
  val destination: ArtifactDestinationSpec,
  val overwrite: Boolean,
  val payload: JsonObject,
)

sealed interface ArtifactArgumentsParseResult {
  data class Valid(val request: ArtifactCreateRequest) : ArtifactArgumentsParseResult
  data class Invalid(val message: String) : ArtifactArgumentsParseResult
}

/** Strict common envelope; each format adapter validates its own typed payload. */
object ArtifactCreateArguments {
  const val MAX_FILE_NAME_CHARS = 128
  const val MAX_DESTINATION_CHARS = 1_024
  const val MAX_PAYLOAD_JSON_CHARS = 128 * 1_024
  const val MAX_PAYLOAD_DEPTH = 12
  const val MAX_PAYLOAD_NODES = 5_000

  private val allowedKeys = setOf(
    "format", "file_name", "destination_type", "tree_uri", "parent_document_id",
    "backend", "path", "overwrite", "payload",
  )

  fun parse(arguments: JsonObject): ArtifactArgumentsParseResult {
    val unknown = arguments.keySet() - allowedKeys
    if (unknown.isNotEmpty()) return invalid("Unexpected arguments: ${unknown.sorted().joinToString()}.")
    val format = string(arguments, "format")?.let { value ->
      ArtifactFormat.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    } ?: return invalid("format must be one of: pdf, docx, xlsx, pptx, zip, image.")
    val fileName = string(arguments, "file_name")
      ?: return invalid("file_name must be a string.")
    if (!isSafeFileName(fileName) || fileName.length > MAX_FILE_NAME_CHARS) {
      return invalid("file_name must be a safe leaf name of at most $MAX_FILE_NAME_CHARS characters.")
    }
    if (!format.acceptsFileName(fileName)) {
      return invalid("file_name extension does not match the selected ${format.name.lowercase()} format.")
    }
    val destinationType = string(arguments, "destination_type")
      ?: return invalid("destination_type must be 'saf' or 'shell'.")
    val parentId = optionalString(arguments, "parent_document_id")
      ?: if (arguments.has("parent_document_id")) return invalid("parent_document_id must be a string.") else null
    val destination = when (destinationType.lowercase()) {
      "saf" -> {
        if (arguments.has("backend") || arguments.has("path")) {
          return invalid("SAF destinations cannot include shell backend or path arguments.")
        }
        val treeUri = string(arguments, "tree_uri")
          ?: return invalid("SAF destination requires tree_uri from a current Gallery document-tree grant.")
        if (!treeUri.startsWith("content://") || treeUri.length > MAX_DESTINATION_CHARS) {
          return invalid("tree_uri must be a bounded content URI from Gallery's document-tree grant flow.")
        }
        ArtifactDestinationSpec.SafTree(treeUri, parentId)
      }
      "shell" -> {
        if (arguments.has("tree_uri") || arguments.has("parent_document_id")) {
          return invalid("Shell destinations cannot include SAF tree arguments.")
        }
        val backendName = string(arguments, "backend")
          ?: return invalid("Shell destination requires an explicit backend.")
        val backend = ShellExecutionBackend.entries.firstOrNull { it.name == backendName }
          ?: return invalid("backend must be TERMUX, SHIZUKU, or TERMUX_RISH.")
        val path = string(arguments, "path")
          ?: return invalid("Shell destination requires an absolute path.")
        if (!isSafeAbsolutePath(path) || path.length > MAX_DESTINATION_CHARS) {
          return invalid("path must be a bounded absolute path without dot-segment traversal.")
        }
        if (path.substringAfterLast('/') != fileName) {
          return invalid("The final path component must exactly match file_name.")
        }
        ArtifactDestinationSpec.ShellPath(backend, path)
      }
      else -> return invalid("destination_type must be 'saf' or 'shell'.")
    }
    val overwrite = if (!arguments.has("overwrite")) false else {
      val value = arguments.get("overwrite")
      if (value !is JsonPrimitive || !value.isBoolean) return invalid("overwrite must be a boolean.")
      value.asBoolean
    }
    val payload = arguments.get("payload")
      ?.takeIf { it.isJsonObject }?.asJsonObject
      ?: return invalid("payload must be a JSON object.")
    if (payload.toString().length > MAX_PAYLOAD_JSON_CHARS) {
      return invalid("payload exceeds the common request-size limit.")
    }
    if (!withinPayloadBudget(payload)) {
      return invalid("payload exceeds the supported nesting or node-count limit.")
    }
    return ArtifactArgumentsParseResult.Valid(
      ArtifactCreateRequest(format, fileName, destination, overwrite, payload.deepCopy()),
    )
  }

  private fun withinPayloadBudget(root: JsonElement): Boolean {
    var nodes = 0
    fun visit(value: JsonElement, depth: Int): Boolean {
      if (depth > MAX_PAYLOAD_DEPTH || ++nodes > MAX_PAYLOAD_NODES) return false
      return when {
        value.isJsonObject -> value.asJsonObject.entrySet().all { visit(it.value, depth + 1) }
        value.isJsonArray -> value.asJsonArray.all { visit(it, depth + 1) }
        else -> true
      }
    }
    return visit(root, 0)
  }

  private fun isSafeFileName(value: String): Boolean =
    value.isNotBlank() && value != "." && value != ".." &&
      '/' !in value && '\\' !in value &&
      value.none { it.code < 0x20 || it.code == 0x7f }

  private fun isSafeAbsolutePath(value: String): Boolean =
    value.startsWith("/") && value.length <= MAX_DESTINATION_CHARS &&
      !value.contains('\\u0000') && '\\\\' !in value &&
      value.split('/').drop(1).all { segment ->
        segment.isNotBlank() && segment != "." && segment != ".." &&
          segment.none { it.code < 0x20 || it.code == 0x7f }
      } &&
      value.substringAfterLast('/').isNotBlank()

  private fun string(obj: JsonObject, key: String): String? {
    val value = obj.get(key)
    return if (value is JsonPrimitive && value.isString) value.asString else null
  }

  private fun optionalString(obj: JsonObject, key: String): String? {
    if (!obj.has(key)) return null
    return string(obj, key)?.takeIf { it.isNotBlank() }
  }

  private fun invalid(message: String) = ArtifactArgumentsParseResult.Invalid(message)
}
