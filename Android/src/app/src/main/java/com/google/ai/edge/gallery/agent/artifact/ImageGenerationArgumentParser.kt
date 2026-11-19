/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.artifact

import android.net.Uri
import android.provider.DocumentsContract
import com.google.gson.JsonObject

internal data class ImageGenerationArguments(
  val providerId: String,
  val request: ImageGenerationRequest,
)

/** Strict payload parser for optional provider-backed image generation. */
internal object ImageGenerationArgumentParser {
  fun parse(payload: JsonObject): ImageGenerationArguments {
    val allowed = setOf("operation", "provider_id", "prompt", "output_mime_type", "source_images")
    val unknown = payload.keySet() - allowed
    require(unknown.isEmpty()) { "Unsupported image-generation field(s): ${unknown.sorted().joinToString()}." }
    require(string(payload, "operation") == "generate") { "Image-generation operation must be 'generate'." }
    val providerId = string(payload, "provider_id")
      ?: throw IllegalArgumentException("Image generation requires an explicit provider_id.")
    require(providerId.isNotBlank() && providerId.length <= 128) { "provider_id must be a bounded non-empty string." }
    val prompt = string(payload, "prompt")
      ?: throw IllegalArgumentException("Image generation requires a prompt.")
    require(prompt.isNotBlank() && prompt.length <= MAX_PROMPT_CHARS) {
      "Image-generation prompt must contain 1–$MAX_PROMPT_CHARS characters."
    }
    val mime = string(payload, "output_mime_type") ?: "image/png"
    require(mime in SUPPORTED_MIME_TYPES) { "output_mime_type must be PNG, JPEG, or WebP." }

    val sources = payload.get("source_images")
    require(sources == null || sources.isJsonArray) { "source_images must be an array." }
    val sourceUris = mutableListOf<Uri>()
    val labels = mutableListOf<String>()
    sources?.asJsonArray?.forEachIndexed { index, element ->
      require(index < MAX_SOURCE_IMAGES) { "Image generation accepts at most $MAX_SOURCE_IMAGES source images." }
      require(element.isJsonObject) { "source_images item ${index + 1} must be an object." }
      val source = element.asJsonObject
      val sourceUnknown = source.keySet() - setOf("source_tree_uri", "source_document_id", "label")
      require(sourceUnknown.isEmpty()) { "Unsupported source image field(s): ${sourceUnknown.sorted().joinToString()}." }
      val treeText = string(source, "source_tree_uri")
        ?: throw IllegalArgumentException("Each source image requires source_tree_uri.")
      val tree = Uri.parse(treeText)
      require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree)) {
        "Source image tree URI must come from Gallery's document-tree grant flow."
      }
      val documentId = string(source, "source_document_id")
        ?: throw IllegalArgumentException("Each source image requires source_document_id.")
      require(documentId.isNotBlank() && documentId.length <= 1_024 &&
        documentId.none { it.code < 0x20 || it.code == 0x7f }) {
        "Source image document ID must be a bounded, non-empty string."
      }
      val label = string(source, "label")
        ?: throw IllegalArgumentException("Each remote source image requires a visible label.")
      require(label.isNotBlank() && label.length <= MAX_SOURCE_LABEL_CHARS) {
        "Source image labels must contain 1–$MAX_SOURCE_LABEL_CHARS characters."
      }
      sourceUris += DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
      labels += label
    }
    return ImageGenerationArguments(
      providerId,
      ImageGenerationRequest(prompt, mime, sourceUris, labels),
    )
  }

  private fun string(obj: JsonObject, key: String): String? {
    val value = obj.get(key)
    return if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else null
  }

  // Members belong directly to this singleton; Kotlin objects do not use companion objects.
  private const val MAX_PROMPT_CHARS = 10_000
  private const val MAX_SOURCE_IMAGES = 8
  private const val MAX_SOURCE_LABEL_CHARS = 200
  private val SUPPORTED_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp")
}
