/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.google.ai.edge.gallery.agent.capability

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

data class DocumentTreeGrant(
  val treeUri: String,
  val rootDocumentId: String,
  val canRead: Boolean,
  val canWrite: Boolean,
)
data class DocumentEntry(
  val documentId: String,
  val displayName: String,
  val mimeType: String,
  val sizeBytes: Long?,
  val lastModifiedMs: Long?,
  val flags: Long?,
)
data class DocumentListing(val entries: List<DocumentEntry>, val truncated: Boolean)
data class DocumentText(val document: DocumentEntry, val text: String)
data class DocumentSearchResult(
  val matches: List<DocumentEntry>,
  val visitedCount: Int,
  val truncated: Boolean,
)

interface DocumentTreeOperations : CapabilityProviderProbe {
  suspend fun listGrants(): List<DocumentTreeGrant>
  suspend fun requestAccess(): DocumentTreeGrant?
  suspend fun list(treeUri: String, parentDocumentId: String?): DocumentListing
  suspend fun metadata(treeUri: String, documentId: String): DocumentEntry
  suspend fun readText(treeUri: String, documentId: String): DocumentText
  suspend fun createFile(treeUri: String, parentDocumentId: String?, name: String, mimeType: String): String
  suspend fun createTextFile(treeUri: String, parentDocumentId: String?, name: String, mimeType: String, text: String): String
  suspend fun createDirectory(treeUri: String, parentDocumentId: String?, name: String): String
  suspend fun writeText(treeUri: String, documentId: String, text: String): Long
  suspend fun rename(treeUri: String, documentId: String, newName: String): String
  suspend fun delete(treeUri: String, documentId: String): Boolean
  suspend fun copy(treeUri: String, documentId: String, targetParentDocumentId: String): String
  suspend fun move(treeUri: String, documentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String
  suspend fun copyBetweenTrees(sourceTreeUri: String, documentId: String, targetTreeUri: String, targetParentDocumentId: String): String
  suspend fun moveBetweenTrees(sourceTreeUri: String, documentId: String, targetTreeUri: String, targetParentDocumentId: String): String
  suspend fun search(treeUri: String, query: String, parentDocumentId: String?): DocumentSearchResult
}

fun interface DocumentTreePickerRequester {
  suspend fun requestTree(): String?
}

class ChannelDocumentTreePickerRequester(
  private val actionChannel: SendChannel<com.google.ai.edge.gallery.tools.ToolAction>,
  private val timeoutMs: Long = 120_000L,
) : DocumentTreePickerRequester {
  override suspend fun requestTree(): String? {
    val action = com.google.ai.edge.gallery.tools.OpenDocumentTreeToolAction()
    return try {
      withTimeoutOrNull(timeoutMs) {
        actionChannel.send(action)
        action.result.await()
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      null
    } finally {
      if (!action.result.isCompleted) action.result.complete(null)
    }
  }
}

/** All access is constrained to persisted user-selected SAF tree grants. */
class AndroidDocumentTreeAdapter(
  context: Context,
  private val authorizer: NativeCapabilityAuthorizer,
  private val pickerRequester: DocumentTreePickerRequester,
) : DocumentTreeOperations {
  private val appContext = context.applicationContext
  private val resolver: ContentResolver = appContext.contentResolver

  override val descriptor: CapabilityProviderDescriptor =
    CapabilityCatalog.providers.single { it.id == CapabilityProviderId.ANDROID_DOCUMENTS }

  override suspend fun inspect(): CapabilityProviderObservation {
    val grants = resolver.persistedUriPermissions.filter { DocumentsContract.isTreeUri(it.uri) }
    val canRead = grants.any { it.isReadPermission }
    val canWrite = grants.any { it.isWritePermission }
    val availability = mapOf(
      AndroidCapability.DOCUMENT_TREE_LIST to permissionState(canRead),
      AndroidCapability.DOCUMENT_TREE_READ to permissionState(canRead),
      AndroidCapability.DOCUMENT_TREE_METADATA_READ to permissionState(canRead),
      AndroidCapability.DOCUMENT_TREE_CREATE to permissionState(canWrite),
      AndroidCapability.DOCUMENT_TREE_WRITE to permissionState(canWrite),
      AndroidCapability.DOCUMENT_TREE_RENAME to permissionState(canWrite),
      AndroidCapability.DOCUMENT_TREE_DELETE to permissionState(canWrite),
      AndroidCapability.DOCUMENT_TREE_COPY to permissionState(canWrite),
      AndroidCapability.DOCUMENT_TREE_MOVE to permissionState(canWrite),
      AndroidCapability.DOCUMENT_TREE_SEARCH to permissionState(canRead),
      AndroidCapability.DOCUMENT_TREE_METADATA_WRITE to ProviderAvailability.UNSUPPORTED,
    )
    val details = mapOf(
      AndroidCapability.DOCUMENT_TREE_METADATA_WRITE to "General SAF metadata writes are not supported; rename is exposed separately.",
    )
    return CapabilityProviderObservation(
      providerId = descriptor.id,
      availability = if (canRead || canWrite) ProviderAvailability.AVAILABLE else ProviderAvailability.PERMISSION_REQUIRED,
      detail = if (canRead || canWrite) "User-granted document trees are available."
      else "No persisted document-tree grant exists; use request_access to let the user choose a folder or USB drive.",
      capabilityAvailability = availability,
      capabilityDetails = details,
    )
  }

  override suspend fun listGrants(): List<DocumentTreeGrant> {
    authorize(AndroidCapability.DOCUMENT_TREE_LIST, "List folders and removable-storage roots granted to Gallery.")
    return resolver.persistedUriPermissions
      .filter { DocumentsContract.isTreeUri(it.uri) && (it.isReadPermission || it.isWritePermission) }
      .map { DocumentTreeGrant(it.uri.toString(), DocumentsContract.getTreeDocumentId(it.uri), it.isReadPermission, it.isWritePermission) }
  }

  override suspend fun requestAccess(): DocumentTreeGrant? {
    authorize(
      AndroidCapability.DOCUMENT_TREE_LIST,
      "Open Android's folder picker so the user can grant Gallery access to a folder or attached USB drive.",
    )
    val selected = pickerRequester.requestTree() ?: return null
    val uri = Uri.parse(selected)
    require(uri.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(uri)) {
      "The picker did not return a valid document-tree URI."
    }
    resolver.takePersistableUriPermission(
      uri,
      Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )
    return DocumentTreeGrant(
      treeUri = uri.toString(),
      rootDocumentId = DocumentsContract.getTreeDocumentId(uri),
      canRead = true,
      canWrite = true,
    )
  }

  override suspend fun list(treeUri: String, parentDocumentId: String?): DocumentListing {
    val tree = requireTreeGrant(treeUri, write = false)
    authorize(AndroidCapability.DOCUMENT_TREE_LIST, "List documents in the selected tree.")
    return withContext(Dispatchers.IO) {
      val parentId = parentDocumentId ?: DocumentsContract.getTreeDocumentId(tree)
      val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
      val entries = mutableListOf<DocumentEntry>()
      var truncated = false
      resolver.query(childrenUri, DOCUMENT_PROJECTION, null, null, null)?.use { cursor ->
        while (cursor.moveToNext()) {
          if (entries.size >= MAX_LIST_ENTRIES) {
            truncated = true
            break
          }
          entries += cursor.toDocumentEntry()
        }
      } ?: throw IllegalStateException("The document provider returned no listing cursor.")
      DocumentListing(entries, truncated)
    }
  }

  override suspend fun metadata(treeUri: String, documentId: String): DocumentEntry {
    val tree = requireTreeGrant(treeUri, write = false)
    val entry = withContext(Dispatchers.IO) { queryMetadata(tree, documentId) }
    authorize(AndroidCapability.DOCUMENT_TREE_METADATA_READ, "Read metadata for '" + entry.displayName + "'.")
    return entry
  }

  override suspend fun readText(treeUri: String, documentId: String): DocumentText {
    val tree = requireTreeGrant(treeUri, write = false)
    val entry = withContext(Dispatchers.IO) { queryMetadata(tree, documentId) }
    require(isTextMime(entry.mimeType)) { "Only text, JSON, XML, CSV, Markdown, and YAML documents can be read as text." }
    require(entry.sizeBytes == null || entry.sizeBytes <= MAX_TEXT_BYTES) {
      "Document exceeds $MAX_TEXT_BYTES bytes."
    }
    authorize(AndroidCapability.DOCUMENT_TREE_READ, "Read the contents of '" + entry.displayName + "'.")
    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
    val text = withContext(Dispatchers.IO) {
      val stream = resolver.openInputStream(uri)
        ?: throw IllegalStateException("Could not open '" + entry.displayName + "'.")
      stream.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
          val count = input.read(buffer)
          if (count < 0) break
          total += count
          require(total <= MAX_TEXT_BYTES) { "Document exceeds $MAX_TEXT_BYTES bytes." }
          output.write(buffer, 0, count)
        }
        String(output.toByteArray(), StandardCharsets.UTF_8)
      }
    }
    return DocumentText(entry, text)
  }

