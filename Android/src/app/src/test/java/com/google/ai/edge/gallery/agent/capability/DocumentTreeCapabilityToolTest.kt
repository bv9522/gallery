/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.capability

import com.google.ai.edge.gallery.tools.OpenDocumentTreeToolAction
import com.google.ai.edge.gallery.tools.ToolAction
import com.google.gson.JsonObject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTreeCapabilityToolTest {
  private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"

  @Test
  fun grantsAndRequestAccessReturnPersistedTreeInformation() = runBlocking {
    val operations = FakeOperations()
    val tool = DocumentTreeCapabilityTool(operations)

    val grants = tool.execute(arguments("grants"))
    val requested = tool.execute(arguments("request_access"))

    assertTrue(grants.isSuccessful)
    assertTrue(grants.content.contains("primary:Documents"))
    assertTrue(requested.isSuccessful)
    assertTrue(requested.content.contains(treeUri))
    assertTrue(requested.content.contains("rootDocumentId"))
  }

  @Test
  fun listAndCreateOperationsPreserveTheSelectedTreeAndParentDocumentIds() = runBlocking {
    val operations = FakeOperations()
    val tool = DocumentTreeCapabilityTool(operations)

    val listed = tool.execute(arguments("list").apply {
      addProperty("tree_uri", treeUri)
      addProperty("parent_document_id", "primary:Documents/Reports")
    })
    val created = tool.execute(arguments("create_file").apply {
      addProperty("tree_uri", treeUri)
      addProperty("parent_document_id", "primary:Documents/Reports")
      addProperty("name", "report.json")
      addProperty("mime_type", "application/json")
    })

    assertTrue(listed.isSuccessful)
    assertTrue(created.isSuccessful)
    assertEquals(
      listOf(
        "list:" + treeUri + ":primary:Documents/Reports",
        "create_file:" + treeUri + ":primary:Documents/Reports:report.json:application/json",
      ),
      operations.calls,
    )
  }

  @Test
  fun textAndManagementOperationsDispatchWithExactDocumentIdentifiers() = runBlocking {
    val operations = FakeOperations()
    val tool = DocumentTreeCapabilityTool(operations)

    val read = tool.execute(arguments("read_text").apply {
      addProperty("tree_uri", treeUri)
      addProperty("document_id", "primary:Documents/readme.txt")
    })
    val write = tool.execute(arguments("write_text").apply {
      addProperty("tree_uri", treeUri)
      addProperty("document_id", "primary:Documents/readme.txt")
      addProperty("text", "updated text")
    })
    val rename = tool.execute(arguments("rename").apply {
      addProperty("tree_uri", treeUri)
      addProperty("document_id", "primary:Documents/readme.txt")
      addProperty("new_name", "notes.txt")
    })
    val delete = tool.execute(arguments("delete").apply {
      addProperty("tree_uri", treeUri)
      addProperty("document_id", "primary:Documents/old.txt")
    })

    assertTrue(read.isSuccessful)
    assertTrue(read.content.contains("hello from SAF"))
    assertTrue(write.isSuccessful)
    assertTrue(rename.isSuccessful)
    assertTrue(delete.isSuccessful)
    assertTrue(operations.calls.contains("write:" + treeUri + ":primary:Documents/readme.txt:updated text"))
    assertTrue(operations.calls.contains("rename:" + treeUri + ":primary:Documents/readme.txt:notes.txt"))
    assertTrue(operations.calls.contains("delete:" + treeUri + ":primary:Documents/old.txt"))
  }

  @Test
  fun malformedAndUnexpectedArgumentsFailBeforeDispatch() = runBlocking {
    val operations = FakeOperations()
    val tool = DocumentTreeCapabilityTool(operations)

    val unknown = tool.execute(arguments("grants").apply { addProperty("tree_uri", treeUri) })
    val wrongOptionalType = tool.execute(arguments("create_file").apply {
      addProperty("tree_uri", treeUri)
      addProperty("name", "new.txt")
      addProperty("mime_type", 42)
    })
    val missing = tool.execute(arguments("read_text").apply { addProperty("tree_uri", treeUri) })

    assertFalse(unknown.isSuccessful)
    assertFalse(wrongOptionalType.isSuccessful)
    assertFalse(missing.isSuccessful)
    assertTrue(operations.calls.isEmpty())
  }

  @Test
  fun copyMoveAndNameSearchDispatchWithinTheSelectedTree() = runBlocking {
    val operations = FakeOperations()
    val tool = DocumentTreeCapabilityTool(operations)
    val copied = tool.execute(arguments("copy").apply {
      addProperty("tree_uri", treeUri)
      addProperty("document_id", "primary:Documents/a.txt")
      addProperty("target_parent_document_id", "primary:Documents/Backup")
    })
    val moved = tool.execute(arguments("move").apply {
      addProperty("tree_uri", treeUri)
      addProperty("document_id", "primary:Documents/b.txt")
      addProperty("source_parent_document_id", "primary:Documents")
      addProperty("target_parent_document_id", "primary:Documents/Archive")
    })
    val searched = tool.execute(arguments("search").apply {
      addProperty("tree_uri", treeUri)
      addProperty("query", "readme")
    })
    assertTrue(copied.isSuccessful)
    assertTrue(moved.isSuccessful)
    assertTrue(searched.isSuccessful)
    assertTrue(searched.content.contains("visitedCount"))
    assertEquals(
      listOf(
        "copy:" + treeUri + ":primary:Documents/a.txt:primary:Documents/Backup",
        "move:" + treeUri + ":primary:Documents/b.txt:primary:Documents:primary:Documents/Archive",
        "search:" + treeUri + ":readme:root",
      ),
      operations.calls,
    )
  }

  @Test
  fun generatedTextFileAndCrossTreeTransfersDispatchExactGrantIdentifiers() = runBlocking {
    val operations = FakeOperations()
    val tool = DocumentTreeCapabilityTool(operations)
    val usbTree = "content://com.android.externalstorage.documents/tree/ABCD-1234%3A"
    val generated = tool.execute(arguments("create_text_file").apply {
      addProperty("tree_uri", treeUri)
      addProperty("parent_document_id", "primary:Documents/Reports")
      addProperty("name", "summary.md")
      addProperty("mime_type", "text/markdown")
      addProperty("text", "# Summary")
    })
    val copied = tool.execute(arguments("copy_between_trees").apply {
      addProperty("tree_uri", treeUri)
      addProperty("document_id", "primary:Documents/report.pdf")
      addProperty("target_tree_uri", usbTree)
      addProperty("target_parent_document_id", "ABCD-1234:")
    })
    val moved = tool.execute(arguments("move_between_trees").apply {
      addProperty("tree_uri", usbTree)
      addProperty("document_id", "ABCD-1234:/archive/report.pdf")
      addProperty("target_tree_uri", treeUri)
      addProperty("target_parent_document_id", "primary:Documents/Archive")
    })
    assertTrue(generated.isSuccessful)
    assertTrue(copied.isSuccessful)
    assertTrue(moved.isSuccessful)
    assertTrue(operations.calls.contains("create_text_file:" + treeUri + ":primary:Documents/Reports:summary.md:text/markdown:# Summary"))
    assertTrue(operations.calls.contains("copy_between_trees:" + treeUri + ":primary:Documents/report.pdf:" + usbTree + ":ABCD-1234:"))
    assertTrue(operations.calls.contains("move_between_trees:" + usbTree + ":ABCD-1234:/archive/report.pdf:" + treeUri + ":primary:Documents/Archive"))
  }

  @Test
  fun crossTreeOperationsRejectMissingTargetGrantArgumentsBeforeDispatch() = runBlocking {
    val operations = FakeOperations()
    val tool = DocumentTreeCapabilityTool(operations)
    val copy = tool.execute(arguments("copy_between_trees").apply {
      addProperty("tree_uri", treeUri)
      addProperty("document_id", "primary:Documents/a.txt")
      addProperty("target_parent_document_id", "primary:Documents/Backup")
    })
    assertFalse(copy.isSuccessful)
    assertTrue(operations.calls.isEmpty())
  }

  @Test
  fun generalMetadataWriteRemainsUnsupported() = runBlocking {
    val tool = DocumentTreeCapabilityTool(FakeOperations())
    assertFalse(tool.execute(arguments("metadata_write")).isSuccessful)
  }

  @Test
  fun pickerRequesterPassesTheSelectedUriBackToTheAdapter() = runBlocking {
    val actions = Channel<ToolAction>(Channel.UNLIMITED)
    val requester = ChannelDocumentTreePickerRequester(actions, timeoutMs = 1_000L)
    val selectedUri = treeUri
    val responder = launch {
      val action = actions.receive() as OpenDocumentTreeToolAction
      action.result.complete(selectedUri)
    }

    assertEquals(selectedUri, requester.requestTree())
    responder.join()
  }

  @Test
  fun pickerRequesterFailsClosedWhenNoUiConsumerIsAvailable() = runBlocking {
    val actions = Channel<ToolAction>(capacity = 0)
    val requester = ChannelDocumentTreePickerRequester(actions, timeoutMs = 10L)
    assertEquals(null, requester.requestTree())
  }

  private fun arguments(operation: String) = JsonObject().apply { addProperty("operation", operation) }

  private class FakeOperations : DocumentTreeOperations {
    override val descriptor = CapabilityCatalog.providers.single {
      it.id == CapabilityProviderId.ANDROID_DOCUMENTS
    }
    val calls = mutableListOf<String>()

    override suspend fun inspect() = CapabilityProviderObservation(
      descriptor.id,
      ProviderAvailability.AVAILABLE,
      "Fake document provider.",
      descriptor.capabilities.associateWith { ProviderAvailability.AVAILABLE },
    )

    override suspend fun listGrants() = listOf(
      DocumentTreeGrant(
        "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
        "primary:Documents",
        true,
        true,
      ),
    )

    override suspend fun requestAccess(): DocumentTreeGrant {
      calls += "request_access"
      return DocumentTreeGrant(
        "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
        "primary:Documents",
        true,
        true,
      )
    }

    override suspend fun list(treeUri: String, parentDocumentId: String?): DocumentListing {
      calls += "list:" + treeUri + ":" + (parentDocumentId ?: "root")
      return DocumentListing(
        listOf(DocumentEntry("primary:Documents/readme.txt", "readme.txt", "text/plain", 10, 1, 0)),
        false,
      )
    }

    override suspend fun metadata(treeUri: String, documentId: String): DocumentEntry {
      calls += "metadata:" + treeUri + ":" + documentId
      return DocumentEntry(documentId, "readme.txt", "text/plain", 10, 1, 0)
    }

    override suspend fun readText(treeUri: String, documentId: String): DocumentText {
      calls += "read:" + treeUri + ":" + documentId
      return DocumentText(DocumentEntry(documentId, "readme.txt", "text/plain", 10, 1, 0), "hello from SAF")
    }

    override suspend fun createFile(
      treeUri: String,
      parentDocumentId: String?,
      name: String,
      mimeType: String,
    ): String {
      calls += "create_file:" + treeUri + ":" + (parentDocumentId ?: "root") + ":" + name + ":" + mimeType
      return "content://created/" + name
    }

    override suspend fun createTextFile(
      treeUri: String, parentDocumentId: String?, name: String, mimeType: String, text: String,
    ): String {
      calls += "create_text_file:" + treeUri + ":" + (parentDocumentId ?: "root") + ":" + name + ":" + mimeType + ":" + text
      return "content://created/" + name
    }

    override suspend fun createDirectory(treeUri: String, parentDocumentId: String?, name: String): String {
      calls += "create_directory:" + treeUri + ":" + (parentDocumentId ?: "root") + ":" + name
      return "content://created/" + name
    }

    override suspend fun writeText(treeUri: String, documentId: String, text: String): Long {
      calls += "write:" + treeUri + ":" + documentId + ":" + text
      return text.toByteArray().size.toLong()
    }

    override suspend fun rename(treeUri: String, documentId: String, newName: String): String {
      calls += "rename:" + treeUri + ":" + documentId + ":" + newName
      return "content://renamed/" + newName
    }

    override suspend fun delete(treeUri: String, documentId: String): Boolean {
      calls += "delete:" + treeUri + ":" + documentId
      return true
    }

    override suspend fun copy(treeUri: String, documentId: String, targetParentDocumentId: String): String {
      calls += "copy:" + treeUri + ":" + documentId + ":" + targetParentDocumentId
      return "content://copied"
    }

    override suspend fun move(
      treeUri: String,
      documentId: String,
      sourceParentDocumentId: String,
      targetParentDocumentId: String,
    ): String {
      calls += "move:" + treeUri + ":" + documentId + ":" + sourceParentDocumentId + ":" + targetParentDocumentId
      return "content://moved"
    }

    override suspend fun copyBetweenTrees(
      sourceTreeUri: String, documentId: String, targetTreeUri: String, targetParentDocumentId: String,
    ): String {
      calls += "copy_between_trees:" + sourceTreeUri + ":" + documentId + ":" + targetTreeUri + ":" + targetParentDocumentId
      return "Copied across trees."
    }

    override suspend fun moveBetweenTrees(
      sourceTreeUri: String, documentId: String, targetTreeUri: String, targetParentDocumentId: String,
    ): String {
      calls += "move_between_trees:" + sourceTreeUri + ":" + documentId + ":" + targetTreeUri + ":" + targetParentDocumentId
      return "Moved across trees."
    }

    override suspend fun search(treeUri: String, query: String, parentDocumentId: String?): DocumentSearchResult {
      calls += "search:" + treeUri + ":" + query + ":" + (parentDocumentId ?: "root")
      return DocumentSearchResult(
        listOf(DocumentEntry("primary:Documents/readme.txt", "readme.txt", "text/plain", 10, 1, 0)),
        12,
        true,
      )
    }
  }
}
