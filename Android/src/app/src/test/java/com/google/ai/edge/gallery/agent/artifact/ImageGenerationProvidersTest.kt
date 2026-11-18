package com.google.ai.edge.gallery.agent.artifact

import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationProvidersTest {
  @Test fun remoteProviderIsNotCalledWhenConsentIsDenied() = runBlocking {
    var generated = false
    val provider = FakeProvider(ImageGenerationDataBoundary.REMOTE) { generated = true; "image/png" }
    val dispatcher = GuardedImageGenerationDispatcher(
      ImageGenerationProviderRegistry(listOf(provider)),
      RemoteImageGenerationConsentRequester { false },
    )
    try {
      dispatcher.generate("fake", ImageGenerationRequest("make a diagram", "image/png"), ByteArrayOutputStream())
      throw AssertionError("Expected remote generation to be denied.")
    } catch (_: SecurityException) {
      assertFalse(generated)
    }
  }

  @Test fun remoteProviderRunsOnlyAfterConsent() = runBlocking {
    var consentRequests = 0
    var generated = false
    val provider = FakeProvider(ImageGenerationDataBoundary.REMOTE) { generated = true; "image/png" }
    val dispatcher = GuardedImageGenerationDispatcher(
      ImageGenerationProviderRegistry(listOf(provider)),
      RemoteImageGenerationConsentRequester { details ->
        consentRequests++
        assertEquals("fake", details.providerId)
        assertEquals("make a diagram", details.prompt)
        assertEquals(0, details.sourceImageCount)
        true
      },
    )
    assertEquals("image/png", dispatcher.generate(
      "fake", ImageGenerationRequest("make a diagram", "image/png"), ByteArrayOutputStream()
    ))
    assertEquals(1, consentRequests)
    assertTrue(generated)
  }

  @Test fun onDeviceProviderDoesNotRequestRemoteConsent() = runBlocking {
    var consentRequests = 0
    val provider = FakeProvider(ImageGenerationDataBoundary.ON_DEVICE) { "image/webp" }
    val dispatcher = GuardedImageGenerationDispatcher(
      ImageGenerationProviderRegistry(listOf(provider)),
      RemoteImageGenerationConsentRequester { consentRequests++; false },
    )
    assertEquals("image/webp", dispatcher.generate(
      "fake", ImageGenerationRequest("draw locally", "image/webp", listOf(Uri.parse("content://local/image"))),
      ByteArrayOutputStream()
    ))
    assertEquals(0, consentRequests)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsDuplicateProviderIds() {
    ImageGenerationProviderRegistry(
      listOf(
        FakeProvider(ImageGenerationDataBoundary.ON_DEVICE) { "image/png" },
        FakeProvider(ImageGenerationDataBoundary.REMOTE) { "image/jpeg" },
      )
    )
  }

  private class FakeProvider(
    override val dataBoundary: ImageGenerationDataBoundary,
    private val action: () -> String,
  ) : ImageGenerationProvider {
    override val id = "fake"
    override suspend fun generate(request: ImageGenerationRequest, output: java.io.OutputStream): String = action()
  }
}
