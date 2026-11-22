# Agent Context Budget and Progressive Capability Disclosure Roadmap

**Status:** Architecture approved; implementation is measurement-first and not yet started.  
**Owner:** Brian (product direction and device acceptance); Cocochat (architecture and implementation).  
**Scope:** Reduce Gemma's always-visible tool/schema and instruction footprint without deleting capabilities, weakening execution controls, or reducing the runtime's ability to dispatch approved operations.  
**Implementation branch:** `diagnostics/agent-context-footprint` (stacked on the current Stage 10 implementation baseline). Keep PR #62 and PR #60 open and unmerged unless Brian explicitly approves integration.

## 1. Architectural decision

Adopt a hybrid **small permanent core + dynamically exposed capability groups** architecture.

The model-visible toolset is a task-specific projection of the application's complete capability inventory. It is not the authority for what the application can execute.

### Permanent model-visible core

Keep the always-available surface deliberately small:

- `execute_shell` — the existing general-purpose shell route, retaining explicit backend identity and its existing bounds, approvals, timeout, cancellation, and result contract.
- Capability discovery/router — identifies available capability groups and requests activation of the relevant group.
- Minimal skill and MCP discovery — searchable/listable catalogs that reveal only the relevant skill or integration metadata on demand.

The permanent core should use compact schemas and concise instructions. Do not duplicate shell functionality with many narrow model-facing wrappers when the existing shell route can perform the task reliably.

### Dynamically exposed capability groups

Keep implementations registered and available application-side, but expose only the groups relevant to the current task. Split unusually broad schemas into smaller coherent subgroups where measurements and model reliability justify it.

1. **Android controls:** device/build information, Bluetooth, notifications, telephony, keystore/biometrics, and system controls. Split by domain as appropriate (for example connectivity, communications, security, and device settings).
2. **Screen and media:** accessibility inspection and approved UI actions, active media sessions, media operations, and sharing.
3. **Storage and artifacts:** Gallery-owned document trees and SAF, USB mass-storage access through SAF, and artifact generation/processing for PDF, DOCX, XLSX, PPTX, ZIP, and images.
4. **Termux:API:** bounded sensor, location, Wi-Fi, NFC, infrared, and camera operations, using Termux:API where it is the selected provider.
5. **External integrations:** MCP servers, JavaScript skills, Android intents, and specialized skills.

These are logical groups, not a requirement to create one giant schema per group. Prefer small, cohesive tool schemas and subgroups when that improves token cost, selection accuracy, or safety.

## 2. Two registries, one authority

Maintain a strict separation between:

- **Application-side capability/execution registry:** the complete set of implemented providers, dispatchers, validators, approval policies, permission gates, and execution routes.
- **Model-visible registry:** only the tools and schemas currently exposed to Gemma for the active task.

The application-side registry and coordinator retain full dispatch and approval authority regardless of the model-visible projection. Hiding a schema from Gemma must not unregister, destroy, or make the underlying implementation unavailable to the runtime.

The existing `AgentCoordinator` is the foundation because it already separates model-generated tool requests from actual tool execution. Complete this separation for Gallery's legacy tools as well as native tools: build/retain a full legacy execution dispatcher independently of the providers passed to the active LiteRT-LM conversation. A filtered conversation tool list must never accidentally make an otherwise implemented Gallery tool undispatchable.

All current validators, exact scoped approvals, Android permission ordering, execution identity rules, output limits, cancellation, timeout, cleanup, privacy protections, and fail-closed behavior remain authoritative and unchanged. Capability discovery is observational and must not itself trigger permission dialogs or sensitive operations.

## 3. Activation strategy: deterministic routing plus model discovery

Use both activation paths:

- **Deterministic router:** classify clear user intent before inference and activate the smallest matching group. Examples include an explicit Bluetooth request, a request to inspect the screen, or a request to create a PDF. Routing should be fast, predictable, and based on lightweight intent rules; it must not execute the requested operation.
- **Model-driven discovery fallback:** when intent is ambiguous, unclassified, or outside the currently exposed group, Gemma can search/list capability groups and request the relevant group. Discovery returns concise descriptions and activation options, not a dump of every schema.

