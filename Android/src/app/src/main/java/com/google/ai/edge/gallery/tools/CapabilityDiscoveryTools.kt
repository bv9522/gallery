/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.tools

import com.google.ai.edge.gallery.mcp.McpServersProvider
import com.google.ai.edge.gallery.proto.McpTool
import com.google.ai.edge.gallery.proto.Skill
import com.google.ai.edge.gallery.skills.SkillsProvider
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private const val MAX_DISCOVERY_RESULTS = 8
private const val MAX_DESCRIPTION_CHARS = 240

/**
 * Read-only skill discovery. It exposes only enabled skill names and short descriptions;
 * [LoadSkillTool] remains the sole path that returns full skill instructions.
 */
class DiscoverSkillsTool(private val skillsProvider: SkillsProvider) : ToolDefinition {
  override val alwaysAllow: Boolean = true
  override var executionContext: ToolExecutionContext? = null

  @Tool(
    description =
      "Search or list enabled skills by name or purpose. Returns only names and short descriptions. " +
        "Use load_skill to retrieve full instructions for a selected skill."
  )
  fun discoverSkills(
    @ToolParam(description = "Words describing the skill or task. Use an empty string to list enabled skills.")
    query: String
  ): Map<String, String> = runBlocking(Dispatchers.IO) {
    val matches = selectDiscoveryMatches(
      values = skillsProvider.getAvailableSkills(),
      query = query,
      searchableText = { "${it.name} ${it.description}" },
    )
    val rendered = matches.joinToString("\n") { skill ->
      "- ${skill.name}: ${compactDiscoveryDescription(skill.description)}"
    }
    mapOf(
      "status" to if (matches.isEmpty()) "no_matches" else "ok",
      "count" to matches.size.toString(),
      "skills" to rendered,
      "next_step" to "Call load_skill with the exact name to load full instructions.",
    )
  }
}

/**
 * Read-only MCP discovery. Only enabled tools on enabled, connected servers are listed.
 * Schemas are returned only for matches to the requested query, never in the base system prompt.
 */
class DiscoverMcpToolsTool(private val mcpServersProvider: McpServersProvider) : ToolDefinition {
  override val alwaysAllow: Boolean = true
  override var executionContext: ToolExecutionContext? = null

  @Tool(
    description =
      "Search or list enabled tools from connected MCP servers. Returns matching tool names, " +
        "short descriptions, and their input JSON schemas. This is discovery only; invoke a " +
        "selected tool separately with run_mcp_tool."
  )
  fun discoverMcpTools(
    @ToolParam(description = "Words describing the MCP capability. Use an empty string to list enabled tools.")
    query: String
  ): Map<String, String> {
    val enabledTools = mcpServersProvider.mcpServers
      .filter { it.mcpServer.enabled && it.client != null }
      .flatMap { state -> state.mcpServer.toolsList.filter { it.enabled } }

    // run_mcp_tool addresses tools by name only. Do not advertise duplicate names because the
    // existing dispatcher would otherwise resolve an ambiguous name to the first server.
    val grouped = enabledTools.groupBy { it.name }
    val ambiguousNames = grouped.filterValues { it.size > 1 }.keys
    val candidates = grouped
      .filterKeys { it !in ambiguousNames }
      .values
      .mapNotNull { it.firstOrNull() }

    val matches = selectDiscoveryMatches(
      values = candidates,
      query = query,
      searchableText = { "${it.name} ${it.description}" },
    )
    val rendered = matches.joinToString("\n\n") { tool ->
      "MCP tool: ${tool.name}\n" +
        "Description: ${compactDiscoveryDescription(tool.description)}\n" +
        "Input schema JSON: ${tool.inputSchema}"
    }
    return mapOf(
      "status" to if (matches.isEmpty()) "no_matches" else "ok",
      "count" to matches.size.toString(),
      "tools" to rendered,
      "ambiguous_name_count" to ambiguousNames.size.toString(),
      "next_step" to "Call run_mcp_tool with the exact tool name and JSON arguments. User consent is still required when configured.",
    )
  }
}

internal fun <T> selectDiscoveryMatches(
  values: List<T>,
  query: String,
  searchableText: (T) -> String,
  limit: Int = MAX_DISCOVERY_RESULTS,
): List<T> {
  val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
  return values
    .filter { value ->
      val searchable = searchableText(value).lowercase()
      terms.all { term -> searchable.contains(term) }
    }
    .take(limit.coerceAtLeast(1))
}

internal fun compactDiscoveryDescription(description: String): String {
  val compact = description.replace(Regex("\\s+"), " ").trim()
  return if (compact.length <= MAX_DESCRIPTION_CHARS) compact
  else compact.take(MAX_DESCRIPTION_CHARS - 1).trimEnd() + "…"
}
