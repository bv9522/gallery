package com.google.ai.edge.gallery.agent.artifact

import com.google.ai.edge.gallery.agent.coordinator.ShellExecutionBackend
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactGenerationEngineTest {
  @Test fun approvalDenialPreventsDestinationWrite() = runBlocking {
    var writes = 0
    val engine = engine(approved = false, onWrite = { writes++ })
    val result = engine.create(request())
    assertFalse(result.isSuccessful)
    assertEquals(0, writes)
  }

  @Test fun approvedWriteReturnsTypedSizeAndDigest() = runBlocking {
    val engine = engine(approved = true)
    val result = engine.create(request())
    assertTrue(result.isSuccessful)
    assertEquals("report.pdf", result.fileName)
    assertEquals("application/pdf", result.mimeType)
    assertEquals(7L, result.bytesWritten)
    assertEquals(64, result.sha256?.length)
  }

  @Test(expected = IllegalArgumentException::class)
  fun duplicateFormatOwnersAreRejected() {
    ArtifactGeneratorRegistry(listOf(FakeGenerator(), FakeGenerator()))
  }

  private fun engine(approved: Boolean, onWrite: () -> Unit = {}): DefaultArtifactGenerationEngine {
    val destination = object : ArtifactDestinationAdapter {
      override val destinationType = DestinationType.SAF
      override suspend fun describe(request: ArtifactCreateRequest) = "Granted Documents folder"
      override suspend fun write(
        request: ArtifactCreateRequest,
        producer: ArtifactOutputProducer,
      ): ArtifactDestinationWriteResult {
        onWrite()
        val bytes = ByteArrayOutputStream()
        val mimeType = producer.write(bytes)
        return ArtifactDestinationWriteResult(
          "Granted Documents folder",
          ArtifactOutputMetadata(mimeType, bytes.size().toLong(), "0".repeat(64)),
        )
      }
    }
    return DefaultArtifactGenerationEngine(
      ArtifactGeneratorRegistry(listOf(FakeGenerator())),
      ArtifactDestinationRegistry(listOf(destination)),
      ArtifactGenerationApprovalRequester { approved },
    )
  }

  private fun request() = ArtifactCreateRequest(
    ArtifactFormat.PDF,
    "report.pdf",
    ArtifactDestinationSpec.SafTree("content://provider/tree/root", null),
    false,
    JsonObject(),
  )

  private class FakeGenerator : ArtifactGenerator {
    override val format = ArtifactFormat.PDF
    override val maxOutputBytes = 1_024L
    override fun write(request: ArtifactCreateRequest, output: java.io.OutputStream): String {
      output.write("pdfdata".toByteArray())
      return "application/pdf"
    }
  }
}
