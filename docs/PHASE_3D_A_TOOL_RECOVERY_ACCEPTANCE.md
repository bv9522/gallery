# Phase 3D-A tool-call recovery acceptance

Phase 3D-A improves recovery from malformed or mismatched `android_control` calls. It does not reinterpret an invalid request or execute a substitute operation.

## Unit acceptance

- [ ] `status` with an unsupported `stream` argument is rejected with explicit guidance to call `volume_status` and preserve the requested stream.
- [ ] `volume_status` accepts `stream=MUSIC` and the default MUSIC stream.
- [ ] Missing required mutation arguments are rejected before adapter invocation or approval.
- [ ] Unknown operations and unsupported arguments remain fail-closed.
- [ ] Agent system guidance tells Gemma to retry once with an exact validator-suggested correction.
- [ ] Existing shell backend selection and native approval tests remain green.

## Galaxy S21 device acceptance

Use Gemma 4 E2B in Agent Chat.

1. Ask: `Read my MUSIC stream volume. Give me the exact current volume and maximum returned by Android.`
2. If the model initially calls `status` with `stream=MUSIC`, verify that the tool rejects it and returns the explicit `volume_status` correction.
3. Verify Gemma makes one corrected call with `operation=volume_status` and `stream=MUSIC`.
4. Verify the final current and maximum values match the actual Android result. Do not accept a provider-probe diagnostic as a volume reading.
5. Ask for device identity and compare the exact model identifier to Android's returned value. Gemma must not invent a product tier.
6. Confirm malformed mutation arguments do not display an approval dialog and do not change device state.

## Scope and limitations

- The validator improves error feedback; it cannot guarantee that a probabilistic model will follow the correction.
- The existing single `android_control` tool and operation enum remain unchanged.
- No native operation is inferred from an invalid call.
- Context budgeting and deterministic user-facing result rendering are follow-on reliability work.
- This checklist requires a real S21 test; unit tests and CI alone do not establish device acceptance.
