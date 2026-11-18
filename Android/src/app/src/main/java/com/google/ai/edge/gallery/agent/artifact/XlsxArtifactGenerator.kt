/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.artifact

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.io.OutputStream
import java.math.BigDecimal
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Minimal editable XLSX OOXML writer with typed cells and no formula execution surface. */
class XlsxArtifactGenerator : ArtifactGenerator {
  override val format = ArtifactFormat.XLSX
  override val maxOutputBytes = MAX_OUTPUT_BYTES

  override fun estimateBytes(request: ArtifactCreateRequest): Long? =
    (request.payload.toString().length * ESTIMATED_BYTES_PER_CHARACTER)
      .toLong().coerceAtLeast(MIN_ESTIMATED_BYTES).coerceAtMost(maxOutputBytes)

  fun validatePayload(payload: JsonObject) { XlsxPayload.parse(payload) }

  override fun write(request: ArtifactCreateRequest, output: OutputStream): String {
    require(request.format == ArtifactFormat.XLSX) { "XLSX generator received a non-XLSX request." }
    val workbook = XlsxPayload.parse(request.payload)
    val zip = ZipOutputStream(output).apply { setLevel(COMPRESSION_LEVEL) }
    addEntry(zip, "[Content_Types].xml", contentTypes(workbook.sheets.size))
    addEntry(zip, "_rels/.rels", rootRelationships())
    addEntry(zip, "xl/workbook.xml", workbookXml(workbook))
    addEntry(zip, "xl/_rels/workbook.xml.rels", workbookRelationships(workbook.sheets.size))
    addEntry(zip, "xl/styles.xml", stylesXml())
    workbook.sheets.forEachIndexed { index, sheet ->
      addEntry(zip, "xl/worksheets/sheet${index + 1}.xml", worksheetXml(sheet))
    }
    zip.finish()
    zip.flush()
    return format.mimeType
  }

  private fun addEntry(zip: ZipOutputStream, name: String, value: String) {
    zip.putNextEntry(ZipEntry(name))
    zip.write(value.toByteArray(Charsets.UTF_8))
    zip.closeEntry()
  }