The router chooses what Gemma may see; it does not grant permission or approval. Actual execution still passes through the complete application-side coordinator and existing policy boundary.

## 4. Runtime and LiteRT-LM constraints

The app currently pins LiteRT-LM Android 0.18.1. In that version, the tool manager is constructed from the provider list when a `Conversation` is created and does not expose supported add/remove operations for mutating that registry in place. Therefore, dynamically changing the native tool schemas requires creating a new conversation with the desired provider set.

The model `Engine` is separate from the `Conversation`; keep the engine/model weights loaded while rebuilding only the conversation. Conversation recreation still incurs prefill/KV-cache initialization latency and memory costs, so it must be measured and managed carefully.

Use the existing conversation factory/history-replay and compaction foundations where possible. Define explicit switching semantics:

- Activate the required group before the task's tool-using inference.
- Preserve complete conversation turns and tool-call/result transactions when rebuilding.
- Never replay partial tool transactions or transient image/audio payload bytes that are no longer valid.
- Avoid retaining two large conversations longer than necessary; create and validate the replacement, then close/release the old conversation safely, with a bounded fallback if creation fails.
- Do not lose the current user turn, duplicate a tool call, or repeat a mutation during a switch.
- If a switch cannot be completed safely, fail clearly and retain the last valid conversation rather than silently changing execution behavior.

A generic `invoke_capability(name, json)` tool for every native operation is not the default design. It could reduce schema registration/rebuild costs, but it also removes strongly typed per-operation schemas and may worsen Gemma's malformed-call behavior. Evaluate it only as a controlled alternative after establishing baseline measurements; MCP and skill discovery are better early candidates because their existing execution paths already accept generic names/arguments or load content on demand.

## 5. Measurement-first implementation plan

Do not remove capabilities or redesign the whole runtime based on character counts or intuition. Use the instrumented build and LiteRT-LM's native token accounting as the source of truth.

### Step 1 — Validate context diagnostics

- [ ] Validate PR #62 on its actual head branch, `diagnostics/agent-context-footprint`, with GitHub Actions.
- [ ] Install/run the diagnostic build on the target Galaxy S21 when the workflow is green.
- [ ] Capture native input/KV-cache token counts, provider counts, serialized schema sizes, system/safety instruction sizes, prompt component sizes, initialization/failure stages, and conversation initialization latency where available.
- [ ] Confirm diagnostics do not log prompt contents, user messages, sensitive provider results, or exception payloads.
- [ ] Record model/runtime version, device state, and test conditions so comparisons are reproducible.

### Step 2 — Establish a controlled token-cost matrix

Measure actual native token counts for the same model, system instructions, and representative initial user context under these configurations:

- [ ] No model-visible tools.
- [ ] Permanent core only.
- [ ] Each capability group individually, added to the permanent core.
- [ ] All current native and Gallery legacy capabilities.
- [ ] MCP catalog before/after on-demand discovery.
- [ ] Selected-skill metadata before/after on-demand skill discovery.

Record total tokens and incremental cost by group, plus schema character/byte sizes as secondary diagnostics. Keep system instructions, initial messages, model settings, and measurement procedure constant. Repeat measurements where runtime variance is material. The native tokenizer is authoritative; character-to-token ratios are not acceptance evidence.

Use the results to prioritize the largest savings and identify schemas that should be split or compressed. Do not assume the largest character schema is necessarily the largest token contributor.

### Step 3 — Progressive disclosure for MCP and skills first

- [ ] Replace the always-expanded MCP tool catalog in the system prompt with a compact MCP discovery/search/list surface.
- [ ] Return only relevant MCP tool names, descriptions, and input schemas after discovery; preserve the existing guarded MCP execution path and exact argument validation.
- [ ] Replace or slim always-on selected-skill metadata with compact skill discovery/search/list. Load full skill instructions only when the selected skill is needed.
- [ ] Preserve skill/MCP availability, permission/consent behavior, execution bounds, and truthful unavailable/error results.
- [ ] Compare native token count, tool selection, malformed calls, discovery latency, and successful execution against the baseline.

### Step 4 — Add native capability groups

