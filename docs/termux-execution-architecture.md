# Termux execution architecture

## Goal

Provide the local agent with a Termux userspace independently of Shizuku, while retaining the existing direct Shizuku shell backend. A combined workflow will later let Termux-launched scripts invoke the same Shizuku service through the user's configured `rish` installation.

The intended modes are:

1. **Termux:** local commands, packages, scripts, and files execute with Termux's Android application identity.
2. **Shizuku:** the existing native UserService executes Android shell commands as UID 2000.
3. **Combined:** Termux runs local programs and explicitly invokes `rish` for operations that need the Shizuku shell identity.

There must be one Shizuku backend, not a second Shizuku implementation hidden inside the Termux backend. Termux's `rish` command is a bridge to the existing Shizuku service.

## Initial transport

The first implementation uses Termux's documented Android `RUN_COMMAND` service:

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
5. Grant Termux shared-storage access separately if commands need to access shared storage.

The app declares the Termux permission and package visibility query. It checks that Termux is installed and that the caller permission is granted before dispatch.

Clearing Termux app data resets its userspace and configuration, including `allow-external-apps`. The user can restore the environment using their own setup/reset command document.

## Security

The Termux permission plus `allow-external-apps=true` authorizes this app to execute arbitrary commands inside the Termux environment. This is intentionally powerful. Model-generated commands must not be dispatched without the app's explicit approval policy. The command shown for approval must be the exact command sent to Termux.

The direct Shizuku backend remains separate and continues to require Shizuku permission. The combined mode must use `rish` from within Termux and must not create another Shizuku UserService.

## Current adapter limitations

The initial `TermuxRunCommandClient` is a one-shot execution adapter and a transport proof:

- It returns stdout, stderr, exit status, Termux internal errors, and truncation metadata.
- Termux caps callback output to approximately 100 KiB combined; the client applies a stricter caller-provided limit.
- A client-side timeout stops waiting for the result but does **not** guarantee that an already-dispatched Termux process has been terminated.
- Each invocation starts a new Bash process. It does not preserve shell variables, current directory, or interactive stdin between calls.
- It does not yet invoke `rish` or expose arbitrary Termux commands to Gemma.

These are explicit limitations, not hidden guarantees. Before the backend is exposed for unrestricted agent execution, the next execution-runtime stage must provide managed cancellation/process cleanup, bounded sessions, and a persistent session strategy. If the Termux API cannot supply the required semantics, a small app-owned bridge process launched and supervised through Termux will be evaluated. No network service should be added unless the native service API proves insufficient.

## Device validation

The diagnostic screen includes a Termux-only test that runs `id`, prints `HOME` and `PREFIX`, and locates Bash. It deliberately does not invoke `rish` or Shizuku. This proves the local Termux path independently before combined execution is tested.
