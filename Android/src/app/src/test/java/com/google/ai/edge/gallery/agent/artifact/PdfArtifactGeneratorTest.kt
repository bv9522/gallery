package com.google.ai.edge.gallery.agent.artifact

import com.google.gson.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfArtifactGeneratorTest {
  private val generator = PdfArtifactGenerator()

  @Test fun acceptsBoundedStructuredPdfContent() {
    val payload = JsonObject().apply {
      addProperty("title", "Project Summary")
      add("pages", com.google.gson.JsonArray().apply {
        add(JsonObject().apply {
          addProperty("heading", "Overview")
          add("paragraphs", com.google.gson.JsonArray().apply { add("A concise report paragraph.") })
          add("bullet_items", com.google.gson.JsonArray().apply { add("First item"); add("Second item") })
          add("table", com.google.gson.JsonArray().apply {
            add(com.google.gson.JsonArray().apply { add("Name"); add("Status") })
            add(com.google.gson.JsonArray().apply { add("Engine"); add("In progress") })
          })
        })
      })
    }
    generator.validatePayload(payload)
    assertTrue(generator.estimateBytes(
      ArtifactCreateRequest(ArtifactFormat.PDF, "summary.pdf",
        ArtifactDestinationSpec.SafTree("content://provider/tree/root", null), false, payload)
    )!! > 0L)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsUnknownPdfPayloadFields() {
    generator.validatePayload(JsonObject().apply {
      addProperty("title", "Report")
      addProperty("script", "do not execute")
      add("pages", com.google.gson.JsonArray().apply { add(JsonObject()) })
    })
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsNonStringParagraphItems() {
    generator.validatePayload(JsonObject().apply {
      addProperty("title", "Report")
      add("pages", com.google.gson.JsonArray().apply {
        add(JsonObject().apply {
          add("paragraphs", com.google.gson.JsonArray().apply { add(42) })
        })
      })
    })
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsMoreThanOneHundredSections() {
    generator.validatePayload(JsonObject().apply {
      addProperty("title", "Report")
      add("pages", com.google.gson.JsonArray().apply {
        repeat(101) { add(JsonObject()) }
      })
    })
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsExcessiveTotalText() {
    generator.validatePayload(JsonObject().apply {
      addProperty("title", "Report")
      add("pages", com.google.gson.JsonArray().apply {
        add(JsonObject().apply {
          add("paragraphs", com.google.gson.JsonArray().apply {
            repeat(13) { add("x".repeat(8_000)) }
          })
        })
      })
    })
  }
}
