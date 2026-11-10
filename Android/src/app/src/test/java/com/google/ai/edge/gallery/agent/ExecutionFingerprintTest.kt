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
import org.junit.Test

class ExecutionFingerprintTest {
  @Test
  fun sha256MatchesStandardKnownVector() {
    assertEquals(
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
      ExecutionFingerprint.sha256("abc"),
    )
  }

  @Test
  fun sha256PreservesWhitespaceAndNewlines() {
    assertEquals(
      ExecutionFingerprint.sha256("marker\n"),
      ExecutionFingerprint.sha256("marker\n"),
    )
    assertTrueDifferent(ExecutionFingerprint.sha256("marker"), ExecutionFingerprint.sha256("marker\n"))
  }

  private fun assertTrueDifferent(first: String, second: String) {
    org.junit.Assert.assertNotEquals(first, second)
  }
}
