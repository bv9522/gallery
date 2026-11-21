/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.artifact

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.DocumentsContract
import androidx.exifinterface.media.ExifInterface
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.ImagePart
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream

/**
 * Optional remote image provider backed by Gemini 3.1 Flash Image through Firebase AI Logic.
 * The dispatcher must obtain one-time UI consent before this provider is called.
 */
class FirebaseAiLogicImageGenerationProvider(context: Context) : ImageGenerationProvider {
  private val appContext = context.applicationContext
  private val resolver = appContext.contentResolver

  override val id = PROVIDER_ID
  override val dataBoundary = ImageGenerationDataBoundary.REMOTE

  override suspend fun generate(request: ImageGenerationRequest, output: OutputStream): String {
    require(request.outputMimeType in SUPPORTED_OUTPUT_MIME_TYPES) {
      "Firebase image generation supports PNG, JPEG, or WebP output."
    }
    require(request.sourceImages.size <= MAX_SOURCE_IMAGES) {
      "Firebase image generation accepts at most $MAX_SOURCE_IMAGES source images."
    }
    require(request.sourceImageLabels.size == request.sourceImages.size) {
      "Every uploaded source image must have a visible consent label."
    }

    var totalSourceBytes = 0L
    val sourceBitmaps = mutableListOf<Bitmap>()
    try {
      request.sourceImages.forEach { uri ->
        val bytes = readGrantedSource(uri, MAX_SOURCE_BYTES_PER_IMAGE)
        totalSourceBytes += bytes.size
        require(totalSourceBytes <= MAX_TOTAL_SOURCE_BYTES) {
          "Remote source images exceed the $MAX_TOTAL_SOURCE_BYTES byte combined upload limit."
        }
        sourceBitmaps += decodeNormalized(bytes)
      }

      val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
        modelName = MODEL_NAME,
        generationConfig = generationConfig {
          responseModalities = listOf(ResponseModality.IMAGE)
        },
      )
      val prompt = content {
        sourceBitmaps.forEach { image(it) }
        text("Generate an image that follows this request: " + request.prompt)
      }
      val response = model.generateContent(prompt)
      val generated = response.candidates.firstOrNull()?.content?.parts
        ?.filterIsInstance<ImagePart>()?.firstOrNull()?.image
        ?: throw IllegalStateException("The remote provider returned no image. No artifact was written.")

      try {
        val format = when (request.outputMimeType) {
          "image/png" -> Bitmap.CompressFormat.PNG
          "image/jpeg" -> Bitmap.CompressFormat.JPEG
          "image/webp" -> Bitmap.CompressFormat.WEBP_LOSSY
          else -> error("Unsupported generated image format.")
        }
        val bounded = BoundedImageOutputStream(output, MAX_OUTPUT_BYTES)
        require(generated.compress(format, OUTPUT_QUALITY, bounded)) {
          "Android could not encode the generated image."
        }
        bounded.flush()
      } finally {
        generated.recycle()
      }
      return request.outputMimeType
    } finally {
      sourceBitmaps.forEach { if (!it.isRecycled) it.recycle() }
    }
  }

  private fun readGrantedSource(uri: Uri, maxBytes: Long): ByteArray {
    require(uri.scheme == "content" && uri.authority != null) {
      "Remote source images must be SAF content URIs."
    }
    val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }
      .getOrElse { throw SecurityException("The source image is not inside a persisted SAF tree.") }
    val documentId = DocumentsContract.getDocumentId(uri)
    require(treeId != documentId) { "Select an image file inside the granted SAF tree." }
    val tree = DocumentsContract.buildTreeDocumentUri(uri.authority!!, treeId)
    val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == tree }
      ?: throw SecurityException("Gallery has no persisted read grant for a remote source image.")
    require(grant.isReadPermission) { "The source image grant does not include read access." }
    val root = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
    require(DocumentsContract.isChildDocument(resolver, root, uri)) {
      "The remote source image is outside the selected SAF tree."
    }
    val output = ByteArrayOutputStream()
    resolver.openInputStream(uri)?.use { input ->
      val buffer = ByteArray(16 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        require(output.size().toLong() + read <= maxBytes) {
          "A remote source image exceeds the $maxBytes byte limit."
        }
        output.write(buffer, 0, read)
      }
    } ?: throw IllegalStateException("Could not open a remote source image.")
    return output.toByteArray()
  }

  private fun decodeNormalized(bytes: ByteArray): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth in 1..MAX_SOURCE_DIMENSION && bounds.outHeight in 1..MAX_SOURCE_DIMENSION &&
      bounds.outWidth.toLong() * bounds.outHeight <= MAX_SOURCE_PIXELS) {
      "A remote source image exceeds the supported dimensions."
    }
    val orientation = ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
      ExifInterface.TAG_ORIENTATION,
      ExifInterface.ORIENTATION_NORMAL,
    )
    var sample = 1
    while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > MAX_DECODE_PIXELS) {
      sample *= 2
    }
    val decoded = BitmapFactory.decodeByteArray(
      bytes,
      0,
      bytes.size,
      BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
      },
    ) ?: throw IllegalArgumentException("Android could not decode a remote source image.")
    val matrix = orientationMatrix(orientation, decoded.width, decoded.height)
    if (matrix.isIdentity) return decoded
    return try {
      Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        .also { if (it !== decoded) decoded.recycle() }
    } catch (error: Throwable) {
      decoded.recycle()
      throw error
    }
  }

  private fun orientationMatrix(orientation: Int, width: Int, height: Int) = Matrix().apply {
    val values = when (orientation) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
        floatArrayOf(-1f, 0f, width.toFloat(), 0f, 1f, 0f, 0f, 0f, 1f)
      ExifInterface.ORIENTATION_ROTATE_180 ->
        floatArrayOf(-1f, 0f, width.toFloat(), 0f, -1f, height.toFloat(), 0f, 0f, 1f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL ->
        floatArrayOf(1f, 0f, 0f, 0f, -1f, height.toFloat(), 0f, 0f, 1f)
      ExifInterface.ORIENTATION_TRANSPOSE ->
        floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
      ExifInterface.ORIENTATION_ROTATE_90 ->
        floatArrayOf(0f, -1f, height.toFloat(), 1f, 0f, 0f, 0f, 0f, 1f)
      ExifInterface.ORIENTATION_TRANSVERSE ->
        floatArrayOf(0f, -1f, height.toFloat(), -1f, 0f, width.toFloat(), 0f, 0f, 1f)
      ExifInterface.ORIENTATION_ROTATE_270 ->
        floatArrayOf(0f, 1f, 0f, -1f, 0f, width.toFloat(), 0f, 0f, 1f)
      else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }
    setValues(values)
  }

  private class BoundedImageOutputStream(output: OutputStream, private val limit: Long) :
    FilterOutputStream(output) {
    private var count = 0L
    override fun write(value: Int) {
      check(count < limit) { "Generated image exceeds the $limit byte output limit." }
      out.write(value)
      count++
    }
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
      require(length >= 0 && count + length <= limit) {
        "Generated image exceeds the $limit byte output limit."
      }
      out.write(buffer, offset, length)
      count += length
    }
  }

  companion object {
    const val PROVIDER_ID = "firebase-gemini-3.1-flash-image"
    const val MODEL_NAME = "gemini-3.1-flash-image"
    const val MAX_SOURCE_IMAGES = 8
    const val MAX_SOURCE_BYTES_PER_IMAGE = 8L * 1024L * 1024L
    const val MAX_TOTAL_SOURCE_BYTES = 12L * 1024L * 1024L
    const val MAX_OUTPUT_BYTES = 4L * 1024L * 1024L
    const val MAX_SOURCE_DIMENSION = 8_192
    const val MAX_SOURCE_PIXELS = 20L * 1024L * 1024L
    const val MAX_DECODE_PIXELS = 4L * 1024L * 1024L
    const val OUTPUT_QUALITY = 90
    val SUPPORTED_OUTPUT_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp")
  }
}
