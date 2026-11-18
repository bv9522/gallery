/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Shows the exact artifact format, destination, name, and replacement behavior before writing. */
@Composable
fun ArtifactGenerationPermissionDialog(
  format: String,
  fileName: String,
  destination: String,
  estimatedBytes: Long?,
  overwrite: Boolean,
  onResult: (Boolean) -> Unit,
) {
  val sizeLabel = estimatedBytes?.let { "$it bytes (estimate)" } ?: "Calculated during generation"
  AlertDialog(
    onDismissRequest = { onResult(false) },
    title = { Text("Approve artifact creation?") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text("Format: $format", fontWeight = FontWeight.Bold)
        Text("File name: $fileName")
        Text("Destination: $destination")
        Text("Estimated size: $sizeLabel")
        Text(
          if (overwrite) "An existing file may be replaced only after the destination adapter confirms the exact target."
          else "Existing files will not be overwritten.",
          color = if (overwrite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(
          "Approval applies to this artifact only. Generated content stays local unless a separate, explicit remote-provider consent is requested.",
          style = MaterialTheme.typography.bodySmall,
        )
      }
    },
    confirmButton = {
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = { onResult(true) }, modifier = Modifier.fillMaxWidth()) { Text("Allow once") }
        OutlinedButton(onClick = { onResult(false) }, modifier = Modifier.fillMaxWidth()) { Text("Deny") }
      }
    },
  )
}
