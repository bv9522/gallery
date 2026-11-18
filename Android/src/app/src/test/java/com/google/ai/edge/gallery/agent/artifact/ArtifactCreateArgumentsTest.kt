package com.google.ai.edge.gallery.agent.artifact

import com.google.ai.edge.gallery.agent.coordinator.ShellExecutionBackend
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactCreateArgumentsTest {
  @Test fun parsesSafPdfRequest() {
    val args = JsonObject().apply {
      addProperty("format", "pdf")
      addProperty("file_name", "report.pdf")
      addProperty("destination_type", "saf")
      addProperty("tree_uri", "content://provider/tree/root")
      add("payload", JsonObject().apply { addProperty("title", "Report") })
    }
    val parsed = ArtifactCreateArguments.parse(args)
    assertTrue(parsed is ArtifactArgumentsParseResult.Valid)
    assertFalse((parsed as ArtifactArgumentsParseResult.Valid).request.overwrite)
  }

  @Test fun parsesExplicitShellBackendAndPath() {
    val args = JsonObject().apply {
      addProperty("format", "zip")
      addProperty("file_name", "bundle.zip")
      addProperty("destination_type", "shell")
      addProperty("backend", "SHIZUKU")
      addProperty("path", "/storage/emulated/0/Documents/bundle.zip")
      addProperty("overwrite", true)
      add("payload", JsonObject())
    }
    val parsed = ArtifactCreateArguments.parse(args) as ArtifactArgumentsParseResult.Valid
    val destination = parsed.request.destination as ArtifactDestinationSpec.ShellPath
    assertEquals(ShellExecutionBackend.SHIZUKU, destination.backend)
    assertTrue(parsed.request.overwrite)
  }

  @Test fun acceptsConcreteImageMimeTypesAndSignatures() {
    assertTrue(ArtifactFormat.IMAGE.acceptsFileName("photo.png"))
    assertTrue(ArtifactFormat.IMAGE.acceptsFileName("photo.jpeg"))
    assertTrue(ArtifactFormat.IMAGE.acceptsMimeType("image/webp"))
    assertTrue(ArtifactFormat.IMAGE.acceptsSignature(
      byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    ))
    assertFalse(ArtifactFormat.IMAGE.acceptsMimeType("application/octet-stream"))
  }

  @Test fun validatesPdfMimeAndBinarySignature() {
    assertTrue(ArtifactFormat.PDF.acceptsMimeType("application/pdf"))
    assertTrue(ArtifactFormat.PDF.acceptsSignature("%PDF-1.7".toByteArray()))
    assertFalse(ArtifactFormat.PDF.acceptsSignature("not a pdf".toByteArray()))
  }

  @Test fun rejectsMixedDestinationIdentities() {
    val args = JsonObject().apply {
      addProperty("format", "pdf")
      addProperty("file_name", "report.pdf")
      addProperty("destination_type", "saf")
      addProperty("tree_uri", "content://provider/tree/root")
      addProperty("backend", "TERMUX")
      addProperty("path", "/tmp/report.pdf")
      add("payload", JsonObject())
    }
    assertTrue(ArtifactCreateArguments.parse(args) is ArtifactArgumentsParseResult.Invalid)
  }

  @Test fun rejectsShellPathsWithControlCharactersOrRepeatedSeparators() {
    val control = shellRequest("/storage/emulated/0/Documents/bad\nname.pdf")
    val repeated = shellRequest("/storage//emulated/0/Documents/report.pdf")
    val backslash = shellRequest("/storage/emulated/0/Documents\\\\report.pdf")
    assertTrue(ArtifactCreateArguments.parse(control) is ArtifactArgumentsParseResult.Invalid)
    assertTrue(ArtifactCreateArguments.parse(repeated) is ArtifactArgumentsParseResult.Invalid)
    assertTrue(ArtifactCreateArguments.parse(backslash) is ArtifactArgumentsParseResult.Invalid)
  }

  @Test fun rejectsPathTraversalAndMismatchedLeafName() {
    val traversal = shellRequest("/storage/emulated/0/../report.pdf")
    val mismatched = shellRequest("/storage/emulated/0/other.pdf")
    assertTrue(ArtifactCreateArguments.parse(traversal) is ArtifactArgumentsParseResult.Invalid)
    assertTrue(ArtifactCreateArguments.parse(mismatched) is ArtifactArgumentsParseResult.Invalid)
  }

  @Test fun rejectsWrongExtensionAndUnknownArguments() {
    val wrongExtension = safRequest("report.docx")
    val unknown = safRequest("report.pdf").apply { addProperty("raw_uri", "content://private") }
    assertTrue(ArtifactCreateArguments.parse(wrongExtension) is ArtifactArgumentsParseResult.Invalid)
    assertTrue(ArtifactCreateArguments.parse(unknown) is ArtifactArgumentsParseResult.Invalid)
  }

  private fun safRequest(name: String) = JsonObject().apply {
    addProperty("format", "pdf")
    addProperty("file_name", name)
    addProperty("destination_type", "saf")
    addProperty("tree_uri", "content://provider/tree/root")
    add("payload", JsonObject())
  }

  private fun shellRequest(path: String) = JsonObject().apply {
    addProperty("format", "pdf")
    addProperty("file_name", "report.pdf")
    addProperty("destination_type", "shell")
    addProperty("backend", "TERMUX")
    addProperty("path", path)
    add("payload", JsonObject())
  }
}
