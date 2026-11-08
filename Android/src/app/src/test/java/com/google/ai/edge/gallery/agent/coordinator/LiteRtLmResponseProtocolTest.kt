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

package com.google.ai.edge.gallery.agent.coordinator

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtLmResponseProtocolTest {
  @Test
  fun detectsCanonicalGemmaNativeToolCallAsUnparsedContent() {
    val response =
      """<|tool_call>call:execute_shell{backend:<|"|>SHIZUKU<|"|>,command:<|"|>id<|"|>}<tool_call|>"""

    assertTrue(LiteRtLmResponseProtocol.hasUnparsedToolCallMarkers(response))
  }

  @Test
  fun doesNotFlagOrdinaryAssistantText() {
    assertFalse(
      LiteRtLmResponseProtocol.hasUnparsedToolCallMarkers(
        "The Android shell identity is uid=2000(shell)."
      )
    )
  }
}
