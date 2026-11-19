package com.google.ai.edge.gallery.agent.artifact

import android.net.Uri
import com.google.ai.edge.gallery.tools.AskRemoteImageGenerationPermissionAction
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationProvidersTest {
  @Test fun channelConsentRequesterShowsExactRemoteDisclosureAndWaitsForOneTimeDecision(): Unit = runBlocking<Unit> {
    val channel = Channel<com.google.ai.edge.gallery.tools.ToolAction>(Channel.UNLIMITED)
    val requester = ChannelRemoteImageGenerationConsentRequester(channel)
    val pending = async {
      requester.requestConsent(
        RemoteImageGenerationConsentDetails(
          providerId = "firebase-gemini-image",
          prompt = "Create a blue mountain landscape",
          outputMimeType = "image/png",
          sourceImageCount = 1,
          sourceImageLabels = listOf("Private family photo"),
        ),
      )
    }
    val action = channel.receive() as AskRemoteImageGenerationPermissionAction
    assertEquals("firebase-gemini-image", action.providerId)
    assertEquals("Create a blue mountain landscape", action.prompt)
    assertEquals("image/png", action.outputMimeType)
    assertEquals(listOf("Private family photo"), action.sourceImageLabels)
    action.result.complete(true)
    assertTrue(pending.await())
    channel.close()
  }

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

  @Test fun remoteProviderRejectsSourceLabelCountMismatch() = runBlocking {
    var generated = false
    val provider = FakeProvider(ImageGenerationDataBoundary.REMOTE) { generated = true; "image/png" }
    val dispatcher = GuardedImageGenerationDispatcher(
      ImageGenerationProviderRegistry(listOf(provider)),
      RemoteImageGenerationConsentRequester { true },
    )
    try {
      dispatcher.generate(
        "fake",
        ImageGenerationRequest("make a diagram", "image/png", sourceImageLabels = listOf("Reference photo")),
        ByteArrayOutputStream(),
      )
      throw AssertionError("Expected source labels without matching source images to be rejected.")
    } catch (_: IllegalArgumentException) {
      assertFalse(generated)
    }
  }

  @Test fun onDeviceProviderDoesNotRequestRemoteConsent() = runBlocking {
    var consentRequests = 0
    val provider = FakeProvider(ImageGenerationDataBoundary.ON_DEVICE) { "image/webp" }
    val dispatcher = GuardedImageGenerationDispatcher(
      ImageGenerationProviderRegistry(listOf(provider)),
      RemoteImageGenerationConsentRequester { consentRequests++; false },
    )
    assertEquals("image/webp", dispatcher.generate(
      "fake", ImageGenerationRequest("draw locally", "image/webp"),
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
