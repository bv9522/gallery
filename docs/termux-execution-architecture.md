# Termux execution architecture

## Goal

Provide the local agent with a Termux userspace independently of Shizuku, while retaining the existing direct Shizuku shell backend. A combined workflow will later let Termux-launched scripts invoke the same Shizuku service through the user's configured `rish` installation.

The intended modes are:

1. **Termux:** local commands, packages, scripts, and files execute with Termux's Android application identity.
2. **Shizuku:** the existing native UserService executes Android shell commands as UID 2000.
3. **Combined:** Termux runs local programs and explicitly invokes `rish` for operations that need the Shizuku shell identity.

There must be one Shizuku backend, not a second Shizuku implementation hidden inside the Termux backend. Termux's `rish` command is a bridge to the existing Shizuku service.

## Initial transport

The implementation uses Termux's documented Android `RUN_COMMAND` service:

- Package: `com.termux`
- Service: `com.termux.app.RunCommandService`
- Action: `com.termux.RUN_COMMAND`
- Executable: Termux's Bash at `/data/data/com.termux/files/usr/bin/bash`
- Arguments: `-lc` and the requested command
- Runner: background app-shell execution, so stdout and stderr are returned separately
- Result: one-shot explicit mutable PendingIntent callback to the app's private BroadcastReceiver

The integration intentionally avoids a TCP listener, local server, Termux:Tasker, or another inference/runtime dependency.

## User-side prerequisites

1. Termux must be installed and initialized.
2. In Android Settings, grant this app the **Run commands in Termux environment** permission.
3. In Termux's `~/.termux/termux.properties`, set:
   `allow-external-apps=true`
4. Reload Termux settings or restart Termux after changing the property.
5. Install Termux's `util-linux` package (`pkg install util-linux`) so the `setsid` process-group supervisor is available. The app fails closed if it is missing.\n6. Grant Termux shared-storage access separately if commands need to access shared storage.

The app declares the Termux permission and package visibility query. It checks that Termux is installed and that the caller permission is granted before dispatch.

Clearing Termux app data resets its userspace and configuration, including `allow-external-apps`. The user can restore the environment using their own setup/reset command document.

## Security

The Termux permission plus `allow-external-apps=true` authorizes this app to execute arbitrary commands inside the Termux environment. This is intentionally powerful. Model-generated commands must not be dispatched without the app's explicit approval policy. The command shown for approval must be the exact command sent to Termux.

The direct Shizuku backend remains separate and continues to require Shizuku permission. The combined mode must use `rish` from within Termux and must not create another Shizuku UserService.

## Process supervision and cancellation

Each command is launched beneath Termux's `setsid` utility in its own process group. The wrapper records the process-group leader PID in a private Termux-home pid file and removes it on normal completion. On timeout or coroutine cancellation, the client dispatches a separate Termux control command that sends SIGTERM to the process group, waits briefly, escalates to SIGKILL, and confirms that the group is gone before reporting successful cleanup.

This supervises ordinary child processes. A command that deliberately daemonizes, creates a new session, or otherwise escapes its process group can outlive the supervisor; such a case is reported as unconfirmed rather than falsely reported as terminated. If the pid file cannot be read or the cleanup callback fails, the result sets `processMayStillBeRunning=true`.

## Current adapter limitations

- The adapter is one-shot: each invocation starts a new Bash process and does not preserve shell variables, current directory, or interactive stdin between calls.
- The supervisor requires Termux's `util-linux` `setsid` command. If it is absent, the requested command is not started.
- Termux caps callback output to approximately 100 KiB combined; the client applies a stricter caller-provided limit.
- The `rish` backend requires a working Termux-side `rish` installation and an active, authorized Shizuku service. It never silently falls back to Termux's own UID.

## Device validation

The diagnostic screen includes a Termux-only test that runs `id`, prints `HOME` and `PREFIX`, and locates Bash. It deliberately does not invoke `rish` or Shizuku. This proves the local Termux path independently before combined execution is tested.
