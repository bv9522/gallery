/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.artifact

import com.google.gson.JsonElement

/** Shared bounded payload parser for images embedded in generated documents. */
internal object ArtifactEmbeddedImageArguments {
  const val MAX_IMAGES_PER_SECTION = 4

  private fun isXml10Text(value: String): Boolean {
    var index = 0
    while (index < value.length) {
      val codePoint = value.codePointAt(index)
      val valid = codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD ||
        codePoint in 0x20..0xD7FF || codePoint in 0xE000..0xFFFD ||
        codePoint in 0x10000..0x10FFFF
      if (!valid) return false
      index += Character.charCount(codePoint)
    }
    return true
  }

  fun parse(element: JsonElement?): List<ArtifactEmbeddedImageRequest> {
    if (element == null) return emptyList()
    require(element.isJsonArray) { "images must be an array of SAF image sources." }
    val images = element.asJsonArray
    require(images.size() <= MAX_IMAGES_PER_SECTION) {
      "A document section may contain at most $MAX_IMAGES_PER_SECTION embedded images."
    }
    return images.mapIndexed { index, item ->
      require(item.isJsonObject) { "images item ${index + 1} must be an object." }
      val value = item.asJsonObject
      val unknown = value.keySet() - setOf("source_tree_uri", "source_document_id", "alt_text")
      require(unknown.isEmpty()) {
        "Unsupported image source field(s): ${unknown.sorted().joinToString()}."
      }
      fun requiredString(key: String): String {
        val field = value.get(key)
        require(field != null && field.isJsonPrimitive && field.asJsonPrimitive.isString &&
          field.asString.isNotBlank() && field.asString.length <= 1_024) {
          "images item ${index + 1} requires a bounded $key string."
        }
        return field.asString
      }
      val alt = value.get("alt_text")
      require(alt == null || (alt.isJsonPrimitive && alt.asJsonPrimitive.isString &&
        alt.asString.length <= 500 && isXml10Text(alt.asString))) {
        "images item ${index + 1} alt_text must be XML-safe text of at most 500 characters."
      }
      ArtifactEmbeddedImageRequest(
        sourceTreeUri = requiredString("source_tree_uri"),
        sourceDocumentId = requiredString("source_document_id"),
        altText = alt?.asString.orEmpty(),
      )
    }
  }
}
