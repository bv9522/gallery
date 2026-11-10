# Phase 3B native capability acceptance plan

This checklist is for device validation after the Phase 3B APK is built and installed. It does
not imply that the adapter is already exposed as a Gemma-facing tool; the native adapter currently
requires an injected approval implementation before mutations can be called.

## Read-only discovery

- [ ] App starts and the existing Gemma chat/shell flows still work.
- [ ] Native provider probe completes without opening permission prompts or changing device state.
- [ ] Device information reports manufacturer, brand, model, Android release, API level, and optional
      security patch; it must not expose serial numbers or account identifiers.
- [ ] Battery status reports percentage when available, charging state, plugged source,
      temperature, and voltage. Unknown readings may be null.
- [ ] App discovery lists launcher-visible apps only. No broad installed-package enumeration is
      requested or required.
- [ ] URL capability is available when an HTTP(S) handler can resolve.

## Mutating operations (only after existing user approval is wired)

- [ ] Volume change is rejected when outside the stream's valid 0..maximum range.
- [ ] A denied volume approval leaves the current volume unchanged.
- [ ] An approved volume change affects only the selected stream.
- [ ] Flashlight reports permission-required when CAMERA permission is not granted; the probe
      must not request it.
- [ ] An approved flashlight action turns the rear flash on/off; camera-in-use or hardware errors
      are returned as failures rather than crashing the agent.
- [ ] App launch accepts only a package returned by launcher discovery; unknown/non-launchable
      packages are rejected.
- [ ] A denied app-launch approval does not start the app.
- [ ] URL handling accepts only absolute HTTP(S) URLs with a host. file:, content:, intent:,
      javascript:, and malformed URLs are rejected.
- [ ] A denied URL approval does not launch another application.

## Regression checks

- [ ] Explicit TERMUX, SHIZUKU, and TERMUX_RISH shell backend selection is unchanged.
- [ ] A failed or unavailable native capability does not silently override an explicit shell backend.
- [ ] Missing provider observations remain UNKNOWN and are never selected.
- [ ] No Bluetooth, USB/OTG, AccessibilityService, or generated-code execution is introduced here.