  override suspend fun createFile(
    treeUri: String,
    parentDocumentId: String?,
    name: String,
    mimeType: String,
  ): String {
    val tree = requireTreeGrant(treeUri, write = true)
    validateName(name)
    require(mimeType in ALLOWED_CREATE_MIME_TYPES) { "mime_type is not in the supported text-file MIME allowlist." }
    authorize(AndroidCapability.DOCUMENT_TREE_CREATE, "Create '" + name + "' in the selected document folder.")
    return withContext(Dispatchers.IO) {
      val parentId = parentDocumentId ?: DocumentsContract.getTreeDocumentId(tree)
      val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
      val created = DocumentsContract.createDocument(resolver, parent, mimeType, name)
        ?: throw IllegalStateException("The document provider refused to create '" + name + "'.")
      created.toString()
    }
  }

  override suspend fun createTextFile(
    treeUri: String,
    parentDocumentId: String?,
    name: String,
    mimeType: String,
    text: String,
  ): String {
    val tree = requireTreeGrant(treeUri, write = true)
    validateName(name)
    require(mimeType in ALLOWED_CREATE_MIME_TYPES) { "mime_type is not in the supported text-file MIME allowlist." }
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    require(bytes.size <= MAX_TEXT_BYTES) { "Text exceeds $MAX_TEXT_BYTES bytes." }
    authorize(
      AndroidCapability.DOCUMENT_TREE_CREATE,
      "Create and populate '" + name + "' (" + mimeType + ", " + bytes.size + " UTF-8 bytes). Preview: " + text.take(80),
    )
    return withContext(Dispatchers.IO) {
      val parentId = parentDocumentId ?: DocumentsContract.getTreeDocumentId(tree)
      val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
      val created = DocumentsContract.createDocument(resolver, parent, mimeType, name)
        ?: throw IllegalStateException("The document provider refused to create '" + name + "'.")
      try {
        val stream = resolver.openOutputStream(created, "wt")
          ?: throw IllegalStateException("Could not open the new document for writing.")
        stream.use { output -> output.write(bytes); output.flush() }
        created.toString()
      } catch (error: Throwable) {
        runCatching { DocumentsContract.deleteDocument(resolver, created) }
        throw error
      }
    }
  }

