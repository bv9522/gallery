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
import com.google.gson.JsonObject
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.floor

/** Bounded on-device raster transforms; source images are read only from persisted SAF grants. */
class ImageArtifactGenerator(context: Context) : ArtifactGenerator {
  private val appContext = context.applicationContext
  private val resolver = appContext.contentResolver
  override val format = ArtifactFormat.IMAGE
  override val maxOutputBytes = MAX_OUTPUT_BYTES

  override fun estimateBytes(request: ArtifactCreateRequest): Long? =
    (request.payload.toString().length * ESTIMATED_BYTES_PER_CHARACTER)
      .toLong().coerceAtLeast(MIN_ESTIMATED_BYTES).coerceAtMost(maxOutputBytes)

  override fun write(request: ArtifactCreateRequest, output: OutputStream): String {
    require(request.format == ArtifactFormat.IMAGE) { "Image generator received a non-image request." }
    val transform = ImageTransformArguments.parse(request.payload)
    val sourceTree = requireTreeGrant(transform.sourceTreeUri)
    val rootId = DocumentsContract.getTreeDocumentId(sourceTree)
    require(transform.sourceDocumentId != rootId) { "Select an image file inside the granted SAF tree." }
    val sourceUri = DocumentsContract.buildDocumentUriUsingTree(sourceTree, transform.sourceDocumentId)
    val rootUri = DocumentsContract.buildDocumentUriUsingTree(sourceTree, rootId)
    require(DocumentsContract.isChildDocument(resolver, rootUri, sourceUri)) {
      "The image source is outside the selected persisted SAF tree."
    }
    val metadata = queryMetadata(sourceUri)
    require(metadata.mimeType in SUPPORTED_SOURCE_MIME_TYPES ||
      metadata.displayName.substringAfterLast('.', "").lowercase() in SUPPORTED_SOURCE_EXTENSIONS) {
      "The selected source is not a supported PNG, JPEG, or WebP image."
    }
    require(metadata.sizeBytes == null || metadata.sizeBytes <= MAX_SOURCE_BYTES) {
      "Image source exceeds the $MAX_SOURCE_BYTES byte compressed-input limit."
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openBounded(sourceUri).use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth in 1..MAX_SOURCE_DIMENSION && bounds.outHeight in 1..MAX_SOURCE_DIMENSION) {
      "Image dimensions exceed the supported $MAX_SOURCE_DIMENSION px per-side limit."
    }
    val sourcePixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
    require(sourcePixels <= MAX_SOURCE_PIXELS) {
      "Image exceeds the $MAX_SOURCE_PIXELS source-pixel limit."
    }
    validateCrop(transform, bounds.outWidth, bounds.outHeight)
    val sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
    val options = BitmapFactory.Options().apply {
      inSampleSize = sampleSize
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    var current = openBounded(sourceUri).use { BitmapFactory.decodeStream(it, null, options) }
      ?: throw IllegalArgumentException("Android could not decode the selected image.")
    try {
      if (transform.cropWidth != null) {
        val scaleX = bounds.outWidth.toDouble() / current.width
        val scaleY = bounds.outHeight.toDouble() / current.height
        val left = floor(transform.cropX!! / scaleX).toInt().coerceAtLeast(0)
        val top = floor(transform.cropY!! / scaleY).toInt().coerceAtLeast(0)
        val right = ceil((transform.cropX + transform.cropWidth) / scaleX).toInt().coerceAtMost(current.width)
        val bottom = ceil((transform.cropY + transform.cropHeight!!) / scaleY).toInt().coerceAtMost(current.height)
        require(right > left && bottom > top) { "Crop rectangle is empty after safe image sampling." }
        current = replaceBitmap(current, Bitmap.createBitmap(current, left, top, right - left, bottom - top))
      }
      if (transform.rotationDegrees != 0) {
        val matrix = Matrix().apply { postRotate(transform.rotationDegrees.toFloat()) }
        current = replaceBitmap(current, Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true))
      }
      if (transform.resizeWidth != null) {
        current = replaceBitmap(
          current,
          Bitmap.createScaledBitmap(current, transform.resizeWidth, transform.resizeHeight!!, true),
        )
      }
      val outputPixels = current.width.toLong() * current.height.toLong()
      require(current.width <= MAX_OUTPUT_DIMENSION && current.height <= MAX_OUTPUT_DIMENSION &&
        outputPixels <= MAX_OUTPUT_PIXELS) {
        "Transformed image exceeds the $MAX_OUTPUT_DIMENSION px / $MAX_OUTPUT_PIXELS pixel output limit."
      }
      val outputMime = outputMimeType(request.fileName)
      val bitmapForOutput = if (outputMime == "image/jpeg" && current.hasAlpha()) {
        Bitmap.createBitmap(current.width, current.height, Bitmap.Config.RGB_565).also { flattened ->
          Canvas(flattened).apply {
            drawColor(Color.WHITE)
            drawBitmap(current, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
          }
        }
      } else current
      try {
        val compression = when (outputMime) {
          "image/png" -> Bitmap.CompressFormat.PNG
          "image/jpeg" -> Bitmap.CompressFormat.JPEG
          else -> Bitmap.CompressFormat.WEBP_LOSSY
        }
        require(bitmapForOutput.compress(compression, transform.quality, output)) {
          "Android failed to encode the transformed image."
        }
      } finally {
        if (bitmapForOutput !== current) bitmapForOutput.recycle()
      }
      return outputMime
    } finally {
      current.recycle()
    }
  }

  private fun replaceBitmap(previous: Bitmap, next: Bitmap): Bitmap {
    if (next !== previous) previous.recycle()
    return next
  }

  private fun validateCrop(transform: ImageTransformRequest, sourceWidth: Int, sourceHeight: Int) {
    val width = transform.cropWidth ?: return
    val height = transform.cropHeight!!
    require(transform.cropX!! + width <= sourceWidth && transform.cropY!! + height <= sourceHeight) {
      "Crop rectangle extends beyond the source image bounds."
    }
  }

  private fun calculateSampleSize(width: Int, height: Int): Int {
    var sample = 1
    while ((width.toLong() / sample) * (height.toLong() / sample) > MAX_DECODE_PIXELS ||
      width / sample > MAX_OUTPUT_DIMENSION || height / sample > MAX_OUTPUT_DIMENSION) {
      sample *= 2
    }
    return sample
  }

  private fun openBounded(uri: Uri): InputStream {
    val stream = resolver.openInputStream(uri)
      ?: throw IllegalStateException("Could not open the selected image source.")
    return BoundedImageInputStream(stream, MAX_SOURCE_BYTES)
  }

  private fun requireTreeGrant(treeUri: String): Uri {
    val tree = Uri.parse(treeUri)
    require(tree.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(tree)) {
      "source_tree_uri must be a content URI returned by Gallery's document-tree grant flow."
    }
    val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == tree }
      ?: throw SecurityException("Gallery has no persisted permission for this image source tree.")
    require(grant.isReadPermission) { "The selected image source tree has no persisted read permission." }
    return tree
  }

