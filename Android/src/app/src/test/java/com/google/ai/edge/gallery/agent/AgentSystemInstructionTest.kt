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

package com.google.ai.edge.gallery.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSystemInstructionTest {
  @Test
  fun builtInInstructionStaysWithinConservativePromptBudget() {
    val instruction = buildAgentSystemInstruction(null)

    // The native Gemma 4 E2B conversation has a 4,000-token input ceiling. Keep this
    // app-authored policy prompt small so tool schemas and the current user turn still fit.
    assertTrue(
      "Built-in agent instruction is too large: ${instruction.length} characters.",
      instruction.length <= MAX_BUILT_IN_INSTRUCTION_CHARS,
    )
  }

  @Test
  fun existingSystemInstructionIsPreserved() {
    val instruction = buildAgentSystemInstruction("Keep the user's selected assistant persona.")

    assertTrue(instruction.startsWith("Keep the user's selected assistant persona."))
  }

  @Test
  fun compactInstructionRetainsCriticalSafetyBoundaries() {
    val instruction = buildAgentSystemInstruction(null)

    assertTrue(instruction.contains("Provider discovery/status is observational"))
    assertTrue(instruction.contains("fail closed"))
    assertTrue(instruction.contains("Never use shell as an automatic fallback"))
    assertTrue(instruction.contains("Never read it without an explicit request"))
    assertTrue(instruction.contains("fresh opaque handles"))
    assertTrue(instruction.contains("separate disclosure and consent"))
  }

  private companion object {
    const val MAX_BUILT_IN_INSTRUCTION_CHARS = 4_500
  }
}
