package com.google.ai.edge.gallery.agent.artifact

import com.google.ai.edge.gallery.agent.coordinator.ShellExecutionBackend
import com.google.ai.edge.gallery.agent.coordinator.ShellExecutionRequest
import com.google.ai.edge.gallery.agent.coordinator.ShellExecutionResult
import com.google.ai.edge.gallery.agent.coordinator.ShellExecutionRouter
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellArtifactDestinationAdapterTest {
  @Test fun describesExactSelectedBackendAndPath() = runBlocking {
    val router = ShellExecutionRouter(
      mapOf(
        ShellExecutionBackend.TERMUX to ShellExecutionTransport { request ->
          ShellExecutionResult(request.backend, "READY", "", 0)
        },
      ),
    )
    val adapter = ShellArtifactDestinationAdapter(
      router,
      mapOf(ShellExecutionBackend.TERMUX to FakeBinaryTransport()),
    )
    val request = shellRequest(ShellExecutionBackend.TERMUX, "/storage/emulated/0/Documents/report.pdf")
    assertEquals(
      "TERMUX shell path '/storage/emulated/0/Documents/report.pdf'",
      adapter.describe(request),
    )
  }

  @Test fun doesNotFallbackWhenRequestedBackendIsNotRegistered() = runBlocking {
    var invoked = false
    val router = ShellExecutionRouter(
      mapOf(
        ShellExecutionBackend.TERMUX to ShellExecutionTransport { request ->
          invoked = true
          ShellExecutionResult(request.backend, "READY", "", 0)
        },
      ),
    )
    val adapter = ShellArtifactDestinationAdapter(
      router,
      mapOf(ShellExecutionBackend.TERMUX to FakeBinaryTransport()),
    )
    try {
      adapter.describe(shellRequest(ShellExecutionBackend.TERMUX_RISH, "/storage/emulated/0/Documents/report.pdf"))
      throw AssertionError("Expected unregistered Rish backend to be rejected.")
    } catch (_: IllegalArgumentException) {
      assertTrue(!invoked)
    }
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsOverwriteRequests() = runBlocking {
    val router = ShellExecutionRouter(
      mapOf(
        ShellExecutionBackend.SHIZUKU to ShellExecutionTransport { request ->
          ShellExecutionResult(request.backend, "READY", "", 0)
        },
      ),
    )
    val adapter = ShellArtifactDestinationAdapter(
      router,
      mapOf(ShellExecutionBackend.SHIZUKU to FakeBinaryTransport()),
    )
    adapter.describe(shellRequest(ShellExecutionBackend.SHIZUKU, "/storage/emulated/0/Documents/report.pdf", overwrite = true))
  }

  private fun shellRequest(
    backend: ShellExecutionBackend,
    path: String,
    overwrite: Boolean = false,
  ) = ArtifactCreateRequest(
    ArtifactFormat.PDF,
    "report.pdf",
    ArtifactDestinationSpec.ShellPath(backend, path),
    overwrite,
    JsonObject(),
  )

  private class FakeBinaryTransport : ShellBinaryArtifactTransport {
    override suspend fun write(
      request: ArtifactCreateRequest,
      producer: ArtifactOutputProducer,
    ) = ArtifactDestinationWriteResult(
      "fake",
      ArtifactOutputMetadata("application/pdf", 5, "0".repeat(64)),
    )
  }
}