- [ ] Define stable group/subgroup IDs and map every existing native and legacy Gallery tool to exactly one model-visible group (or explicitly document shared/core status).
- [ ] Build the complete application-side dispatcher independently from the filtered model-visible provider list.
- [ ] Implement deterministic intent-to-group routing and model-driven discovery fallback.
- [ ] Create conversations with only the permanent core plus selected group providers.
- [ ] Implement safe conversation rebuilding, history replay, current-turn preservation, resource release, and bounded failure recovery.
- [ ] Split monolithic schemas (especially broad Android control operations) into cohesive subgroups where measurements and Gemma reliability support the split.
- [ ] Keep all existing capability implementations and runtime policy controls available; this is progressive disclosure, not feature removal.

### Step 5 — Prove execution, safety, and history integrity

- [ ] Every previously implemented tool remains dispatchable through the full application-side registry, including Gallery legacy tools not currently visible to Gemma.
- [ ] Unexposed tools cannot be invoked by fabricated model tool calls; the coordinator rejects unknown/unavailable model-visible tool IDs safely.
- [ ] Group activation never bypasses or pre-approves a sensitive operation.
- [ ] Discovery does not request Android permissions, read sensitive data, or mutate device state.
- [ ] Approval denial/dismissal/timeout/cancellation remains fail-closed before provider invocation.
- [ ] Permission revocation and provider unavailability are rechecked at execution time.
- [ ] Conversation switching preserves complete history/tool transactions, does not duplicate side effects, and handles failed rebuilds without losing the last valid session.
- [ ] Image/audio payload cleanup and screenshot privacy guarantees remain intact across conversation rebuilds and compaction.

### Step 6 — Compare reliability and resource behavior

Compare baseline (all tools) against permanent-core-only and progressively activated groups:

- Native input tokens and incremental schema cost.
- Correct tool selection, successful tool-call round trips, and discovery success.
- Malformed/literal JSON frequency and recovery behavior.
- False-positive/false-negative routing and unnecessary group activation.
- Conversation creation/prefill latency and group-switch latency.
- Peak and steady-state memory, KV-cache size, and resource release after switching.
- CPU, battery, thermal behavior, and repeated-turn stability on the S21.
- History correctness, cancellation, and duplicate-execution rates.

Retain the hybrid design only if it yields material context savings without unacceptable degradation in tool reliability, latency, or memory. If one group or the full toolset is already inexpensive enough, avoid needless complexity.

## 6. Acceptance gates and non-goals

**Acceptance gates**

- The permanent core remains compact and sufficient to discover/activate every supported group.
- Native token measurements demonstrate the savings; no estimate is substituted for native counts.
- All capabilities remain implemented and reachable through the application-side registry.
- Coordinator approval, validation, permission, execution-identity, cancellation, and fail-closed semantics are unchanged.
- Conversation rebuilds are atomic from the user's perspective and preserve valid history.
- Gemma's end-to-end tool-use reliability is at least as good as baseline for the agreed regression set, with measurable context reduction.
- S21 latency, peak memory, thermal, and battery behavior remain acceptable.

**Non-goals**

- Removing implemented Android/Termux/SAF/artifact capabilities to make the prompt smaller.
- Replacing the existing general-purpose shell route with many narrow shell wrappers.
- Giving Gemma direct access to providers or bypassing `AgentCoordinator`.
- Weakening approvals, permissions, validation, privacy, or execution identity.
- Loading every schema into the prompt and merely telling Gemma not to use most of them.
- Rebuilding the model engine/weights for every group switch.
- Adopting generic JSON dispatch for all native capabilities without comparative reliability evidence.

## 7. Current state and immediate next action

This document records the approved target architecture and staged plan; it does not claim that progressive disclosure has been implemented.

**Immediate sequence:** finish PR #62 validation → collect S21 native token diagnostics → establish the controlled token-cost matrix → prioritize MCP/skill progressive discovery → implement native groups and safe conversation switching → run execution/safety/reliability/performance regression.

The existing feature-by-feature device acceptance roadmap remains authoritative for capability acceptance. This document governs the separate agent-context and model-visible registry workstream.
