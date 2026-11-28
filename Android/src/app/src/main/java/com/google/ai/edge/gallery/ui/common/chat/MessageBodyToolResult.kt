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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private const val COLLAPSED_OUTPUT_CHARS = 1_200

/** Renders coordinator-retained evidence without involving model-authored commentary. */
@Composable
fun MessageBodyToolResult(
  message: ChatMessageToolResult,
  onCopyClicked: (String) -> Unit = {},
) {
  var expanded by remember(message.id) { mutableStateOf(false) }
  val result = message.result
  val raw = result.rawContent
  val canExpand = raw.length > COLLAPSED_OUTPUT_CHARS
  val visible = if (canExpand && !expanded) raw.take(COLLAPSED_OUTPUT_CHARS) + "\n…" else raw

  Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
          text = if (result.succeeded) "Tool result · ${result.toolName}" else "Tool failed · ${result.toolName}",
          style = MaterialTheme.typography.labelLarge,
          color = if (result.succeeded) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
        )
        Text(text = result.toolCallId, style = MaterialTheme.typography.labelSmall)
      }
      if (raw.isEmpty()) {
        Text("(empty result)", style = MaterialTheme.typography.bodySmall)
      } else {
        SelectionContainer {
          Text(
            text = visible,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
          )
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canExpand) {
          TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Show less" else "Show all")
          }
        }
        TextButton(onClick = { onCopyClicked(raw) }) {
          Text("Copy result")
        }
      }
    }
  }
}
