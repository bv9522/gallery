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

package com.google.ai.edge.gallery.agent.capability

/**
 * Resolves battery percentage from Android's broadcast snapshot first, falling back to the
 * BatteryManager property only when the broadcast level/scale pair is invalid.
 *
 * This pure helper has no Android framework dependencies so conflicting and malformed source
 * values can be covered by deterministic unit tests.
 */
internal fun resolveBatteryPercent(
  broadcastLevel: Int,
  broadcastScale: Int,
  propertyCapacity: Int?,
): Int? {
  val broadcastPercent = if (
    broadcastLevel >= 0 && broadcastScale > 0 && broadcastLevel <= broadcastScale
  ) {
    ((broadcastLevel.toLong() * 100L) / broadcastScale).toInt().coerceIn(0, 100)
  } else {
    null
  }
  return broadcastPercent ?: propertyCapacity?.takeIf { it in 0..100 }
}
