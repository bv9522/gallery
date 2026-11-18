/*
 * Copyright 2026 Google LLC
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.ai.edge.gallery.agent.capability

/** Pure, bounded text handling for explicitly requested active-notification snapshots. */
internal object NotificationContentPolicy {
  fun sanitize(value: String?, maxChars: Int): String? {
    require(maxChars > 0) { "maxChars must be positive." }
    if (value == null) return null
    val normalized = buildString {
      value.forEach { character ->
        append(if (character.isISOControl()) ' ' else character)
      }
    }.replace(Regex("\\s+"), " ").trim().take(maxChars)
    return normalized.takeIf { it.isNotEmpty() }
  }
}
