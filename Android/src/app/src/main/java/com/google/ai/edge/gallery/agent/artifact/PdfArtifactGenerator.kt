/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.artifact

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.google.gson.JsonObject
import java.io.OutputStream

/** Native Android PDF writer. It creates real PDF pages, not a renamed text or HTML file. */
class PdfArtifactGenerator(context: Context? = null) : ArtifactGenerator {
  private val imageLoader = context?.let(::ArtifactImageSourceLoader)
  override val format = ArtifactFormat.PDF
  override val maxOutputBytes = MAX_OUTPUT_BYTES

  override fun estimateBytes(request: ArtifactCreateRequest): Long? {
    val imageCount = request.payload.get("pages")?.takeIf { it.isJsonArray }?.asJsonArray?.sumOf { page ->
      page.takeIf { it.isJsonObject }?.asJsonObject?.get("images")
        ?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0
    } ?: 0
    return (request.payload.toString().length.toLong() * ESTIMATED_BYTES_PER_CHARACTER +
      imageCount.toLong() * MAX_ESTIMATED_EMBEDDED_IMAGE_BYTES)
      .coerceAtLeast(MIN_ESTIMATED_BYTES)
      .coerceAtMost(maxOutputBytes)
  }

  override fun write(request: ArtifactCreateRequest, output: OutputStream): String {
    require(request.format == ArtifactFormat.PDF) { "PDF generator received a non-PDF request." }
    val document = parse(request.payload)
    val pdf = PdfDocument()
    var totalEmbeddedBytes = 0
    try {
      val renderer = PdfPageRenderer(pdf)
      renderer.drawText(document.title, TITLE_PAINT, bottomGap = 18f)
      document.pages.forEachIndexed { pageIndex, content ->
        content.heading?.takeIf { it.isNotBlank() }?.let {
          renderer.drawText(it, HEADING_PAINT, bottomGap = 10f)
        }
        content.paragraphs.forEach { renderer.drawText(it, BODY_PAINT, bottomGap = 10f) }
        content.bullets.forEach { renderer.drawText("•  $it", BODY_PAINT, bottomGap = 5f, indent = 14f) }
        content.tables.forEach { table ->
          table.forEach { row ->
            renderer.drawText(row.joinToString("   |   "), TABLE_PAINT, bottomGap = 4f)
          }
          renderer.addGap(10f)
        }
        content.images.forEach { imageRequest ->
          val asset = checkNotNull(imageLoader) {
            "Embedded PDF images require the Gallery application context."
          }.load(imageRequest)
          totalEmbeddedBytes += asset.bytes.size
          require(totalEmbeddedBytes <= MAX_TOTAL_EMBEDDED_IMAGE_BYTES) {
            "Embedded PDF images exceed the $MAX_TOTAL_EMBEDDED_IMAGE_BYTES byte combined limit."
          }
          val bitmap = BitmapFactory.decodeByteArray(asset.bytes, 0, asset.bytes.size)
            ?: throw IllegalArgumentException("Could not decode the normalized PDF image.")
          try {
            renderer.drawImage(bitmap)
          } finally {
            bitmap.recycle()
          }
        }
      }
      renderer.finish()
      pdf.writeTo(output)
      return format.mimeType
    } finally {
      pdf.close()
    }
  }

  fun validatePayload(payload: JsonObject) { parse(payload) }

