# Phase 4A — Termux:API integration and overlap probe

## Purpose and current status

Phase 4A added an explicit `termux_api` tool backed by the existing supervised Termux
RUN_COMMAND client. It was introduced to inspect the companion app and compare a small overlap
slice before provider ownership was decided.

The revised ownership audit preserves a **single-owner policy** but selects Termux:API for a
defined subset of simple, bounded operations. The unified shell router owns path-based file
operations; Gallery's SAF grants own user-selected document trees; AccessibilityService owns
semantic UI operations. Termux shell remains an execution transport and is distinct from the
TERMUX_API capability owner.

The existing Phase 4A operations remain transitional, explicit-comparison functionality only:

- `status`: checks the companion package and selected command binaries.
- `battery_status`: `termux-battery-status`.
- `volume_status`: `termux-volume`.
- `set_volume`: `termux-volume <stream> <volume>`.
- `set_flashlight`: `termux-torch on|off`.

Do not treat the three overlap operations as production alternatives to the selected native owners.
The wider Termux:API command families are now selected owners where listed in the canonical plan.
The Phase 5A branch extends `termux_api` with read-only `sensor_list`, finite `sensor_read`,
location `once`/`last`, `wifi_connection_info`, and `wifi_last_scan` operations. Sensor reads are
limited to 1–10 samples, 100–5000 ms delay, and 8 KiB output. Location and Wi-Fi reads require
scoped user approval. Sensor streams and location updates are deliberately not exposed. The
status operation checks companion-app and command-binary presence separately but leaves permission
state unknown until a real operation runs. The Phase 5A implementation still requires local
unit/build validation and target-device acceptance; do not mark these capabilities production-ready.

See [ANDROID_CAPABILITY_OWNERSHIP_AND_IMPLEMENTATION_PLAN.md](ANDROID_CAPABILITY_OWNERSHIP_AND_IMPLEMENTATION_PLAN.md)
for the complete Termux:API command inventory, selected owners, and implementation constraints.

## Security and execution

- No arbitrary model-supplied shell command is accepted.
- Commands are fixed allowlisted strings with validated arguments.
- Execution uses the existing TermuxRunCommandClient and its supervision, timeout, output bounds,
  cancellation, and RUN_COMMAND permission contract.
- Sensor listing/finite reads are read-only. Location and Wi-Fi reads use scoped user approval
  because their results can reveal sensitive location or network details.
- Volume and flashlight mutations use the existing one-time Agent Chat approval action.
- Denial, timeout, or closed action channel fails closed.
- No automatic native-to-Termux fallback is permitted.
- The companion package `com.termux.api` is declared in package visibility queries.
- No root access, new service, or additional Android MCP process is introduced.

## Companion setup

The comparison tool requires both compatible components:

1. Android companion app: Termux:API (`com.termux.api`).
2. Termux package: `pkg install termux-api`.

## Historical device checks

The user confirmed that the companion app and Termux package were installed and that
`termux-battery-status`, `termux-volume`, and `termux-torch` were available.

The raw battery command was once run directly in Termux and returned plausible JSON. Subsequent
Gemma-reproduced outputs contained corrupted keys, digits, and units. The tool currently passes
stdout through without parsing; therefore those reproduced strings do not establish that the
underlying command output was corrupt. Do not use Gemma's narration as raw command evidence.

## Acceptance status

Phase 4A unit tests and the Shizuku debug build succeeded locally. Phase 5A's new handlers and
unit tests are awaiting local validation and target-device acceptance. Device acceptance of all
mutations and result integrity is not complete. No PR has been merged.
