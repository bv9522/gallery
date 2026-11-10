/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * This utility exposes content fingerprints only; it never logs command or output contents.
 */

package com.google.ai.edge.gallery.agent

import android.util.Log
import java.security.MessageDigest

internal object ExecutionFingerprint {
  fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte ->
      "%02x".format(byte.toInt() and 0xff)
    }
  }
}

/** Logging is best-effort so local JVM tests without Android's Log runtime remain executable. */
internal fun logExecutionIntegrity(tag: String, message: String) {
  runCatching { Log.i(tag, message) }
}