  private fun parse(payload: JsonObject): PdfDocumentContent {
    val allowed = setOf("title", "pages")
    val unknown = payload.keySet() - allowed
    require(unknown.isEmpty()) { "Unsupported PDF payload field(s): ${unknown.sorted().joinToString()}." }
    val title = payload.get("title")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
      ?: throw IllegalArgumentException("PDF payload requires a string title.")
    require(title.isNotBlank() && title.length <= MAX_TITLE_CHARS) {
      "PDF title must contain 1–$MAX_TITLE_CHARS characters."
    }
    val pages = payload.get("pages")?.takeIf { it.isJsonArray }?.asJsonArray
      ?: throw IllegalArgumentException("PDF payload requires a pages array.")
    require(pages.size() in 1..MAX_PAGES) { "PDF must contain 1–$MAX_PAGES content sections." }
    var totalCharacters = title.length
    var totalImages = 0
    val parsedPages = pages.mapIndexed { index, element ->
      require(element.isJsonObject) { "PDF page section ${index + 1} must be an object." }
      val page = element.asJsonObject
      val pageUnknown = page.keySet() - setOf("heading", "paragraphs", "bullet_items", "table", "images")
      require(pageUnknown.isEmpty()) {
        "Unsupported field(s) in PDF section ${index + 1}: ${pageUnknown.sorted().joinToString()}."
      }
      val heading = optionalString(page, "heading", MAX_BLOCK_CHARS)
      val paragraphs = stringArray(page, "paragraphs", MAX_BLOCKS_PER_SECTION)
      val bullets = stringArray(page, "bullet_items", MAX_BLOCKS_PER_SECTION)
      val tables = parseTables(page.get("table"))
      val images = ArtifactEmbeddedImageArguments.parse(page.get("images"))
      totalImages += images.size
      require(totalImages <= MAX_TOTAL_EMBEDDED_IMAGES) {
        "PDF exceeds the $MAX_TOTAL_EMBEDDED_IMAGES embedded image limit."
      }
      require(paragraphs.size + bullets.size <= MAX_BLOCKS_PER_SECTION) {
        "PDF section ${index + 1} exceeds $MAX_BLOCKS_PER_SECTION paragraph and bullet blocks."
      }
      totalCharacters += (heading?.length ?: 0) + paragraphs.sumOf { it.length } + bullets.sumOf { it.length }
      totalCharacters += tables.flatten().sumOf { row -> row.sumOf { it.length } }
      require(totalCharacters <= MAX_TOTAL_CHARACTERS) {
        "PDF text exceeds the $MAX_TOTAL_CHARACTERS character content limit."
      }
      PdfSection(heading, paragraphs, bullets, tables, images)
    }
    return PdfDocumentContent(title, parsedPages)
  }

  private fun optionalString(obj: JsonObject, key: String, maxLength: Int): String? {
    if (!obj.has(key)) return null
    val value = obj.get(key)
    require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$key must be a string." }
    return value.asString.also {
      require(it.length <= maxLength) { "$key exceeds $maxLength characters." }
    }
  }

  private fun stringArray(obj: JsonObject, key: String, maxItems: Int): List<String> {
    if (!obj.has(key)) return emptyList()
    val array = obj.get(key)
    require(array.isJsonArray) { "$key must be an array of strings." }
    require(array.asJsonArray.size() <= maxItems) { "$key contains more than $maxItems items." }
    return array.asJsonArray.mapIndexed { index, item ->
      require(item.isJsonPrimitive && item.asJsonPrimitive.isString) {
        "$key item ${index + 1} must be a string."
      }
      item.asString.also { require(it.length <= MAX_BLOCK_CHARS) { "$key item exceeds $MAX_BLOCK_CHARS characters." } }
    }
  }

