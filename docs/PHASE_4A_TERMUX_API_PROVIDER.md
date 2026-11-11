# Phase 4A — Termux:API provider

## Scope of this first integration slice

This change adds a distinct `termux_api` model tool backed by the existing supervised
Termux RUN_COMMAND client. It does not replace `android_control` and does not create a
second shell transport.

Initial operations deliberately cover the overlapping capabilities so both providers can
be inspected and compared before changing route priorities:

- `status`: checks the Termux:API companion package and probes the installed command
  binaries independently.
- `battery_status`: `termux-battery-status`.
- `volume_status`: `termux-volume`.
- `set_volume`: `termux-volume <stream> <volume>`.
- `set_flashlight`: `termux-torch on|off`.

The capability catalog already identifies TERMUX_API as a provider for battery, flashlight,
and volume. Existing preference order remains provisional: native Android is currently
preferred, Termux:API is a separately callable comparison provider. No route priority is
changed by this PR.

## Security and execution

- The model cannot supply arbitrary command text to this tool.
- Commands are fixed allowlisted strings with validated arguments.
- Execution uses the existing TermuxRunCommandClient and its process supervision, timeout,
  output bounds, cancellation, and RUN_COMMAND permission contract.
- Read operations do not request mutation approval.
- Volume and flashlight mutations use the existing one-time Agent Chat approval action.
- Denial, timeout, or closed action channel fails closed.
- No native-to-Termux fallback occurs automatically.
- The companion package `com.termux.api` is declared in package visibility queries;
  no broad package visibility permission is added.
- No root access, new service, or additional process is introduced by Android MCP.

## Companion setup

Both components are required and must come from compatible Termux release/signing sources:

1. Android companion app: Termux:API (`com.termux.api`).
2. Termux package: `pkg install termux-api`.

The app and plugin must have compatible signatures. The Termux command package is installed
inside the existing Termux environment.

## Device acceptance checklist

1. Ask `termux_api` for `status`. Confirm companionInstalled=true and all three commands
   report AVAILABLE.
2. Retrieve Termux:API battery status; compare with native `android_control` battery status
   at approximately the same time.
3. Retrieve Termux:API volume status; compare its stream/current/max values with native
   `volume_status`.
4. Deny a one-step volume change; verify the level remains unchanged.
5. Approve the same bounded volume change; verify with both providers.
6. Deny flashlight ON; verify it remains OFF.
7. Approve flashlight ON, verify, then approve OFF and verify.
8. Repeat reads with the companion app missing or command package unavailable only in a
   controlled test environment; expected result is a clear unavailable/failure state, never
   a false success.

## Broader Termux:API inventory — next implementation slice

This first slice does not claim full Termux:API coverage. After both overlapping providers
are verified, inventory and add the unique APIs behind explicit capability/permission review.
Candidates include sensors, location, Wi-Fi connection/scan information, telephony metadata,
clipboard, notifications, camera capture, speech recognition, and text-to-speech. Each needs
a decision on privacy sensitivity, runtime permission, read vs mutation semantics, result
size, and whether Android native APIs already provide a stronger implementation.

Do not promote any provider priority until the comparison matrix is populated with observed
availability, permission behavior, data quality, latency, and reliability on-device.
