/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentPromptComponentSizeTest {
  @Test
  fun recordsCharacterAndUtf8ByteCountsWithoutRetainingSourceText() {
    val source = "Aé🙂"
    val size = AgentPromptComponentSize.from("initial_message_1", source)

    assertEquals("initial_message_1", size.name)
    assertEquals(source.length, size.characterCount)
    assertEquals(source.toByteArray(Charsets.UTF_8).size, size.utf8ByteCount)
    assertFalse(size.toString().contains(source))
  }

  @Test
  fun emptyComponentHasZeroSize() {
    val size = AgentPromptComponentSize.from("mcp_tools_prompt", "")

    assertEquals(0, size.characterCount)
    assertEquals(0, size.utf8ByteCount)
  }
}
