# Phase 3D-B context and native-result acceptance

Phase 3D-B makes native Android results deterministic and bounds tool output before it is retained in the agent conversation. It does not change the model-facing tool count, shell backend routing, or mutation approval policy.

## Unit acceptance

- [ ] Device information formatter preserves the exact `modelIdentifier` string, including punctuation.
- [ ] Battery formatter reports a decoded `batteryState` (`CHARGING`, `DISCHARGING`, `NOT_CHARGING`, `FULL`, or `UNKNOWN`) and human-readable power source rather than raw Android status/plugged integers.
- [ ] App discovery emits `returned`, `total`, `truncated`, and `remaining` fields; the 25-of-95 case must say `truncated=true` and `remaining=70`.
- [ ] Provider status is explicitly labeled as an availability diagnostic and lists each capability exposed by the native provider.
- [ ] Tool output is capped per tool and per user turn, with a truncation marker that preserves the beginning and end of oversized output.
- [ ] Coordinator tests verify that the budget is applied before tool results enter retained conversation history.
- [ ] Existing native approval, argument validation, shell routing, cancellation, and capability registry tests remain green.

## Galaxy S21 acceptance

Use Gemma 4 E2B in Agent Chat. Run each request separately:

1. `Identify my phone. Return the exact modelIdentifier from Android, character-for-character.`
   - Expected model identifier: `SM-G991U`.
   - It must not become `SMG91U`, `S21 Ultra`, or another inferred product name.
2. `Read my battery status. Return batteryPercent, batteryState, charging, powerSource, and temperatureCelsius exactly as returned by Android.`
   - State must be readable text, not a raw integer.
3. `List the apps I can launch. Tell me returned, total, truncated, and remaining.`
   - With 95 apps and the current 25-item cap: 25 returned, 95 total, truncated=true, 70 remaining.
4. `Show the availability of every capability exposed by the native Android provider. This is a provider diagnostic, not a battery or volume reading. Include the availability state for each capability.`
   - Expected output is the native provider diagnostic, not a catalogue of unrelated skills.
5. `Run a shell command that produces more than 6,000 characters of output. Report whether the result was truncated.`
   - Expected: the model receives no more than the per-tool result budget and sees an explicit truncation marker. Use a harmless read-only command, for example a generated sequence of repeated text.

## Scope and limitations

- The per-tool and per-turn budgets bound tool-result text, not the entire retained LiteRT-LM KV cache. Long-lived conversation compaction is a separate concern because the LiteRT-LM conversation retains its own history.
- Truncation is explicit and preserves both ends of a result; it does not paginate arbitrary shell output.
- App discovery remains capped at 25 entries. A future pagination interface can be added if real usage demonstrates a need.
- No new model-facing tool is introduced. `android_control` remains the single native Android interface.
- All mutations continue to require the existing one-time user approval.