  private fun queryMetadata(uri: Uri): ImageSourceMetadata {
    resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
        fun string(column: String): String {
          val index = cursor.getColumnIndex(column)
          return if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else ""
        }
        fun long(column: String): Long? {
          val index = cursor.getColumnIndex(column)
          return if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
        }
        return ImageSourceMetadata(
          string(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
          string(DocumentsContract.Document.COLUMN_MIME_TYPE),
          long(DocumentsContract.Document.COLUMN_SIZE),
        )
      }
    }
    throw IllegalStateException("The SAF provider did not return image metadata.")
  }

  private fun outputMimeType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    else -> throw IllegalArgumentException("Image output filename must end in .png, .jpg, .jpeg, or .webp.")
  }

  private data class ImageSourceMetadata(val displayName: String, val mimeType: String, val sizeBytes: Long?)

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

  private companion object {
    const val MAX_OUTPUT_BYTES = 20L * 1024L * 1024L
    const val ESTIMATED_BYTES_PER_CHARACTER = 4
    const val MIN_ESTIMATED_BYTES = 2_048L
    const val MAX_SOURCE_BYTES = 20L * 1024L * 1024L
    const val MAX_SOURCE_DIMENSION = 8_192
    const val MAX_SOURCE_PIXELS = 20L * 1024L * 1024L
    const val MAX_DECODE_PIXELS = 8L * 1024L * 1024L
    const val MAX_OUTPUT_DIMENSION = 4_096
    const val MAX_OUTPUT_PIXELS = 16L * 1024L * 1024L
    val SUPPORTED_SOURCE_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp")
    val SUPPORTED_SOURCE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    val PROJECTION = arrayOf(
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE,
      DocumentsContract.Document.COLUMN_SIZE,
    )
  }
}

