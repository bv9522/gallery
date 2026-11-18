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

/** Explicitly discloses the exact remote provider and data before any upload can begin. */
@Composable
fun RemoteImageGenerationPermissionDialog(
  providerId: String,
  prompt: String,
  outputMimeType: String,
  sourceImageLabels: List<String>,
  onResult: (Boolean) -> Unit,
) {
  AlertDialog(
    onDismissRequest = { onResult(false) },
    title = { Text("Send image request to a remote provider?") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text("Provider: $providerId", fontWeight = FontWeight.Bold)
        Text("Output: $outputMimeType")
        Text(
          "Your prompt will leave this device and be sent to the provider. Any listed source images will also be uploaded.",
          color = MaterialTheme.colorScheme.error,
        )
        Text("Prompt", fontWeight = FontWeight.SemiBold)
        Text(prompt)
        if (sourceImageLabels.isNotEmpty()) {
          Text("Source images", fontWeight = FontWeight.SemiBold)
          sourceImageLabels.forEach { Text("• $it") }
        }
        Text(
          "This is a one-time decision for this request only. Denying or dismissing sends no data.",
          style = MaterialTheme.typography.bodySmall,
        )
      }
    },
    confirmButton = {
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = { onResult(true) }, modifier = Modifier.fillMaxWidth()) {
          Text("Send once")
        }
        OutlinedButton(onClick = { onResult(false) }, modifier = Modifier.fillMaxWidth()) {
          Text("Cancel")
        }
      }
    },
  )
}
