/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.artifact

import com.google.gson.JsonObject
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bounded ZIP creator for structured UTF-8 text entries; extraction is a separate guarded operation. */
class ZipArtifactGenerator : ArtifactGenerator {
  override val format = ArtifactFormat.ZIP
  override val maxOutputBytes = MAX_OUTPUT_BYTES

  override fun estimateBytes(request: ArtifactCreateRequest): Long? =
    (request.payload.toString().length * ESTIMATED_BYTES_PER_CHARACTER)
      .toLong().coerceAtLeast(MIN_ESTIMATED_BYTES).coerceAtMost(maxOutputBytes)

  fun validatePayload(payload: JsonObject) { ZipPayload.parse(payload) }

  override fun write(request: ArtifactCreateRequest, output: OutputStream): String {
    require(request.format == ArtifactFormat.ZIP) { "ZIP generator received a non-ZIP request." }
    val entries = ZipPayload.parse(request.payload)
    val zip = ZipOutputStream(output).apply { setLevel(COMPRESSION_LEVEL) }
    entries.forEach { entry ->
      zip.putNextEntry(ZipEntry(entry.path))
      zip.write(entry.content.toByteArray(Charsets.UTF_8))
      zip.closeEntry()
    }
    zip.finish()
    zip.flush()
    return format.mimeType
  }

  private data class ZipTextEntry(val path: String, val content: String)

  private object ZipPayload {
    fun parse(payload: JsonObject): List<ZipTextEntry> {
      val unknown = payload.keySet() - setOf("operation", "entries")
      require(unknown.isEmpty()) { "Unsupported ZIP payload field(s): ${unknown.sorted().joinToString()}." }
      val operation = payload.get("operation")?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "ZIP operation must be a string." }
        it.asString
      } ?: "create"
      require(operation == "create") { "ZIP create payload operation must be 'create'; use the guarded extraction operation for extraction." }
      val values = payload.get("entries")?.takeIf { it.isJsonArray }?.asJsonArray
        ?: throw IllegalArgumentException("ZIP payload requires an entries array.")
      require(values.size() in 1..MAX_ENTRIES) { "ZIP must contain 1–$MAX_ENTRIES text entries." }
      val seen = mutableSetOf<String>()
      var totalBytes = 0L
      val entries = values.mapIndexed { index, value ->
        require(value.isJsonObject) { "ZIP entry ${index + 1} must be an object." }
        val item = value.asJsonObject
        val unexpected = item.keySet() - setOf("path", "content")
        require(unexpected.isEmpty()) { "Unsupported ZIP entry field(s): ${unexpected.sorted().joinToString()}." }
        val path = string(item, "path")
        validatePath(path)
        val normalized = path.lowercase(Locale.ROOT)
        require(seen.add(normalized)) { "ZIP contains duplicate paths: '$path'." }
        val content = string(item, "content")
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_ENTRY_BYTES) { "ZIP entry '$path' exceeds $MAX_ENTRY_BYTES bytes." }
        totalBytes += bytes.size
        require(totalBytes <= MAX_TOTAL_UNCOMPRESSED_BYTES) {
          "ZIP content exceeds $MAX_TOTAL_UNCOMPRESSED_BYTES uncompressed bytes."
        }
        ZipTextEntry(path, content)
      }
      val paths = entries.map { it.path.lowercase(Locale.ROOT) }.toSet()
      entries.forEach { entry ->
        var parent = entry.path.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
          require(parent.lowercase(Locale.ROOT) !in paths) {
            "ZIP path '${entry.path}' conflicts with a file entry '$parent'."
          }
          parent = parent.substringBeforeLast('/', "")
        }
      }
      return entries
    }

    private fun string(obj: JsonObject, key: String): String {
      val value = obj.get(key)
      require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
        "ZIP entry $key must be a string."
      }
      return value.asString
    }

    private fun validatePath(path: String) {
      require(path.isNotBlank() && path.length <= MAX_PATH_CHARS) {
        "ZIP entry paths must contain 1–$MAX_PATH_CHARS characters."
      }
      require(!path.startsWith('/') && '\\' !in path && ':' !in path &&
        path.none { it.isISOControl() || it == '\u0000' }) {
        "ZIP entry '$path' contains an absolute path, drive prefix, backslash, or control character."
      }
      val segments = path.split('/')
      require(segments.all { it.isNotBlank() && it != "." && it != ".." && it.length <= MAX_SEGMENT_CHARS }) {
        "ZIP entry '$path' contains an empty, dot, traversal, or overlong path segment."
      }
      require(segments.none { segment ->
        val stem = segment.substringBefore('.').uppercase(Locale.ROOT)
        stem in WINDOWS_RESERVED_NAMES
      }) { "ZIP entry '$path' contains a reserved Windows filename." }
    }
  }

  private companion object {
    const val MAX_OUTPUT_BYTES = 20L * 1024L * 1024L
    const val ESTIMATED_BYTES_PER_CHARACTER = 2
    const val MIN_ESTIMATED_BYTES = 1_024L
    const val COMPRESSION_LEVEL = 6
    const val MAX_ENTRIES = 100
    const val MAX_PATH_CHARS = 240
    const val MAX_SEGMENT_CHARS = 128
    const val MAX_ENTRY_BYTES = 1L * 1024L * 1024L
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 5L * 1024L * 1024L
    val WINDOWS_RESERVED_NAMES = setOf("CON", "PRN", "AUX", "NUL") +
      (1..9).flatMap { listOf("COM$it", "LPT$it") }
  }
}
