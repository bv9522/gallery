/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.artifact

import android.net.Uri
import com.google.ai.edge.gallery.tools.AskRemoteImageGenerationPermissionAction
import com.google.ai.edge.gallery.tools.ToolAction
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withTimeoutOrNull

/** Whether image generation keeps all source data on-device or transmits it to a remote provider. */
enum class ImageGenerationDataBoundary { ON_DEVICE, REMOTE }

data class ImageGenerationRequest(
  val prompt: String,
  val outputMimeType: String,
  val sourceImages: List<Uri> = emptyList(),
  val sourceImageLabels: List<String> = emptyList(),
)

data class RemoteImageGenerationConsentDetails(
  val providerId: String,
  val prompt: String,
  val outputMimeType: String,
  val sourceImageCount: Int,
  val sourceImageLabels: List<String>,
)

fun interface RemoteImageGenerationConsentRequester {
  /** Must show provider identity and disclose that prompt/source images leave the device. */
  suspend fun requestConsent(details: RemoteImageGenerationConsentDetails): Boolean
}

/** Bridges the remote data-disclosure request to the chat UI; every request is one-time. */
class ChannelRemoteImageGenerationConsentRequester(
  private val actionChannel: SendChannel<ToolAction>,
  private val timeoutMs: Long = DEFAULT_CONSENT_TIMEOUT_MS,
) : RemoteImageGenerationConsentRequester {
  override suspend fun requestConsent(details: RemoteImageGenerationConsentDetails): Boolean {
    val action = AskRemoteImageGenerationPermissionAction(
      providerId = details.providerId,
      prompt = details.prompt,
      outputMimeType = details.outputMimeType,
      sourceImageLabels = details.sourceImageLabels,
    )
    return try {
      actionChannel.send(action)
      withTimeoutOrNull(timeoutMs) { action.result.await() } ?: false
    } catch (cancelled: CancellationException) {
      throw cancelled
    } finally {
      if (!action.result.isCompleted) action.result.complete(false)
    }
  }

  private companion object {
    const val DEFAULT_CONSENT_TIMEOUT_MS = 120_000L
  }
}

/** Optional provider contract; concrete providers are registered only when their runtime is configured. */
interface ImageGenerationProvider {
  val id: String
  val dataBoundary: ImageGenerationDataBoundary
  suspend fun generate(request: ImageGenerationRequest, output: OutputStream): String
}

class ImageGenerationProviderRegistry(providers: List<ImageGenerationProvider>) {
  private val byId = providers.associateBy { it.id }
  init {
    require(providers.all { it.id.isNotBlank() }) { "Image generation provider IDs must not be blank." }
    require(byId.size == providers.size) { "Image generation provider IDs must be unique." }
  }
  fun find(id: String): ImageGenerationProvider? = byId[id]
  fun availableProviderIds(): Set<String> = byId.keys
}

/**
 * Centralizes the remote-consent gate so a future provider adapter cannot silently upload prompts
 * or source images. Local providers do not invoke the remote-consent requester.
 */
class GuardedImageGenerationDispatcher(
  private val providers: ImageGenerationProviderRegistry,
  private val remoteConsent: RemoteImageGenerationConsentRequester,
) {
  suspend fun generate(
    providerId: String,
    request: ImageGenerationRequest,
    output: OutputStream,
  ): String {
    require(request.prompt.isNotBlank() && request.prompt.length <= MAX_PROMPT_CHARS) {
      "Image generation prompt must contain 1–$MAX_PROMPT_CHARS characters."
    }
    require(request.outputMimeType in SUPPORTED_OUTPUT_MIME_TYPES) { "Unsupported generated image MIME type." }
    require(request.sourceImages.size <= MAX_SOURCE_IMAGES) { "Too many source images were supplied." }
    require(request.sourceImageLabels.isEmpty() || request.sourceImageLabels.size == request.sourceImages.size) {
      "Source image labels must match the source image list."
    }
    val provider = providers.find(providerId)
      ?: throw IllegalArgumentException("Image generation provider '$providerId' is not configured.")
    if (provider.dataBoundary == ImageGenerationDataBoundary.REMOTE) {
      require(request.sourceImages.isEmpty() ||
        (request.sourceImageLabels.size == request.sourceImages.size && request.sourceImageLabels.all { it.isNotBlank() })) {
        "Remote image generation requires a visible label for every source image before consent can be requested."
      }
      val approved = remoteConsent.requestConsent(
        RemoteImageGenerationConsentDetails(
          providerId = provider.id,
          prompt = request.prompt,
          outputMimeType = request.outputMimeType,
          sourceImageCount = request.sourceImages.size,
          sourceImageLabels = request.sourceImageLabels,
        ),
      )
      if (!approved) throw SecurityException("Remote image generation was not approved; no data was sent.")
    }
    return provider.generate(request, output)
  }

  private companion object {
    const val MAX_PROMPT_CHARS = 10_000
    const val MAX_SOURCE_IMAGES = 8
    val SUPPORTED_OUTPUT_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp")
  }
}
