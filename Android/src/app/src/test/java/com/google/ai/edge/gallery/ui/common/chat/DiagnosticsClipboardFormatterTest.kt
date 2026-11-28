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

package com.google.ai.edge.gallery.ui.common.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsClipboardFormatterTest {
  @Test
  fun includesHeaderAcceleratorAndEveryLogEntry() {
    val formatted = formatDiagnosticsForClipboard(
      title = "Execution Details",
      accelerator = "CPU",
      logs = listOf(
        LogMessage(LogMessageLevel.Info, "Model input", message = "turn=1, visibleTools=2"),
        LogMessage(LogMessageLevel.Warning, "Tool dispatch", message = "battery_status failed"),
        LogMessage(LogMessageLevel.Error, "Runtime", lineNumber = 42, message = "Failure"),
      ),
    )

    assertEquals(
      """Execution Details
Accelerator: CPU
Entries: 3
[Info] Model input — turn=1, visibleTools=2
[Warning] Tool dispatch — battery_status failed
[Error] Runtime:42 — Failure""",
      formatted,
    )
  }

  @Test
  fun omitsAcceleratorWhenUnavailableAndPreservesMessagesWithoutSource() {
    val formatted = formatDiagnosticsForClipboard(
      title = "Execution Details",
      accelerator = "",
      logs = listOf(LogMessage(level = LogMessageLevel.Info, message = "Run completed")),
    )

    assertEquals(
      """Execution Details
Entries: 1
[Info] Run completed""",
      formatted,
    )
  }
  @Test
  fun executionDetailsPanelPreservesCopyActionWhenCloned() {
    val panel = ChatMessageCollapsableProgressPanel(
      title = "Execution Details",
      inProgress = false,
      accelerator = "CPU",
      copyLogsEnabled = true,
    )

    val cloned = panel.clone() as ChatMessageCollapsableProgressPanel

    assertEquals(true, cloned.copyLogsEnabled)
  }

}