  private fun parseTables(element: com.google.gson.JsonElement?): List<List<List<String>>> {
    if (element == null) return emptyList()
    require(element.isJsonArray) { "table must be an array of rows." }
    val rows = element.asJsonArray
    require(rows.size() <= MAX_TABLE_ROWS) { "table exceeds $MAX_TABLE_ROWS rows." }
    return listOf(rows.mapIndexed { rowIndex, row ->
      require(row.isJsonArray && row.asJsonArray.size() in 1..MAX_TABLE_COLUMNS) {
        "Table row ${rowIndex + 1} must contain 1–$MAX_TABLE_COLUMNS cells."
      }
      row.asJsonArray.mapIndexed { cellIndex, cell ->
        require(cell.isJsonPrimitive && cell.asJsonPrimitive.isString) {
          "Table cell ${rowIndex + 1}, ${cellIndex + 1} must be a string."
        }
        cell.asString.also { require(it.length <= MAX_CELL_CHARS) { "Table cell exceeds $MAX_CELL_CHARS characters." } }
      }
    }) }
  }

  private data class PdfDocumentContent(val title: String, val pages: List<PdfSection>)
  private data class PdfSection(
    val heading: String?,
    val paragraphs: List<String>,
    val bullets: List<String>,
    val tables: List<List<List<String>>>,
    val images: List<ArtifactEmbeddedImageRequest>,
  )

  private class PdfPageRenderer(private val document: PdfDocument) {
    private var pageNumber = 0
    private var page: PdfDocument.Page? = null
    private var canvas: android.graphics.Canvas? = null
    private var cursorY = TOP_MARGIN

    init { startPage() }

    fun drawText(text: String, paint: TextPaint, bottomGap: Float = 0f, indent: Float = 0f) {
      if (text.isBlank()) {
        cursorY += paint.textSize + bottomGap
        return
      }
      val availableWidth = PAGE_WIDTH - LEFT_MARGIN - RIGHT_MARGIN - indent
      val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, availableWidth.toInt())
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .setLineSpacing(0f, 1.15f)
        .build()
      for (line in 0 until layout.lineCount) {
        val lineHeight = (layout.getLineBottom(line) - layout.getLineTop(line)).toFloat()
        if (cursorY + lineHeight > PAGE_HEIGHT - BOTTOM_MARGIN) startPage()
        val start = layout.getLineStart(line)
        val end = layout.getLineEnd(line)
        val lineText = text.substring(start, end).trimEnd('\n', '\r')
        canvas?.drawText(lineText, LEFT_MARGIN + indent, cursorY + layout.getLineBaseline(line), paint)
        cursorY += lineHeight
      }
      cursorY += bottomGap
    }

    fun addGap(points: Float) { cursorY += points }

    fun drawImage(bitmap: Bitmap) {
      val maxWidth = PAGE_WIDTH - LEFT_MARGIN - RIGHT_MARGIN
      val maxHeight = 360f
      val scale = minOf(maxWidth / bitmap.width, maxHeight / bitmap.height)
      val width = bitmap.width * scale
      val height = bitmap.height * scale
      if (cursorY + height > PAGE_HEIGHT - BOTTOM_MARGIN) startPage()
      val target = RectF(LEFT_MARGIN, cursorY, LEFT_MARGIN + width, cursorY + height)
      canvas?.drawBitmap(bitmap, null, target, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
      cursorY = target.bottom + 12f
    }

    fun newPage() { startPage() }

    fun finish() {
      finishPage()
    }

    private fun startPage() {
      finishPage()
      pageNumber++
      require(pageNumber <= MAX_PAGES) { "Generated PDF exceeds the $MAX_PAGES page limit." }
      val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), pageNumber).create()
      page = document.startPage(info)
      canvas = page?.canvas
      cursorY = TOP_MARGIN
    }

    private fun finishPage() {
      page?.let { current ->
        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
          color = 0xff666666.toInt()
          textSize = 8f
          typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        canvas?.drawText("Page $pageNumber", PAGE_WIDTH - RIGHT_MARGIN - 42f, PAGE_HEIGHT - 18f, footerPaint)
        document.finishPage(current)
      }
      page = null
      canvas = null
    }
  }

  private companion object {
    const val MAX_OUTPUT_BYTES = 10L * 1024L * 1024L
    const val MAX_PAGES = 100
    const val MAX_TOTAL_EMBEDDED_IMAGES = 8
    const val MAX_TOTAL_EMBEDDED_IMAGE_BYTES = 8 * 1024 * 1024
    const val MAX_ESTIMATED_EMBEDDED_IMAGE_BYTES = 4L * 1024L * 1024L
    const val MAX_TITLE_CHARS = 200
    const val MAX_BLOCK_CHARS = 8_000
    const val MAX_BLOCKS_PER_SECTION = 100
    const val MAX_TOTAL_CHARACTERS = 100_000
    const val MAX_TABLE_ROWS = 100
    const val MAX_TABLE_COLUMNS = 12
    const val MAX_CELL_CHARS = 500
    const val ESTIMATED_BYTES_PER_CHARACTER = 8
    const val MIN_ESTIMATED_BYTES = 2_048L
    const val PAGE_WIDTH = 595f
    const val PAGE_HEIGHT = 842f
    const val LEFT_MARGIN = 48f
    const val RIGHT_MARGIN = 48f
    const val TOP_MARGIN = 54f
    const val BOTTOM_MARGIN = 48f
    val TITLE_PAINT = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = 0xff111111.toInt()
      textSize = 22f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val HEADING_PAINT = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = 0xff202020.toInt()
      textSize = 15f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val BODY_PAINT = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = 0xff222222.toInt()
      textSize = 10.5f
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    val TABLE_PAINT = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      color = 0xff333333.toInt()
      textSize = 8.5f
      typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
  }
}
