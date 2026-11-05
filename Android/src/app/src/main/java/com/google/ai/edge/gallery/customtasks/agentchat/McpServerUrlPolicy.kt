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

package com.google.ai.edge.gallery.customtasks.agentchat

import java.net.URI
import java.util.Locale

/**
 * Restricts MCP endpoints to HTTPS, except for explicit loopback HTTP endpoints used by the
 * local-MCP build. This is enforced at the connection boundary so restored DataStore entries
 * receive the same validation as newly entered URLs.
 */
internal fun validateMcpServerUrl(url: String) {
  val uri =
    try {
      URI(url)
    } catch (e: Exception) {
      throw IllegalArgumentException("Invalid MCP server URL.", e)
    }

  require(uri.isAbsolute && !uri.isOpaque) { "MCP server URL must be an absolute HTTP(S) URL." }
  require(uri.rawUserInfo == null) { "MCP server URLs must not contain embedded credentials." }
  require(uri.rawFragment == null) { "MCP server URLs must not contain a fragment." }

  val scheme = uri.scheme.lowercase(Locale.ROOT)
  require(scheme == "https" || scheme == "http") {
    "MCP server URL must use HTTPS, or HTTP for localhost only."
  }

  val host = uri.host?.lowercase(Locale.ROOT)
  require(!host.isNullOrBlank()) { "MCP server URL must include a valid host." }
  require(uri.port == -1 || uri.port in 1..65535) { "MCP server URL has an invalid port." }

  if (scheme == "http") {
    require(host == "localhost" || host == "127.0.0.1") {
      "Unencrypted HTTP is allowed only for localhost (localhost or 127.0.0.1). Use HTTPS for remote MCP servers."
    }
  }
}