  private fun contentTypes(sheetCount: Int) = buildString {
    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
    append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
    append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
    append("""<Default Extension="xml" ContentType="application/xml"/>""")
    append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
    append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
    repeat(sheetCount) { index ->
      append("""<Override PartName="/xl/worksheets/sheet${index + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
    }
    append("</Types>")
  }

  private fun rootRelationships() =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
      """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
      """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
      "</Relationships>"

  private fun workbookXml(workbook: XlsxWorkbook) = buildString {
    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
    append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
    workbook.sheets.forEachIndexed { index, sheet ->
      append("""<sheet name="${xmlEscape(sheet.name)}" sheetId="${index + 1}" r:id="rId${index + 1}"/>""")
    }
    append("</sheets></workbook>")
  }

  private fun workbookRelationships(sheetCount: Int) = buildString {
    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
    append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
    repeat(sheetCount) { index ->
      append("""<Relationship Id="rId${index + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${index + 1}.xml"/>""")
    }
    append("""<Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
    append("</Relationships>")
  }

  private fun stylesXml() =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
      """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
      """<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>""" +
      """<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>""" +
      """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
      """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
      """<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>""" +
      """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""

  private fun worksheetXml(sheet: XlsxSheet) = buildString {
    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
    append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"/></sheetViews>""")
    if (sheet.columnWidths.isNotEmpty()) {
      append("<cols>")
      sheet.columnWidths.forEachIndexed { index, width ->
        append("""<col min="${index + 1}" max="${index + 1}" width="$width" customWidth="1"/>""")
      }
      append("</cols>")
    }
    append("<sheetData>")
    sheet.rows.forEachIndexed { rowIndex, row ->
      append("""<row r="${rowIndex + 1}">""")
      row.forEachIndexed { columnIndex, cell ->
        if (cell != null) append(cellXml(cell, cellReference(columnIndex + 1, rowIndex + 1), sheet.headerRow && rowIndex == 0))
      }
      append("</row>")
    }
    append("</sheetData>")
    if (sheet.autoFilter && sheet.rows.isNotEmpty()) {
      val lastColumn = columnName(sheet.rows.maxOf { it.size })
      append("""<autoFilter ref="A1:$lastColumn${sheet.rows.size}"/>""")
    }
    append("</worksheet>")
  }

  private fun cellXml(cell: XlsxCell, reference: String, header: Boolean): String {
    val style = if (header) """ s="1"""" else ""
    return when (cell) {
      is XlsxCell.Text -> """<c r="$reference" t="inlineStr"$style><is><t xml:space="preserve">${xmlEscape(cell.value)}</t></is></c>"""
      is XlsxCell.Number -> """<c r="$reference"$style><v>${cell.value}</v></c>"""
      is XlsxCell.BooleanValue -> """<c r="$reference" t="b"$style><v>${if (cell.value) 1 else 0}</v></c>"""
      XlsxCell.Blank -> ""
    }
  }

  private fun cellReference(column: Int, row: Int) = columnName(column) + row

  private fun columnName(column: Int): String {
    var value = column
    val result = StringBuilder()
    while (value > 0) {
      val remainder = (value - 1) % 26
      result.append(('A'.code + remainder).toChar())
      value = (value - 1) / 26
    }
    return result.reverse().toString()
  }

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

  private sealed interface XlsxCell {
    data class Text(val value: String) : XlsxCell
    data class Number(val value: String) : XlsxCell
    data class BooleanValue(val value: Boolean) : XlsxCell
    data object Blank : XlsxCell
  }
  private data class XlsxSheet(
    val name: String,
    val rows: List<List<XlsxCell?>>,
    val columnWidths: List<Int>,
    val headerRow: Boolean,
    val autoFilter: Boolean,
  )
  private data class XlsxWorkbook(val sheets: List<XlsxSheet>)

  private object XlsxPayload {
    fun parse(payload: JsonObject): XlsxWorkbook {
      val unknown = payload.keySet() - setOf("sheets")
      require(unknown.isEmpty()) { "Unsupported XLSX payload field(s): ${unknown.sorted().joinToString()}." }
      val sheets = payload.get("sheets")?.takeIf { it.isJsonArray }?.asJsonArray
        ?: throw IllegalArgumentException("XLSX payload requires a sheets array.")
      require(sheets.size() in 1..MAX_SHEETS) { "XLSX must contain 1–$MAX_SHEETS worksheets." }
      val names = mutableSetOf<String>()
      var totalCells = 0
      var totalCharacters = 0
      val parsed = sheets.mapIndexed { sheetIndex, element ->
        require(element.isJsonObject) { "Worksheet ${sheetIndex + 1} must be an object." }
        val sheet = element.asJsonObject
        val unexpected = sheet.keySet() - setOf("name", "rows", "column_widths", "header_row", "auto_filter")
        require(unexpected.isEmpty()) { "Unsupported worksheet field(s): ${unexpected.sorted().joinToString()}." }
        val name = string(sheet, "name")
        require(isValidSheetName(name)) { "Worksheet name '$name' is invalid; use 1–31 characters without []:*?/\\ or edge apostrophes." }
        require(names.add(name.lowercase(Locale.ROOT))) { "Worksheet names must be unique (case-insensitive)." }
        val rows = sheet.get("rows")?.takeIf { it.isJsonArray }?.asJsonArray
          ?: throw IllegalArgumentException("Worksheet '$name' requires a rows array.")
        require(rows.size() in 1..MAX_ROWS_PER_SHEET) {
          "Worksheet '$name' must contain 1–$MAX_ROWS_PER_SHEET rows."
        }
        val parsedRows = rows.mapIndexed { rowIndex, row ->
          require(row.isJsonArray && row.asJsonArray.size() in 1..MAX_COLUMNS) {
            "Worksheet '$name' row ${rowIndex + 1} must contain 1–$MAX_COLUMNS cells."
          }
          totalCells += row.asJsonArray.size()
          require(totalCells <= MAX_TOTAL_CELLS) { "Workbook exceeds $MAX_TOTAL_CELLS cells." }
          row.asJsonArray.map { value ->
            parseCell(value).also { cell ->
              if (cell is XlsxCell.Text) totalCharacters += cell.value.length
            }
          }
        }
        val widths = parseWidths(sheet.get("column_widths"))
        val header = optionalBoolean(sheet, "header_row", false)
        val autoFilter = optionalBoolean(sheet, "auto_filter", false)
        require(totalCharacters <= MAX_TOTAL_CHARACTERS) {
          "Workbook text exceeds $MAX_TOTAL_CHARACTERS characters."
        }
        XlsxSheet(name, parsedRows, widths, header, autoFilter)
      }
      return XlsxWorkbook(parsed)
    }

    private fun parseCell(value: JsonElement): XlsxCell? {
      if (value.isJsonNull) return null
      require(value.isJsonPrimitive) { "Workbook cells must be strings, finite numbers, booleans, or null." }
      val primitive = value.asJsonPrimitive
      return when {
        primitive.isString -> {
          val text = primitive.asString
          require(text.length <= MAX_CELL_CHARS) { "Workbook text cell exceeds $MAX_CELL_CHARS characters." }
          require(isXml10Text(text)) { "Workbook text contains characters not permitted in XML 1.0." }
          XlsxCell.Text(text)
        }
        primitive.isBoolean -> XlsxCell.BooleanValue(primitive.asBoolean)
        primitive.isNumber -> XlsxCell.Number(parseNumber(primitive))
        else -> throw IllegalArgumentException("Unsupported workbook cell value.")
      }
    }

    private fun parseNumber(value: JsonPrimitive): String {
      val decimal = try { value.asBigDecimal } catch (_: Exception) {
        throw IllegalArgumentException("Workbook numeric cells must be finite decimal values.")
      }
      require(decimal.precision() <= MAX_NUMBER_PRECISION && decimal.scale() in -MAX_NUMBER_SCALE..MAX_NUMBER_SCALE) {
        "Workbook numeric cells exceed supported precision or scale."
      }
      require(decimal.abs() <= MAX_ABSOLUTE_NUMBER) { "Workbook numeric value exceeds the supported range." }
      val plain = decimal.stripTrailingZeros().toPlainString()
      require(plain.length <= MAX_NUMBER_CHARS) { "Workbook numeric value is too long." }
      return plain
    }

    private fun parseWidths(value: JsonElement?): List<Int> {
      if (value == null) return emptyList()
      require(value.isJsonArray && value.asJsonArray.size() <= MAX_COLUMNS) {
        "column_widths must be an array with at most $MAX_COLUMNS entries."
      }
      return value.asJsonArray.mapIndexed { index, width ->
        require(width.isJsonPrimitive && width.asJsonPrimitive.isNumber) {
          "column_widths item ${index + 1} must be numeric."
        }
        val number = width.asDouble
        require(number.isFinite() && number % 1.0 == 0.0 && number.toInt() in MIN_COLUMN_WIDTH..MAX_COLUMN_WIDTH) {
          "column_widths item ${index + 1} must be an integer from $MIN_COLUMN_WIDTH to $MAX_COLUMN_WIDTH."
        }
        number.toInt()
      }
    }

    private fun optionalBoolean(obj: JsonObject, key: String, default: Boolean): Boolean {
      if (!obj.has(key)) return default
      val value = obj.get(key)
      require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "$key must be a boolean." }
      return value.asBoolean
    }

    private fun string(obj: JsonObject, key: String): String {
      val value = obj.get(key)
      require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$key must be a string." }
      return value.asString
    }

    private fun isValidSheetName(name: String): Boolean =
      name.isNotBlank() && name.length <= MAX_SHEET_NAME_CHARS &&
        name.first() != '\'' && name.last() != '\'' &&
        name.none { it in "[]:*?/" || it == '\\' || it.isISOControl() } && isXml10Text(name)

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
    const val ESTIMATED_BYTES_PER_CHARACTER = 16
    const val MIN_ESTIMATED_BYTES = 4_096L
    const val COMPRESSION_LEVEL = 6
    const val MAX_SHEETS = 20
    const val MAX_ROWS_PER_SHEET = 500
    const val MAX_COLUMNS = 32
    const val MAX_TOTAL_CELLS = 10_000
    const val MAX_TOTAL_CHARACTERS = 100_000
    const val MAX_CELL_CHARS = 4_096
    const val MAX_SHEET_NAME_CHARS = 31
    const val MIN_COLUMN_WIDTH = 4
    const val MAX_COLUMN_WIDTH = 60
    const val MAX_NUMBER_PRECISION = 15
    const val MAX_NUMBER_SCALE = 308
    const val MAX_NUMBER_CHARS = 64
    val MAX_ABSOLUTE_NUMBER = BigDecimal("1E+308")
  }
}
