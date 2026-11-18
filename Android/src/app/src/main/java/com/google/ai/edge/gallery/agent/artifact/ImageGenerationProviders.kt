/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.artifact

import android.net.Uri
import java.io.OutputStream

/** Whether image generation keeps all source data on-device or transmits it to a remote provider. */
enum class ImageGenerationDataBoundary { ON_DEVICE, REMOTE }

data class ImageGenerationRequest(
  val prompt: String,
  val outputMimeType: String,
  val sourceImages: List<Uri> = emptyList(),
)

data class RemoteImageGenerationConsentDetails(
  val providerId: String,
  val prompt: String,
  val outputMimeType: String,
  val sourceImageCount: Int,
)

fun interface RemoteImageGenerationConsentRequester {
  /** Must show provider identity and disclose that prompt/source images leave the device. */
  suspend fun requestConsent(details: RemoteImageGenerationConsentDetails): Boolean
}

/** Optional provider contract. No provider is registered by the local-only baseline. */
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
    val provider = providers.find(providerId)
      ?: throw IllegalArgumentException("Image generation provider '$providerId' is not configured.")
    if (provider.dataBoundary == ImageGenerationDataBoundary.REMOTE) {
      val approved = remoteConsent.requestConsent(
        RemoteImageGenerationConsentDetails(
          providerId = provider.id,
          prompt = request.prompt,
          outputMimeType = request.outputMimeType,
          sourceImageCount = request.sourceImages.size,
        ),
      )
      if (!approved) throw SecurityException("Remote image generation was not approved; no data was sent.")
    }
    return provider.generate(request, output)
  }
}