  override suspend fun createDirectory(treeUri: String, parentDocumentId: String?, name: String): String {
    val tree = requireTreeGrant(treeUri, write = true)
    validateName(name)
    authorize(AndroidCapability.DOCUMENT_TREE_CREATE, "Create directory '" + name + "' in the selected document folder.")
    return withContext(Dispatchers.IO) {
      val parentId = parentDocumentId ?: DocumentsContract.getTreeDocumentId(tree)
      val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
      val created = DocumentsContract.createDocument(
        resolver,
        parent,
        DocumentsContract.Document.MIME_TYPE_DIR,
        name,
      ) ?: throw IllegalStateException("The document provider refused to create directory '" + name + "'.")
      created.toString()
    }
  }

  override suspend fun writeText(treeUri: String, documentId: String, text: String): Long {
    val tree = requireTreeGrant(treeUri, write = true)
    val entry = withContext(Dispatchers.IO) { queryMetadata(tree, documentId) }
    require(isTextMime(entry.mimeType)) { "Only supported text documents can be written by write_text." }
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    require(bytes.size <= MAX_TEXT_BYTES) { "Text exceeds $MAX_TEXT_BYTES bytes." }
    authorize(
      AndroidCapability.DOCUMENT_TREE_WRITE,
      "Write " + bytes.size + " UTF-8 bytes to '" + entry.displayName + "'. Preview: " + text.take(80),
    )
    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
    withContext(Dispatchers.IO) {
      val stream = resolver.openOutputStream(uri, "wt")
        ?: throw IllegalStateException("Could not open '" + entry.displayName + "' for writing.")
      stream.use {
        it.write(bytes)
        it.flush()
      }
    }
    return bytes.size.toLong()
  }

