# Phase 3D-E: Native mutation acceptance and execution integrity

## Purpose

Close the Phase 3 native capability implementation with explicit test coverage for mutation dispatch and a clear device acceptance boundary. This phase does not add new providers or hardware-specific behavior.

## Native mutation path

The model-facing `android_control` tool exposes four mutations:

- `set_volume`
- `set_flashlight`
- `launch_app`
- `open_url`

Each mutation follows the same sequence:

1. Validate the operation-specific argument set.
2. Inspect native provider availability and resolve the requested capability through `CapabilityRegistry`.
3. Reject unavailable or permission-gated capabilities without dispatching the mutation.
4. Invoke the native adapter.
5. The adapter validates the requested action and obtains one-time approval through `ChannelNativeCapabilityAuthorizer`.
6. Only after approval does the adapter call the Android framework API.
7. Return the action result to the coordinator and preserve the exact bounded tool output in the authoritative transcript.

The Compose dialog is connected to `AskNativeCapabilityPermissionAction`. Dismissal and Deny resolve the pending request as false. There is no persistent allow state.

## Code changes

- Introduce `NativeCapabilityOperations`, an injectable contract between the model-facing tool and Android framework adapter.
- Keep the production constructor wired to `AndroidNativeCapabilityAdapter` and the existing one-time action channel.
- Add unit tests proving each mutation dispatches to its matching adapter method.
- Add tests proving unavailable capabilities and invalid arguments never reach a mutation method.
- Add a test proving adapter failures remain failed tool results.

These tests validate tool routing and gating. They do not replace device tests of actual Android framework behavior or the Compose approval dialog.

## Required device acceptance

Run these checks in Agent Chat with Gemma 4 E2B after installing the Phase 3D-E build.

### Read-only baseline

- [ ] `device_info` matches Android system properties.
- [ ] `battery_status` reports plausible values and decoded state.
- [ ] `volume_status` reports the selected stream and current/max values.
- [ ] `list_apps` returns launcher-visible apps and explicit truncation metadata.
- [ ] `status` reports provider availability only; it does not substitute device readings.

### Mutations: deny first

1. Ask to set MUSIC volume to a value different from the current value. Confirm the dialog names MUSIC and the exact target. Select **Deny**. Verify the volume remains unchanged.
2. Ask to turn the flashlight on. If Android reports CAMERA permission required, grant CAMERA permission through the normal Android permission flow and retry. Confirm the dialog says it will turn the flashlight on. Select **Deny**. Verify the flashlight remains off.
3. Ask to launch a known app using its exact package name from `list_apps`. Select **Deny**. Verify the app does not launch.
4. Ask to open a harmless HTTPS URL. Select **Deny**. Verify no browser or other handler opens.

### Mutations: approve

1. Repeat the volume change and select **Allow once**. Verify the actual volume changes to the requested value. Read it back with `volume_status`.
2. Repeat flashlight-on and select **Allow once**. Verify the flashlight turns on. Then request flashlight-off and approve it.
3. Repeat app launch and select **Allow once**. Verify the selected app opens.
4. Repeat URL opening and select **Allow once**. Verify the selected HTTPS handler opens the exact URL.

### Approval and failure invariants

- [ ] Read-only operations never display the mutation approval dialog.
- [ ] Each mutation displays exactly one approval dialog naming the intended action.
- [ ] Denial, dismissal, timeout, or a closed action channel never executes the mutation.
- [ ] Invalid arguments never display an approval dialog.
- [ ] Permission-gated flashlight operations fail without pretending the action succeeded.
- [ ] An unavailable native capability does not silently fall back to shell.
- [ ] The authoritative transcript preserves exact tool results.

## TERMUX_RISH integrity status

The Termux foreground-service startup repair is implemented. The later device transcript showed that simple output from TERMUX_RISH could be duplicated or have characters altered even when the process reported exit code 0 and no output truncation.

Source inspection found the following boundaries:

1. Gemma structured arguments enter `UnifiedShellTool`.
2. The immutable request is displayed for approval.
3. `TermuxRishShellTransport` quotes the working directory and remote command, then wraps it in `rish -c`.
4. `TermuxRunCommandClient` passes the wrapped command to Termux RUN_COMMAND.
5. The Termux-side supervisor captures stdout and stderr into separate files.
6. The client reads the captured files and formats the bounded result.

The Kotlin-side quoting is covered by a unit test, but the current test only verifies the generated wrapper string. It does not exercise the installed Rish client or prove remote command/output fidelity.

No speculative Rish transport change is included. The next diagnostic must compare the exact command and output at each boundary, using deterministic markers and hashes, before changing the transport. TERMUX and SHIZUKU remain separate execution identities and must not be used as silent substitutes for TERMUX_RISH.

## Completion criteria

Phase 3 is ready for owner device acceptance when:

- [ ] Unit tests and Android debug build pass on the new stacked branch.
- [ ] All four native mutation operations pass deny and approve checks.
- [ ] The read-only native capability checks continue to pass.
- [ ] TERMUX_RISH output integrity is either demonstrated to be correct or isolated to a specific boundary with a reproducible diagnostic. If unresolved, the Rish backend remains explicitly marked as not accepted; the rest of Phase 3 must not be represented as fully accepted.
