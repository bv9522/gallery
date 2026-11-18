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
import android.graphics.RectF
import android.net.Uri
import android.provider.DocumentsContract
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.exifinterface.media.ExifInterface
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
  private val embeddedImageLoader = ArtifactImageSourceLoader(appContext)
  override val format = ArtifactFormat.IMAGE
  override val maxOutputBytes = MAX_OUTPUT_BYTES

  override fun estimateBytes(request: ArtifactCreateRequest): Long? = runCatching {
    val transform = ImageTransformArguments.parse(request.payload)
    val width = transform.resizeWidth ?: transform.cropWidth ?: return@runCatching null
    val height = transform.resizeHeight ?: transform.cropHeight ?: return@runCatching null
    (width.toLong() * height.toLong() * 4L).coerceAtLeast(MIN_ESTIMATED_BYTES).coerceAtMost(maxOutputBytes)
  }.getOrNull()

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
    val exifOrientation = openBounded(sourceUri).use { input ->
      ExifInterface(input).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
      )
    }
    val swapsAxes = exifOrientation in setOf(
      ExifInterface.ORIENTATION_TRANSPOSE,
      ExifInterface.ORIENTATION_ROTATE_90,
      ExifInterface.ORIENTATION_TRANSVERSE,
      ExifInterface.ORIENTATION_ROTATE_270,
    )
    val visualWidth = if (swapsAxes) bounds.outHeight else bounds.outWidth
    val visualHeight = if (swapsAxes) bounds.outWidth else bounds.outHeight
    validateCrop(transform, visualWidth, visualHeight)
    val sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
    val options = BitmapFactory.Options().apply {
      inSampleSize = sampleSize
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    var current = try {
      openBounded(sourceUri).use { BitmapFactory.decodeStream(it, null, options) }
        ?: throw IllegalArgumentException("Android could not decode the selected image.")
    } catch (memory: OutOfMemoryError) {
      throw IllegalStateException("Insufficient memory to decode the bounded source image.", memory)
    }
    try {
      val oriented = applyExifOrientation(current, exifOrientation)
      if (oriented !== current) current.recycle()
      current = oriented
      if (transform.additionalSources.isNotEmpty()) {
        current = replaceBitmap(current, composeImages(current, transform.additionalSources))
      }
      if (transform.cropWidth != null) {
        val scaleX = visualWidth.toDouble() / current.width
        val scaleY = visualHeight.toDouble() / current.height
        val left = floor(transform.cropX!! / scaleX).toInt().coerceAtLeast(0)
        val top = floor(transform.cropY!! / scaleY).toInt().coerceAtLeast(0)
        val right = ceil((transform.cropX!! + transform.cropWidth) / scaleX).toInt().coerceAtMost(current.width)
        val bottom = ceil((transform.cropY!! + transform.cropHeight!!) / scaleY).toInt().coerceAtMost(current.height)
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
      transform.annotationText?.let { annotation ->
        current = replaceBitmap(current, drawAnnotation(current, annotation, transform.annotationPosition))
      }
      val outputPixels = current.width.toLong() * current.height.toLong()
      require(current.width <= MAX_OUTPUT_DIMENSION && current.height <= MAX_OUTPUT_DIMENSION &&
        outputPixels <= MAX_OUTPUT_PIXELS) {
        "Transformed image exceeds the $MAX_OUTPUT_DIMENSION px / $MAX_OUTPUT_PIXELS pixel output limit."
      }
      val outputMime = outputMimeType(request.fileName)
      val bitmapForOutput = if (outputMime == "image/jpeg" && current.hasAlpha()) {
        val flattened = Bitmap.createBitmap(current.width, current.height, Bitmap.Config.RGB_565)
        try {
          Canvas(flattened).apply {
            drawColor(Color.WHITE)
            drawBitmap(current, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
          }
          flattened
        } catch (error: Throwable) {
          flattened.recycle()
          throw error
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
    } catch (memory: OutOfMemoryError) {
      throw IllegalStateException("Insufficient memory to complete the bounded image transform.", memory)
    } finally {
      current.recycle()
    }
  }

  /** Applies all eight EXIF orientation states to pixels; output carries normal orientation. */
  private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val width = bitmap.width.toFloat()
    val height = bitmap.height.toFloat()
    val matrix = Matrix().apply {
      val values = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
          floatArrayOf(-1f, 0f, width, 0f, 1f, 0f, 0f, 0f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 ->
          floatArrayOf(-1f, 0f, width, 0f, -1f, height, 0f, 0f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL ->
          floatArrayOf(1f, 0f, 0f, 0f, -1f, height, 0f, 0f, 1f)
        ExifInterface.ORIENTATION_TRANSPOSE ->
          floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        ExifInterface.ORIENTATION_ROTATE_90 ->
          floatArrayOf(0f, -1f, height, 1f, 0f, 0f, 0f, 0f, 1f)
        ExifInterface.ORIENTATION_TRANSVERSE ->
          floatArrayOf(0f, -1f, height, -1f, 0f, width, 0f, 0f, 1f)
        ExifInterface.ORIENTATION_ROTATE_270 ->
          floatArrayOf(0f, 1f, 0f, -1f, 0f, width, 0f, 0f, 1f)
        else -> return bitmap
      }
      setValues(values)
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
  }

  /** Composes the selected source and up to three additional SAF images into a bounded grid. */
  private fun composeImages(
    primary: Bitmap,
    additionalSources: List<ArtifactEmbeddedImageRequest>,
  ): Bitmap {
    val count = additionalSources.size + 1
    val columns = 2
    val rows = (count + columns - 1) / columns
    val cellSize = 1_024
    val result = Bitmap.createBitmap(columns * cellSize, rows * cellSize, Bitmap.Config.ARGB_8888)
    try {
      val canvas = Canvas(result)
      canvas.drawColor(Color.WHITE)
      drawInCollageCell(canvas, primary, 0, cellSize)
      additionalSources.forEachIndexed { index, source ->
        val asset = embeddedImageLoader.load(source)
        val bitmap = BitmapFactory.decodeByteArray(asset.bytes, 0, asset.bytes.size)
          ?: throw IllegalArgumentException("Could not decode a normalized composition source image.")
        try {
          drawInCollageCell(canvas, bitmap, index + 1, cellSize)
        } finally {
          bitmap.recycle()
        }
      }
      return result
    } catch (error: Throwable) {
      result.recycle()
      throw error
    }
  }

  private fun drawInCollageCell(canvas: Canvas, bitmap: Bitmap, index: Int, cellSize: Int) {
    val column = index % 2
    val row = index / 2
    val scale = minOf(cellSize.toFloat() / bitmap.width, cellSize.toFloat() / bitmap.height)
    val width = bitmap.width * scale
    val height = bitmap.height * scale
    val left = column * cellSize + (cellSize - width) / 2f
    val top = row * cellSize + (cellSize - height) / 2f
    canvas.drawBitmap(
      bitmap,
      null,
      RectF(left, top, left + width, top + height),
      Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
    )
  }

  /** Adds a readable, bounded top/bottom caption without changing the original source bitmap. */
  private fun drawAnnotation(bitmap: Bitmap, text: String, position: String): Bitmap {
    val annotated = bitmap.copy(Bitmap.Config.ARGB_8888, true)
      ?: throw IllegalStateException("Could not allocate the annotated image.")
    try {
      val padding = (annotated.width / 32f).coerceIn(12f, 48f)
      val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = (annotated.width / 36f).coerceIn(18f, 48f)
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
      }
      val textWidth = (annotated.width - padding * 2).toInt().coerceAtLeast(1)
      val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, textWidth)
        .setAlignment(Layout.Alignment.ALIGN_CENTER)
        .setIncludePad(true)
        .setMaxLines(4)
        .build()
      val bandHeight = (layout.height + padding * 2).coerceAtMost(annotated.height.toFloat())
      val top = if (position == "top") 0f else annotated.height - bandHeight
      val canvas = Canvas(annotated)
      canvas.drawRect(
        RectF(0f, top, annotated.width.toFloat(), top + bandHeight),
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xb0000000.toInt() },
      )
      canvas.save()
      canvas.translate(padding, top + padding)
      layout.draw(canvas)
      canvas.restore()
      return annotated
    } catch (error: Throwable) {
      annotated.recycle()
      throw error
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
  val additionalSources: List<ArtifactEmbeddedImageRequest>,
  val annotationText: String?,
  val annotationPosition: String,
)

internal object ImageTransformArguments {
  fun parse(payload: JsonObject): ImageTransformRequest {
    val allowed = setOf(
      "source_tree_uri", "source_document_id", "resize_width", "resize_height",
      "crop_x", "crop_y", "crop_width", "crop_height", "rotation_degrees", "quality",
      "additional_sources", "annotation_text", "annotation_position",
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
    val additionalSources = ArtifactEmbeddedImageArguments.parse(payload.get("additional_sources"))
    require(additionalSources.size <= 3) { "Image composition supports at most four total source images." }
    require(additionalSources.isEmpty() ||
      (resizeWidth == null && cropWidth == null && rotation == 0)) {
      "Image composition cannot be combined with resize, crop, or rotation; annotate the composed result instead."
    }
    val annotationText = if (!payload.has("annotation_text")) null else {
      val value = payload.get("annotation_text")
      require(value.isJsonPrimitive && value.asJsonPrimitive.isString &&
        value.asString.isNotBlank() && value.asString.length <= MAX_ANNOTATION_CHARS) {
        "annotation_text must contain 1–$MAX_ANNOTATION_CHARS characters."
      }
      value.asString
    }
    val annotationPosition = if (!payload.has("annotation_position")) "bottom" else {
      val value = payload.get("annotation_position")
      require(value.isJsonPrimitive && value.asJsonPrimitive.isString &&
        value.asString in setOf("top", "bottom")) {
        "annotation_position must be 'top' or 'bottom'."
      }
      value.asString
    }
    require(annotationText != null || !payload.has("annotation_position")) {
      "annotation_position requires annotation_text."
    }
    return ImageTransformRequest(
      sourceTree, sourceId, resizeWidth, resizeHeight, cropX, cropY, cropWidth, cropHeight,
      rotation, quality, additionalSources, annotationText, annotationPosition,
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
  private const val MAX_ANNOTATION_CHARS = 500
}
