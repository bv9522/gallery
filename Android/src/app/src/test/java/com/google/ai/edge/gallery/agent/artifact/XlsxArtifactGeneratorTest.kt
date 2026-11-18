package com.google.ai.edge.gallery.agent.artifact

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.xml.sax.InputSource
import java.io.StringReader
import org.junit.Test

class XlsxArtifactGeneratorTest {
  private val generator = XlsxArtifactGenerator()

  @Test fun createsEditableWorkbookWithTypedCellsStylesAndFilter() {
    val output = ByteArrayOutputStream()
    val mime = generator.write(request(), output)
    val entries = linkedMapOf<String, String>()
    ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
      }
    }
    assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", mime)
    assertTrue(entries.containsKey("[Content_Types].xml"))
    assertTrue(entries.containsKey("_rels/.rels"))
    assertTrue(entries.containsKey("xl/workbook.xml"))
    assertTrue(entries.containsKey("xl/styles.xml"))
    assertTrue(entries.containsKey("xl/worksheets/sheet1.xml"))
    val sheet = entries.getValue("xl/worksheets/sheet1.xml")
    assertTrue(sheet.contains("t=\"inlineStr\""))
    assertTrue(sheet.contains("<v>42</v>"))
    assertTrue(sheet.contains("t=\"b\""))
    assertTrue(sheet.contains("<autoFilter ref=\"A1:C2\"/>"))
    assertTrue(sheet.contains("=1+1"))
    assertFalse(sheet.contains("<f>"))
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

  @Test fun acceptsBoundedWorkbookPayload() {
    generator.validatePayload(request().payload)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsInvalidWorksheetNames() {
    generator.validatePayload(JsonObject().apply {
      add("sheets", JsonArray().apply {
        add(JsonObject().apply {
          addProperty("name", "bad/name")
          add("rows", JsonArray().apply { add(JsonArray().apply { add("x") }) })
        })
      })
    })
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsDuplicateWorksheetNamesIgnoringCase() {
    generator.validatePayload(JsonObject().apply {
      add("sheets", JsonArray().apply {
        add(sheet("Summary"))
        add(sheet("summary"))
      })
    })
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsExcessiveNumericPrecision() {
    val payload = request().payload.deepCopy()
    payload.getAsJsonArray("sheets").get(0).asJsonObject
      .getAsJsonArray("rows").get(1).asJsonArray.set(1, com.google.gson.JsonPrimitive(12345678901234567L))
    generator.validatePayload(payload)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsUnknownFormulaOrMacroFields() {
    val payload = request().payload.deepCopy().apply { addProperty("macro", "execute") }
    generator.validatePayload(payload)
  }

  private fun request() = ArtifactCreateRequest(
    ArtifactFormat.XLSX,
    "summary.xlsx",
    ArtifactDestinationSpec.SafTree("content://provider/tree/root", null),
    false,
    JsonObject().apply {
      add("sheets", JsonArray().apply {
        add(JsonObject().apply {
          addProperty("name", "Summary")
          addProperty("header_row", true)
          addProperty("auto_filter", true)
          add("column_widths", JsonArray().apply { add(18); add(14); add(20) })
          add("rows", JsonArray().apply {
            add(JsonArray().apply { add("Item"); add("Count"); add("Enabled") })
            add(JsonArray().apply {
              add("=1+1")
              add(42)
              add(true)
            })
          })
        })
      })
    },
  )

  private fun sheet(name: String) = JsonObject().apply {
    addProperty("name", name)
    add("rows", JsonArray().apply { add(JsonArray().apply { add("x") }) })
  }
}
