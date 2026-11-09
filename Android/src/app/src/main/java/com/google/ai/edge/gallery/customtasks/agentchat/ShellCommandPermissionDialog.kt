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

package com.google.ai.edge.gallery.customtasks.agentchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Displays the exact backend, command, and resource limits before one-time shell approval. */
@Composable
fun ShellCommandPermissionDialog(
  backend: String,
  command: String,
  workingDirectory: String,
  timeoutMs: Long,
  maxOutputChars: Int,
  onResult: (Boolean) -> Unit,
) {
  AlertDialog(
    onDismissRequest = { onResult(false) },
    title = { Text("Approve shell command?") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text("Backend: $backend", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Text("Working directory: $workingDirectory", style = MaterialTheme.typography.bodySmall)
        Text("Timeout: $timeoutMs ms · Output limit: $maxOutputChars characters", style = MaterialTheme.typography.bodySmall)
        Text("Exact command", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Text(command, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Text(
          "Approval applies to this command only. Shell access is not root and can still modify or delete data available to the selected execution identity.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
    },
    confirmButton = {
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = { onResult(true) }, modifier = Modifier.fillMaxWidth()) {
          Text("Allow once")
        }
        OutlinedButton(onClick = { onResult(false) }, modifier = Modifier.fillMaxWidth()) {
          Text("Deny")
        }
      }
    },
  )
}
