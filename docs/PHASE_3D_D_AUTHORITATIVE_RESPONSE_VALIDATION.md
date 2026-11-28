# Phase 3D-D — Authoritative Response Validation and Assembly

**Status:** M1–M4 IMPLEMENTED AND LOCAL TEST/BUILD CONFIRMED; M5 REGRESSION MATRIX IN PROGRESS  
**Branch:** `phase-10i-native-capability-groups`  
**Purpose:** Keep model-written explanations useful while making runtime-captured facts the unambiguous source of truth.

## Contract

- Only successful results from the explicit `android_control`, `android_device_info`, and `execute_shell` allowlist are eligible for authoritative presentation.
- The deterministic M3 renderer is fed the coordinator's per-run captured results, not a model-transcribed history.
- Authoritative facts appear before model prose and include exact bounded values, types, paths, tool names, and call provenance as supported by the renderer.
- Model response remains intact under a clearly marked interpretation heading. The assembler does not silently correct, regenerate, or delete model prose.
- Numeric claims are conservatively compared with numeric values in eligible source results. Unsupported numeric claims trigger a visible warning; matching numbers are not proof that units or semantic field mapping are correct.
- Failed results and non-allowlisted/sensitive tools are excluded from both authoritative fact rendering and numeric validation.
- If no eligible authoritative result exists, the model response is returned unchanged.
- Raw outputs remain subject to the existing per-tool/per-turn result budget.

## Implementation

- `AuthoritativeAgentResponseAssembler` performs deterministic validation and assembles facts ahead of model prose.
- `NativeAgentRuntimeExecutor` now uses the assembler for completed native agent turns.
- M1–M4 implementation and local validation are owner-confirmed: `:app:testShizukuDebugUnitTest` and `:app:assembleShizukuDebug` passed after fixing the `mV` suffix tokenization regression.
- M5 expands the matrix for unit-suffixed corrupted claims, nested arrays/signed decimals, and numeric strings in structured JSON. Parsed JSON numeric validation now uses typed numeric nodes only, avoiding false validation from digits inside labels/version strings; unstructured output retains text scanning.

## M5 regression matrix

| Case | Expected behavior | Coverage |
|---|---|---|
| Leading-digit corruption: 27→7, 27.7→7.7, 3775→775 | Flag all unsupported claims; preserve model text and source facts | Unit test |
| Unit suffixes (`°C`, `mV`, `%`) | Tokenize numeric value; flag corrupted value | Unit tests |
| Equivalent formatting (`27.700` vs `27.7`) | Compare canonical numeric values; preserve source lexeme | Unit test |
| Null/missing values | Do not invent a numeric source fact; flag claim | Unit test |
| Typed numeric values nested in objects/arrays; signed/decimal values | Recursively validate exact numeric values | Unit test |
| Numeric-looking strings (serials, labels, versions) in structured JSON | Must not count as numeric facts | Unit test |
| Multiple eligible tools | Render independent provenance and source values | Unit test |
| Failed and non-allowlisted results | Exclude from authoritative facts/validation | Unit test |
| No eligible results | Leave model response unchanged | Unit test |
| Result bounds/truncation | Verify existing capture/renderer limits remain respected and truncation is explicit | Existing layer + acceptance review |
| Per-turn isolation | Verify prior-turn facts cannot appear in current assembly | Runtime integration review/test |
| Source/model conflict | Facts are first and clearly authoritative; model text is not rewritten | Unit test |

## Validation gate

Run on the updated branch head:

```powershell
cd C:\Users\bvell\Downloads\gallery

git pull --ff-only
cd Android\src
.\gradlew.bat :app:testShizukuDebugUnitTest :app:assembleShizukuDebug
```

After local matrix passes, complete the S21 acceptance: compare direct Android observations against model prose and the authoritative fact block for battery percentage, temperature, voltage, device identity, provider status, and shell output. Also inspect result bounds/truncation and verify turn isolation. This is the device acceptance gate; no device acceptance is claimed before it is performed.

## Galaxy S21 acceptance procedure (manual gate)

Use the same app build/model configuration for each run. Capture the direct baseline immediately before prompting the agent, then compare the tool facts and narration. Avoid changing battery/thermal state between baseline and observation.

1. **Battery/temperature/voltage baseline:** From an authorized ADB shell, run `adb shell dumpsys battery`. Record level, temperature (Android reports tenths of a degree Celsius), and voltage (mV). Ask the agent for those three values in one request. Verify authoritative typed values agree with the baseline and that the narration does not drop leading digits or decimal places.
2. **Device identity baseline:** Read non-sensitive identity fields with `adb shell getprop ro.product.model`, `adb shell getprop ro.product.device`, and `adb shell getprop ro.build.version.release`. Ask for model/device/Android version; verify exact source fields and ensure no serial/IMEI identifiers are exposed.
3. **Provider/backend status:** Ask the agent to report its active execution backend and available shell provider. Confirm the authoritative output reflects observed provider state; an unavailable provider must not be narrated as ready.
4. **Shell output:** Ask for a harmless command such as `id` and `printf 'M5-marker-27.7-3775\\n'`. Confirm output text is verbatim, values remain exact, and call provenance is shown.
5. **Conflict/corruption stress:** Ask the agent to summarize the returned facts. Inspect that model prose is explicitly marked as interpretation, unsupported claims trigger the warning, and the runtime does not silently rewrite the prose.
6. **Turn isolation:** In a new chat/turn, ask an unrelated question without invoking tools. Verify previous-turn facts/call IDs do not appear. Then invoke a tool with different values and confirm only the current invocation is represented.
7. **Bounds/truncation:** Run a deliberately bounded command/output case supported by the current tool contract. Confirm the source reports truncation (if applicable), output stays within the documented limit, and the model does not present omitted tail data as observed.

Record pass/fail for each item and retain screenshots/log excerpts only as needed. Device acceptance is not complete if any baseline mismatch, cross-turn leakage, unreported truncation, or unsupported provider claim occurs.

## Known limits

This validator is a numeric-value consistency check, not a semantic/unit-aware proof engine. It deliberately does not rewrite a model response based on heuristics. The authoritative fact block is the trustworthy representation; unsupported numeric claims are flagged for user scrutiny. Unit semantics, numeric claim-to-field binding, and model response reconstruction remain follow-up work if device acceptance demonstrates a need.
