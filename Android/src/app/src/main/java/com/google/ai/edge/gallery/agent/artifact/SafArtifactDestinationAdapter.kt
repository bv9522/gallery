/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.artifact

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Binary SAF writer restricted to Gallery's persisted user-selected document-tree grants.
 * It deliberately does not reuse the text-only document adapter or its 256 KiB limit.
 */
class SafArtifactDestinationAdapter(context: Context) : ArtifactDestinationAdapter {
  private val appContext = context.applicationContext
  private val resolver = appContext.contentResolver
  override val destinationType = DestinationType.SAF

  override suspend fun describe(request: ArtifactCreateRequest): String {
    val destination = request.destination as? ArtifactDestinationSpec.SafTree
      ?: throw IllegalArgumentException("SAF adapter received a non-SAF destination.")
    val tree = requireTreeGrant(destination.treeUri)
    val parentId = destination.parentDocumentId ?: DocumentsContract.getTreeDocumentId(tree)
    val parent = queryDocument(tree, parentId)
    require(parent.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
      "The selected SAF destination is not a folder."
    }
    val existing = findChild(tree, parentId, request.fileName)
    if (existing != null) {
      if (request.overwrite) {
        throw IllegalArgumentException(
          "Safe replacement is not available for this document provider yet; the existing file was left untouched.",
        )
      }
      throw IllegalArgumentException(
        "A file named '${request.fileName}' already exists in the selected folder. Choose another name; nothing was changed.",
      )
    }
    return "SAF folder '${parent.displayName.ifBlank { "selected folder" }}' / ${request.fileName}"
  }

  override suspend fun write(
    request: ArtifactCreateRequest,
    producer: ArtifactOutputProducer,
  ): ArtifactDestinationWriteResult {
    val destination = request.destination as? ArtifactDestinationSpec.SafTree
      ?: throw IllegalArgumentException("SAF adapter received a non-SAF destination.")
    val tree = requireTreeGrant(destination.treeUri)
    val parentId = destination.parentDocumentId ?: DocumentsContract.getTreeDocumentId(tree)
    val parent = queryDocument(tree, parentId)
    require(parent.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
      "The selected SAF destination is not a folder."
    }
    require(findChild(tree, parentId, request.fileName) == null) {
      "The destination file appeared after approval; refusing to overwrite it."
    }
    var created: Uri? = null
    try {
      val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
      val documentUri = withContext(Dispatchers.IO) {
        DocumentsContract.createDocument(resolver, parentUri, request.format.mimeType, request.fileName)
      } ?: throw IllegalStateException("The SAF provider refused to create '${request.fileName}'.")
      created = documentUri
      val metadata = withContext(Dispatchers.IO) {
        val output = resolver.openOutputStream(documentUri, "w")
          ?: throw IllegalStateException("Could not open the new SAF document for binary output.")
        output.use { stream -> producer.write(stream) }
      }
      val actual = queryUriMetadata(documentUri)
      require(actual.mimeType == request.format.mimeType) {
        "The SAF provider reported MIME type '${actual.mimeType}' instead of '${request.format.mimeType}'."
      }
      require(actual.displayName == request.fileName) {
        "The SAF provider changed the requested filename to '${actual.displayName}'."
      }
      ArtifactDestinationWriteResult(
        "SAF folder '${parent.displayName.ifBlank { "selected folder" }}' / ${actual.displayName}",
        metadata,
      )
    } catch (error: Throwable) {
      val partial = created
      if (partial != null) {
        val removed = runCatching {
          withContext(NonCancellable + Dispatchers.IO) { DocumentsContract.deleteDocument(resolver, partial) }
        }.getOrDefault(false)
        if (!removed) {
          error.addSuppressed(
            IllegalStateException("Cleanup of the incomplete SAF artifact could not be confirmed; a partial file may remain."),
          )
        }
      }
      if (error is CancellationException) throw error
      throw error
    }
  }

  private fun requireTreeGrant(treeUri: String): Uri {
    val tree = Uri.parse(treeUri)
    require(tree.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(tree)) {
      "tree_uri must be a content URI returned by Gallery's document-tree grant flow."
    }
    val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == tree }
      ?: throw SecurityException("Gallery has no persisted permission for this document tree. Request access first.")
    if (!grant.isReadPermission || !grant.isWritePermission) {
      throw SecurityException("The selected document tree requires persisted read and write permissions.")
    }
    return tree
  }

  private fun queryDocument(tree: Uri, documentId: String): DocumentMetadata {
    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
    return queryUriMetadata(uri)
  }

  private fun queryUriMetadata(uri: Uri): DocumentMetadata {
    resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
        fun string(column: String): String {
          val index = cursor.getColumnIndex(column)
          return if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else ""
        }
        return DocumentMetadata(
          string(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
          string(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
          string(DocumentsContract.Document.COLUMN_MIME_TYPE),
        )
      }
    }
    throw IllegalStateException("The SAF provider did not return document metadata.")
  }

  private fun findChild(tree: Uri, parentId: String, name: String): DocumentMetadata? {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
    resolver.query(children, PROJECTION, null, null, null)?.use { cursor ->
      while (cursor.moveToNext()) {
        val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        if (nameIndex >= 0 && !cursor.isNull(nameIndex) && cursor.getString(nameIndex) == name) {
          fun string(column: String): String {
            val index = cursor.getColumnIndex(column)
            return if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else ""
          }
          return DocumentMetadata(
            string(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            string(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            string(DocumentsContract.Document.COLUMN_MIME_TYPE),
          )
        }
      }
    } ?: throw IllegalStateException("The SAF provider returned no folder listing.")
    return null
  }

  private data class DocumentMetadata(val id: String, val displayName: String, val mimeType: String)

  private companion object {
    val PROJECTION = arrayOf(
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE,
    )
  }
}
