package com.google.ai.edge.gallery.agent.artifact

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageTransformArgumentsTest {
  @Test fun acceptsResizeCropRotationAndQuality() {
    val parsed = ImageTransformArguments.parse(JsonObject().apply {
      addProperty("source_tree_uri", "content://provider/tree/root")
      addProperty("source_document_id", "document-123")
      addProperty("resize_width", 1600)
      addProperty("resize_height", 1200)
      addProperty("crop_x", 10)
      addProperty("crop_y", 20)
      addProperty("crop_width", 800)
      addProperty("crop_height", 600)
      addProperty("rotation_degrees", 90)
      addProperty("quality", 85)
    })
    assertEquals(1600, parsed.resizeWidth)
    assertEquals(90, parsed.rotationDegrees)
    assertEquals(85, parsed.quality)
  }

  @Test fun allowsFormatConversionWithoutGeometricTransform() {
    val parsed = ImageTransformArguments.parse(JsonObject().apply {
      addProperty("source_tree_uri", "content://provider/tree/root")
      addProperty("source_document_id", "document-123")
    })
    assertEquals(0, parsed.rotationDegrees)
    assertEquals(90, parsed.quality)
  }

  @Test fun acceptsImageCompositionAndAnnotation() {
    val parsed = ImageTransformArguments.parse(JsonObject().apply {
      addProperty("source_tree_uri", "content://provider/tree/root")
      addProperty("source_document_id", "primary")
      add("additional_sources", com.google.gson.JsonArray().apply {
        add(JsonObject().apply {
          addProperty("source_tree_uri", "content://provider/tree/root")
          addProperty("source_document_id", "secondary")
          addProperty("alt_text", "Second image")
        })
      })
      addProperty("annotation_text", "Project milestone")
      addProperty("annotation_position", "top")
    })
    assertEquals(1, parsed.additionalSources.size)
    assertEquals("Project milestone", parsed.annotationText)
    assertEquals("top", parsed.annotationPosition)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsCompositionCombinedWithGeometricTransforms() {
    ImageTransformArguments.parse(JsonObject().apply {
      addProperty("source_tree_uri", "content://provider/tree/root")
      addProperty("source_document_id", "primary")
      add("additional_sources", com.google.gson.JsonArray().apply {
        add(JsonObject().apply {
          addProperty("source_tree_uri", "content://provider/tree/root")
          addProperty("source_document_id", "secondary")
        })
      })
      addProperty("resize_width", 800)
      addProperty("resize_height", 600)
    })
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsAnnotationPositionWithoutAnnotationText() {
    ImageTransformArguments.parse(JsonObject().apply {
      addProperty("source_tree_uri", "content://provider/tree/root")
      addProperty("source_document_id", "primary")
      addProperty("annotation_position", "top")
    })
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsIncompleteResizePair() {
    ImageTransformArguments.parse(JsonObject().apply {
      addProperty("source_tree_uri", "content://provider/tree/root")
      addProperty("source_document_id", "document-123")
      addProperty("resize_width", 800)
    })
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsIncompleteCropRectangle() {
    ImageTransformArguments.parse(JsonObject().apply {
      addProperty("source_tree_uri", "content://provider/tree/root")
      addProperty("source_document_id", "document-123")
      addProperty("crop_x", 0)
      addProperty("crop_y", 0)
      addProperty("crop_width", 200)
    })
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsUnsupportedRotation() {
    ImageTransformArguments.parse(JsonObject().apply {
      addProperty("source_tree_uri", "content://provider/tree/root")
      addProperty("source_document_id", "document-123")
      addProperty("rotation_degrees", 45)
    })
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsUnknownFields() {
    ImageTransformArguments.parse(JsonObject().apply {
      addProperty("source_tree_uri", "content://provider/tree/root")
      addProperty("source_document_id", "document-123")
      addProperty("script", "execute")
    })
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsOutOfRangeDimensions() {
    ImageTransformArguments.parse(JsonObject().apply {
      addProperty("source_tree_uri", "content://provider/tree/root")
      addProperty("source_document_id", "document-123")
      addProperty("resize_width", 9000)
      addProperty("resize_height", 1000)
    })
  }
}