  override suspend fun rename(treeUri: String, documentId: String, newName: String): String {
    val tree = requireTreeGrant(treeUri, write = true)
    validateName(newName)
    val entry = withContext(Dispatchers.IO) { queryMetadata(tree, documentId) }
    authorize(AndroidCapability.DOCUMENT_TREE_RENAME, "Rename '" + entry.displayName + "' to '" + newName + "'.")
    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
    return withContext(Dispatchers.IO) {
      val renamed = DocumentsContract.renameDocument(resolver, uri, newName)
        ?: throw IllegalStateException("The document provider refused to rename '" + entry.displayName + "'.")
      renamed.toString()
    }
  }

  override suspend fun copy(
    treeUri: String,
    documentId: String,
    targetParentDocumentId: String,
  ): String {
    val tree = requireTreeGrant(treeUri, write = true)
    val entry = withContext(Dispatchers.IO) { queryMetadata(tree, documentId) }
    if (entry.flags != null && (entry.flags.toInt() and DocumentsContract.Document.FLAG_SUPPORTS_COPY) == 0) {
      throw IllegalArgumentException("The document provider does not support copying this item.")
    }
    authorize(
      AndroidCapability.DOCUMENT_TREE_COPY,
      "Copy '" + entry.displayName + "' into the selected destination folder.",
    )
    val source = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
    val target = DocumentsContract.buildDocumentUriUsingTree(tree, targetParentDocumentId)
    return withContext(Dispatchers.IO) {
      val copied = DocumentsContract.copyDocument(resolver, source, target)
        ?: throw IllegalStateException("The document provider refused to copy '" + entry.displayName + "'.")
      copied.toString()
    }
  }

  override suspend fun move(
    treeUri: String,
    documentId: String,
    sourceParentDocumentId: String,
    targetParentDocumentId: String,
  ): String {
    val tree = requireTreeGrant(treeUri, write = true)
    val entry = withContext(Dispatchers.IO) { queryMetadata(tree, documentId) }
    if (entry.flags != null && (entry.flags.toInt() and DocumentsContract.Document.FLAG_SUPPORTS_MOVE) == 0) {
      throw IllegalArgumentException("The document provider does not support moving this item.")
    }
    authorize(
      AndroidCapability.DOCUMENT_TREE_MOVE,
      "Move '" + entry.displayName + "' to the selected destination folder.",
    )
    val source = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
    val sourceParent = DocumentsContract.buildDocumentUriUsingTree(tree, sourceParentDocumentId)
    val targetParent = DocumentsContract.buildDocumentUriUsingTree(tree, targetParentDocumentId)
    return withContext(Dispatchers.IO) {
      val moved = DocumentsContract.moveDocument(resolver, source, sourceParent, targetParent)
        ?: throw IllegalStateException("The document provider refused to move '" + entry.displayName + "'.")
      moved.toString()
    }
  }

  override suspend fun copyBetweenTrees(
    sourceTreeUri: String,
    documentId: String,
    targetTreeUri: String,
    targetParentDocumentId: String,
  ): String = transferBetweenTrees(
    sourceTreeUri, documentId, targetTreeUri, targetParentDocumentId, move = false,
  )

  override suspend fun moveBetweenTrees(
    sourceTreeUri: String,
    documentId: String,
    targetTreeUri: String,
    targetParentDocumentId: String,
  ): String = transferBetweenTrees(
    sourceTreeUri, documentId, targetTreeUri, targetParentDocumentId, move = true,
  )

