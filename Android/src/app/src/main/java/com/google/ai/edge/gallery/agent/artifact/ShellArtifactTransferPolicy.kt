/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.artifact

import com.google.ai.edge.gallery.agent.coordinator.ShellExecutionBackend
import java.util.Base64

/** Pure limits and backend policy shared by the shell artifact adapter and its unit tests. */
internal object ShellArtifactTransferPolicy {
  const val TERMUX_RAW_CHUNK_BYTES = 96 * 1_024
  const val MAX_TERMUX_STDIN_CHARS = 128 * 1_024
  const val MAX_STAGED_ARTIFACT_BYTES = 50L * 1_024L * 1_024L

  fun supportsBinaryOutput(backend: ShellExecutionBackend): Boolean =
    backend == ShellExecutionBackend.TERMUX || backend == ShellExecutionBackend.SHIZUKU

  fun encodeTermuxChunk(buffer: ByteArray, count: Int): String {
    require(count in 1..minOf(buffer.size, TERMUX_RAW_CHUNK_BYTES)) {
      "Termux binary chunk length is outside the supported bound."
    }
    return Base64.getEncoder().encodeToString(buffer.copyOf(count)).also {
      require(it.length <= MAX_TERMUX_STDIN_CHARS) {
        "Encoded Termux stdin chunk exceeds the configured Intent-extra bound."
      }
    }
  }
}
