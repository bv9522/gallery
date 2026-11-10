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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryPercentResolverTest {
  @Test
  fun validBroadcastSnapshotWinsWhenBatteryManagerPropertyConflicts() {
    assertEquals(36, resolveBatteryPercent(
      broadcastLevel = 36,
      broadcastScale = 100,
      propertyCapacity = 9,
    ))
  }

  @Test
  fun validZeroPercentIsNotTreatedAsMissing() {
    assertEquals(0, resolveBatteryPercent(
      broadcastLevel = 0,
      broadcastScale = 100,
      propertyCapacity = 42,
    ))
  }

  @Test
  fun invalidBroadcastFallsBackToValidBatteryManagerProperty() {
    assertEquals(9, resolveBatteryPercent(
      broadcastLevel = -1,
      broadcastScale = 100,
      propertyCapacity = 9,
    ))
    assertEquals(9, resolveBatteryPercent(
      broadcastLevel = 101,
      broadcastScale = 100,
      propertyCapacity = 9,
    ))
  }

  @Test
  fun invalidSourcesReturnUnknownRatherThanInventingAPercentage() {
    assertNull(resolveBatteryPercent(
      broadcastLevel = -1,
      broadcastScale = 0,
      propertyCapacity = 101,
    ))
  }
}
