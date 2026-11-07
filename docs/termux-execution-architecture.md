# Termux execution architecture

## Goal

Provide the local agent with a Termux userspace independently of Shizuku, while retaining the existing direct Shizuku shell backend. The combined workflow lets Termux-launched scripts invoke the same Shizuku service through the user's configured `rish` installation.

The intended modes are:

1. **Termux:** local commands, packages, scripts, and files execute with Termux's Android application identity.
2. **Shizuku:** the existing native UserService executes Android shell commands as UID 2000.
3. **Combined (`TERMUX_RISH`):** Termux runs local programs and explicitly invokes `rish` for operations that need the Shizuku shell identity. It connects to the already-running Shizuku service; it does not create a second service.

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
6. For `TERMUX_RISH`, install and initialize the `rish` client using the Shizuku app's supported setup instructions, ensure the Shizuku service is running and this app is authorized, and verify `rish -c 'id'` from Termux. This must report Android shell identity (`uid=2000(shell)`). The native runtime does not silently substitute Termux's own UID if `rish` is unavailable.
7. Grant Termux shared-storage access separately if commands need to access shared storage.

The app declares the Termux permission and package visibility query. It checks that Termux is installed and that the caller permission is granted before dispatch.

Clearing Termux app data resets its userspace and configuration, including `allow-external-apps`. The user can restore the environment using their own setup/reset command document.

## Security

The Termux permission plus `allow-external-apps=true` authorizes this app to execute arbitrary commands inside the Termux environment. This is intentionally powerful. Model-generated commands must not be dispatched without the app's explicit approval policy. The command shown for approval must be the exact command sent to Termux.

The direct Shizuku backend remains separate and continues to require Shizuku permission. Its persistent shell is isolated in a dedicated process group; cancelling or closing the session terminates that group. The combined mode must use `rish` from within Termux and must not create another Shizuku UserService. All three modes use the same one-time approval dialog, and the exact immutable request approved is the request routed to the selected backend.

## Process supervision and current limitations

The app does not rely on RUN_COMMAND to expose a cancellation handle. It starts a detached process group inside Termux using `setsid`, records the supervisor and GNU `timeout` process IDs, and stores stdout/stderr in a private per-invocation directory. Output capture drains the full pipes while retaining only the configured number of bytes per stream, so large command output does not grow the files without bound.

- On normal completion, the app reads the exit status and bounded output, then sends TERM/KILL to the isolated process group to clean up ordinary background descendants before removing the per-invocation directory.
- On coroutine cancellation or app-side timeout, the app sends TERM and then KILL to the isolated process group. It reports when termination cannot be confirmed.
- GNU `timeout` is also enforced inside Termux. This is a safety net if Android MCP is killed before it can send explicit cleanup.
- Each invocation still starts a new Bash process. Shell variables, current directory, and interactive stdin are not preserved between calls.
- Commands that deliberately daemonize into a new session can escape process-group supervision. The UI must not claim that arbitrary daemonized descendants are always terminated.
- The combined Termux+rish mode is a one-shot command transport; it does not create a second Shizuku service.

These constraints are explicit. Persistent interactive shell sessions are not part of the current implementation.

## Device validation

The diagnostic screen includes a Termux-only test that runs `id`, prints `HOME` and `PREFIX`, and locates Bash. It deliberately does not invoke `rish` or Shizuku. The production Agent Chat path is the real-model acceptance surface: ask Gemma to run a harmless identity command with each backend, approve the exact request, and confirm the returned UID. Use `TERMUX` for the Termux app UID, `SHIZUKU` for `uid=2000(shell)`, and `TERMUX_RISH` for `uid=2000(shell)` through Termux's `rish` client.


## Agent Chat integration

For LiteRT-LM models, production Agent Chat uses one `NativeAgentRuntimeExecutor`, one `AgentCoordinator`, and one model-visible `execute_shell` tool. Existing Gallery skills and MCP tools are adapted into that coordinator's registry; the existing MCP client remains available. AICore models continue through the established fallback executor because they do not expose the LiteRT-LM manual conversation API used by this adapter.

The `execute_shell` tool requires an explicit backend and command. The shared approval policy displays the backend, exact command, working directory, timeout, and output cap before any transport is invoked. Approval is one-time only. Missing UI collectors, dismissal, timeout, malformed input, and unavailable backends fail closed.

## Validation status