  private suspend fun transferBetweenTrees(
    sourceTreeUri: String,
    documentId: String,
    targetTreeUri: String,
    targetParentDocumentId: String,
    move: Boolean,
  ): String {
    val sourceTree = requireTreeGrant(sourceTreeUri, write = move)
    val targetTree = requireTreeGrant(targetTreeUri, write = true)
    val entry = withContext(Dispatchers.IO) { queryMetadata(sourceTree, documentId) }
    require(entry.mimeType != DocumentsContract.Document.MIME_TYPE_DIR) {
      "Cross-tree transfer currently supports files only; directory transfer is not supported."
    }
    require(entry.sizeBytes == null || entry.sizeBytes <= MAX_TRANSFER_BYTES) {
      "The document exceeds the cross-tree transfer limit of $MAX_TRANSFER_BYTES bytes."
    }
    val targetParent = withContext(Dispatchers.IO) { queryMetadata(targetTree, targetParentDocumentId) }
    require(targetParent.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
      "The destination document must be a directory."
    }
    val capability = if (move) AndroidCapability.DOCUMENT_TREE_MOVE else AndroidCapability.DOCUMENT_TREE_COPY
    val action = if (move) "Move" else "Copy"
    authorize(
      capability,
      action + " '" + entry.displayName + "' from the selected source tree to '" + targetParent.displayName + "'. " +
        if (move) "The source will be deleted only after the destination copy completes." else "The source will remain unchanged.",
    )
    val source = DocumentsContract.buildDocumentUriUsingTree(sourceTree, documentId)
    val destination = withContext(Dispatchers.IO) {
      var created: Uri? = null
      try {
        val parent = DocumentsContract.buildDocumentUriUsingTree(targetTree, targetParentDocumentId)
        created = DocumentsContract.createDocument(resolver, parent, entry.mimeType, entry.displayName)
          ?: throw IllegalStateException("The destination provider refused to create '" + entry.displayName + "'.")
        val input = resolver.openInputStream(source)
          ?: throw IllegalStateException("Could not open the source document for reading.")
        val output = resolver.openOutputStream(created, "wt")
          ?: throw IllegalStateException("Could not open the destination document for writing.")
        var total = 0L
        input.use { sourceStream ->
          output.use { targetStream ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
              currentCoroutineContext().ensureActive()
              val count = sourceStream.read(buffer)
              if (count < 0) break
              total += count
              require(total <= MAX_TRANSFER_BYTES) {
                "The document exceeded the cross-tree transfer limit of $MAX_TRANSFER_BYTES bytes; the incomplete destination was removed."
              }
              targetStream.write(buffer, 0, count)
            }
            targetStream.flush()
          }
        }
        created!!
      } catch (error: Throwable) {
        created?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
        throw error
      }
    }
    if (move) {
      val removed = withContext(Dispatchers.IO) { DocumentsContract.deleteDocument(resolver, source) }
      if (!removed) {
        throw IllegalStateException(
          "The destination copy was created at " + destination + ", but the source could not be deleted and remains in place.",
        )
      }
    }
    return if (move) "Moved '" + entry.displayName + "' to " + destination + "; source deleted."
    else "Copied '" + entry.displayName + "' to " + destination + "; source unchanged."
  }

  override suspend fun search(
    treeUri: String,
    query: String,
    parentDocumentId: String?,
  ): DocumentSearchResult {
    val tree = requireTreeGrant(treeUri, write = false)
    require(query.isNotBlank() && query.length <= MAX_SEARCH_QUERY_CHARS && query.none { it == '\u0000' || it.isISOControl() }) {
      "Search query must contain 1–$MAX_SEARCH_QUERY_CHARS printable characters."
    }
    authorize(AndroidCapability.DOCUMENT_TREE_SEARCH, "Search document names for '" + query + "' in the selected tree.")
    return withContext(Dispatchers.IO) {
      val queue = java.util.ArrayDeque<SearchNode>()
      queue.add(SearchNode(parentDocumentId ?: DocumentsContract.getTreeDocumentId(tree), 0))
      val matches = mutableListOf<DocumentEntry>()
      var visited = 0
      var truncated = false
      while (queue.isNotEmpty() && visited < MAX_SEARCH_NODES && matches.size < MAX_SEARCH_RESULTS) {
        val node = queue.removeFirst()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, node.documentId)
        val cursor = resolver.query(childrenUri, DOCUMENT_PROJECTION, null, null, null)
          ?: throw IllegalStateException("The document provider returned no search cursor.")
        cursor.use {
          while (it.moveToNext()) {
            if (visited >= MAX_SEARCH_NODES || matches.size >= MAX_SEARCH_RESULTS) {
              truncated = true
              break
            }
            visited += 1
            val entry = it.toDocumentEntry()
            if (entry.displayName.contains(query, ignoreCase = true)) matches += entry
            if (entry.mimeType == DocumentsContract.Document.MIME_TYPE_DIR && node.depth < MAX_SEARCH_DEPTH) {
              queue.add(SearchNode(entry.documentId, node.depth + 1))
            }
          }
        }
      }
      if (queue.isNotEmpty() || matches.size >= MAX_SEARCH_RESULTS || visited >= MAX_SEARCH_NODES) truncated = true
      DocumentSearchResult(matches, visited, truncated)
    }
  }

  override suspend fun delete(treeUri: String, documentId: String): Boolean {
    val tree = requireTreeGrant(treeUri, write = true)
    val entry = withContext(Dispatchers.IO) { queryMetadata(tree, documentId) }
    authorize(
      AndroidCapability.DOCUMENT_TREE_DELETE,
      "Permanently delete '" + entry.displayName + "' from the selected document tree.",
    )
    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
    return withContext(Dispatchers.IO) { DocumentsContract.deleteDocument(resolver, uri) }
  }

  private suspend fun authorize(capability: AndroidCapability, summary: String) {
    if (!authorizer.authorize(capability, summary)) {
      throw SecurityException(capability.name + " was denied or approval is unavailable.")
    }
  }

  private fun requireTreeGrant(treeUri: String, write: Boolean): Uri {
    val uri = Uri.parse(treeUri)
    require(uri.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(uri)) {
      "tree_uri must be a content URI returned by grants or request_access."
    }
    val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == uri }
      ?: throw SecurityException("Gallery has no persisted permission for this document tree. Use request_access first.")
    if (!grant.isReadPermission || (write && !grant.isWritePermission)) {
      throw SecurityException(if (write) "The selected document tree has no persisted write permission."
      else "The selected document tree has no persisted read permission.")
    }
    return uri
  }

  private fun queryMetadata(tree: Uri, documentId: String): DocumentEntry {
    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
    resolver.query(uri, DOCUMENT_PROJECTION, null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) return cursor.toDocumentEntry()
    } ?: throw IllegalStateException("The document provider returned no metadata cursor.")
    throw IllegalArgumentException("Document '" + documentId + "' was not found in the selected tree.")
  }

  private fun Cursor.toDocumentEntry(): DocumentEntry {
    fun string(column: String): String? {
      val index = getColumnIndex(column)
      return if (index >= 0 && !isNull(index)) getString(index) else null
    }
    fun long(column: String): Long? {
      val index = getColumnIndex(column)
      return if (index >= 0 && !isNull(index)) getLong(index) else null
    }
    val id = string(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
      ?: throw IllegalStateException("Document provider omitted document_id.")
    return DocumentEntry(
      documentId = id,
      displayName = string(DocumentsContract.Document.COLUMN_DISPLAY_NAME).orEmpty(),
      mimeType = string(DocumentsContract.Document.COLUMN_MIME_TYPE).orEmpty(),
      sizeBytes = long(DocumentsContract.Document.COLUMN_SIZE),
      lastModifiedMs = long(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
      flags = long(DocumentsContract.Document.COLUMN_FLAGS),
    )
  }

  private data class SearchNode(val documentId: String, val depth: Int)

  private fun validateName(name: String) {
    require(name.isNotBlank() && name.length <= MAX_NAME_CHARS) {
      "Document name must contain 1–$MAX_NAME_CHARS characters."
    }
    require(name != "." && name != ".." && name.none {
      it == '/' || it == '\\' || it == '\u0000' || it.isISOControl()
    }) {
      "Document name cannot contain path separators, control characters, or traversal names."
    }
  }

  private fun isTextMime(mimeType: String): Boolean =
    mimeType.startsWith("text/") || mimeType in ALLOWED_TEXT_MIME_TYPES

  private fun permissionState(granted: Boolean) =
    if (granted) ProviderAvailability.AVAILABLE else ProviderAvailability.PERMISSION_REQUIRED

  private companion object {
    const val MAX_LIST_ENTRIES = 100
    const val MAX_NAME_CHARS = 128
    const val MAX_TEXT_BYTES = 256 * 1024
    const val MAX_TRANSFER_BYTES = 100L * 1024L * 1024L
    const val MAX_SEARCH_QUERY_CHARS = 128
    const val MAX_SEARCH_NODES = 500
    const val MAX_SEARCH_RESULTS = 50
    const val MAX_SEARCH_DEPTH = 4
    val DOCUMENT_PROJECTION = arrayOf(
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE,
      DocumentsContract.Document.COLUMN_SIZE,
      DocumentsContract.Document.COLUMN_LAST_MODIFIED,
      DocumentsContract.Document.COLUMN_FLAGS,
    )
    val ALLOWED_CREATE_MIME_TYPES = setOf(
      "text/plain", "text/markdown", "text/csv", "application/json", "application/xml",
      "text/xml", "application/yaml",
    )
    val ALLOWED_TEXT_MIME_TYPES = setOf(
      "application/json", "application/xml", "application/yaml", "application/x-yaml",
    )
  }
}
