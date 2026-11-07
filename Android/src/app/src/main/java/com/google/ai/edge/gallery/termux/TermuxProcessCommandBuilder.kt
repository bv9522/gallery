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

package com.google.ai.edge.gallery.termux

/**
 * Builds safely quoted Termux-side scripts for isolated command execution and process-group cleanup.
 * The process group is the supervision boundary: ordinary child processes are terminated together,
 * while commands that deliberately daemonize or call setsid themselves can escape it.
 */
internal object TermuxProcessCommandBuilder {
  fun buildLaunchCommand(command: String, pidFile: String): String {
    require(command.isNotBlank())
    require('\u0000' !in command)
    require(pidFile.isNotBlank() && '\u0000' !in pidFile)

    val quotedPidFile = shellQuote(pidFile)
    val quotedCommand = shellQuote(command)
    return """
      command -v setsid >/dev/null 2>&1 || {
        printf '%s\n' 'Android MCP requires Termux util-linux (setsid) for process supervision.' >&2
        exit 127
      }
      mkdir -p "\$HOME/.cache" || exit 125
      pid_file="\$HOME"/$quotedPidFile
      rm -f "\$pid_file"
      setsid bash -lc $quotedCommand &
      child_pid=\$!
      printf '%s\n' "\$child_pid" > "\$pid_file"
      wait "\$child_pid"
      command_status=\$?
      rm -f "\$pid_file"
      exit "\$command_status"
    """.trimIndent()
  }

  fun buildTerminateCommand(pidFile: String): String {
    require(pidFile.isNotBlank() && '\u0000' !in pidFile)
    val quotedPidFile = shellQuote(pidFile)
    return """
      pid_file=$quotedPidFile
      for attempt in 1 2 3 4 5; do
        [ -s "\$pid_file" ] && break
        sleep 0.2
      done
      [ -s "\$pid_file" ] || exit 1
      pid=\$(cat "\$pid_file" 2>/dev/null)
      case "\$pid" in
        ''|*[!0-9]*) rm -f "\$pid_file"; exit 1 ;;
      esac
      kill -TERM -- "-\$pid" 2>/dev/null || true
      for attempt in 1 2 3 4 5; do
        if ! kill -0 -- "-\$pid" 2>/dev/null; then
          rm -f "\$pid_file"
          exit 0
        fi
        sleep 0.2
      done
      kill -KILL -- "-\$pid" 2>/dev/null || true
      sleep 0.2
      if kill -0 -- "-\$pid" 2>/dev/null; then exit 1; fi
      rm -f "\$pid_file"
      exit 0
    """.trimIndent()
  }

  private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
