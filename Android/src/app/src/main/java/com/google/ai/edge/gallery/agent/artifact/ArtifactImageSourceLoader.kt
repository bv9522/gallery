/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.artifact

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.provider.DocumentsContract
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream

/** A user-granted image source for embedding in an office document or PDF. */
internal data class ArtifactEmbeddedImageRequest(
  val sourceTreeUri: String,
  val sourceDocumentId: String,
  val altText: String,
)

internal data class ArtifactEmbeddedImageAsset(
  val bytes: ByteArray,
  val mimeType: String,
  val extension: String,
  val width: Int,
  val height: Int,
  val altText: String,
)

/**
 * Reads one bounded image from a persisted Gallery SAF read grant, applies EXIF orientation to
 * pixels, and returns a small interoperable JPEG/PNG payload. No source bytes leave the device.
 */
internal class ArtifactImageSourceLoader(context: Context) {
  private val resolver = context.applicationContext.contentResolver

  fun load(request: ArtifactEmbeddedImageRequest): ArtifactEmbeddedImageAsset {
    require(request.altText.length <= MAX_ALT_TEXT_CHARS) {
      "Image alt text exceeds $MAX_ALT_TEXT_CHARS characters."
    }
    val tree = Uri.parse(request.sourceTreeUri)
    require(tree.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(tree)) {
      "Image source must use a Gallery document-tree URI."
    }
    val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == tree }
      ?: throw SecurityException("Gallery has no persisted permission for this image source tree.")
    require(grant.isReadPermission) { "The image source tree has no persisted read permission." }

    val rootId = DocumentsContract.getTreeDocumentId(tree)
    require(request.sourceDocumentId != rootId) { "Select an image file inside the granted SAF tree." }
    val source = DocumentsContract.buildDocumentUriUsingTree(tree, request.sourceDocumentId)
    val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
    require(DocumentsContract.isChildDocument(resolver, root, source)) {
      "The image source is outside the selected persisted SAF tree."
    }

    val metadata = resolver.query(source, PROJECTION, null, null, null)?.use { cursor ->
      require(cursor.moveToFirst()) { "The SAF provider returned no image metadata." }
      val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
      val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
      val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
      val name = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
      val mime = if (mimeIndex >= 0) cursor.getString(mimeIndex).orEmpty() else ""
      val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
      Triple(name, mime, size)
    } ?: throw IllegalStateException("The SAF provider could not inspect the selected image.")
    val extension = metadata.first.substringAfterLast('.', "").lowercase()
    require(metadata.second in SUPPORTED_MIME_TYPES || extension in SUPPORTED_EXTENSIONS) {
      "Embedded sources must be PNG, JPEG, or WebP images."
    }
    require(metadata.third == null || metadata.third in 1L..MAX_SOURCE_BYTES) {
      "Embedded image source exceeds the $MAX_SOURCE_BYTES byte limit."
    }