internal data class ImageTransformRequest(
  val sourceTreeUri: String,
  val sourceDocumentId: String,
  val resizeWidth: Int?,
  val resizeHeight: Int?,
  val cropX: Int?,
  val cropY: Int?,
  val cropWidth: Int?,
  val cropHeight: Int?,
  val rotationDegrees: Int,
  val quality: Int,
)

internal object ImageTransformArguments {
  fun parse(payload: JsonObject): ImageTransformRequest {
    val allowed = setOf(
      "source_tree_uri", "source_document_id", "resize_width", "resize_height",
      "crop_x", "crop_y", "crop_width", "crop_height", "rotation_degrees", "quality",
    )
    val unknown = payload.keySet() - allowed
    require(unknown.isEmpty()) { "Unsupported image transform field(s): ${unknown.sorted().joinToString()}." }
    val sourceTree = string(payload, "source_tree_uri")
    require(sourceTree.startsWith("content://") && sourceTree.length <= 1_024) {
      "source_tree_uri must be a bounded Gallery-granted content URI."
    }
    val sourceId = string(payload, "source_document_id")
    require(sourceId.length <= 1_024) { "source_document_id is too long." }
    val resizeWidth = optionalInt(payload, "resize_width", 1..MAX_DIMENSION)
    val resizeHeight = optionalInt(payload, "resize_height", 1..MAX_DIMENSION)
    require((resizeWidth == null) == (resizeHeight == null)) {
      "resize_width and resize_height must be supplied together."
    }
    val cropX = optionalInt(payload, "crop_x", 0..MAX_DIMENSION)
    val cropY = optionalInt(payload, "crop_y", 0..MAX_DIMENSION)
    val cropWidth = optionalInt(payload, "crop_width", 1..MAX_DIMENSION)
    val cropHeight = optionalInt(payload, "crop_height", 1..MAX_DIMENSION)
    val cropFields = listOf(cropX, cropY, cropWidth, cropHeight)
    require(cropFields.all { it == null } || cropFields.all { it != null }) {
      "crop_x, crop_y, crop_width, and crop_height must be supplied together."
    }
    val rotation = optionalInt(payload, "rotation_degrees", setOf(0, 90, 180, 270)) ?: 0
    val quality = optionalInt(payload, "quality", 1..100) ?: DEFAULT_QUALITY
    return ImageTransformRequest(
      sourceTree, sourceId, resizeWidth, resizeHeight, cropX, cropY, cropWidth, cropHeight, rotation, quality,
    )
  }

  private fun string(payload: JsonObject, key: String): String {
    val value = payload.get(key)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.isNotBlank()) {
      "$key must be a non-empty string."
    }
    return value.asString
  }

  private fun optionalInt(payload: JsonObject, key: String, range: IntRange): Int? {
    if (!payload.has(key)) return null
    val value = payload.get(key)
    require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$key must be an integer." }
    val number = value.asDouble
    require(number.isFinite() && number % 1.0 == 0.0 && number.toInt() in range) {
      "$key must be an integer from ${range.first} to ${range.last}."
    }
    return number.toInt()
  }

  private fun optionalInt(payload: JsonObject, key: String, allowed: Set<Int>): Int? {
    if (!payload.has(key)) return null
    val value = payload.get(key)
    require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$key must be an integer." }
    val number = value.asDouble
    require(number.isFinite() && number % 1.0 == 0.0 && number.toInt() in allowed) {
      "$key must be one of ${allowed.sorted().joinToString()}."
    }
    return number.toInt()
  }

  private const val MAX_DIMENSION = 4_096
  private const val DEFAULT_QUALITY = 90
}
