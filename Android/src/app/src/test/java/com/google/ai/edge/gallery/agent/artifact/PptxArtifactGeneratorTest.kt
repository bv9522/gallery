package com.google.ai.edge.gallery.agent.artifact

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.InputSource

class PptxArtifactGeneratorTest {
  private val generator = PptxArtifactGenerator()

  @Test fun createsEditablePresentationWithSlidesMasterLayoutAndTheme() {
    val output = ByteArrayOutputStream()
    val mime = generator.write(request(), output)
    val entries = linkedMapOf<String, String>()
    ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
      }
    }
    assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation", mime)
    assertTrue(entries.containsKey("[Content_Types].xml"))
    assertTrue(entries.containsKey("_rels/.rels"))
    assertTrue(entries.containsKey("ppt/presentation.xml"))
    assertTrue(entries.containsKey("ppt/_rels/presentation.xml.rels"))
    assertTrue(entries.containsKey("ppt/slideMasters/slideMaster1.xml"))
    assertTrue(entries.containsKey("ppt/slideLayouts/slideLayout1.xml"))
    assertTrue(entries.containsKey("ppt/theme/theme1.xml"))
    assertTrue(entries.containsKey("ppt/slides/slide1.xml"))
    assertTrue(entries.containsKey("ppt/slides/slide2.xml"))
    val slide = entries.getValue("ppt/slides/slide1.xml")
    assertTrue(slide.contains("<p:sp>"))
    assertTrue(slide.contains("<a:buChar char=\"•\"/>"))
    assertTrue(slide.contains("Strategy &amp; Delivery"))
    assertTrue(output.toByteArray().copyOfRange(0, 2).contentEquals(byteArrayOf(0x50, 0x4b)))
  }

  @Test fun everyXmlPackagePartIsWellFormed() {
    val output = ByteArrayOutputStream()
    generator.write(request(), output)
    ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        if (entry.name.endsWith(".xml") || entry.name.endsWith(".rels")) {
          DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(zip.readBytes().toString(Charsets.UTF_8))))
        }
      }
    }
  }

  @Test fun imagePayloadIsIncludedInApprovalSizeEstimate() {
    val payload = request().payload.deepCopy()
    payload.getAsJsonArray("slides")[0].asJsonObject.add(
      "images",
      JsonArray().apply { add(imageSource("slide-one-image")) },
    )
    assertTrue(generator.estimateBytes(request().copy(payload = payload))!! >= 4L * 1024L * 1024L)
  }

  @Test fun validatesOneEmbeddedImagePerSlide() {
    val payload = request().payload.deepCopy()
    payload.getAsJsonArray("slides")[0].asJsonObject.add(
      "images",
      JsonArray().apply { add(imageSource("slide-one-image")) },
    )
    generator.validatePayload(payload)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsMoreThanFourEmbeddedImagesAcrossPresentation() {
    val payload = JsonObject().apply {
      add("slides", JsonArray().apply {
        repeat(5) { index ->
          add(JsonObject().apply {
            addProperty("title", "Slide $index")
            add("images", JsonArray().apply { add(imageSource("slide-image-$index")) })
          })
        }
      })
    }
    generator.validatePayload(payload)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsMoreThanOneImageOnASlide() {
    val payload = request().payload.deepCopy()
    payload.getAsJsonArray("slides")[0].asJsonObject.add(
      "images",
      JsonArray().apply {
        add(imageSource("first"))
        add(imageSource("second"))
      },
    )
    generator.validatePayload(payload)
  }

  private fun imageSource(id: String) = JsonObject().apply {
    addProperty("source_tree_uri", "content://provider/tree/root")
    addProperty("source_document_id", id)
    addProperty("alt_text", "Slide illustration")
  }

  @Test fun validatesBoundedSlidesAndText() {
    generator.validatePayload(request().payload)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsUnknownSlideFields() {
    generator.validatePayload(request().payload.deepCopy().apply { addProperty("macro", "execute") })
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsNonXmlCharacters() {
    generator.validatePayload(JsonObject().apply {
      add("slides", JsonArray().apply {
        add(JsonObject().apply {
          addProperty("title", "Bad\u0001title")
        })
      })
    })
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsMoreThanFiftySlides() {
    generator.validatePayload(JsonObject().apply {
      add("slides", JsonArray().apply {
        repeat(51) { add(JsonObject().apply { addProperty("title", "Slide") }) }
      })
    })
  }

  private fun request() = ArtifactCreateRequest(
    ArtifactFormat.PPTX,
    "strategy.pptx",
    ArtifactDestinationSpec.SafTree("content://provider/tree/root", null),
    false,
    JsonObject().apply {
      add("slides", JsonArray().apply {
        add(JsonObject().apply {
          addProperty("title", "Strategy & Delivery")
          addProperty("subtitle", "Quarterly overview")
          add("paragraphs", JsonArray().apply { add("An editable presentation slide.") })
          add("bullet_items", JsonArray().apply { add("First milestone"); add("Second milestone") })
        })
        add(JsonObject().apply {
          addProperty("title", "Next steps")
          add("paragraphs", JsonArray().apply { add("Keep the work bounded and measurable.") })
        })
      })
    },
  )
}