    val orientation = openBounded(source).use { input ->
      ExifInterface(input).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
      )
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openBounded(source).use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth in 1..MAX_SOURCE_DIMENSION && bounds.outHeight in 1..MAX_SOURCE_DIMENSION) {
      "Embedded image dimensions exceed $MAX_SOURCE_DIMENSION px per side."
    }
    require(bounds.outWidth.toLong() * bounds.outHeight.toLong() <= MAX_SOURCE_PIXELS) {
      "Embedded image exceeds the $MAX_SOURCE_PIXELS pixel source limit."
    }
    val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight)
    val decoded = try {
      openBounded(source).use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
          inSampleSize = sample
          inPreferredConfig = Bitmap.Config.ARGB_8888
        })
      } ?: throw IllegalArgumentException("Android could not decode the selected image.")
    } catch (memory: OutOfMemoryError) {
      throw IllegalStateException("Insufficient memory to decode the bounded embedded image.", memory)
    }

    var oriented = decoded
    try {
      val matrix = orientationMatrix(orientation, decoded.width, decoded.height)
      if (!matrix.isIdentity) {
        oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (oriented !== decoded) decoded.recycle()
      }
      require(oriented.width <= MAX_OUTPUT_DIMENSION && oriented.height <= MAX_OUTPUT_DIMENSION &&
        oriented.width.toLong() * oriented.height <= MAX_DECODE_PIXELS) {
        "Embedded image exceeds the normalized $MAX_OUTPUT_DIMENSION px / $MAX_DECODE_PIXELS pixel limit."
      }

      // OOXML and PDF readers broadly support JPEG and PNG, but not WebP. Preserve alpha only
      // when the source is PNG; flatten other alpha-bearing inputs onto white before JPEG output.
      val keepPng = metadata.second == "image/png" && oriented.hasAlpha()
      val mime = if (keepPng) "image/png" else "image/jpeg"
      val format = if (keepPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
      val bitmapForOutput = if (!keepPng && oriented.hasAlpha()) {
        Bitmap.createBitmap(oriented.width, oriented.height, Bitmap.Config.RGB_565).also { flattened ->
          Canvas(flattened).apply {
            drawColor(Color.WHITE)
            drawBitmap(oriented, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
          }
        }
      } else oriented
      try {
        val encoded = BoundedImageOutputStream(MAX_EMBEDDED_BYTES)
        require(bitmapForOutput.compress(format, JPEG_QUALITY, encoded)) {
          "Android could not encode the embedded image."
        }
        return ArtifactEmbeddedImageAsset(
          bytes = encoded.toByteArray(),
          mimeType = mime,
          extension = if (keepPng) "png" else "jpg",
          width = bitmapForOutput.width,
          height = bitmapForOutput.height,
          altText = request.altText.ifBlank {
            metadata.first.filter { char ->
              val code = char.code
              code == 0x9 || code == 0xA || code == 0xD ||
                code in 0x20..0xD7FF || code in 0xE000..0xFFFD || code in 0x10000..0x10FFFF
            }.take(MAX_ALT_TEXT_CHARS).ifBlank { "Embedded image" }
          },
        )
      } finally {
        if (bitmapForOutput !== oriented) bitmapForOutput.recycle()
      }
    } catch (memory: OutOfMemoryError) {
      throw IllegalStateException("Insufficient memory to normalize the embedded image.", memory)
    } finally {
      if (!oriented.isRecycled) oriented.recycle()
      if (decoded !== oriented && !decoded.isRecycled) decoded.recycle()
    }
  }

  private fun openBounded(uri: Uri): InputStream {
    val stream = resolver.openInputStream(uri)
      ?: throw IllegalStateException("Could not open the selected image source.")
    return BoundedImageInputStream(stream, MAX_SOURCE_BYTES)
  }

  private fun calculateSampleSize(width: Int, height: Int): Int {
    var sample = 1
    while ((width.toLong() / sample) * (height.toLong() / sample) > MAX_DECODE_PIXELS ||
      width / sample > MAX_OUTPUT_DIMENSION || height / sample > MAX_OUTPUT_DIMENSION) {
      sample *= 2
    }
    return sample
  }

  private fun orientationMatrix(orientation: Int, width: Int, height: Int): Matrix =
    Matrix().apply {
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

  private class BoundedImageInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L
    override fun read(): Int {
      val value = super.read()
      if (value >= 0) addCount(1)
      return value
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      val read = super.read(buffer, offset, length)
      if (read > 0) addCount(read)
      return read
    }
    private fun addCount(bytes: Int) {
      count += bytes
      require(count <= limit) { "Image source exceeds the $limit byte compressed-input limit." }
    }
  }

  private class BoundedImageOutputStream(private val limit: Int) : ByteArrayOutputStream() {
    override fun write(value: Int) {
      require(size() < limit) { "Normalized embedded image exceeds the $limit byte limit." }
      super.write(value)
    }
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
      require(length >= 0 && size().toLong() + length <= limit) {
        "Normalized embedded image exceeds the $limit byte limit."
      }
      super.write(buffer, offset, length)
    }
  }

  private companion object {
    const val MAX_ALT_TEXT_CHARS = 500
    const val MAX_SOURCE_BYTES = 12L * 1024L * 1024L
    const val MAX_SOURCE_DIMENSION = 8_192
    const val MAX_SOURCE_PIXELS = 20L * 1024L * 1024L
    const val MAX_DECODE_PIXELS = 4L * 1024L * 1024L
    const val MAX_OUTPUT_DIMENSION = 4_096
    const val MAX_EMBEDDED_BYTES = 4 * 1024 * 1024
    const val JPEG_QUALITY = 88
    val SUPPORTED_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp")
    val SUPPORTED_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    val PROJECTION = arrayOf(
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE,
      DocumentsContract.Document.COLUMN_SIZE,
    )
  }
}
