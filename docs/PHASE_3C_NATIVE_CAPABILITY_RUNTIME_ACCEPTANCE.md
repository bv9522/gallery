# Phase 3C native capability runtime acceptance

Phase 3C connects the Phase 3B Android framework adapter to the existing LiteRT-LM agent coordinator. The model receives one compact `android_control` tool; the existing `execute_shell` tool remains separate and retains explicit TERMUX, SHIZUKU, and TERMUX_RISH identities.

## Model-facing operations

- [ ] `status`: report capability availability without requesting permissions or changing state.
- [ ] `device_info`: report manufacturer, model, Android release, API level, and security patch only.
- [ ] `battery_status`: report percentage, charging state, plugged source, temperature, and voltage where available.
- [ ] `volume_status`: inspect a selected audio stream; default is MUSIC.
- [ ] `list_apps`: list launcher-visible apps only.
- [ ] `set_volume`: validate the requested range and require one-time approval.
- [ ] `set_flashlight`: require camera permission and one-time approval; permission discovery must not prompt.
- [ ] `launch_app`: accept only a package returned by `list_apps` and require one-time approval.
- [ ] `open_url`: accept only absolute HTTP(S) URLs with a host and require one-time approval.

## Approval and routing guarantees

- [ ] All mutations use the existing Agent Chat action channel and a one-time confirmation dialog.
- [ ] Approval is tied to the exact capability and operation summary; there is no persistent allow option.
- [ ] Dismissal, timeout, closed action channel, or denied approval fails closed.
- [ ] Cancellation propagates; it is not converted into an ordinary tool error.
- [ ] Every operation resolves its capability through `CapabilityRegistry`.
- [ ] An unavailable native capability returns an explicit error. The tool never silently falls back to shell.
- [ ] Shell requests still use `execute_shell` and preserve explicit backend selection.

## Device acceptance on the Galaxy S21

1. Open Agent Chat with Gemma 4 E2B.
2. Ask for device information and battery status; neither should show an approval dialog.
3. Ask for media volume status; no approval dialog should appear.
4. Ask for the launchable app list; only launcher-visible apps should be returned.
5. Ask to set media volume to its current value. Verify the one-time approval dialog names the exact stream and target. Deny once and confirm no change; repeat and approve.
6. Test flashlight only after CAMERA permission is granted through the normal Android permission flow. Deny the native action once, then approve it and verify on/off behavior.
7. Ask to launch a known app using its listed package name. Deny once, then approve.
8. Ask to open a harmless HTTPS page. Deny once, then approve.
9. Verify `execute_shell` still works with each explicitly selected backend.

## Out of scope

This phase does not add Bluetooth, USB/OTG, AccessibilityService, unrestricted file access, arbitrary generated-code execution, or shell fallback from native operations. Termux:API and SAF remain future provider integrations.
