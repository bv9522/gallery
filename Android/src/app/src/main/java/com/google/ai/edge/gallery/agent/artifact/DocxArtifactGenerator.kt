/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.artifact

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Minimal editable Word OOXML package writer; no office runtime or third-party dependency. */
class DocxArtifactGenerator : ArtifactGenerator {
  override val format = ArtifactFormat.DOCX
  override val maxOutputBytes = MAX_OUTPUT_BYTES

  override fun estimateBytes(request: ArtifactCreateRequest): Long? =
    (request.payload.toString().length * ESTIMATED_BYTES_PER_CHARACTER)
      .toLong().coerceAtLeast(MIN_ESTIMATED_BYTES).coerceAtMost(maxOutputBytes)

  override fun write(request: ArtifactCreateRequest, output: OutputStream): String {
    require(request.format == ArtifactFormat.DOCX) { "DOCX generator received a non-DOCX request." }
    val content = DocxPayload.parse(request.payload)
    val zip = ZipOutputStream(output).apply { setLevel(COMPRESSION_LEVEL) }
    addEntry(zip, "[Content_Types].xml", contentTypes(content))
    addEntry(zip, "_rels/.rels", rootRelationships())
    addEntry(zip, "word/document.xml", documentXml(content))
    addEntry(zip, "word/styles.xml", stylesXml())
    addEntry(zip, "word/_rels/document.xml.rels", documentRelationships(content))
    if (content.header != null) addEntry(zip, "word/header1.xml", headerFooterXml("hdr", content.header))
    if (content.footer != null) addEntry(zip, "word/footer1.xml", headerFooterXml("ftr", content.footer))
    zip.finish()
    zip.flush()
    return format.mimeType
  }

  private fun addEntry(zip: ZipOutputStream, name: String, value: String) {
    zip.putNextEntry(ZipEntry(name))
    zip.write(value.toByteArray(Charsets.UTF_8))
    zip.closeEntry()
  }

  private fun contentTypes(content: DocxContent) = buildString {
    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
    append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
    append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
    append("""<Default Extension="xml" ContentType="application/xml"/>""")
    append("""<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""")
    append("""<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>""")
    if (content.header != null) append("""<Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>""")
    if (content.footer != null) append("""<Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>""")
    append("</Types>")
  }

  private fun rootRelationships() =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
      """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
      """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
      "</Relationships>"

