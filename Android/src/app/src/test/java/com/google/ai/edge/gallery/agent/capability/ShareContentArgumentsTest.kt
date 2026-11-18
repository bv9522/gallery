package com.google.ai.edge.gallery.agent.capability

import com.google.gson.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareContentArgumentsTest {
  @Test
  fun acceptsBoundedTextOnly() {
    val valid = JsonObject().apply {
      addProperty("operation", "share_text")
      addProperty("text", "Hello from Gallery")
    }
    val empty = JsonObject().apply {
      addProperty("operation", "share_text")
      addProperty("text", "  ")
    }
    val oversized = JsonObject().apply {
      addProperty("operation", "share_text")
      addProperty("text", "x".repeat(4_001))
    }
    val unexpected = JsonObject().apply {
      addProperty("operation", "share_text")
      addProperty("text", "Hello")
      addProperty("package_name", "com.example.receiver")
    }

    assertNull(ShareContentArguments.validate(valid))
    assertTrue(ShareContentArguments.validate(empty).orEmpty().contains("1–4000"))
    assertTrue(ShareContentArguments.validate(oversized).orEmpty().contains("1–4000"))
    assertTrue(ShareContentArguments.validate(unexpected).orEmpty().contains("Unexpected"))
  }

  @Test
  fun fileSharingRequiresDocumentTreeReferenceNotArbitraryUri() {
    val valid = JsonObject().apply {
      addProperty("operation", "share_file")
      addProperty("tree_uri", "content://com.android.externalstorage.documents/tree/primary%3ADocuments")
      addProperty("document_id", "primary:Documents/report.pdf")
    }
    val arbitraryUri = JsonObject().apply {
      addProperty("operation", "share_file")
      addProperty("tree_uri", "content://media/external/file/123")
      addProperty("document_id", "123")
    }
    val path = JsonObject().apply {
      addProperty("operation", "share_file")
      addProperty("tree_uri", "/storage/emulated/0/Documents")
      addProperty("document_id", "report.pdf")
    }
    val missingId = JsonObject().apply {
      addProperty("operation", "share_file")
      addProperty("tree_uri", "content://com.android.externalstorage.documents/tree/primary%3ADocuments")
    }

    assertNull(ShareContentArguments.validate(valid))
    assertTrue(ShareContentArguments.validate(arbitraryUri).orEmpty().contains("tree_uri"))
    assertTrue(ShareContentArguments.validate(path).orEmpty().contains("tree_uri"))
    assertTrue(ShareContentArguments.validate(missingId).orEmpty().contains("document_id"))
  }

  @Test
  fun unsupportedOperationAndMalformedArgumentsFailClosed() {
    val unsupported = JsonObject().apply { addProperty("operation", "send_sms") }
    val malformed = JsonObject().apply {
      addProperty("operation", "share_file")
      addProperty("tree_uri", "content://provider/tree/x")
      addProperty("document_id", "bad\u0000id")
    }

    assertNotNull(ShareContentArguments.validate(unsupported))
    assertFalse(ShareContentArguments.validate(malformed).isNullOrBlank())
  }
}
