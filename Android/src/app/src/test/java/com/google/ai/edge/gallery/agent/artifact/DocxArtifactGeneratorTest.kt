package com.google.ai.edge.gallery.agent.artifact

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocxArtifactGeneratorTest {
  private val generator = DocxArtifactGenerator()

  @Test fun createsEditableOoxmlPackageWithDocumentStylesHeaderAndFooter() {
    val output = ByteArrayOutputStream()
    val mime = generator.write(request(), output)
    val entries = linkedMapOf<String, String>()
    ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
      }
    }
    assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", mime)
    assertTrue(entries.containsKey("[Content_Types].xml"))
    assertTrue(entries.containsKey("_rels/.rels"))
    assertTrue(entries.containsKey("word/document.xml"))
    assertTrue(entries.containsKey("word/styles.xml"))
    assertTrue(entries.containsKey("word/header1.xml"))
    assertTrue(entries.containsKey("word/footer1.xml"))
    assertTrue(entries.getValue("word/document.xml").contains("<w:tbl>"))
    assertTrue(entries.getValue("word/document.xml").contains("Heading1"))
    assertTrue(entries.getValue("word/document.xml").contains("A &amp; B"))
    assertTrue(entries.getValue("word/_rels/document.xml.rels").contains("rIdHeader"))
    assertTrue(output.toByteArray().copyOfRange(0, 2).contentEquals(byteArrayOf(0x50, 0x4b)))
  }

  @Test fun validatesBoundedStructuredContent() {
    generator.validatePayload(request().payload)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsUnknownExecutablePayloadFields() {
    val payload = request().payload.deepCopy().apply { addProperty("macro", "must not run") }
    generator.validatePayload(payload)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsXmlControlCharacters() {
    val payload = request().payload.deepCopy().apply {
      add("sections", JsonArray().apply {
        add(JsonObject().apply {
          add("paragraphs", JsonArray().apply { add("bad\u0001text") })
        })
      })
    }
    generator.validatePayload(payload)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsMoreThanEightTableColumns() {
    val payload = request().payload.deepCopy().apply {
      add("sections", JsonArray().apply {
        add(JsonObject().apply {
          add("table", JsonArray().apply {
            add(JsonArray().apply { repeat(9) { add("cell") } })
          })
        })
      })
    }
    generator.validatePayload(payload)
  }

  private fun request() = ArtifactCreateRequest(
    ArtifactFormat.DOCX,
    "report.docx",
    ArtifactDestinationSpec.SafTree("content://provider/tree/root", null),
    false,
    JsonObject().apply {
      addProperty("title", "A & B")
      addProperty("header", "Confidential draft")
      addProperty("footer", "Prepared locally")
      add("sections", JsonArray().apply {
        add(JsonObject().apply {
          addProperty("heading", "Overview")
          add("paragraphs", JsonArray().apply { add("Editable report paragraph.") })
          add("bullet_items", JsonArray().apply { add("First item") })
          add("table", JsonArray().apply {
            add(JsonArray().apply { add("Name"); add("Status") })
            add(JsonArray().apply { add("Engine"); add("In progress") })
          })
        })
      })
    },
  )
}
