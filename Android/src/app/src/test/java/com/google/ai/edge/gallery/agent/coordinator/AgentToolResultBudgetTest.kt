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

package com.google.ai.edge.gallery.agent.coordinator

import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolResultBudgetTest {
  @Test
  fun oversizedToolResultIsTruncatedWithOriginalLengthAndBothEndsRetained() {
    val budget = AgentToolResultBudget(maxCharsPerTool = 100, maxCharsPerTurn = 200)
    val source = "BEGIN" + "x".repeat(500) + "END"

    val output = budget.constrain(source)

    assertTrue(output.length <= 100)
    assertTrue(output.startsWith("BEGIN"))
    assertTrue(output.contains("TRUNCATED original_chars=508"))
    assertTrue(output.endsWith("END"))
  }

  @Test
  fun multipleResultsShareOnePerTurnBudget() {
    val budget = AgentToolResultBudget(maxCharsPerTool = 80, maxCharsPerTurn = 100)

    val first = budget.constrain("a".repeat(80))
    val second = budget.constrain("b".repeat(80))
    val third = budget.constrain("c".repeat(80))
    val fourth = budget.constrain("d".repeat(80))

    assertTrue(first.length <= 80)
    assertTrue(second.contains("TRUNCATED"))
    assertTrue(third.isEmpty())
    assertTrue(fourth.isEmpty())
    assertTrue(first.length + second.length + third.length + fourth.length <= 100)
  }

  @Test
  fun budgetResetsForTheNextUserTurn() {
    val budget = AgentToolResultBudget(maxCharsPerTool = 80, maxCharsPerTurn = 80)
    budget.constrain("a".repeat(80))

    budget.reset()
    val next = budget.constrain("b".repeat(20))

    assertTrue(next == "b".repeat(20))
  }
}
