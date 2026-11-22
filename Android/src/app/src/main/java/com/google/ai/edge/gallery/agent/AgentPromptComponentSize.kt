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

/** Size-only metadata for a prompt component; the source text is deliberately not retained. */
data class AgentPromptComponentSize(
  val name: String,
  val characterCount: Int,
  val utf8ByteCount: Int,
) {
  init {
    require(name.isNotBlank()) { "Prompt component name must not be blank." }
    require(characterCount >= 0) { "Prompt component character count cannot be negative." }
    require(utf8ByteCount >= 0) { "Prompt component byte count cannot be negative." }
  }

  companion object {
    fun from(name: String, value: String): AgentPromptComponentSize =
      AgentPromptComponentSize(
        name = name,
        characterCount = value.length,
        utf8ByteCount = value.toByteArray(Charsets.UTF_8).size,
      )
  }
}
