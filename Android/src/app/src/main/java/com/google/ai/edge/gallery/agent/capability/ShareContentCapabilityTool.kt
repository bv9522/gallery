/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.capability

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.google.ai.edge.gallery.agent.coordinator.AgentTool
import com.google.ai.edge.gallery.agent.coordinator.AgentToolResult
import com.google.ai.edge.gallery.tools.ShareContentToolAction
import com.google.ai.edge.gallery.tools.ToolAction
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withTimeoutOrNull

/** Shares only after scoped approval and only through Android's user-controlled chooser. */
class ShareContentCapabilityTool internal constructor(
  private val context: Context,
  private val authorizer: NativeCapabilityAuthorizer,
  private val requester: ShareContentRequester,
) : AgentTool {
  constructor(context: Context, actionChannel: SendChannel<ToolAction>) : this(
    context.applicationContext,
    ChannelNativeCapabilityAuthorizer(actionChannel),
    ChannelShareContentRequester(actionChannel),
  )

  override val name = "share_content"
  override val description =
    "share_text previews and approves exact text, then opens Android's share chooser. share_file accepts only tree_uri and document_id from an existing document_tree grant/list result, verifies the persisted read grant and file metadata, then opens Android's chooser with temporary read access. The user chooses the receiving app and completes or cancels. Never claim delivery merely because the chooser opened. No receiver is targeted and no background send occurs."

  override val parametersSchema: JsonObject
    get() = JsonObject().apply {
      addProperty("type", "object")
      add("properties", JsonObject().apply {
        add("operation", JsonObject().apply {
          addProperty("type", "string")
          add("enum", JsonArray().apply { add("share_text"); add("share_file") })
        })
        add("text", JsonObject().apply { addProperty("type", "string"); addProperty("maxLength", MAX_TEXT_CHARS) })
        add("tree_uri", JsonObject().apply { addProperty("type", "string") })
        add("document_id", JsonObject().apply { addProperty("type", "string"); addProperty("maxLength", MAX_DOCUMENT_ID_CHARS) })
      })
      add("required", JsonArray().apply { add("operation") })
      addProperty("additionalProperties", false)
    }

  override suspend fun execute(arguments: JsonObject): AgentToolResult {
    ShareContentArguments.validate(arguments)?.let { return AgentToolResult(it, false) }
    return try {
      when (arguments.get("operation").asString) {
        "share_text" -> shareText(arguments.get("text").asString)
        "share_file" -> shareFile(arguments.get("tree_uri").asString, arguments.get("document_id").asString)
        else -> AgentToolResult("Unsupported share operation.", false)
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Exception) {
      AgentToolResult(error.message ?: "The share could not be prepared.", false)
    }
  }

  private suspend fun shareText(text: String): AgentToolResult {
    val preview = text.take(500) + if (text.length > 500) "…" else ""
    if (!authorizer.authorize(AndroidCapability.SHARE_CONTENT, "Open Android's share chooser with this exact text:\n\n" + preview)) {
      return AgentToolResult("Text sharing was denied or approval was unavailable.", false)
    }
    val opened = requester.request(ShareContentToolAction("text/plain", text = text, chooserTitle = "Share text"))
    return if (opened) AgentToolResult("Android's share chooser was opened with the approved text. The user must choose a recipient and complete or cancel; delivery is not confirmed.")
    else AgentToolResult("Android's share chooser could not be opened.", false)
  }

  private suspend fun shareFile(treeUri: String, documentId: String): AgentToolResult {
    val tree = Uri.parse(treeUri)
    require(tree.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(tree)) {
      "tree_uri must be a content document-tree URI returned by document_tree."
    }
    val grant = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == tree }
      ?: throw SecurityException("Gallery has no persisted permission for this tree. Use document_tree request_access first.")
    require(grant.isReadPermission) { "The selected document tree has no persisted read permission." }
    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
    val projection = arrayOf(
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE,
      DocumentsContract.Document.COLUMN_SIZE,
    )
    val metadata = context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
      if (!cursor.moveToFirst()) null else {
        val id = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
        require(id == documentId) { "The selected document does not belong to the granted tree." }
        val name = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)).orEmpty()
        val mime = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)).orEmpty()
        val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
        val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
        Triple(name, mime, size)
      }
    } ?: throw IllegalArgumentException("The document was not found in the selected tree.")
    val (name, mime, size) = metadata
    require(mime.isNotBlank() && mime != DocumentsContract.Document.MIME_TYPE_DIR) { "Only individual files can be shared." }
    require(size in 0..MAX_FILE_BYTES) { "File size must be known and no larger than 25 MiB to share." }
    val summary = "Open Android's share chooser for '" + name + "' (" + mime + ", " + size + " bytes). The receiver gets temporary read-only access."
    if (!authorizer.authorize(AndroidCapability.SHARE_CONTENT, summary)) {
      return AgentToolResult("File sharing was denied or approval was unavailable.", false)
    }
    val opened = requester.request(
      ShareContentToolAction(mime, contentUri = uri.toString(), displayName = name, chooserTitle = "Share " + name)
    )
    return if (opened) AgentToolResult("Android's share chooser was opened for '" + name + "'. The user must choose a recipient and complete or cancel; delivery is not confirmed.")
    else AgentToolResult("Android's share chooser could not be opened for '" + name + "'.", false)
  }

  companion object {
    const val MAX_TEXT_CHARS = 4_000
    const val MAX_FILE_BYTES = 25L * 1024L * 1024L
    const val MAX_DOCUMENT_ID_CHARS = 1_024
  }
}

fun interface ShareContentRequester {
  suspend fun request(action: ShareContentToolAction): Boolean
}

class ChannelShareContentRequester(
  private val actionChannel: SendChannel<ToolAction>,
  private val timeoutMs: Long = 120_000L,
) : ShareContentRequester {
  override suspend fun request(action: ShareContentToolAction): Boolean =
    try {
      withTimeoutOrNull(timeoutMs) {
        actionChannel.send(action)
        action.result.await()
      } ?: false
    } finally {
      if (!action.result.isCompleted) action.result.complete(false)
    }
}

internal object ShareContentArguments {
  fun validate(arguments: JsonObject): String? {
    val operation = arguments.get("operation")
      ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
      ?: return "operation must be share_text or share_file."
    val accepted = when (operation) {
      "share_text" -> setOf("operation", "text")
      "share_file" -> setOf("operation", "tree_uri", "document_id")
      else -> return "Unsupported share operation. Choose share_text or share_file."
    }
    val unexpected = arguments.keySet() - accepted
    if (unexpected.isNotEmpty()) return "Unexpected argument(s): " + unexpected.sorted().joinToString(", ") + "."
    if (operation == "share_text") {
      val text = arguments.get("text")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: return "share_text requires a string text argument."
      if (text.isBlank() || text.length > ShareContentCapabilityTool.MAX_TEXT_CHARS) {
        return "text must contain 1–4000 non-whitespace characters."
      }
    } else {
      val tree = arguments.get("tree_uri")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: return "share_file requires tree_uri from document_tree grants/request_access."
      val id = arguments.get("document_id")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        ?: return "share_file requires document_id from a document_tree list/search result."
      if (!tree.startsWith("content://") || tree.length > 2_048 || !tree.contains("/tree/")) {
        return "tree_uri must be a content document-tree URI from document_tree."
      }
      if (id.isBlank() || id.length > ShareContentCapabilityTool.MAX_DOCUMENT_ID_CHARS || id.any { it.isISOControl() }) {
        return "document_id must contain 1–1024 non-control characters."
      }
    }
    return null
  }
}