  private fun documentRelationships(content: DocxContent) = buildString {
    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
    append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
    append("""<Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
    if (content.header != null) append("""<Relationship Id="rIdHeader" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>""")
    if (content.footer != null) append("""<Relationship Id="rIdFooter" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>""")
    append("</Relationships>")
  }

  private fun stylesXml() =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
      """<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
      """<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:rPr><w:sz w:val="22"/></w:rPr></w:style>""" +
      """<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="240"/></w:pPr><w:rPr><w:b/><w:sz w:val="36"/></w:rPr></w:style>""" +
      """<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="120"/></w:pPr><w:rPr><w:b/><w:sz w:val="28"/></w:rPr></w:style>""" +
      "</w:styles>"

  private fun documentXml(content: DocxContent) = buildString {
    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
    append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body>""")
    append(paragraph(content.title, "Title"))
    content.sections.forEach { section ->
      section.heading?.takeIf { it.isNotBlank() }?.let { append(paragraph(it, "Heading1")) }
      section.paragraphs.forEach { append(paragraph(it)) }
      section.bullets.forEach { append(paragraph("•  $it")) }
      section.tables.forEach { append(tableXml(it)) }
    }
    append("<w:sectPr>")
    if (content.header != null) append("""<w:headerReference w:type="default" r:id="rIdHeader"/>""")
    if (content.footer != null) append("""<w:footerReference w:type="default" r:id="rIdFooter"/>""")
    append("""<w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720" w:gutter="0"/>""")
    append("</w:sectPr></w:body></w:document>")
  }

  private fun paragraph(text: String, style: String? = null): String = buildString {
    append("<w:p>")
    if (style != null) append("""<w:pPr><w:pStyle w:val="$style"/></w:pPr>""")
    append("<w:r><w:t xml:space=\"preserve\">")
    append(xmlEscape(text))
    append("</w:t></w:r></w:p>")
  }

  private fun tableXml(rows: List<List<String>>) = buildString {
    val columns = rows.maxOfOrNull { it.size } ?: 1
    append("<w:tbl><w:tblPr><w:tblBorders>")
    listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach {
      append("""<w:$it w:val="single" w:sz="4" w:space="0" w:color="B7B7B7"/>""")
    }
    append("</w:tblBorders></w:tblPr><w:tblGrid>")
    repeat(columns) { append("""<w:gridCol w:w="2200"/>""") }
    append("</w:tblGrid>")
    rows.forEach { row ->
      append("<w:tr>")
      repeat(columns) { index ->
        val value = row.getOrNull(index).orEmpty()
        append("<w:tc><w:tcPr><w:tcW w:w=\"2200\" w:type=\"dxa\"/></w:tcPr>")
        append(paragraph(value))
        append("</w:tc>")
      }
      append("</w:tr>")
    }
    append("</w:tbl>")
  }

  private fun headerFooterXml(root: String, text: String) =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
      """<w:$root xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
      paragraph(text) + "</w:$root>"

  private fun xmlEscape(value: String): String = buildString(value.length) {
    value.forEach { char ->
      when (char) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&apos;")
        else -> append(char)
      }
    }
  }

  private data class DocxContent(
    val title: String,
    val header: String?,
    val footer: String?,
    val sections: List<DocxSection>,
  )
  private data class DocxSection(
    val heading: String?,
    val paragraphs: List<String>,
    val bullets: List<String>,
    val tables: List<List<List<String>>>,
  )

  private object DocxPayload {
    fun parse(payload: JsonObject): DocxContent {
      val unknown = payload.keySet() - setOf("title", "header", "footer", "sections")
      require(unknown.isEmpty()) { "Unsupported DOCX payload field(s): ${unknown.sorted().joinToString()}." }
      val title = string(payload, "title")
      require(title.isNotBlank() && title.length <= MAX_TITLE_CHARS) {
        "DOCX title must contain 1–$MAX_TITLE_CHARS characters."
      }
      val header = optionalString(payload, "header", MAX_HEADER_FOOTER_CHARS)
      val footer = optionalString(payload, "footer", MAX_HEADER_FOOTER_CHARS)
      val sections = payload.get("sections")?.takeIf { it.isJsonArray }?.asJsonArray
        ?: throw IllegalArgumentException("DOCX payload requires a sections array.")
      require(sections.size() in 1..MAX_SECTIONS) { "DOCX must contain 1–$MAX_SECTIONS sections." }
      var total = title.length + (header?.length ?: 0) + (footer?.length ?: 0)
      val parsed = sections.mapIndexed { index, element ->
        require(element.isJsonObject) { "DOCX section ${index + 1} must be an object." }
        val section = element.asJsonObject
        val unexpected = section.keySet() - setOf("heading", "paragraphs", "bullet_items", "table")
        require(unexpected.isEmpty()) {
          "Unsupported DOCX section field(s): ${unexpected.sorted().joinToString()}."
        }
        val heading = optionalString(section, "heading", MAX_BLOCK_CHARS)
        val paragraphs = stringArray(section, "paragraphs")
        val bullets = stringArray(section, "bullet_items")
        require(paragraphs.size + bullets.size <= MAX_BLOCKS_PER_SECTION) {
          "DOCX section ${index + 1} exceeds $MAX_BLOCKS_PER_SECTION paragraph and bullet blocks."
        }
        val tables = parseTables(section.get("table"))
        total += (heading?.length ?: 0) + paragraphs.sumOf { it.length } + bullets.sumOf { it.length }
        total += tables.flatten().sumOf { row -> row.sumOf { it.length } }
        require(total <= MAX_TOTAL_CHARACTERS) { "DOCX text exceeds the $MAX_TOTAL_CHARACTERS character limit." }
        DocxSection(heading, paragraphs, bullets, tables)
      }
      return DocxContent(title, header, footer, parsed)
    }

    private fun string(obj: JsonObject, key: String): String {
      val value = obj.get(key)
      require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
        "$key must be a string."
      }
      return value.asString
    }

    private fun optionalString(obj: JsonObject, key: String, max: Int): String? {
      if (!obj.has(key)) return null
      val value = obj.get(key)
      require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$key must be a string." }
      return value.asString.also {
        require(it.length <= max) { "$key exceeds $max characters." }
        require(isXml10Text(it)) { "$key contains characters not permitted in XML 1.0." }
      }
    }

    private fun stringArray(obj: JsonObject, key: String): List<String> {
      if (!obj.has(key)) return emptyList()
      val array = obj.get(key)
      require(array.isJsonArray) { "$key must be an array of strings." }
      require(array.asJsonArray.size() <= MAX_BLOCKS_PER_SECTION) { "$key has too many items." }
      return array.asJsonArray.mapIndexed { index, item ->
        require(item.isJsonPrimitive && item.asJsonPrimitive.isString) { "$key item ${index + 1} must be a string." }
        item.asString.also {
          require(it.length <= MAX_BLOCK_CHARS) { "$key item exceeds $MAX_BLOCK_CHARS characters." }
          require(isXml10Text(it)) { "$key item contains characters not permitted in XML 1.0." }
        }
      }
    }

    private fun parseTables(element: JsonElement?): List<List<List<String>>> {
      if (element == null) return emptyList()
      require(element.isJsonArray) { "table must be an array of rows." }
      val rows = element.asJsonArray
      require(rows.size() <= MAX_TABLE_ROWS) { "table exceeds $MAX_TABLE_ROWS rows." }
      return listOf(rows.mapIndexed { rowIndex, row ->
        require(row.isJsonArray && row.asJsonArray.size() in 1..MAX_TABLE_COLUMNS) {
          "DOCX table row ${rowIndex + 1} must contain 1–$MAX_TABLE_COLUMNS cells."
        }
        row.asJsonArray.mapIndexed { cellIndex, cell ->
          require(cell.isJsonPrimitive && cell.asJsonPrimitive.isString) {
            "DOCX table cell ${rowIndex + 1}, ${cellIndex + 1} must be a string."
          }
          cell.asString.also {
            require(it.length <= MAX_CELL_CHARS) { "DOCX table cell exceeds $MAX_CELL_CHARS characters." }
            require(isXml10Text(it)) { "DOCX table cell contains characters not permitted in XML 1.0." }
          }
        }
      }) }
    }

    private fun isXml10Text(value: String): Boolean {
      var index = 0
      while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val valid = codePoint == 0x9 || codePoint == 0xa || codePoint == 0xd ||
          codePoint in 0x20..0xd7ff || codePoint in 0xe000..0xfffd ||
          codePoint in 0x10000..0x10ffff
        if (!valid) return false
        index += Character.charCount(codePoint)
      }
      return true
    }
  }

  private companion object {
    const val MAX_OUTPUT_BYTES = 20L * 1024L * 1024L
    const val ESTIMATED_BYTES_PER_CHARACTER = 12
    const val MIN_ESTIMATED_BYTES = 4_096L
    const val COMPRESSION_LEVEL = 6
    const val MAX_SECTIONS = 100
    const val MAX_TITLE_CHARS = 200
    const val MAX_HEADER_FOOTER_CHARS = 500
    const val MAX_BLOCK_CHARS = 8_000
    const val MAX_BLOCKS_PER_SECTION = 100
    const val MAX_TOTAL_CHARACTERS = 100_000
    const val MAX_TABLE_ROWS = 100
    const val MAX_TABLE_COLUMNS = 8
    const val MAX_CELL_CHARS = 1_000
  }
}
