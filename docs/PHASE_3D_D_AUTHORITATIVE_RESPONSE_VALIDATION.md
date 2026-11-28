# Phase 3D-D — Authoritative Response Validation and Assembly

**Status:** CODE COMPLETE / LOCAL VALIDATION PENDING  
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
- Tests cover leading-digit corruption (27→7, 27.7→7.7, 3775→775), null/missing source values, multiple tools and call provenance, allowlist/failure filtering, unchanged no-tool responses, and exact source lexeme preservation.

## Validation required

Run on the branch head:

```powershell
cd C:\Users\bvell\Downloads\gallery\Android\src
.\gradlew.bat :app:testShizukuDebugUnitTest
.\gradlew.bat :app:assembleShizukuDebug
```

The build/test gate must pass before M4 is considered validated. The S21 acceptance must then compare model summary and authoritative transcript for battery percentage, temperature, voltage, device identity, provider status, and shell output. This code does not claim device acceptance.

## Known limits

This validator is a numeric-value consistency check, not a semantic/unit-aware proof engine. It deliberately does not rewrite a model response based on heuristics. The authoritative fact block is the trustworthy representation; unsupported numeric claims are flagged for user scrutiny. Unit semantics, numeric claim-to-field binding, and model response reconstruction remain follow-up work if device acceptance demonstrates a need.
