package com.google.ai.edge.gallery.agent.artifact

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtifactEmbeddedImageArgumentsTest {
  @Test fun parsesBoundedSafImageSourcesAndAltText() {
    val parsed = ArtifactEmbeddedImageArguments.parse(JsonArray().apply {
      add(source("photo-one", "Front view"))
      add(source("photo-two", "Rear view"))
    })
    assertEquals(2, parsed.size)
    assertEquals("Front view", parsed.first().altText)
  }

  @Test fun acceptsSupplementaryUnicodeInAltText() {
    val parsed = ArtifactEmbeddedImageArguments.parse(JsonArray().apply {
      add(source("photo", "Front view 📷"))
    })
    assertEquals("Front view 📷", parsed.single().altText)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsMoreThanFourEmbeddedImagesInOnePayloadGroup() {
    ArtifactEmbeddedImageArguments.parse(JsonArray().apply {
      repeat(5) { add(source("photo-$it", "Photo $it")) }
    })
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsUnknownImageSourceFields() {
    ArtifactEmbeddedImageArguments.parse(JsonArray().apply {
      add(source("photo", "Photo").apply { addProperty("path", "/sdcard/private.jpg") })
    })
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsXmlControlCharactersInAltText() {
    ArtifactEmbeddedImageArguments.parse(JsonArray().apply {
      add(source("photo", "Bad\u0001label"))
    })
  }

  private fun source(id: String, alt: String) = JsonObject().apply {
    addProperty("source_tree_uri", "content://provider/tree/root")
    addProperty("source_document_id", id)
    addProperty("alt_text", alt)
  }
}
