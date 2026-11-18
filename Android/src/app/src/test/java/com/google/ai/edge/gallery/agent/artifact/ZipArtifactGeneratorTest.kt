package com.google.ai.edge.gallery.agent.artifact

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipArtifactGeneratorTest {
  private val generator = ZipArtifactGenerator()

  @Test fun createsBoundedZipWithNestedTextEntries() {
    val output = ByteArrayOutputStream()
    val mime = generator.write(request(), output)
    val entries = linkedMapOf<String, String>()
    ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
      }
    }
    assertEquals("application/zip", mime)
    assertEquals("Project notes", entries["docs/notes.md"])
    assertEquals("name,value\nalpha,1", entries["data/summary.csv"])
    assertTrue(output.toByteArray().copyOfRange(0, 2).contentEquals(byteArrayOf(0x50, 0x4b)))
  }

  @Test fun validatesStructuredTextEntries() {
    generator.validatePayload(request().payload)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsAbsoluteAndTraversalPaths() {
    generator.validatePayload(payload("/storage/emulated/0/secret.txt"))
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsBackslashAndDrivePaths() {
    generator.validatePayload(payload("C:\\temp\\file.txt"))
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsCaseInsensitiveDuplicatePaths() {
    generator.validatePayload(JsonObject().apply {
      add("entries", JsonArray().apply {
        add(entry("Readme.txt", "one"))
        add(entry("README.TXT", "two"))
      })
    })
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsFileAndDirectoryPathCollisions() {
    generator.validatePayload(JsonObject().apply {
      add("entries", JsonArray().apply {
        add(entry("docs", "file"))
        add(entry("docs/readme.txt", "child"))
      })
    })
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsReservedWindowsNames() {
    generator.validatePayload(payload("folder/CON.txt"))
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsTooManyEntries() {
    generator.validatePayload(JsonObject().apply {
      add("entries", JsonArray().apply {
        repeat(101) { add(entry("file$it.txt", "x")) }
      })
    })
  }

  private fun request() = ArtifactCreateRequest(
    ArtifactFormat.ZIP,
    "bundle.zip",
    ArtifactDestinationSpec.SafTree("content://provider/tree/root", null),
    false,
    JsonObject().apply {
      add("entries", JsonArray().apply {
        add(entry("docs/notes.md", "Project notes"))
        add(entry("data/summary.csv", "name,value\nalpha,1"))
      })
    },
  )

  private fun payload(path: String) = JsonObject().apply {
    add("entries", JsonArray().apply { add(entry(path, "content")) })
  }

  private fun entry(path: String, content: String) = JsonObject().apply {
    addProperty("path", path)
    addProperty("content", content)
  }
}
