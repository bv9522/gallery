# Phase 4A — Termux:API overlap probe (transitional)

## Purpose and current status

Phase 4A added an explicit `termux_api` tool backed by the existing supervised Termux
RUN_COMMAND client. It was introduced to inspect the companion app and compare a small overlap
slice before provider ownership was decided.

The subsequent capability-ownership audit established a **single-owner policy**. Native Android
Kotlin owns typed Android framework capabilities; the unified shell router owns path-based file
operations; SAF owns user-granted document trees; AccessibilityService owns semantic UI
operations. Termux:API is not registered as a second automatic owner.

The existing Phase 4A operations remain transitional, explicit-comparison functionality only:

- `status`: checks the companion package and selected command binaries.
- `battery_status`: `termux-battery-status`.
- `volume_status`: `termux-volume`.
- `set_volume`: `termux-volume <stream> <volume>`.
- `set_flashlight`: `termux-torch on|off`.

Do not expand this overlapping slice into a second production provider. Once the native capability
owners and deterministic result contracts are implemented, remove the redundant Termux:API
operations from the model-facing runtime unless device evidence establishes a concrete advantage
that justifies a documented ownership change.

See [ANDROID_CAPABILITY_OWNERSHIP_AND_IMPLEMENTATION_PLAN.md](ANDROID_CAPABILITY_OWNERSHIP_AND_IMPLEMENTATION_PLAN.md)
for the complete Termux:API command inventory and selected owner for each capability.

## Security and execution

- No arbitrary model-supplied shell command is accepted.
- Commands are fixed allowlisted strings with validated arguments.
- Execution uses the existing TermuxRunCommandClient and its supervision, timeout, output bounds,
  cancellation, and RUN_COMMAND permission contract.
- Read operations do not request mutation approval.
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

Phase 4A unit tests and the Shizuku debug build succeeded locally. Device acceptance of all
mutations and result integrity is not complete. No PR has been merged.
