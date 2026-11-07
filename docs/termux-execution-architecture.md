# Termux execution architecture

## Goal

Provide the local agent with a Termux userspace independently of Shizuku, while retaining the existing direct Shizuku shell backend. A combined workflow will later let Termux-launched scripts invoke the same Shizuku service through the user's configured `rish` installation.

The intended modes are:

1. **Termux:** local commands, packages, scripts, and files execute with Termux's Android application identity.
2. **Shizuku:** the existing native UserService executes Android shell commands as UID 2000.
3. **Combined:** Termux runs local programs and explicitly invokes `rish` for operations that need the Shizuku shell identity.

There must be one Shizuku backend, not a second Shizuku implementation hidden inside the Termux backend. Termux's `rish` command is a bridge to the existing Shizuku service.

## Initial transport

The transport uses Termux's documented Android `RUN_COMMAND` service as a control channel:

- Package: `com.termux`
- Service: `com.termux.app.RunCommandService`
- Action: `com.termux.RUN_COMMAND`
- Executable: Termux's Bash at `/data/data/com.termux/files/usr/bin/bash`
- Arguments: `-lc` and a short supervisor/control command
- Runner: a detached `setsid` process group executes the user command; stdout/stderr are captured into bounded files
- Supervision: GNU `timeout` provides a process-side deadline; the app records supervisor and timeout PIDs and sends TERM followed by KILL on cancellation
- Result: short control commands use one-shot explicit mutable PendingIntent callbacks to the app's private BroadcastReceiver; the app polls the supervisor status file until completion

The integration intentionally avoids a TCP listener, local server, Termux:Tasker, or another inference/runtime dependency.

## User-side prerequisites

1. Termux must be installed and initialized.
2. In Android Settings, grant this app the **Run commands in Termux environment** permission.
3. In Termux's `~/.termux/termux.properties`, set:
   `allow-external-apps=true`
4. Reload Termux settings or restart Termux after changing the property.
5. Install the process-supervision utilities once: `pkg install -y util-linux coreutils`.
6. Grant Termux shared-storage access separately if commands need to access shared storage.

The app declares the Termux permission and package visibility query. It checks that Termux is installed and that the caller permission is granted before dispatch.

Clearing Termux app data resets its userspace and configuration, including `allow-external-apps`. The user can restore the environment using their own setup/reset command document.

## Security

The Termux permission plus `allow-external-apps=true` authorizes this app to execute arbitrary commands inside the Termux environment. This is intentionally powerful. Model-generated commands must not be dispatched without the app's explicit approval policy. The command shown for approval must be the exact command sent to Termux.

The direct Shizuku backend remains separate and continues to require Shizuku permission. The combined mode must use `rish` from within Termux and must not create another Shizuku UserService.

## Process supervision and current limitations

The app does not rely on RUN_COMMAND to expose a cancellation handle. It starts a detached process group inside Termux using `setsid`, records the supervisor and GNU `timeout` process IDs, and stores stdout/stderr in a private per-invocation directory. Output capture drains the full pipes while retaining only the configured number of bytes per stream, so large command output does not grow the files without bound.

- On normal completion, the app reads the exit status and bounded output, then removes the per-invocation directory.
- On coroutine cancellation or app-side timeout, the app sends TERM and then KILL to the supervisor and command process groups. It reports when termination cannot be confirmed.
- GNU `timeout` is also enforced inside Termux. This is a safety net if Android MCP is killed before it can send explicit cleanup.
- Each invocation still starts a new Bash process. Shell variables, current directory, and interactive stdin are not preserved between calls.
- Commands that deliberately daemonize into a new session can escape process-group supervision. The UI must not claim that arbitrary daemonized descendants are always terminated.
- The combined Termux+rish mode is a one-shot command transport; it does not create a second Shizuku service.

These constraints are explicit. Persistent interactive shell sessions are not part of the current implementation.

## Device validation

The diagnostic screen includes a Termux-only test that runs `id`, prints `HOME` and `PREFIX`, and locates Bash. It deliberately does not invoke `rish` or Shizuku. This proves the local Termux path independently before combined execution is tested.
