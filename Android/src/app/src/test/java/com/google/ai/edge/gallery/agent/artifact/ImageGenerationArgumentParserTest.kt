package com.google.ai.edge.gallery.agent.artifact

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageGenerationArgumentParserTest {
  @Test fun parsesRemoteProviderAndLabeledSafSources() {
    val parsed = ImageGenerationArgumentParser.parse(JsonObject().apply {
      addProperty("operation", "generate")
      addProperty("provider_id", FirebaseAiLogicImageGenerationProvider.PROVIDER_ID)
      addProperty("prompt", "Generate a watercolor landscape")
      addProperty("output_mime_type", "image/png")
    })
    assertEquals(FirebaseAiLogicImageGenerationProvider.PROVIDER_ID, parsed.providerId)
    assertEquals("Generate a watercolor landscape", parsed.request.prompt)
    assertEquals(0, parsed.request.sourceImages.size)
    assertEquals(emptyList<String>(), parsed.request.sourceImageLabels)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsRemoteSourceWithoutVisibleConsentLabel() {
    ImageGenerationArgumentParser.parse(JsonObject().apply {
      addProperty("operation", "generate")
      addProperty("provider_id", FirebaseAiLogicImageGenerationProvider.PROVIDER_ID)
      addProperty("prompt", "Generate a watercolor landscape")
      add("source_images", JsonArray().apply {
        add(JsonObject().apply {
          addProperty("source_tree_uri", "content://provider/tree/root")
          addProperty("source_document_id", "photo-1")
        })
      })
    })
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsUnsupportedGenerationOperation() {
    ImageGenerationArgumentParser.parse(JsonObject().apply {
      addProperty("operation", "edit")
      addProperty("provider_id", FirebaseAiLogicImageGenerationProvider.PROVIDER_ID)
      addProperty("prompt", "Make it brighter")
    })
  }
}
