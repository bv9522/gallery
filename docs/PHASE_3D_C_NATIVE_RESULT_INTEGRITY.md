# Phase 3D-C native result integrity

This follow-up hardens result integrity across Android framework providers and shell execution. It is deliberately device-agnostic: no manufacturer, model identifier, Android build, or device-family conditional is introduced.

## Changes

- Resolve battery percentage from the valid `ACTION_BATTERY_CHANGED` level/scale snapshot first. Use `BatteryManager.BATTERY_PROPERTY_CAPACITY` only when the broadcast pair is absent or invalid.
- Keep battery state, charging state, power source, temperature, and voltage sourced from the same broadcast snapshot.
- Sanitize actual newline and carriage-return characters in provider detail fields so one detail cannot corrupt adjacent diagnostic records.
- Append the current turn's exact, already-budgeted `android_control` and `execute_shell` tool results to the Agent Chat response. This separates tool facts from Gemma's paraphrase and preserves identifiers, numeric values, provider states, and truncation markers.
- Expose backend-level shell truncation as an explicit `Backend output truncated: true/false` field.
- Enforce the per-turn result ceiling even after it is fully consumed; an exhausted budget no longer adds an omission string that exceeds the configured maximum.

## Generic argument validation

The `android_control` schema remains operation-specific, not device-specific. `status` accepts only `operation`; `volume_status` accepts `operation` and optional `stream`. The validator rejects arguments not declared for the selected operation. There are no hardware-specific branches or inferred operation substitutions.

## Unit acceptance

- [ ] A valid broadcast percentage wins over a conflicting BatteryManager capacity property.
- [ ] Zero percent is treated as a valid reading.
- [ ] Invalid broadcast values fall back to a valid property value.
- [ ] Invalid values from both sources produce an unknown percentage.
- [ ] Provider detail newlines are sanitized without corrupting following capability fields.
- [ ] The Agent Chat transcript preserves current-turn native and shell tool results exactly, excludes previous-turn results, and retains the budget truncation marker.
- [ ] Backend truncation is explicitly represented in shell tool output.
- [ ] Repeated tool results never cause aggregate retained output to exceed the configured per-turn ceiling.
- [ ] Existing approval, routing, cancellation, and operation-schema tests remain green.

## Device acceptance

On any supported Android device:

1. Ask for device information. Compare the exact `modelIdentifier` and `deviceCode` in the verified tool-result transcript with `getprop ro.product.model` and `getprop ro.product.device` where available. Do not infer a marketing name.
2. Ask for battery status. Compare the percentage, state, power source, temperature, and voltage with a contemporaneous `dumpsys battery` snapshot. Small timing differences are possible if the battery level changes between reads.
3. Request native provider availability. Confirm each capability and any permission detail appears as a separate, intact field in the verified tool-result transcript.
4. Run a harmless shell command that produces substantially more than 6,000 characters. Confirm the verified tool-result transcript includes the explicit coordinator `TRUNCATED` marker. Also inspect `Backend output truncated` to distinguish transport truncation from coordinator context truncation.

## Scope

- No new model-facing tool is introduced.
- No device-specific identifiers or conditional behavior are introduced.
- The existing one-time mutation approval boundary and explicit shell backend selection remain unchanged.
- The transcript contains the bounded tool output already passed to the model; it does not expose unbounded process output or bypass the shared result budget.
- Result budgeting does not compact the persistent LiteRT-LM KV cache.
