/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */

package com.google.ai.edge.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryAllowlistedModelCatalogTest {
  @Test
  fun resolvesOnlyCurrentNonImportedAllowlistModels() {
    val allowlisted = Model(
      name = "gemma_e2b",
      displayName = "Gemma E2B",
    )
    val imported = Model(
      name = "my_imported_model",
      displayName = "My Imported Model",
      downloadInfo = ModelDownloadInfo(imported = true),
    )

    GalleryAllowlistedModelCatalog.replace(listOf(allowlisted, imported))

    assertEquals("Gemma E2B", GalleryAllowlistedModelCatalog.displayName("gemma_e2b"))
    assertNull(GalleryAllowlistedModelCatalog.displayName("my_imported_model"))
    assertNull(GalleryAllowlistedModelCatalog.displayName("unknown_model"))

    GalleryAllowlistedModelCatalog.replace(emptyList())
    assertNull(GalleryAllowlistedModelCatalog.displayName("gemma_e2b"))
  }
}
