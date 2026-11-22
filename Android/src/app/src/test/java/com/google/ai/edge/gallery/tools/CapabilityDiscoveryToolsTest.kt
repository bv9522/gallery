/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityDiscoveryToolsTest {
  @Test
  fun discoveryRequiresEveryQueryTermToMatch() {
    val values = listOf(
      "Google Drive list files",
      "Google Drive create folder",
      "Calendar list events",
    )

    assertEquals(
      listOf("Google Drive list files"),
      selectDiscoveryMatches(values, "drive list", searchableText = { it }),
    )
  }

  @Test
  fun emptyQueryListsOnlyTheBoundedNumberOfResults() {
    val values = (1..12).map { "skill-$it" }

    val matches = selectDiscoveryMatches(
      values = values,
      query = "",
      searchableText = { it },
      limit = 5,
    )

    assertEquals(5, matches.size)
    assertEquals(values.take(5), matches)
  }

  @Test
  fun descriptionsAreWhitespaceCompactedAndLengthBounded() {
    val compacted = compactDiscoveryDescription("  A useful  description\nwith   extra spaces.  ")
    assertEquals("A useful description with extra spaces.", compacted)

    val longDescription = compactDiscoveryDescription("x".repeat(500))
    assertEquals(240, longDescription.length)
    assertTrue(longDescription.endsWith("…"))
  }
}
