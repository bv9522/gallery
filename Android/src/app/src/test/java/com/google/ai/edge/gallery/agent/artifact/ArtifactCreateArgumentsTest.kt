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
