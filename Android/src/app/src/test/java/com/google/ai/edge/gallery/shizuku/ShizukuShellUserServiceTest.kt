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

package com.google.ai.edge.gallery.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuShellUserServiceTest {
  @Test
  fun stdoutAndStderrShareOneOutputBudget() {
    val capture = ShizukuShellUserService.CommandCapture("completion-token", 10)

    capture.accept(isStdout = true, chunk = "123456")
    capture.accept(isStdout = false, chunk = "abcdef")

    assertEquals(10, capture.stdout().length + capture.stderr().length)
    assertTrue(capture.outputTruncated())
  }
}
