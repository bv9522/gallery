/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.artifact

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Extracts ZIP entries only into a user-granted SAF tree. Entries are always materialized as
 * regular files/directories; archive paths are never interpreted as device filesystem paths.
 */
class SafZipExtractionHandler(
  context: Context,
  private val approvalRequester: ArtifactGenerationApprovalRequester,
) {
  private val appContext = context.applicationContext
  private val resolver = appContext.contentResolver

  suspend fun execute(request: ArtifactCreateRequest): ArtifactGenerationResult =
    withTimeout(OPERATION_TIMEOUT_MS) {
      require(request.format == ArtifactFormat.ZIP) { "ZIP extraction handler received a non-ZIP request." }
      val sourceTreeUri = payloadString(request, "source_tree_uri")
      val sourceDocumentId = payloadString(request, "source_document_id")
      val sourceTree = requireTreeGrant(sourceTreeUri, write = false)
      val sourceRootId = DocumentsContract.getTreeDocumentId(sourceTree)
      require(sourceDocumentId != sourceRootId) { "Choose a ZIP file inside the selected source tree, not the tree root." }
      val sourceUri = DocumentsContract.buildDocumentUriUsingTree(sourceTree, sourceDocumentId)
      val sourceIsChild = withContext(Dispatchers.IO) {
        DocumentsContract.isChildDocument(
          resolver,
          DocumentsContract.buildDocumentUriUsingTree(sourceTree, sourceRootId),
          sourceUri,
        )
      }
      require(sourceIsChild) { "The selected ZIP source is outside the persisted source tree." }
      val sourceMetadata = withContext(Dispatchers.IO) { queryDocument(sourceUri) }
      require(sourceMetadata.mimeType != DocumentsContract.Document.MIME_TYPE_DIR) {
        "The selected ZIP source must be a file."
      }
      require(
        sourceMetadata.mimeType in ZIP_MIME_TYPES ||
          sourceMetadata.displayName.endsWith(".zip", ignoreCase = true)
      ) { "The selected source is not identified as a ZIP archive." }
      require(sourceMetadata.sizeBytes == null || sourceMetadata.sizeBytes <= MAX_ARCHIVE_BYTES) {
        "ZIP archive exceeds the $MAX_ARCHIVE_BYTES byte compressed-input limit."
      }

      val destination = request.destination as? ArtifactDestinationSpec.SafTree
        ?: throw IllegalArgumentException("ZIP extraction currently requires a SAF destination.")
      val destinationTree = requireTreeGrant(destination.treeUri, write = true)
      val destinationParentId = destination.parentDocumentId
        ?: DocumentsContract.getTreeDocumentId(destinationTree)
      val destinationParent = withContext(Dispatchers.IO) {
        requireParentWithinTree(destinationTree, destinationParentId)
        queryDocument(DocumentsContract.buildDocumentUriUsingTree(destinationTree, destinationParentId))
      }
      require(destinationParent.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
        "The ZIP extraction destination must be a folder."
      }

      val plan = preflight(sourceUri)
      require(plan.files > 0) { "The ZIP archive contains no files to extract." }
      withContext(Dispatchers.IO) {
        ensureDestinationPathsDoNotExist(destinationTree, destinationParentId, plan.entries)
      }
      val approval = ArtifactGenerationApproval(
        format = ArtifactFormat.ZIP,
        fileName = "Extract ${sourceMetadata.displayName} (${plan.entries.size} entries)",
        destinationLabel = "SAF folder '${destinationParent.displayName.ifBlank { "selected folder" }}'",
        estimatedBytes = plan.totalExpandedBytes,
        overwrite = false,
      )
      if (!approvalRequester.requestApproval(approval)) {
        return@withTimeout failure(request, "ZIP extraction was not approved.")
      }

      val created = mutableListOf<Uri>()
      try {
        val written = extractToSaf(
          sourceUri = sourceUri,
          sourceTree = sourceTree,
          destinationTree = destinationTree,
          destinationParentId = destinationParentId,
          plan = plan,
          created = created,
        )
        ArtifactGenerationResult(
          isSuccessful = true,
          format = "zip",
          fileName = sourceMetadata.displayName,
          destination = "SAF folder '${destinationParent.displayName.ifBlank { "selected folder" }}'",
          mimeType = null,
          bytesWritten = written,
          sha256 = null,
          message = "Extracted ${plan.files} files (${written} expanded bytes); ${plan.directories} directories were created or materialized.",
        )
      } catch (cancelled: CancellationException) {
        cleanup(created)
        throw cancelled
      } catch (error: Exception) {
        cleanup(created)
        failure(request, error.message ?: error.javaClass.simpleName)
      }
    }

  private suspend fun preflight(sourceUri: Uri): ExtractionPlan = withContext(Dispatchers.IO) {
    val input = resolver.openInputStream(sourceUri)
      ?: throw IllegalStateException("Could not open the selected ZIP archive.")
    val counted = BoundedCountingInputStream(input, MAX_ARCHIVE_BYTES)
    val plans = mutableListOf<PlannedEntry>()
    val seen = mutableSetOf<String>()
    var totalExpanded = 0L
    var fileCount = 0
    var directoryCount = 0
    ZipInputStream(counted).use { zip ->
      val buffer = ByteArray(BUFFER_BYTES)
      while (true) {
        currentCoroutineContext().ensureActive()
        val entry = zip.nextEntry ?: break
        require(plans.size < MAX_ENTRIES) { "ZIP contains more than $MAX_ENTRIES entries." }
        val path = validateEntryPath(entry)
        val normalized = path.lowercase(Locale.ROOT)
        require(seen.add(normalized)) { "ZIP contains duplicate paths: '$path'." }
        var entryBytes = 0L
        if (!entry.isDirectory) {
          fileCount++
          while (true) {
            currentCoroutineContext().ensureActive()
            val read = zip.read(buffer)
            if (read < 0) break
            entryBytes += read
            totalExpanded += read
            require(entryBytes <= MAX_ENTRY_EXPANDED_BYTES) {
              "ZIP entry '$path' exceeds the $MAX_ENTRY_EXPANDED_BYTES byte expanded-size limit."
            }
            require(totalExpanded <= MAX_TOTAL_EXPANDED_BYTES) {
              "ZIP exceeds the $MAX_TOTAL_EXPANDED_BYTES byte total expanded-size limit."
            }
          }
          val compressed = entry.compressedSize
          if (compressed >= 0L) {
            require(compressed > 0L || entryBytes == 0L) { "ZIP entry '$path' has an invalid compressed size." }
            require(compressed == 0L || entryBytes <= compressed * MAX_COMPRESSION_RATIO) {
              "ZIP entry '$path' exceeds the $MAX_COMPRESSION_RATIO:1 compression-ratio limit."
            }
          }
        } else {
          directoryCount++
        }
        plans += PlannedEntry(path, entry.isDirectory, entryBytes)
        zip.closeEntry()
      }
    }
    validatePathCollisions(plans)
    ExtractionPlan(plans, fileCount, directoryCount, totalExpanded, counted.bytesRead)
  }

  private suspend fun extractToSaf(
    sourceUri: Uri,
    sourceTree: Uri,
    destinationTree: Uri,
    destinationParentId: String,
    plan: ExtractionPlan,
    created: MutableList<Uri>,
  ): Long = withContext(Dispatchers.IO) {
    requireTreeGrant(sourceTree.toString(), write = false)
    requireTreeGrant(destinationTree.toString(), write = true)
    requireParentWithinTree(destinationTree, destinationParentId)
    val directories = mutableMapOf("" to destinationParentId)
    var totalWritten = 0L
    var planIndex = 0
    val input = resolver.openInputStream(sourceUri)
      ?: throw IllegalStateException("Could not reopen the ZIP archive after approval.")
    val counted = BoundedCountingInputStream(input, MAX_ARCHIVE_BYTES)
    ZipInputStream(counted).use { zip ->
      val buffer = ByteArray(BUFFER_BYTES)
      while (true) {
        currentCoroutineContext().ensureActive()
        val entry = zip.nextEntry ?: break
        require(planIndex < plan.entries.size) { "ZIP changed after preflight; refusing additional entries." }
        val expected = plan.entries[planIndex++]
        val path = validateEntryPath(entry)
        require(path == expected.path && entry.isDirectory == expected.isDirectory) {
          "ZIP contents changed after approval; extraction was stopped."
        }
        if (entry.isDirectory) {
          ensureDirectoryPath(destinationTree, destinationParentId, path, directories, created)
          zip.closeEntry()
          continue
        }
        val parentPath = path.substringBeforeLast('/', "")
        val parentId = if (parentPath.isEmpty()) destinationParentId
          else ensureDirectoryPath(destinationTree, destinationParentId, parentPath, directories, created)
        val name = path.substringAfterLast('/')
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(destinationTree, parentId)
        val target = DocumentsContract.createDocument(resolver, parentUri, mimeForName(name), name)
          ?: throw IllegalStateException("SAF provider refused to create extracted file '$path'.")
        created += target
        var entryBytes = 0L
        val output = resolver.openOutputStream(target, "w")
          ?: throw IllegalStateException("Could not open extracted file '$path' for writing.")
        output.use { stream ->
          while (true) {
            currentCoroutineContext().ensureActive()
            val read = zip.read(buffer)
            if (read < 0) break
            entryBytes += read
            totalWritten += read
            require(entryBytes <= MAX_ENTRY_EXPANDED_BYTES && totalWritten <= MAX_TOTAL_EXPANDED_BYTES) {
              "ZIP expanded-size limit was exceeded during extraction."
            }
            stream.write(buffer, 0, read)
          }
          stream.flush()
        }
        require(entryBytes == expected.expandedBytes) {
          "ZIP entry '$path' changed size after preflight; extraction was stopped."
        }
        zip.closeEntry()
      }
    }
    require(planIndex == plan.entries.size) { "ZIP contents changed after preflight; one or more entries disappeared." }
    require(totalWritten == plan.totalExpandedBytes) { "ZIP expanded size changed after preflight." }
    totalWritten
  }

  private suspend fun ensureDirectoryPath(
    tree: Uri,
    rootParentId: String,
    path: String,
    directoryIds: MutableMap<String, String>,
    created: MutableList<Uri>,
  ): String {
    var currentPath = ""
    var parentId = rootParentId
    path.split('/').forEach { segment ->
      currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
      val existing = directoryIds[currentPath]
      if (existing != null) {
        parentId = existing
      } else {
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val createdUri = DocumentsContract.createDocument(
          resolver,
          parentUri,
          DocumentsContract.Document.MIME_TYPE_DIR,
          segment,
        ) ?: throw IllegalStateException("SAF provider refused to create extraction folder '$currentPath'.")
        created += createdUri
        val metadata = queryDocument(createdUri)
        require(metadata.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
          "SAF provider did not create '$currentPath' as a directory."
        }
        directoryIds[currentPath] = metadata.documentId
        parentId = metadata.documentId
      }
    }
    return parentId
  }

  private suspend fun ensureDestinationPathsDoNotExist(
    tree: Uri,
    rootParentId: String,
    entries: List<PlannedEntry>,
  ) {
    val checkedParents = mutableMapOf("" to rootParentId)
    val childrenCache = mutableMapOf<String, Set<String>>()
    entries.forEach { entry ->
      var parentPath = ""
      var parentId = rootParentId
      val segments = entry.path.split('/')
      segments.forEachIndexed { index, segment ->
        val names = childrenCache.getOrPut(parentId) {
          listChildNames(tree, parentId)
        }
        require(names.none { it.equals(segment, ignoreCase = true) }) {
          "Extraction target '$parentPath/$segment' already exists; nothing was overwritten."
        }
        if (index < segments.lastIndex) {
          parentPath = if (parentPath.isEmpty()) segment else "$parentPath/$segment"
          // The target does not exist, so this folder is planned and will be created during extraction.
          parentId = "planned:$parentPath"
        }
      }
    }
  }

  private fun validateEntryPath(entry: ZipEntry): String {
    val raw = entry.name
    val path = if (entry.isDirectory) raw.removeSuffix("/") else raw
    require(path.isNotBlank() && path.length <= MAX_PATH_CHARS) {
      "ZIP entry path must contain 1–$MAX_PATH_CHARS characters."
    }
    require(!path.startsWith('/') && '\\' !in path && ':' !in path &&
      path.none { it.isISOControl() || it == '\u0000' }) {
      "ZIP entry '$path' contains an absolute path, drive prefix, backslash, or control character."
    }
    val segments = path.split('/')
    require(segments.all { segment ->
      segment.isNotBlank() && segment != "." && segment != ".." &&
        segment.length <= MAX_SEGMENT_CHARS &&
        segment.substringBefore('.').uppercase(Locale.ROOT) !in WINDOWS_RESERVED_NAMES
    }) { "ZIP entry '$path' contains an unsafe path segment." }
    return path
  }

  private fun validatePathCollisions(entries: List<PlannedEntry>) {
    val filePaths = entries.filterNot { it.isDirectory }.map { it.path.lowercase(Locale.ROOT) }.toSet()
    entries.forEach { entry ->
      var parent = entry.path.substringBeforeLast('/', "")
      while (parent.isNotEmpty()) {
        require(parent.lowercase(Locale.ROOT) !in filePaths) {
          "ZIP path '${entry.path}' conflicts with a file entry '$parent'."
        }
        parent = parent.substringBeforeLast('/', "")
      }
    }
  }

  private fun requireParentWithinTree(tree: Uri, parentId: String) {
    val rootId = DocumentsContract.getTreeDocumentId(tree)
    if (parentId == rootId) return
    val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
    require(DocumentsContract.isChildDocument(resolver, root, parent)) {
      "The requested destination folder is not inside the selected persisted SAF tree."
    }
  }

  private fun requireTreeGrant(treeUri: String, write: Boolean): Uri {
    val tree = Uri.parse(treeUri)
    require(tree.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(tree)) {
      "A valid persisted SAF document-tree URI is required."
    }
    val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == tree }
      ?: throw SecurityException("Gallery has no persisted permission for this document tree.")
    require(grant.isReadPermission && (!write || grant.isWritePermission)) {
      if (write) "The SAF tree requires persisted read and write permissions."
      else "The SAF tree requires persisted read permission."
    }
    return tree
  }

  private fun queryDocument(uri: Uri): DocumentMetadata {
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
        return DocumentMetadata(
          string(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
          string(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
          string(DocumentsContract.Document.COLUMN_MIME_TYPE),
          long(DocumentsContract.Document.COLUMN_SIZE),
        )
      }
    }
    throw IllegalStateException("The SAF provider did not return document metadata.")
  }

  private fun listChildNames(tree: Uri, parentId: String): Set<String> {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
    val names = mutableSetOf<String>()
    resolver.query(children, PROJECTION, null, null, null)?.use { cursor ->
      val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
      while (cursor.moveToNext()) if (index >= 0 && !cursor.isNull(index)) names += cursor.getString(index)
    } ?: throw IllegalStateException("The SAF provider returned no folder listing.")
    return names
  }

  private suspend fun cleanup(created: List<Uri>) {
    withContext(NonCancellable + Dispatchers.IO) {
      created.asReversed().forEach { uri ->
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
      }
    }
  }

  private fun payloadString(request: ArtifactCreateRequest, key: String): String {
    val value = request.payload.get(key)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.isNotBlank()) {
      "ZIP extraction requires payload.$key from the latest document_tree result."
    }
    return value.asString
  }

  private fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
    "txt", "md", "log" -> "text/plain"
    "csv" -> "text/csv"
    "json" -> "application/json"
    "xml" -> "application/xml"
    "pdf" -> "application/pdf"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    else -> "application/octet-stream"
  }

  private fun failure(request: ArtifactCreateRequest, message: String) = ArtifactGenerationResult(
    false, "zip", request.fileName, "Not written", null, null, null, message,
  )

  private class BoundedCountingInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    var bytesRead: Long = 0
      private set
    override fun read(): Int {
      val value = super.read()
      if (value >= 0) addCount(1)
      return value
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      val count = super.read(buffer, offset, length)
      if (count > 0) addCount(count)
      return count
    }
    private fun addCount(count: Int) {
      bytesRead += count
      require(bytesRead <= limit) { "ZIP compressed input exceeds the $limit byte limit." }
    }
  }

  private data class DocumentMetadata(
    val documentId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
  )
  private data class PlannedEntry(val path: String, val isDirectory: Boolean, val expandedBytes: Long)
  private data class ExtractionPlan(
    val entries: List<PlannedEntry>,
    val files: Int,
    val directories: Int,
    val totalExpandedBytes: Long,
    val compressedBytes: Long,
  )

  private companion object {
    const val OPERATION_TIMEOUT_MS = 120_000L
    const val MAX_ARCHIVE_BYTES = 50L * 1024L * 1024L
    const val MAX_TOTAL_EXPANDED_BYTES = 50L * 1024L * 1024L
    const val MAX_ENTRY_EXPANDED_BYTES = 10L * 1024L * 1024L
    const val MAX_COMPRESSION_RATIO = 100L
    const val MAX_ENTRIES = 500
    const val MAX_PATH_CHARS = 240
    const val MAX_SEGMENT_CHARS = 128
    const val BUFFER_BYTES = 32 * 1024
    val ZIP_MIME_TYPES = setOf("application/zip", "application/x-zip-compressed")
    val WINDOWS_RESERVED_NAMES = setOf("CON", "PRN", "AUX", "NUL") +
      (1..9).flatMap { listOf("COM$it", "LPT$it") }
    val PROJECTION = arrayOf(
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE,
      DocumentsContract.Document.COLUMN_SIZE,
    )
  }
}