The existing Termux-only diagnostic verifies the standalone RUN_COMMAND transport. Unit tests cover routing, approval, no-fallback behavior, argument validation, and rish command quoting. Device-level validation of all three identities and real Gemma-generated tool calls is still required after building and installing the current APK. In particular, test `TERMUX_RISH` only after confirming the user's Termux `rish` client is installed and reports `uid=2000(shell)`.


## Guiding principle and next capability roadmap

The unified execution runtime is the foundation, not a reason to create a separate model tool for every Android feature.

Apply these rules in order:

1. If an operation can be performed reliably with an existing shell command, route it through the shared execute_shell request and approval path.
2. If Android requires a framework API, implement a reusable platform adapter behind the shared execution/capability boundary.
3. If an optional companion such as Termux:API already exposes the capability, detect and reuse it rather than reimplementing it.
4. Add a dedicated model-facing operation only when structured parameters, permission handling, result typing, lifecycle management, or safety materially improve reliability or usability.
5. Keep backend identity explicit. A requested Shizuku operation must never silently fall back to Termux, and TERMUX_RISH must continue to invoke the existing Shizuku service rather than creating another one.

### Finish the initial execution runtime first

Before adding device features, validate all three execution identities from Agent Chat on a physical device:

- TERMUX: confirm the command executes as the Termux app UID.
- SHIZUKU: confirm the native UserService executes as Android shell UID 2000.
- TERMUX_RISH: confirm Termux's configured rish client reaches that same Shizuku service and reports UID 2000.
- Approve the exact command in each case, verify bounded stdout/stderr and exit status, then test timeout, user cancellation, session close, and cleanup of ordinary child processes.
- Confirm missing Termux, missing rish, revoked Shizuku permission, and unavailable /system/bin/setsid fail explicitly without backend substitution.

GitHub Actions unit tests and APK builds are necessary but do not replace this S21 validation. The Pages deployment workflow is unrelated to Android execution.

### Optional Termux:API capability reuse

Termux:API is an optional integration, not a prerequisite for the shell router. It requires both the Termux-side termux-api package and the companion Android application from a compatible distribution/signing family. Detect command and companion availability at runtime; report unavailable capabilities instead of assuming installation.

Prefer the existing Termux shell transport for commands such as battery status, torch, sensors, camera capture, TTS, vibration, and clipboard operations when the Termux:API contract is sufficient. Add a native Android adapter only when the feature needs Android lifecycle/permission handling, richer typed results, image handoff to the model, streaming subscriptions, or functionality Termux:API cannot provide.

### Connected-device capabilities

Connected devices are a planned capability area, with the user's FiiO BTR3K Bluetooth DAC and USB flash drive as initial validation targets.

- Bluetooth / audio: start with Android's supported Bluetooth and audio-routing APIs for connection state, device metadata, and standard media routing. Do not assume that a generic Bluetooth connection grants access to vendor-specific DAC controls, codec settings, filters, or firmware functions. Investigate any BTR3K-specific control protocol separately and require normal Android pairing/permissions.
- USB storage: support removable storage through Android's Storage Access Framework (SAF), USB host APIs where required, and persistable URI grants. The model should operate only within a user-selected, granted document tree. Do not assume shell UID or Shizuku provides unrestricted access to every removable-storage path.
- Windows drive distinction: a Windows drive letter such as F:\Media is not itself an Android path. When the flash drive is attached directly to the S21 using a compatible USB-OTG adapter, Android will expose it as a removable volume/document tree subject to Android's storage access rules. If the drive remains attached to the Windows PC, access would instead require a separately configured network-sharing capability.
- File operations: begin with scoped read/list/search and metadata extraction. Add writes only with clear target paths and confirmation; require stronger confirmation for bulk moves, overwrites, or deletes. Avoid silently indexing or transmitting the entire drive. Make indexing opt-in, local, incremental, and revocable.

### Reusable Android capability adapters

Introduce platform adapters only as real platform constraints require them. Candidate adapter domains include app discovery/launch intents, Bluetooth/audio routing, USB/SAF document access, camera capture and image delivery, sensor subscriptions, notification listening, and screen capture. Adapters should expose availability, permission state, required user action, and typed results to the coordinator. They should reuse the same approval policy for consequential actions and keep read-only inspection distinct from state-changing operations.

Do not turn this roadmap into a fixed catalogue of one-off tools. Capability discovery and adapter reuse should let the model request outcomes while the runtime selects the safest supported implementation.
