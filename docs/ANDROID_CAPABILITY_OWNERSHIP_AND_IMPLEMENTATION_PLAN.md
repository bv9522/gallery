# Android MCP — Master Project Roadmap and Progress Ledger

> **Roadmap authority:** This section is the end-to-end project tracker. The capability ownership
> matrix, Termux:API disposition, detailed provider contracts, and wave-specific notes below remain
> the technical source of truth. If a legacy status below conflicts with this ledger, update both;
> this ledger states the current project sequence and readiness.
>
> **Last reconciled:** 2026-11-27 (M5 regression suite and debug build passed on code head `fafc0c95`; a new injectable LiteRT conversation seam and conversation-switch integration tests were then added; latest CI validation is in progress; S21 acceptance pending)  
> **Repository:** [bv9522/gallery](https://github.com/bv9522/gallery)  
> **Current implementation branch:** `phase-10i-native-capability-groups`  
> **Current change:** [PR #64 — native capability groups and safe conversation switching](https://github.com/bv9522/gallery/pull/64), stacked on PR #63 (MCP/skill progressive disclosure), PR #62 (privacy-safe context diagnostics), and PR #60 (Stage 10 image/document integration).  
> **Overall state:** Capability and artifact implementation onboarding is largely complete. Owner-run unit tests/debug assembly passed on the prior code head; additional conversation lifecycle seam/tests and CI trigger changes are now committed and await validation. Remaining work includes targeted integration/device acceptance, completing the feature-by-feature S21 validation chunks, native token/reliability/resource measurements, stacked-PR integration, and final release gates. TERMUX_RISH binary artifact output remains deliberately unavailable pending proof.

## How to read and maintain this roadmap

- **DONE** — implementation and the stated local validation are confirmed; does not imply merged or device-accepted.
- **CODE / VALIDATION PENDING** — implementation exists in a draft PR/branch; local tests/build have not been confirmed.
- **IN PROGRESS** — active slice being implemented or awaiting immediate validation.
- **PLANNED** — not implemented yet.
- **DEFERRED / NOT EXPOSED** — intentionally excluded until its permission, policy, safety, or reliability conditions are met.
- **DEVICE ACCEPTANCE PENDING** — must be tested on the target Galaxy S21 during the consolidated acceptance pass.
- **COMPLETE** — all required implementation, local validation, device acceptance, documentation, and integration gates for that workstream have passed.

A PR being open, a commit existing, or code compiling is never by itself proof of device acceptance. All implementation PRs
remain draft/open/unmerged until explicitly approved for integration. No PR is to be merged or closed without the owner's
explicit approval.

**Owner-directed validation sequencing (updated 2026-11-27):** The M5 regression suite and `:app:assembleShizukuDebug` passed locally on code head `fafc0c95fe09d683fa09da39335138815ccf766c` (60 actionable tasks; 12 executed, 48 up-to-date; 1m 6s). Subsequent commits introduce an injectable native-conversation seam and three conversation-switch tests, and correct the CI branch filters; those later changes await workflow #152/current-head validation. This is local validation, not device acceptance; `:app:assembleRelease` was not part of the local run. Historical Stage 10 CI workflow #118 remains evidence for its earlier code head only. Proceed with staged S21 acceptance using [ANDROID_MCP_FEATURE_TEST_ROADMAP.md](ANDROID_MCP_FEATURE_TEST_ROADMAP.md); do not wipe app data or uninstall as routine setup. Execution order remains implementation/validation → Stage 8 → Stage 9.

**Progress-update rule:** after every implementation slice, update (1) this ledger, (2) the capability row/status in the
ownership matrix, (3) the provider matrix if ownership/readiness changed, and (4) the relevant PR description. Record the
branch, PR, head SHA, tests/build actually run, and device evidence. Never upgrade a status based on expectation or inference.
If a capability is deliberately deferred or found unsupported, record the reason and its revisit condition.

## Project destination

Deliver a dependable, local-first Android assistant in Gallery on the unrooted Samsung Galaxy S21. A local Gemma model
should be able to reason and invoke a coherent set of useful device capabilities through one agent/coordinator/tool
registry and one approval/security boundary. The assistant should use the existing shell router for general shell work,
native Kotlin where Android APIs or Gallery-owned lifecycle/security require it, Termux:API for bounded operations it
already handles well, SAF for Gallery-granted document trees, and AccessibilityService for semantic UI automation.

The finished system must have:
- one model-facing operation per useful capability and exactly one selected provider owner;
- explicit execution identity/backend selection, reliable tool-call parsing and result delivery;
- bounded inputs, outputs, timeouts, cancellation, cleanup, and typed results;
- scoped approvals for sensitive reads and mutations, with Android permissions requested only when needed;
- no root, no Knox-sensitive modification, no arbitrary model-generated code execution, and no device-specific hacks;
- useful coverage of system, connectivity, media, privacy-sensitive, storage, removable-storage, and UI capabilities;
- documented capability availability and honest failure states when Android or user-granted access prevents an operation;
- acceptable latency, memory, battery, thermal behavior, and storage footprint on the target phone.

## End-to-end roadmap

### Stage 0 — Project definition and architecture decisions
- [x] Establish local-first Android assistant goal and Gallery fork as the app base.
- [x] Establish no-root / preserve Knox constraint and avoid Termux-hosted Ollama or llama.cpp.
- [x] Decide the model should use a universal shell route for general commands instead of a large collection of shell wrappers.
- [x] Establish the ownership principle: shell when reliable, reusable native adapter for Android APIs, Termux:API where it is the right bounded provider.
- [x] Retain discontinued local-MCP/cloud-tunnel experiments as historical branches/PRs; they are not on the critical path.
- [x] Establish one owner per capability, observational discovery, scoped approvals, fail-closed behavior, and bounded contracts.

**Exit gate:** architecture and non-goals documented; no competing provider routes or hidden privilege escalation.

### Stage 1 — Unified execution foundation
- [x] Define the unified execution request, backend identities, router, and common result contract.
- [x] Implement TERMUX, SHIZUKU, and TERMUX_RISH execution paths.
- [x] Integrate Shizuku UserService/Binder execution and the Rish bridge without requiring root.
- [x] Add the common approval policy and execution identity to requests.
- [x] Add process supervision, timeout/cancellation propagation, bounded output, and cleanup.
- [x] Integrate Termux RUN_COMMAND as a supervised execution transport.
- [x] Keep execution transport separate from capability ownership.
- [ ] Final consolidated backend acceptance on the S21: permissions, UID/backend identity, cancellation, timeout, process cleanup, and repeatability.

**Historical PR trail:** PRs #2 and #4–#11 (unified runtime, discovery/runtime integration, reliability, and mutation hardening). PR #11's
84-test result and build were previously user-confirmed. Later capability PRs are stacked and have not been merged.

### Stage 2 — Local model and agent tool execution
- [x] Integrate the LiteRT-LM / Gallery agent model path and AgentCoordinator lifecycle.
- [x] Add the shared AgentToolRegistry and native capability dispatch boundary.
- [x] Connect agent chat to the unified shell execution route.
- [x] Add model tool-call recovery, bounded context/results, and authoritative native-result handling.
- [x] Preserve explicit backend selection; do not silently fall back to a different execution identity.
- [ ] Consolidated Gemma 4 E2B real-device tool-call acceptance: natural-language request → structured tool call → approval → execution → truthful result.
- [ ] Verify malformed/literal JSON recovery, repeated tool calls, denial, cancellation, timeout, and no duplicate execution.
- [ ] Verify Agent Skills/model-selection UI lifecycle and resolve any remaining white-screen or chat integration regressions.

**Exit gate:** local model can reliably invoke the common tool runtime, not merely emit tool-call-looking text.

### Stage 2A — Authoritative tool-result integrity (M1–M5)

**Status:** M1–M5 implementation and local unit-test/debug-build validation confirmed on code head `fafc0c95`; new model-level conversation-switch tests were added afterward and await validation. S21 device acceptance remains outstanding.  
**Current implementation head:** `phase-10i-native-capability-groups` / PR #64. No device acceptance claimed.

- [x] M1 — Inspect execution pipeline and define authoritative result contract.
- [x] M2 — Shared authoritative result layer captures bounded per-run tool results, provenance, success state, and typed structured values.
- [x] M3 — Deterministic renderer preserves typed values, numeric lexemes, nested paths, arrays, and provenance; excludes failed and non-allowlisted results. The top-level-array path regression was fixed in commit `2c4891ba`; Brian confirmed the unit-test suite passed.
- [x] M4 — Assemble authoritative source facts before model-written explanation; validate numeric claims against eligible captured results and flag unsupported claims without silently rewriting prose. Brian confirmed unit tests and debug APK build passed after fixing the `mV` suffix tokenization bug.
- [x] M5 — Expanded regression matrix: units, nested arrays/signed decimals, numeric strings in JSON, null/missing, conflicts, multiple tools, allowlist/failure filtering, bounds/truncation, and turn isolation. Includes independent assembly-call isolation and structured-value validation hardening; owner confirmed unit tests and debug APK build passed on code head `fafc0c95` (2026-11-27).
- [ ] Follow-up integration seam — an injectable native-conversation adapter and three model-level group-switch tests now exercise successful replay/swap, factory failure retention, and candidate token-validation cleanup. Current-head validation is pending CI/local build.
- [ ] M5 device acceptance — On Galaxy S21, compare direct Android/ADB observations with authoritative facts/model narration for battery, temperature, voltage, identity, provider status, and shell results; verify bounds/truncation and per-turn isolation.

**M4 implementation commits:** `6dc11511` (assembler), `d62931cc` (tests), `441fb287` (runtime integration), `551bb5a0` (acceptance documentation).  
**Scope/limitations:** numeric validation is conservative consistency checking, not semantic or unit proof. The authoritative block is source-backed; model text remains explicitly marked interpretation and is never auto-corrected. See [Phase 3D-D acceptance](PHASE_3D_D_AUTHORITATIVE_RESPONSE_VALIDATION.md).

## Stage 3 — Capability vocabulary, inventory, and ownership
- [x] Create stable granular Android capability IDs and typed provider declarations.
- [x] Create one selected owner per capability and enforce registry/catalog completeness.
- [x] Separate Termux:API capability ownership from Termux shell execution transport.
- [x] Record the official Termux:API command inventory and decide which commands are reused, replaced by native APIs, or not exposed.
- [x] Separate shell path-based filesystem operations from Gallery-owned SAF document-tree operations.
- [x] Audit speech, notification, telephony, biometric, and media ownership to prevent duplicate routes.
- [x] Source-level ownership audit: every AndroidCapability enum value is assigned exactly one provider by CapabilityCatalog; CapabilityRegistry rejects missing owners, duplicate owners, duplicate declarations, and unknown provider mappings.
- [x] Reconcile known transitional diagnostics/legacy routes; Termux:API battery/volume/flashlight overlap remains diagnostic-only, with native ownership retained.
- [ ] Verify permission timing, approval behavior, provider discovery, and actual device behavior during consolidated S21 acceptance (Stage 8).

**Historical PR trail:** PRs #12–#15 establish Termux:API, catalog ownership, native system capabilities, and ownership rebalance; PR #29
reconciles readiness and ownership. Ownership documentation is maintained in this file and the provider matrix.

### Stage 4 — System and connectivity capabilities (Wave 2)
- [x] Native device/build and app discovery/launch/URL open capabilities.
- [x] Native battery, flashlight, volume, audio endpoint discovery, brightness, vibration, and bounded wake lock.
- [CODE / VALIDATION PENDING] Termux:API sensor listing and finite reads; location once/last; Wi-Fi connection information and last-scan results (PR #16).
- [CODE / VALIDATION PENDING] Termux:API bounded infrared frequency discovery/transmit (PR #17).
- [CODE / VALIDATION PENDING] Termux:API NFC NDEF read/write (PR #18); foreground tag interaction and cleanup require device verification.
- [CODE / VALIDATION PENDING] Termux:API read-only camera metadata (PR #19).
- [CODE / VALIDATION PENDING] Native Bluetooth bonded-device listing and bounded BLE scan; native USB host device/interface enumeration (PR #20).
- [CODE / VALIDATION PENDING — PR #46] Read-only USB device/configuration/interface/endpoint descriptor inspection using a fresh 30-second one-shot handle, per-device Android USB permission prompt, and separate `USB_DEVICE_IO` approval. No arbitrary endpoint transfers or writes are exposed; USB mass storage remains on SAF.
- [CODE / VALIDATION PENDING] Native Bluetooth GATT service/characteristic inspection and bounded one-shot read/write (PRs #26–#27).
- [CODE / VALIDATION PENDING — PR #47] wifi_open_settings uses Settings.Panel.ACTION_WIFI with Settings.ACTION_WIFI_SETTINGS fallback, requires one-time WIFI_CONTROL approval, and leaves the actual Wi-Fi change to the user. No silent toggle or privileged fallback is attempted.
- [ ] Verify every selected Termux:API command exists on the installed companion/package version, has correct permission behavior, bounded output, and cleanup.
- [ ] Device-test Bluetooth permission states, scan cancellation, GATT compatibility, NFC tag UX, infrared hardware availability, sensor/location/Wi-Fi data quality, USB enumeration, per-device USB permission prompt/denial, descriptor reads, device disconnect/reconnect, and handle expiry.

**Exit gate:** all exposed operations are locally validated and device-verified, or clearly marked unavailable/deferred with evidence.

### Stage 5 — Gallery-owned and privacy-sensitive integrations (Wave 3)

#### 5A — Speech services and user-visible playback
- [x] Assign speech recognition to Gallery's existing AsrServiceProvider; retain user-started hold-to-dictate and no model-triggered microphone activation.
- [x] Assign speech synthesis to Gallery's existing TtsServiceProvider; do not add a Termux TTS model route.
- [IN PROGRESS — PR #33] Add agent `speak_text` through the existing TtsPlaybackViewModel/TtsPlaybackController and Agent Chat action channel; exact text is shown in scoped approval; 1–1,000 character bound; preserve saved engine/voice/language.
- [x] Run `:app:testShizukuDebugUnitTest` and `:app:assembleShizukuDebug` for PR #33; user confirmed both tasks completed with `BUILD SUCCESSFUL` using Android Studio JBR 25.0.4. Unit-test task: 18m 51s (43 tasks); debug build: 8m 13s (50 tasks, 33 up-to-date). Build warnings remain non-fatal. Validated head: `7ac3bdb3904ea105ee7dad6574f8b92a40230a5b`.
- [ ] Device-test speech with each supported configured engine, voice/language persistence, approval denial, playback failure, and cancellation.

#### 5B — Contacts and clipboard
- [CODE / VALIDATION PENDING — PR #28; ownership audit PR #29] Contacts search through ContactsContract, maximum 20 results, bounded phone/email fields, READ_CONTACTS only after scoped approval.
- [CODE / VALIDATION PENDING — PR #28] Foreground-only plain-text clipboard reads (maximum 5 items / 8,192 characters) and writes (maximum 2,048 characters), with exact-content approval for writes.
- [ ] Validate permissions, foreground restrictions, result bounds, and no background clipboard access on device.

#### 5C — Notifications and scheduling
- [x] PR #30 local unit tests and Shizuku debug build confirmed by user.
- [CODE / DEVICE ACCEPTANCE PENDING — PR #30] Gallery-owned notification posting, bounded listing/dismissal, channel operations, reminder schedule/list/cancel using existing AlarmManager/DataStore/receivers.
- [ ] Device-test Android notification permission, disabled channels/global notifications, reboot recovery, local wall-clock scheduling, cancellation, and 50-reminder cap.
- [CODE / VALIDATION PENDING — PR #43] On-demand read of up to 20 currently active notifications from other apps through the existing user-enabled NotificationListenerService. Every read requires explicit user intent and separate one-time `NOTIFICATION_READ` approval; only app labels and bounded title/body text are returned. No notification callbacks are collected, no history is read, no background monitoring occurs, and no notification content is persisted.
- [ ] Device-test notification-listener enable/disable, listener reconnection, private/empty notification payloads, app-label fallback, approval denial, and active-notification bounds.

#### 5D — Telephony and user-mediated communication
- [x] PR #31 local unit tests and Shizuku debug build confirmed by user.
- [CODE / DEVICE ACCEPTANCE PENDING — PR #31] Non-identifying telephony metadata only (phone type/SIM state); ACTION_DIAL and ACTION_SENDTO SMS composer; user places call or presses Send.
- [CODE / VALIDATION PENDING — PR #44] `read_call_log` reads at most 20 recent entries with cached caller name/number, call type, timestamp, and duration. Every read requires explicit user intent, one-time `CALL_LOG_READ` approval, and `READ_CALL_LOG` permission requested only after approval. Android/installer eligibility restrictions fail closed; Gallery never changes the default dialer role or bypasses permission policy.
- [CODE / VALIDATION PENDING — PR #45] `read_sms_inbox` reads at most 20 recent received SMS inbox entries with sender address, bounded message preview, timestamp, and read/unread state. Every read requires explicit user intent, one-time `SMS_READ` approval, and `READ_SMS` permission requested only after approval. Android/installer/default-SMS-role eligibility restrictions fail closed; Gallery never changes the default SMS role or bypasses permission policy.
- [ ] Device-test call-log permission prompt/installer eligibility, denied permission, empty/blocked numbers, ordering, 20-entry cap, and truncation.
- [ ] Device-test SMS permission prompt/installer/default-role eligibility, denied permission, empty/private sender values, body preview bounds, ordering, 20-entry cap, and truncation.

#### 5E — Biometric authentication and secure keys
- [x] PR #32 local unit tests and Shizuku debug build confirmed by user after FragmentActivity host correction.
- [CODE / DEVICE ACCEPTANCE PENDING — PR #32] Foreground strong BiometricPrompt; boolean result only; app-namespaced non-exportable AES-256/GCM Android Keystore keys; fresh strong biometric per encrypt/decrypt; invalidation on enrollment change.
- [ ] Device-test strong biometric prompt, cancel/failure paths, hardware-backed key availability, enrollment invalidation, key deletion, and ciphertext recovery expectations.

#### 5F — Downloads, media, and sharing (remaining Wave 3E)
- [x] Gallery-owned download status/progress and exact-worker cancellation through the existing DownloadRepository/WorkManager path (PR #34); bounded opaque handles and scoped cancellation approval. User confirmed the local unit-test task and Shizuku debug build passed; device acceptance remains pending.
- [CODE / VALIDATION DEFERRED — PR #35] Bounded `media_search` across MediaStore-visible internal/removable media and persisted user-granted SAF trees. AUTO combines filename search with the existing EmbeddingGemma-2 Smart Album visual index; NAME is filename-only; VISUAL requests semantic visual search. Results contain five-minute opaque handles, not paths or content URIs.
- [CODE / VALIDATION DEFERRED — PR #35] `media_index` reuses the existing SmartAlbumIndexingWorker and EmbeddingGemma-2 vector database. It indexes permitted MediaStore photos/videos, media previously selected through Gallery's photo picker, and up to 1,000 media files discovered per pass in already-granted SAF trees; indexing requires one-time approval and remains on-device. SAF discovery is bounded to depth 8 / 10,000 visited nodes; subsequent runs skip already indexed SAF document URIs and advance through the bounded scan.
- [CODE / VALIDATION DEFERRED — PR #35] `media_play` requires a fresh search handle and one-time approval, then opens the selected video in Gallery's own Media3 player. It does not launch an external ACTION_VIEW intent and does not expose cross-app media-session controls.
- [CODE / VALIDATION DEFERRED — PR #37] `media_sessions.list` reads up to 10 active cross-app media sessions through `MediaSessionManager` only after the user enables Gallery's notification-listener special access. It returns bounded app/track metadata, playback state, supported actions, and 2-minute opaque handles. It does not inspect/store notifications or expose package names/session tokens.
- [CODE / VALIDATION DEFERRED — PR #37] `media_sessions.control` supports only advertised PLAY, PAUSE, NEXT, PREVIOUS, and SEEK actions; SEEK requires a known track duration and an in-range integer position. Every inspection/control requires one-time approval. Handles are refreshed by each list call, revalidated against the active session, and consumed after successful dispatch.
- [CODE / VALIDATION DEFERRED — PR #37] `media_sessions.open_settings` is available only when explicitly requested; it opens Android notification-listener settings but does not grant access. Provider status reports `PERMISSION_REQUIRED` until the user enables Gallery manually. The listener service performs no callback collection or background monitoring; PR #43 adds only explicit on-demand active-notification snapshots after separate `NOTIFICATION_READ` approval, with no history or persistence.
- [CODE / VALIDATION DEFERRED — PR #36] `share_content.share_text` approves the exact bounded text (1–4,000 characters) and opens Android's ACTION_SEND chooser. `share_file` accepts only a document ID plus a persisted SAF tree URI already granted to Gallery; it verifies the file metadata, requires a known size up to 25 MiB, and grants temporary read access through ACTION_SEND/ClipData. The user chooses the receiving app and completes or cancels; opening the chooser is not evidence of delivery.
- [x] Add bounded argument-validation tests and document the exact chooser, approval, SAF-grant, temporary-read, and non-delivery semantics. The PR #36 argument/approval unit tests passed in workflow #118; ACTION_SEND chooser behavior and file-grant lifecycle remain pending S21 acceptance.

**Wave 3 exit gate:** all Gallery-owned capabilities have a single owner, scoped approval/permission contract, bounded lifecycle, local validation,
and documented device-acceptance plan. Sensitive capabilities not meeting the gate remain unavailable rather than being silently broadened.

### Stage 6 — Files and removable storage (Wave 4)
- [x] Retain the unified shell router for general path-based file and directory operations; do not create duplicate model-facing file tools.
- [CODE / VALIDATION PENDING — PR #23] Gallery-owned SAF document-tree picker and persisted URI grants; grant listing, bounded directory listing, metadata, text read/write, create/rename/delete.
- [CODE / VALIDATION PENDING — PR #24] Same-tree copy/move and bounded name-only search.
- [CODE / VALIDATION PENDING — PR #25] Cross-tree file copy/move and generated text-file creation; transfer bounded to 100 MiB; source deleted only after successful destination copy.
- [ ] Verify Gallery URI grant persistence/revocation and strict rejection of arbitrary model-supplied paths/URIs.
- [ ] Verify internal storage and attached USB mass-storage devices through SAF; test multiple document providers, errors, low storage, cancellation, partial transfer, and recovery.
- [ ] Verify no confusion between UsbManager peripheral enumeration and USB mass-storage filesystem access.
- [ ] Confirm destructive operations require stronger confirmation and transfer bounds are enforced.

**Exit gate:** safe and reliable user-granted access to phone storage and USB removable storage, with no root or broad storage permission.

### Stage 7 — Accessibility and UI automation (Wave 5)
- [x] Define the opt-in AccessibilityService contract, manual enable/disable lifecycle, and explicit settings handoff.
- [x] Implement bounded, explicitly approved window-specific screenshot capture and multimodal Gemma handoff (Wave 5B / PR #40 draft; validation deferred).
- [x] Implement read-only semantic UI tree inspection with bounded traversal, sanitized labels, opaque short-lived element handles, and sensitive-screen exclusions (PR #39 draft; validation deferred).
- [x] CODE COMPLETE — PR #41 adds approved semantic click, long-click, and forward/backward scroll through fresh opaque handles, exact external-window/node revalidation, and one-time approval for each action. Coordinate gestures remain on the existing unified shell route only when explicitly requested/authorized.
- [x] CODE COMPLETE — PR #42 adds plain-text-only entry into empty ordinary single-line fields and explicit bounded text selection through one-shot handles, exact app/window/node revalidation, and separate UI_TEXT_INPUT approval.
- [ ] Keep coordinate gesture fallback on the existing unified shell route only when explicitly requested/authorized; do not duplicate semantic operations.
- [x] CODE COMPLETE — PR #41 per-action approval, one-shot handle consumption, stale-node rejection, app/window identity checks, and sensitive/incomplete hierarchy exclusions are implemented for click/long-click/scroll.
- [x] CODE COMPLETE — PR #42 text entry is limited to empty ordinary single-line plain-text EditText fields (maximum 256 characters); selection uses explicit end-exclusive character offsets. Current field contents are not returned. Password, numeric, email, URI, multiline, sensitive, and uncertain fields fail closed.
- [ ] Add integration tests for service unavailable/disabled, permission denial, node churn, app transitions, timeouts, and bounded results. PR #41 adds pure argument/action-policy tests, but no tests are run under the consolidated validation plan.
- [ ] Device-test accessibility enablement, screen capture restrictions, multiple apps, keyboard behavior, and safe failure.

**Wave 5A — bounded semantic screen inspection (PR #39 draft):** `screen_inspect` supports `status`, `inspect`, and explicit `open_settings`. Gallery's AccessibilityService is manually enabled in Android settings and reports `PERMISSION_REQUIRED` until enabled and connected. The service ignores accessibility events and reads the active window only during an approved inspection request. Traversal is capped at 120 nodes and depth 12; more complex or changing trees fail closed. At most 32 semantic elements are returned, with labels sanitized to 120 characters and 30-second opaque `ui_` handles. Any editable/password field, sensitive label or identifier, protected app category, Android Settings/system/permission UI, or incomplete tree blocks the entire screen. No package names, view IDs, coordinates, screenshots, hidden content, notifications, or raw node references are returned. The handles are references for a future action slice only; no UI actions are exposed in this PR. Policy/unit tests passed in workflow #118; AccessibilityService runtime acceptance remains pending.

**Wave 5B — bounded active-window vision input (PR #40 draft):** Extends `screen_inspect` with `capture`. Gallery requires the user-enabled AccessibilityService, Android API 34+, a one-time `SCREEN_CAPTURE` approval, and exactly one visible non-Gallery application window alongside Gallery (normally split-screen). The service enumerates interactive application windows and rejects missing, unreadable, ambiguous, empty, truncated, or sensitive hierarchies before capture. It uses only `AccessibilityService.takeScreenshotOfWindow(windowId, ...)`; API 30 display-wide capture and MediaProjection are not fallbacks. The image is resized to at most 1,280 px on its longest side and 2 MiB JPEG, with a 10-second timeout. The agent coordinator carries the raw image bytes into `Message.tool(Contents)` as `Content.ImageBytes` alongside the `Content.ToolResponse`, so Gemma receives actual pixels in the tool-result turn. Per-tool/per-turn image budgets reject rather than truncate images; raw coordinator bytes are zeroed and references removed after LiteRT-LM finishes the turn, and on rejection, cancellation, reset, or turn-limit exit. No screenshot file or background capture is created. The model's in-memory conversation may retain visual context until reset. Sensitive-screen checks are conservative heuristics, not a guarantee that every visually sensitive third-party screen can be recognized; approval explicitly reminds the user to avoid private screens. The selected Gemma 4 E2B entry in `model_allowlists/1_0_23.json` declares image support and an 8 GiB minimum device-memory requirement; the tool is hidden for models/runtime configurations without image support. The multimodal tool-turn path is implemented but still needs consolidated runtime verification and S21 performance acceptance.

**Wave 5C — approved semantic UI actions (PR #41 draft):** Adds `screen_inspect inspect_external` to inspect exactly one visible non-Gallery application window alongside Gallery and issue 30-second opaque, one-shot action handles. `screen_inspect act` supports only `CLICK`, `LONG_CLICK`, `SCROLL_FORWARD`, and `SCROLL_BACKWARD`; every individual action requires `UI_GESTURE` approval describing the action and safe target label. Immediately before dispatch, Gallery confirms the service is still connected, the same single external app/window remains visible, the hierarchy is complete and passes the sensitive-screen policy, and exactly one node matches the stored package/window/class/sanitized label/bounds fingerprint and enabled/action-capability state. The target must still advertise the requested AccessibilityNodeInfo action; no coordinates or gesture fallback are used. Handles are consumed before dispatch, so retries require a fresh inspection. Results report Android's dispatch acceptance only, not a verified app-state change. Swipe and coordinate gestures are not exposed by AccessibilityService; coordinate fallback remains on the existing unified shell route only when explicitly requested and authorized. Text entry/selection are implemented separately in Wave 5D / PR #42. Keyboard automation remains unexposed. Argument and pure action-eligibility unit tests passed in workflow #118; AccessibilityService runtime and S21 acceptance remain pending.

**Wave 5D — approved semantic text entry and selection (PR #42 draft):** Adds `inspect_inputs`, a separate explicitly approved inspection path for exactly one visible external application window alongside Gallery. It accepts only ordinary single-line `EditText` fields whose Android input type is normal plain text; password, numeric, email, URI, multiline, sensitive, unsupported, incomplete, and uncertain fields block the inspection. It returns safe labels and character counts only—never current field contents—and issues 30-second one-shot handles. `enter_text` accepts at most 256 ordinary non-sensitive single-line characters and only targets a fresh enabled empty field, so existing text is never overwritten. `select_text` accepts a non-empty end-exclusive character range within the current field length and does not return field contents. Each operation requires a separate `UI_TEXT_INPUT` approval showing the exact text or selection range. Immediately before dispatch, the service revalidates the same app/window, complete safe hierarchy, exact node class/label/bounds, normal input type, enabled state, current text length, and currently advertised Android accessibility action. Handles are consumed before dispatch; stale or changed targets fail closed. No passwords, clipboard access, arbitrary keyboard events, multiline input, coordinate gestures, or hidden typing are exposed. Pure policy and argument unit tests passed in workflow #118; text-entry/selection runtime and S21 acceptance remain pending.

**Exit gate:** user-enabled accessibility is transparent, controllable, bounded, and demonstrably safer than generic coordinate automation.

### Stage 10 — Artifact generation engine (owner-approved capability onboarding before consolidated validation)

**Status: IMPLEMENTATION COMPLETE — Stage 10A–10I code and documentation are onboarded. Stage 10J consolidated tests/builds and device acceptance are now the only remaining Stage 10 gate. TERMUX_RISH binary output is explicitly unavailable; on-device generative image creation is not registered because no supported resource-qualified provider exists.**

**Approved architecture**
- One shared Artifact Generation Engine and one model-facing artifact-generation contract, not six unrelated tools or duplicated file-management routes.
- Native Kotlin owns orchestration, format dispatch, structured request validation, approvals, lifecycle, temporary-output management, cancellation, verification, and typed results.
- Replaceable format adapters cover PDF, DOCX, XLSX, PPTX, ZIP, and image processing. Select dependencies only after API/minSdk compatibility, licensing, maintenance, APK-size, memory, and feature review.
- Both destination paths are supported:
  - **SAF:** a persisted Gallery-granted document tree selected by the user, including internal storage, SD cards, and USB mass storage exposed through Android document providers.
  - **Unified shell router:** a path accessible to the explicitly selected TERMUX or SHIZUKU execution identity, retaining existing approval and identity semantics. TERMUX_RISH binary artifact output remains unavailable until stdin forwarding, cancellation, and hash verification pass end-to-end.
- SAF and shell are destination adapters, not competing generation owners. No silent fallback or identity substitution. Gallery SAF grants are not transferable to Termux; shell visibility is not inferred from SAF access.
- Image support has two independent paths: local image processing and optional generative-image providers. Gemma 4 E2B image-input support is not image-generation support. Remote generation requires disclosure and explicit opt-in/approval before prompts or source images leave the device. On-device generation is optional and resource-gated; no mandatory cloud dependency.
- Artifact content is structured and typed. Do not execute generated scripts/templates/macros or evaluate spreadsheet formulas; formulas, if supported, are inert workbook content.

**Implementation sequence**
- [x] 10A — Dependency/API/license preflight complete. Baseline is minSdk 31, targetSdk 37, Java/Kotlin bytecode target 11. Use platform `android.graphics.pdf.PdfDocument` for PDF, `java.util.zip` streaming for ZIP and OOXML packages, native Android bitmap/image APIs plus the already-present ExifInterface for local image processing, and a narrowly scoped in-house OOXML writer for DOCX/XLSX/PPTX. No third-party document/image-generation dependency is selected or added; avoid Apache POI/docx4j/heavy office runtimes. AndroidX PDF dependencies already present are viewer/document-service components, not a PDF authoring engine. See `docs/ARTIFACT_GENERATION_DEPENDENCY_REVIEW.md` for API, licensing, APK, SAF, and shell-streaming findings.
- [x] 10B — Define strict shared request/result contracts, format and destination registries, a generic `artifact_create` tool contract, format-specific generator interfaces, typed results, per-generator output caps, common payload size/depth/node bounds, a 120-second operation timeout, cancellation propagation, and SAF/shell destination abstractions. Add exact one-time approval with format/name/destination/estimated-size/overwrite details. Runtime registration of the generic tool is paired with 10C's first working PDF adapter so the model is never shown an empty artifact capability. Unit tests passed in workflow #118; instrumentation and S21 acceptance remain pending.
- [x] 10C — PDF core implemented in PR #51: native `android.graphics.pdf.PdfDocument` output with bounded titles, headings, paragraphs, bullets, simple tables, explicit section pagination, 100-page/100,000-character/10 MiB limits, streaming SHA-256 and `%PDF-` signature/MIME validation, one-time exact approval, and a binary SAF writer that verifies the persisted grant and cleans partial files. The generic tool is registered but advertises only PDF + SAF until other adapters are implemented. Embedded images are implemented in the final image integration slice; both shell destinations are implemented in Stage 10I. Unit tests passed in workflow #118; Android instrumentation and S21 acceptance remain pending.
- [x] 10D — DOCX core implemented in PR #52: genuine editable OOXML ZIP package with content types, package/document relationships, styles, paragraphs, headings, bullets, tables, A4 page layout, optional header/footer parts, XML 1.0 character validation/escaping, and bounded payload/output. Tests inspect package entries and parse every XML/relationship part for well-formedness; unit tests passed in workflow #118; independent office-reader openability and S21 acceptance remain pending. Bounded embedded SAF images are now supported; independent office-reader relationship/openability validation remains Stage 10J.
- [x] 10E — XLSX core implemented in PR #53: genuine editable OOXML workbook package with workbook/sheet relationships, content types, styles, multiple named worksheets, typed string/numeric/boolean/blank cells, bounded column widths, optional header-row styling and auto-filters. Limits: 20 sheets, 500 rows/sheet, 32 columns/row, 10,000 cells, 100,000 text characters, 15 significant numeric digits, and 20 MiB output. Formula-looking strings are stored as inline text; formulas/macros are not generated or evaluated. Tests inspect package structure, typed cells, XML well-formedness, formula-as-text safety, and malformed bounds; unit tests passed in workflow #118; device acceptance remains pending.
- [x] 10F — PPTX core implemented in PR #54: editable OOXML presentation package with presentation/slide/master/layout/theme parts and relationships, 16:9 slide size, editable title/body text shapes, paragraphs and bullet paragraphs, bounded slide/block/text limits, XML escaping/validation, and 30 MiB output cap. Tests inspect required package parts and parse every XML/relationship part for well-formedness; unit tests passed in workflow #118; device acceptance remains pending. One bounded embedded SAF image per slide (four per presentation) is now supported; richer layouts/shapes and independent PowerPoint/LibreOffice openability remain out of scope for this stage.
- [x] 10G — ZIP create/extract implemented across PRs #55–#56. Creation streams up to 100 UTF-8 text entries (1 MiB each, 5 MiB total uncompressed) with 240-character relative paths, case-insensitive duplicate and file/directory collision checks, traversal/absolute/drive/backslash/control-character and reserved-Windows-name rejection, and 20 MiB output cap. Extraction uses a two-pass SAF flow: preflight the user-selected ZIP within a persisted source-tree grant; validate every entry/path, duplicates/collisions, 500-entry, 50 MiB compressed/expanded total, 10 MiB per-file, and known 100:1 compression-ratio limits; hash the archive and verify it is unchanged after approval; then stream regular files/directories into a separate persisted SAF destination tree. It rejects existing targets, never creates/follows symlinks, requests separate exact approval, propagates cancellation, and attempts reverse-order partial-tree cleanup with explicit cleanup-failure reporting. Path-policy unit tests passed in workflow #118; SAF provider behavior and extraction acceptance remain Stage 10J device validation.
- [x] 10H — Local image processing and optional provider boundary implemented in PR #57. Native Android BitmapFactory/Bitmap processing reads only a persisted SAF source document; supports resize, crop, right-angle rotation, and PNG/JPEG/WebP conversion. Bounds: 20 MiB source, 8,192 px/source side, 20 MP source area, 8 MP decode sampling, 4,096 px output side, 16 MP output area, 20 MiB output, quality 1–100. Added `ImageGenerationProvider` interface, provider registry, and guarded dispatcher. Firebase Gemini image generation is conditionally registered when Firebase is configured and always requires one-time consent showing provider ID, exact prompt, output MIME, and visible labels for every source image before any upload. Source count/bytes and output bytes are bounded. Local resize/crop/rotate/convert/compose/annotate remains on-device. Embedded SAF images are supported in PDF (up to eight/document), DOCX (up to four/document), and PPTX (one/slide, four/presentation), with EXIF orientation normalized before embedding. No on-device generative provider is registered; Gemma 4 E2B image input is not image generation. Unit tests passed in workflow #118; instrumentation and S21 acceptance remain pending.
- [x] 10I — SAF binary output already streams through document-provider output streams independently of the 256 KiB UTF-8 text-operation cap. Added Termux binary delivery through `RUN_COMMAND_STDIN` in bounded 96 KiB raw chunks (base64 payload stays at or below 128 KiB characters, about 256 KiB in the UTF-16 Intent string); each chunk explicitly selects the APP_SHELL runner through RUN_COMMAND_RUNNER, which consumes the stdin extra without opening a terminal session, then destination-side byte-count/SHA-256 verification precedes same-directory `mv` finalization. Added Shizuku `writeBinaryFile` over a one-shot `ParcelFileDescriptor` pipe; the shell-UID UserService streams into a same-directory temporary file, enforces a 50 MiB ceiling and deadline, verifies SHA-256, and renames only after verification. Both shell paths use the shared exact destination/backend/overwrite approval, private staging, cancellation cleanup, and no identity fallback. `TERMUX_RISH` is explicitly rejected for binary output until stdin forwarding is independently proven. Pure policy/fake-transport tests cover chunk bounds, multi-chunk delivery, Shizuku identity/metadata, failure/cancellation cleanup, overwrite behavior, and Rish rejection. Android instrumentation tests now exercise real Termux APP_SHELL stdin and Shizuku PFD transfer, but were not executed by workflow #118. Pure policy/fake-transport unit tests passed in the 308-test CI suite. Protocol verification and S21 acceptance remain Stage 10J. The 100 MiB SAF cross-tree transfer cap is not a universal generation limit.
- [x] 10J automated CI gate — workflow #118 passed :app:testShizukuDebugUnitTest (308 tests), :app:assembleShizukuDebug, and :app:assembleRelease on commit 7bacd681bdd8d5b35d255933e21e66c6a3780b09. Run: [Build Android APK #118](https://github.com/bv9522/gallery/actions/runs/44436706557). This validates the JVM unit suite and both APK builds; it does not execute instrumentation or device tests.
- [ ] 10J instrumentation and S21 acceptance — execute the Termux APP_SHELL stdin and Shizuku PFD instrumentation tests on a supported Android test target, then perform the feature-by-feature Galaxy S21 pass in [ANDROID_MCP_FEATURE_TEST_ROADMAP.md](ANDROID_MCP_FEATURE_TEST_ROADMAP.md). Cover format openability, malformed content, size boundaries, low storage, provider errors, revoked SAF grants, inaccessible shell paths, backend identity, overwrite, cancellation, cleanup, process death, ZIP traversal/bombs, image decode limits, and optional-provider unavailable/offline behavior. Measure APK/dependency growth, RAM, CPU, storage, latency, battery, and thermals.

**Shared safety and lifecycle requirements**
- Use app-private temporary staging only when required; enforce a staging quota, stream large content, clean partial outputs on failure/cancellation, and avoid holding entire artifacts in memory.
- Never silently overwrite. Approval identifies the exact destination/name and replacement behavior. Reject ambiguous/provider-inconsistent overwrite states.
- Validate MIME type, extension, and actual signature/package structure where applicable; extensions alone do not prove a valid artifact.
- Enforce per-format limits for bytes, pages/slides/sheets/rows/cells/image dimensions, archive entries/expanded bytes/ratio, generation time, and output count. Set numeric defaults after dependency/resource preflight; do not inherit 256 KiB or 100 MiB indiscriminately.
- Keep generated artifacts local by default. Sharing/uploading remains a separate user-mediated operation; generation never silently transmits files.
- Reuse SAF grants, shell router, approval boundary, and cancellation infrastructure. No root, persistent background service, Python runtime, office-suite daemon, or duplicate file CRUD tools.

**Stage 10 onboarding exit gate:** all format families, embedded-image paths, local image operations, and configured remote image-provider consent path are implemented with bounded contracts; SAF, Termux, and Shizuku have explicit streaming adapters; TERMUX_RISH and on-device generative image creation are honestly marked unavailable. Implementation is complete. Stage 10J must now pass Gradle tests/build and S21 device acceptance before Stage 10 can be declared fully closed. Stage 8 and Stage 9 follow.

**Project completion definition:** Stages 0–10 onboarding, Stage 8 validation/acceptance, and Stage 9 integration/release exit gates have passed, the final app is integrated and reproducibly built,
the target S21 acceptance report is complete, and all remaining unsupported/deferred capabilities are explicitly documented.


### Stage 8 — Consolidated provider verification and S21 acceptance (Wave 6)
**Status: AUTOMATED VALIDATION PARTLY CURRENT-HEAD CONFIRMED; DEVICE ACCEPTANCE PARTIAL.** The previous PR #64 code head passed owner-run unit tests and debug APK assembly on 2026-11-27. Subsequent native-conversation seam/test commits are pending current-head CI validation. Historical workflow #118 validated an earlier Stage 10 head, including release assembly. The S21 feature matrix is still incomplete; proceed one chunk at a time using [ANDROID_MCP_FEATURE_TEST_ROADMAP.md](ANDROID_MCP_FEATURE_TEST_ROADMAP.md), and do not treat local/CI build success as device acceptance.

#### 8A — Consolidated test and build preflight
- [ ] Confirm the complete dependency chain resolves through the final Stage 10 implementation head stacked on PR #48; keep all PRs draft/open/unmerged.
- [x] Inventory every test source in the current branch tree and map major test families to existing seams.
- [ ] Confirm the complete Gradle task graph/instrumentation task names on the local Windows build and map remaining coverage gaps.
- [ ] Confirm Windows JDK/Gradle/Android SDK paths, compile SDK 37 installation, signing identity, available disk space, and ADB/device connectivity before execution.
- [ ] Capture baseline repository state and exact commands/results; do not clean or wipe app/device data as a precondition unless specifically needed and approved.

#### Initial test-source inventory (PR changed-file review: #16–#47)
The first pass over changed-file lists identifies these existing/extended unit-test seams (this is an inventory, not proof that coverage is complete):
- Termux:API: `TermuxApiCapabilityToolTest`, `TermuxApiReadCommandTest`.
- Native capability dispatch and contracts: `AndroidControlArgumentsTest`, `AndroidNativeCapabilityToolTest`.
- Ownership and routing: `CapabilityRegistryTest`.
- SAF and document-tree operations: `DocumentTreeCapabilityToolTest`.
- Media, sharing, and notifications: `ActiveMediaSessionCapabilityToolTest`, `ShareContentArgumentsTest`, `NotificationContentPolicyTest`, plus shared native argument/dispatch tests.
- Accessibility and multimodal agent loop: `AccessibilityScreenInspectionArgumentsTest`, `AccessibilityScreenInspectionPolicyTest`, `AgentCoordinatorTest`, `AgentToolResultBudgetTest`, `LiteRtLmResponseProtocolTest`.
- USB: `UsbDescriptorParserTest`.
- Model catalog: `GalleryAllowlistedModelCatalogTest`.

The original changed-file review of PRs #16–#47 did not include the later Stage 10 instrumentation source. The complete current branch-tree inventory below supersedes that limited review; Gradle task-graph confirmation remains outstanding.

**Complete current-head source inventory (2026-11-27):** The branch tree contains **55 JVM unit-test source files** and **1 Android instrumentation-test source file**. Unit coverage is grouped as: agent/runtime (6); artifact generation/transport (14); Android capabilities and provider policy (15); coordinator/protocol/shell (12); model catalog (1); Shizuku (2); Termux (3); discovery tools (1); UI diagnostics (1). The sole current instrumentation source is `ShellBinaryTransportInstrumentedTest.kt`, which exercises Termux APP_SHELL stdin and Shizuku PFD binary transfer behind device prerequisites. It has not been run. The three new `LiteRtLmAgentModelConversationSwitchTest` cases provide a fake-native-conversation seam for successful replay/swap, factory failure, and candidate token-validation cleanup; current-head CI validation is pending workflow #157.

**Coverage interpretation:** JVM tests validate policy, argument bounds, registry/dispatch behavior, fake transports, and OOXML/package structure. They do not validate Android framework prompts, SAF provider quirks, AccessibilityService windows/nodes, Binder/PFD lifecycle, hardware, model tool-call reliability, or resource impact. These remain device/instrumentation gates.

**High-priority coverage review:** current unit tests cover many policy/fake-provider cases, but identify missing Android instrumentation for permission/approval ordering at the actual framework boundary; SAF grant/revocation/provider behaviors; AccessibilityService enablement/window/node churn; notification/call-log/SMS eligibility; USB permission/disconnect; PFD/Binder cancellation and abrupt-process cleanup; and lifecycle behavior across backgrounding/process death. Multimodal image-byte clearing has unit tests but must be verified in the native model loop. These gaps remain mapped to device chunks and any required instrumentation additions.

#### 8B — Consolidated automated validation
- [x] Run `:app:testShizukuDebugUnitTest` on code head `fafc0c95fe09d683fa09da39335138815ccf766c`; owner confirmed local pass on 2026-11-27. **Latest seam/test changes await workflow #157 or owner-side validation.** Historical workflow #118 also passed (308 tests) on its earlier Stage 10 head.
- [x] Run `:app:assembleShizukuDebug` on code head `fafc0c95`; owner confirmed local pass on 2026-11-27. **Latest seam/test changes await workflow #152 or owner-side validation.** Historical workflow #118 also passed and uploaded its APK artifact.
- [x] Run `:app:assembleRelease` on earlier Stage 10 CI head; workflow #118 passed and uploaded the APK artifact. **Current PR #64 head release assembly remains unverified.**
- [ ] Record APK SHA-256, versionCode, and signing certificate fingerprint during Chunk 00 artifact provenance; CI success alone does not substitute for that installation baseline.
- [ ] Execute Android instrumentation tests and S21 device acceptance in the feature-chunk order; workflow #118 did not run instrumentation.
- If failures occur later, group by root cause, fix on the appropriate stacked branch/PR, add regression coverage, and rerun the affected tests plus the agreed full gate. No PR merge without explicit owner approval.

#### 8C — S21 installation and core runtime smoke tests
- [ ] Install/update using the same signing identity; verify package/version, launch, model selection/load, Agent Skills UI lifecycle, and no white-screen regression.
- [ ] Verify Gemma 4 E2B natural-language request → structured tool call → approval → execution → native result → model response; cover malformed/literal JSON, repeated calls, denial, cancellation, timeout, and no duplicate dispatch.
- [ ] Verify TERMUX, SHIZUKU, and TERMUX_RISH backend identity, permissions, bounded output, timeout/cancellation, and process cleanup without root.

#### 8D — Capability acceptance matrix
- [ ] For every capability: verify provider discovery, unavailable/permission-required states, approval scope and denial, Android permission timing, success result, bounds, stale-handle rejection, timeout/cancellation, and cleanup.
- [ ] System/connectivity: Termux:API, sensors, location, Wi-Fi info/settings handoff, Bluetooth/BLE/GATT, NFC, infrared, USB enumeration/descriptors, camera metadata, and supported hardware states.
- [ ] Storage/media: SAF grants and revocation, removable storage, cross-tree operations, media filename/visual search, indexing bounds, playback, downloads/cancellation, and sharing chooser cancellation/completion.
- [ ] Privacy-sensitive: contacts, clipboard foreground rules, notification listener/content reads, call log, SMS inbox, telephony composer handoffs, biometric/Keystore, and notification scheduling/reboot recovery.
- [ ] Accessibility: service enable/disable, semantic tree inspection, sensitive-screen rejection, split-screen/window identity, screenshot multimodal delivery and memory cleanup, click/long-click/scroll, empty-field text entry, selection, stale handles, and denial paths.
- [ ] Speech: configured ASR/TTS engines, voice/language persistence, explicit playback approval, cancellation, and failure behavior.

#### 8E — Resilience, security, and resource acceptance
- [ ] Exercise missing companion apps, unsupported hardware, revoked permissions, disabled special access, denied approvals, backgrounding, process death, device disconnect/reconnect, expired/replayed handles, timeout, and cancellation.
- [ ] Measure representative task latency, CPU/GPU selection, RAM/model memory, storage growth, battery drain, thermal behavior, and background process count.
- [ ] Confirm no-root/Knox integrity, no silent privilege escalation, no sensitive content persistence, and no unsupported USB endpoint transfers/writes.
- [ ] Record each capability as PASS / LIMITED / UNAVAILABLE / FAIL with device/build details, reproducible steps, and evidence; fix and retest failures.

**Exit gate:** automated tests and debug build pass on the consolidated head; the S21 acceptance matrix has evidence for every exposed capability; limitations are explicit; no unexplained failures remain; security and resource constraints are acceptable.

### Stage 9 — Integration, release, and maintenance
**Status: NOT STARTED — follows Stage 10 onboarding and Stage 8 consolidated validation/S21 acceptance.**
- [ ] Review the complete stacked PR chain for dependency order, duplicate changes, stale branches, and documentation consistency.
- [ ] Obtain explicit owner approval before merging any PR; merge in dependency order only after validation/acceptance criteria are met.
- [ ] Reconcile the final integration branch with the canonical ownership plan and provider matrix.
- [ ] Produce a reproducible signed release APK/build artifact with version/build metadata and documented install/update path.
- [ ] Perform clean install and upgrade testing on the S21; verify model download/use, settings persistence, and app data behavior.
- [ ] Publish user-facing setup, permissions, capability availability, limitations, recovery, and troubleshooting documentation.
- [ ] Establish regression checks for Android/API changes, Termux:API compatibility, Shizuku/Rish behavior, Gallery upstream changes, and model/runtime updates.
- [ ] Maintain the roadmap after each future capability or compatibility change.

## Current progress snapshot (reconciled 2026-11-27)

| Workstream | Current status | Evidence / next gate |
|---|---|---|
| Architecture and no-root principles | DONE | Documented in this plan |
| Unified execution foundation | Implemented; device acceptance pending | PR #2, #4–#11; historical PR #11 local validation was user-confirmed |
| Local Gemma agent/tool runtime | Implemented; consolidated device acceptance pending | AgentCoordinator/LiteRT-LM/tool registry; real-device end-to-end retest remains |
| Capability catalog and owner model | Implemented; final audit pending | PR #12–#15 and #29 |
| System/Termux:API providers | CODE / VALIDATION PENDING | PR #16–#19; native Bluetooth/USB discovery PR #20 |
| Bluetooth GATT | CODE / VALIDATION PENDING | PR #26–#27 |
| Wave 3A contacts/clipboard | CODE / VALIDATION PENDING | PR #28–#29 |
| Wave 3B notifications/scheduling | LOCAL VALIDATION DONE; device acceptance pending | User confirmed tests/build for PR #30 |
| Wave 3C telephony/user-mediated actions | IMPLEMENTED IN DRAFT PRs; UNIT CI PASSED; device acceptance pending | PR #31 local tests/build user-confirmed; PR #44 call-log and PR #45 SMS inbox unit tests passed in workflow #118; device acceptance remains pending |
| Wave 3D biometric/Keystore | LOCAL VALIDATION DONE; device acceptance pending | User confirmed tests/build for PR #32 |
| Wave 3E agent TTS | LOCAL VALIDATION DONE; device acceptance pending | PR #33 draft/open; user-confirmed unit tests and Shizuku debug build |
| Wave 3E downloads/media/sharing/session control | IMPLEMENTED IN DRAFT PRs; UNIT CI PASSED; device acceptance pending | PR #34 downloads (user-confirmed tests/build); PR #35 media, PR #36 sharing, PR #37 active sessions (unit tests passed in workflow #118); device acceptance pending |
| Wave 4 SAF/removable storage | CODE / VALIDATION PENDING | PR #23–#25 |
| Wave 5 Accessibility/UI automation | CODE COMPLETE IN DRAFT PRs; validation deferred | PR #39–#42; optional capability families PR #43–#46; Wi-Fi handoff/audit PR #47 |
| Wave 6 consolidated S21 acceptance | PRIOR-HEAD UNIT/DEBUG PASS; CURRENT-CODE CI PENDING; DEVICE MATRIX PARTIAL | Code head `fafc0c95` passed owner-run tests/debug assembly 2026-11-27; conversation seam/test commits await workflow #157; historical CI #118 covered earlier head; chunked S21 validation remains incomplete |
| Artifact generation (PDF/DOCX/XLSX/PPTX/images/ZIP) | IMPLEMENTATION COMPLETE — 10A–10I; EARLIER CI PASS | Workflow #118 passed 308 tests and debug/release builds on earlier head; instrumentation and S21 protocol acceptance remain outstanding; TERMUX_RISH binary output and on-device generation remain unavailable |
| Integration/release | NOT STARTED | Requires acceptance and explicit merge approval |

## Active PR dependency chain and branch reconciliation

All entries below are tracked as draft/open/unmerged unless explicitly stated otherwise. Draft status is intentional; it is not a release status. Stage 10 PRs are stacked after PR #48 and before Stage 8 validation. The current context-budget stack is PR #60 → #62 → #63 → #64; do not merge until each base/head dependency is validated and integration is explicitly approved.

### Sibling/overlap audit (unresolved; do not merge both branches blindly)
- **PR #38 vs PR #39:** both branch from the same PR #37 base and both add the AccessibilityService declaration/configuration. PR #39's `screen_inspect` status/settings path already declares and uses the service, and is the parent of PRs #40–#47 in the active capability stack. PR #38's standalone `accessibility_control` lifecycle tool appears superseded/overlapping and is not in the current PR #47 chain. Verify all distinct behavior/tests are accounted for before deciding to close or cherry-pick; no PR has been closed.
- **PR #58 vs PR #59:** sibling implementations of Stage 10I shell binary artifact output from the PR #57 base. The active PR #60 chain uses PR #58 (which includes instrumentation tests and conservative transport bounds). PR #59 is a separate alternative with a divergent history and different transport implementation/limits; it is not part of the active stack. Do not merge both; compare for any unique defect fixes before final integration. Neither has device-validated transport proof.
- **PR #1/#2:** historical drafts/experiments; PR #1 local MCP loopback is discontinued. PR #2's relationship to the consolidated runtime integration chain requires final dependency review; do not treat these old drafts as release-ready.

- PR #1 — optional historical local-MCP loopback build; discontinued from the critical path.
- PR #2 — unified native agent runtime / shell execution foundation.
- PR #4–#11 — capability discovery/provider/runtime integration and reliability hardening.
- PR #12–#15 — Termux:API provider, complete capability catalog, native system capabilities, owner rebalance.
- PR #16–#19 — Termux:API sensor/location/Wi-Fi, infrared, NFC, camera metadata/speech historical work.
- PR #20–#22 — native Bluetooth/USB discovery, photo/wallpaper, microphone recording.
- PR #23–#25 — SAF document tree, same-tree operations/search, cross-tree transfer/file generation.
- PR #26–#27 — Bluetooth GATT discovery and characteristic I/O.
- PR #28–#29 — contacts/clipboard and ownership/readiness audit.
- PR #30 — Gallery notifications and scheduling (**local tests/build user-confirmed**).
- PR #31 — telephony metadata and user-mediated dialer/SMS (**local tests/build user-confirmed**).
- PR #32 — strong biometric and Android Keystore (**local tests/build user-confirmed**).
- PR #33 — agent speech through Gallery TTS (**local tests and Shizuku debug build user-confirmed**).
- PR #34 — Gallery-owned download status and exact cancellation (**local tests/build user-confirmed; device acceptance pending**).
- PR #35 — media discovery, visual indexing, and Gallery-owned video playback (**unit tests passed in workflow #118; device acceptance pending**).
- PR #36 — user-mediated text and SAF file sharing through Android's ACTION_SEND chooser (**unit tests passed in workflow #118; device acceptance pending**).
- PR #37 — active cross-app media-session discovery/control through opt-in notification-listener special access (**unit tests passed in workflow #118; device acceptance pending**).
- PR #43 — on-demand active notification content read through the same manually enabled listener (**unit tests passed in workflow #118; device acceptance pending**).
- PR #44 — approved bounded call-log read using `READ_CALL_LOG`, requested only after one-time approval and subject to Android/installer eligibility (**unit tests passed in workflow #118; device acceptance pending**).
- PR #45 — approved bounded SMS inbox read using `READ_SMS`, requested only after one-time approval and subject to Android/installer/default-SMS-role eligibility (**unit tests passed in workflow #118; device acceptance pending**).
- PR #46 — read-only USB descriptor inspection using one-shot handles, per-device Android permission, and USB_DEVICE_IO approval; no arbitrary endpoint transfers or writes (unit tests passed in workflow #118; device acceptance pending).
- PR #47 — approved wifi_open_settings handoff; opens Android Wi-Fi panel or settings fallback, never toggles Wi-Fi silently (unit tests passed in workflow #118; device acceptance pending).
- PR #48 — Stage 10 artifact-generation architecture and roadmap (documentation-only; draft/open/unmerged).
- PR #49 — Stage 10A dependency/API/license preflight; documents platform-native format APIs, no-new-dependency policy, and binary streaming requirements for SAF/Termux/Shizuku/Rish (documentation-only; combined-head unit tests and debug/release builds passed in workflow #118; device acceptance pending).
- PR #50 — Stage 10B shared artifact request/result contracts, generator/destination registries, approval action/dialog, and generic tool contract; unit tests passed in workflow #118, device acceptance pending.
- PR #51 — Stage 10C native PDF generator, strict PDF payload validation, signature/MIME/size enforcement, SAF binary streaming adapter, and runtime tool registration; unit tests passed in workflow #118, device acceptance pending.
- PR #52 — Stage 10D editable DOCX OOXML writer, bounded structured payloads, styles/tables/headers/footers, and XML escaping/validation; package unit tests passed in workflow #118, external-reader acceptance pending.
- PR #53 — Stage 10E editable XLSX OOXML workbook writer, typed cells, bounded worksheets/rows/columns, styles/column widths/filters, and formula-as-text safety; package unit tests passed in workflow #118, external-reader acceptance pending.
- PR #54 — Stage 10F editable PPTX OOXML presentation with master/layout/theme, slide relationships, bounded text shapes and bullet paragraphs; package unit tests passed in workflow #118, external-reader acceptance pending.
- PR #55 — Stage 10G bounded ZIP creation for structured UTF-8 text entries, safe relative paths, duplicate/collision checks, and archive/output limits; safety unit tests passed in workflow #118.
- PR #56 — Stage 10G guarded two-pass SAF ZIP extraction with archive hashing, compressed/expanded/ratio bounds, safe relative paths, duplicate/target checks, exact approval, cancellation, and partial-tree cleanup; path-policy unit tests passed in workflow #118, provider/device acceptance pending.
- PR #57 — Stage 10H local image resize/crop/rotate/convert using bounded SAF source reads, concrete output MIME handling, optional image-generation provider contracts, and remote-consent gate with source labels; payload/consent unit tests passed in workflow #118, image/device acceptance pending.
- PR #58 — Stage 10I binary artifact destinations: Termux bounded RUN_COMMAND stdin chunks and Shizuku PFD pipe transfer; pure policy/transport unit tests passed in workflow #118; Android instrumentation and S21 protocol acceptance remain pending; TERMUX_RISH binary output remains unavailable.
- PR #60 — final Stage 10 embedded-image integration and documentation reconciliation; workflow #118 passed 308 unit tests plus Shizuku debug/release APK builds on its code head; external-reader openability, instrumentation, and S21 acceptance remain pending.
- PR #64 — latest prior code head passed owner-run unit tests/debug assembly. Subsequent commits add `AgentNativeConversation`/`LiteRtLmConversationAdapter` injection and three model-level conversation-switch/replay/recovery tests, plus Android CI branch-filter corrections. Workflow #152 is the current validation gate; its result must be recorded before advancing.
- PR #62 — privacy-safe context diagnostics; included in the current stacked head. Device diagnostics/token measurements remain pending.
- PR #63 — MCP/skill progressive disclosure; included in the current stacked head. Native token/reliability comparison and device acceptance remain pending.
- PR #64 — native capability groups, safe conversation rebuild/history projection, independent legacy dispatcher, schema splitting, and approval/permission hardening; current head `fafc0c95fe09d683fa09da39335138815ccf766c` passed owner-run unit tests and debug APK assembly on 2026-11-27. Native token savings, end-to-end conversation switching/recovery, device acceptance, and release assembly remain pending.

The PR chain is intentionally stacked. A later PR may depend on earlier draft PRs. Do not treat a branch as independently merge-ready merely because its own diff is complete.

---

# Android Capability Ownership and Implementation Plan

## Purpose

This document is the implementation authority for the Android MCP capability inventory.
The goal is complete useful device capability coverage with one selected implementation
provider per capability. Provider duplication is not a goal. Existing shell execution remains
a general-purpose execution transport and is not replicated with dozens of model-facing tools.

This is an architecture decision based on Android API boundaries and the repository design.
On-device acceptance will validate permissions, availability, reliability, latency, and resource
impact; it must not silently change ownership without updating this document.

## Provider ownership rules

1. **Native Android Kotlin** owns capabilities where Gallery needs direct app-identity
   integration, richer lifecycle/control, typed framework access, or app-owned security state.
2. **Termux:API** owns simple, bounded Android operations when its existing command provides the
   required behavior. Reuse the installed companion app and termux-api package rather than
   recreating those operations in Kotlin. Selected capabilities are listed below.
3. **Android AccessibilityService** owns semantic screen inspection and UI interaction.
4. **Android Storage Access Framework (SAF)** owns Gallery's user-granted document trees and
   removable-storage document operations. Termux SAF grants belong to the Termux app identity and
   are not interchangeable with Gallery's persisted URI grants.
5. **Unified shell router** owns general shell execution and path-based filesystem operations.
   Shizuku/Rish and Termux shell are execution transports, not competing capability owners.
6. **One capability, one owner.** Do not register duplicate native/Termux:API routes for the same
   operation. Distinct scopes (path files vs Gallery-owned SAF trees) remain separate capabilities.
7. Termux:API commands must be fixed allowlisted operations with validated arguments; never pass
   model-generated shell text. Use the existing supervised Termux RUN_COMMAND client.
8. Provider discovery is observational only. It never prompts for permissions or mutates state.
9. Mutations require explicit, scoped approval; destructive file operations require stronger
   confirmation. Cancellation, timeout, and denial fail closed regardless of provider.
10. Every operation has deterministic validation, bounded output, explicit units, and a typed
    result contract. A command's existence is not proof that it works on this Android version.
11. Do not add root requirements, arbitrary generated-code execution, or new background services
    where an existing execution path or Android API is sufficient.

## Capability ownership matrix

Status values used in this plan:
- **Existing app feature**: Gallery already has the underlying user-facing service, but it is not necessarily exposed as an agent capability.
- **Draft in PR**: code exists on an open draft PR; it is not merged or device-accepted.
- **Planned**: not yet implemented as an agent capability.
- **Locally validated**: current head passed the documented local tests/build.
- **Device accepted**: verified on the target S21 during the consolidated acceptance pass.

A catalog owner is an architecture decision, not a readiness claim. Shell commands that can perform an operation do not count as a dedicated native capability implementation.

| Capability | Single selected owner | Status / notes |
|---|---|---|
| Device/build information | Native Kotlin (Build / PackageManager) | Implemented |
| Battery status | Native Kotlin (BatteryManager / broadcasts) | Implemented; Termux:API duplicate is transitional |
| Flashlight | Native Kotlin (CameraManager) | Implemented; Termux:API duplicate is transitional |
| Audio volume read/control | Native Kotlin (AudioManager) | Implemented; Termux:API duplicate is transitional |
| Audio device / route information | Native Kotlin (AudioManager) | Implemented; bounded to 24 endpoints |
| Display brightness | Native Kotlin (Settings.System with explicit special access) | Implemented; reports stored setting/mode; mutation requires Modify system settings access and explicit approval; switches to manual mode |
| Vibration | Native Kotlin (VibratorManager) | Implemented; explicit approval; maximum one-second pulse |
| Bounded wake lock | Native Kotlin (PowerManager) | Implemented; partial CPU lock, explicit approval, maximum five-minute timeout with automatic release |
| Sensor listing and finite sensor reads | Termux:API (termux-sensor) | CODE / VALIDATION PENDING — draft handlers in PR #16 enforce listing-gated names, 1–10 samples, 100–5000 ms delay, and 8 KiB output; current-head tests/build and device acceptance pending; not production-available |
| Continuous sensor streams | Termux:API (termux-sensor) | Planned; not exposed until cancellation and sensor cleanup are verified |
| Location once/last | Termux:API (termux-location) | CODE / VALIDATION PENDING — draft handler in PR #16 uses provider allowlist, 45-second timeout, and scoped user approval; current-head tests/build and device acceptance pending; not production-available |
| Continuous location updates | Termux:API (termux-location) | Planned; not exposed until bounded-session cancellation and cleanup are verified |
| Wi-Fi connection state | Termux:API (termux-wifi-connectioninfo) | CODE / VALIDATION PENDING — draft handler in PR #16 uses a fixed no-argument command and scoped user approval; current-head tests/build and device acceptance pending; not production-available |
| Wi-Fi last-scan results | Termux:API (termux-wifi-scaninfo) | CODE / VALIDATION PENDING — draft handler in PR #16 reads only the last scan with a fixed no-argument command and scoped user approval; current-head tests/build and device acceptance pending; not production-available |
| Wi-Fi settings handoff | Native Kotlin (Settings.Panel / Settings.ACTION_WIFI_SETTINGS) | CODE / VALIDATION PENDING — PR #47: one-time WIFI_CONTROL approval; user makes any change; no silent toggle |
| Bluetooth discovery / bonded devices | Native Kotlin (BluetoothAdapter / BLUETOOTH_CONNECT) | Draft implementation in the preceding onboarding branch; current-head local validation and S21 acceptance pending |
| Bluetooth LE scan | Native Kotlin (BluetoothLeScanner / BLUETOOTH_SCAN + CONNECT) | Draft implementation in the preceding onboarding branch; separate capability ID, 1–10 second low-power scan, bounded results, cancellation-safe stop; validation pending |
| Bluetooth GATT operations | Native Kotlin (BluetoothGatt) | Draft in PRs #26–27: short-lived connection, bounded service/characteristic discovery, one-shot characteristic read/write by full UUID, 512-byte value cap, explicit approval and cancellation-safe close; local validation and device acceptance pending |

| NFC read/write | Termux:API (termux-nfc) | CODE / VALIDATION PENDING — PR #18 draft; basic NDEF read/write; foreground/tag interaction requires device verification |
| Infrared capability / transmit | Termux:API (termux-infrared-frequencies / termux-infrared-transmit) | CODE / VALIDATION PENDING — PR #17 draft; bounded carrier frequency and pulse pattern |
| USB host device discovery / permission / I/O | Native Kotlin (UsbManager) | Draft in PR #46: bounded device/interface enumeration plus standard descriptor-only inspection through 30-second one-shot handles, per-device Android permission prompt, and `USB_DEVICE_IO` approval. No arbitrary endpoint transfers or writes; removable-storage files remain on SAF in PRs #23–25; validation/device acceptance pending |
| App discovery | Native Kotlin (PackageManager launcher queries) | Implemented |
| App launch | Native Kotlin (launch intents) | Implemented |
| URL open | Native Kotlin (ACTION_VIEW) | Implemented |
| Contacts read | Native Kotlin (ContactsContract) | Draft in PR #28: scoped name/text search, READ_CONTACTS requested only after approval, maximum 20 results with at most 3 phone numbers and 3 email addresses each; local validation and device acceptance pending |
| Call-log read | Native Kotlin (CallLog provider) | Draft in PR #44: last 20 entries, caller name/number, type, timestamp, duration; one-time approval and `READ_CALL_LOG` requested only after approval; Android/installer restrictions fail closed |
| Telephony/device/network metadata | Native Kotlin (TelephonyManager) | Draft in PR #31: phone type and SIM state only; excludes subscriber IDs, phone numbers, carrier identifiers, and cell-location data; local validation pending |
| SMS inbox/list | Native Kotlin (Telephony provider where permitted) | Draft in PR #45: last 20 received SMS entries, sender/body preview/date/read state; one-time approval and `READ_SMS` requested only after approval; Android/installer/default-role restrictions fail closed |
| SMS compose/send | Native Kotlin (ACTION_SENDTO composer) | Draft in PR #31: scoped approval opens a prefilled composer; user must press Send; no SEND_SMS permission or silent send |
| Place a phone call | Native Kotlin (ACTION_DIAL) | Draft in PR #31: scoped approval opens dialer with number; user must place call; no CALL_PHONE permission |
| Clipboard read/write | Native Kotlin (ClipboardManager) | Draft in PR #28: foreground-only text reads (5 items / 8192 characters), writes capped at 2048 characters, both require scoped approval; local validation and device acceptance pending |
| Notifications created by this app | Native Kotlin (NotificationManager) | Draft in PR #30: immediate posting with scoped approval and POST_NOTIFICATIONS permission/global-enabled checks; user-confirmed unit tests and debug build passed; device acceptance pending |
| Notification listener / read other apps' notifications | Native Kotlin NotificationListenerService | CODE / VALIDATION PENDING — PR #43: explicit user-enabled special access plus one-time approval per on-demand active-notification read; no history/background collection/persistence |
| Notification channel management | Native Kotlin (NotificationManager) | Draft in PR #30: bounded channel listing and approval-gated channel creation; user-confirmed unit tests and debug build passed; channel settings remain user-controlled in Android settings |
| Camera info | Termux:API (termux-camera-info) | CODE / VALIDATION PENDING — PR #19 draft; fixed read-only metadata query |
| Camera photo capture | Native Kotlin (MediaStore + user-mediated camera intent) | Phase 6B draft; app-owned pending URI, explicit approval, publish only after successful capture |
| Microphone recording to file | Native Kotlin (MediaRecorder + MediaStore) | Phase 6C draft; 1–30 seconds, app-owned audio URI, RECORD_AUDIO requested only after scoped approval, cancellation-safe stop/release |
| Basic speech recognition | Native Kotlin (Gallery AsrServiceProvider: Android SpeechRecognizer, ML Kit, MediaPipe) | Phase 9A: existing hold-to-dictate UI already uses native ASR engines; model-initiated recording is intentionally not exposed |
| Text-to-speech / engine information | Native Kotlin (Gallery TtsServiceProvider: Android platform, ML Kit GenAI, MediaPipe) | Existing native engine selection, voice management, and chat playback; speak_text integrated through existing playback controller in PR #33 (local tests/build user-confirmed; device acceptance pending) |
| Biometric authentication | Native Kotlin (BiometricPrompt) | PR #32: foreground strong-biometric prompt via Gallery action channel; returns only success/failure; no biometric data is exposed; user-confirmed local unit tests and debug build passed; device acceptance pending |
| Secure key operations | Native Kotlin (Android Keystore) | PR #32: non-exportable AES-256-GCM keys; key use requires strong biometric authentication; user-confirmed local unit tests and debug build passed; device acceptance pending |
| Download management | Native Kotlin (Gallery DownloadRepository / WorkManager) | CODE / VALIDATION PENDING — PR #34: bounded status and exact-worker cancellation through existing Gallery queue |
| Media scan / index | Native Kotlin (MediaStore + SmartAlbumIndexingWorker / EmbeddingGemma-2) | CODE / VALIDATION DEFERRED — PR #35: reuses Gallery visual index and granted SAF trees; bounded indexing |
| Media playback initiated by the app | Native Kotlin (Gallery Media3 player) | CODE / VALIDATION DEFERRED — PR #35: fresh search handle and one-time approval; Gallery-owned playback |
| Active media-session control | Native Kotlin (MediaSessionManager) | CODE / VALIDATION DEFERRED — PR #37; user-opt-in notification-listener special access, one-time approval, bounded session handles and controls |
| Share text/files | Native Kotlin (ACTION_SEND / chooser + SAF grants) | CODE / VALIDATION DEFERRED — PR #36: exact-content approval, user-selected receiver, temporary read grant; no delivery claim |
| Wallpaper set | Native Kotlin (WallpaperManager + content URI) | Phase 6B draft; Gallery-readable image URI only, SYSTEM/LOCK/BOTH target allowlist, explicit approval |
| App-owned notification scheduling | Native Kotlin (AlarmManager + NotificationScheduleManager / DataStore) | Draft in PR #30: approved schedule/list/cancel operations reuse Gallery's existing scheduler; user-confirmed unit tests and debug build passed; WorkManager remains for deferrable jobs, not exact-time notification alarms |
| File/directory list, read, write, create, copy, move, rename, delete, search, metadata | Unified shell router (Shizuku/Rish or Termux execution identity) | General shell already supports these; no duplicate file-command tool. Mutations remain approval-gated |
| User-selected document trees / document metadata / SAF read-write-delete | Native Kotlin SAF (DocumentsContract / DocumentFile) | CODE / VALIDATION PENDING — PRs #23–#25: persisted user grants, bounded file operations and cross-tree transfer |
| Removable USB storage files | Native Kotlin SAF document-tree URI | Draft in PRs #23–25: user-granted SAF tree access and bounded file operations/transfers; validation/device acceptance pending; UsbManager is not a mass-storage filesystem |
| Artifact generation orchestration and format dispatch | Native Kotlin Artifact Generation Engine | IMPLEMENTED in draft PRs #48–#60; workflow #118 unit CI and debug/release APK builds passed; S21 acceptance pending; TERMUX_RISH binary output and on-device generation remain unavailable |
| Screen capture / visual screen understanding | Android AccessibilityService + LiteRT-LM multimodal tool result | Draft in PR #40: API 34+ window-only capture, one visible external window, explicit SCREEN_CAPTURE approval, conservative sensitive-screen gate, 1,280 px / 2 MiB bounds, in-memory Gemma image input; no display-wide fallback or persistent screenshot file |
| Live semantic UI tree | Android AccessibilityService | Draft in PRs #39–#41: bounded read-only active-window inspection plus one external-window inspection path with short-lived opaque handles; validation deferred |
| Semantic click / long-press / scroll | Android AccessibilityService | CODE / VALIDATION DEFERRED — PR #41: CLICK, LONG_CLICK, SCROLL_FORWARD/BACKWARD; coordinate gesture fallback remains on existing shell route |
| Semantic text entry / selection | Android AccessibilityService | CODE / VALIDATION DEFERRED — PR #42: safe normal single-line text fields only; empty-field entry up to 256 characters; bounded selection ranges; per-operation UI_TEXT_INPUT approval and exact target revalidation |
| Coordinate gesture fallback | Unified shell router only when explicitly requested/authorized | Existing shell capability; not a second semantic UI provider |

## Termux:API command inventory and disposition

The official Termux API package currently contains the following command families. They are
recorded here so the implementation does not overlook a capability simply because Phase 4A
started with only three commands.

| Termux:API command family | Selected owner in Android MCP |
|---|---|
| audio-info, volume | Native Kotlin AudioManager |
| battery-status | Native Kotlin BatteryManager |
| brightness | Native Kotlin display/settings adapter |
| call-log | Native Kotlin CallLog provider, subject to permission |
| camera-info | Termux:API for read-only camera metadata; native Kotlin owns photo capture through Gallery MediaStore and the system camera intent |
| clipboard-get, clipboard-set | Native Kotlin ClipboardManager |
| contact-list | Native Kotlin ContactsContract |
| dialog | Existing Agent Chat/UI interaction; not a device capability tool |
| download | Native Kotlin DownloadManager |
| fingerprint | Native Kotlin BiometricPrompt |
| infrared-frequencies, infrared-transmit | Termux:API for bounded frequency discovery/transmission |
| job-scheduler | Native Kotlin WorkManager |
| keystore | Native Kotlin Android Keystore |
| location | Termux:API for once/last/updates; native only for Gallery-owned location lifecycle |
| media-player, media-scan | Native Kotlin media APIs |
| microphone-record | Native Kotlin MediaRecorder + MediaStore for bounded Gallery-owned file recording |
| nfc | Termux:API for basic NDEF read/write; native for foreground reader-mode integration |
| notification, notification-channel, notification-list, notification-remove | Native Kotlin NotificationManager for Gallery-owned posting, active notification listing/dismissal, channels, and scheduled reminders; other-app content uses the existing manually enabled NotificationListenerService only for explicit on-demand reads with separate one-time approval |
| sensor | Termux:API for bounded reads/streams with explicit cleanup |
| share | Native Kotlin Android share intents |
| sms-inbox, sms-list, sms-send | Native Kotlin telephony/content APIs; sending remains user-mediated |
| speech-to-text | Native Gallery AsrServiceProvider; user-initiated hold-to-dictate, no Termux tool route |
| storage-get, saf-* | Native Kotlin Storage Access Framework |
| telephony-call, telephony-cellinfo, telephony-deviceinfo | Native Kotlin dialer intents / TelephonyManager |
| toast | Existing Agent Chat UI feedback; not a device capability tool |
| tts-engines, tts-speak | Native Gallery TtsServiceProvider; Termux commands are not exposed as Gallery model operations |
| usb | Native Kotlin UsbManager |
| vibrate | Native Kotlin Vibrator / VibratorManager |
| wallpaper | Native Kotlin WallpaperManager; Gallery-readable image content URI only |
| wifi-connectioninfo, wifi-scaninfo | Termux:API for connection info and last-scan results |
| wifi-enable | Native Kotlin user-mediated settings panel; do not rely on legacy direct toggle behavior |
| api-start, api-stop, api-broadcast, callback | Termux:API transport internals; not model-facing capabilities |

The command inventory is based on the official Termux API package scripts:
https://github.com/termux/termux-api-package/tree/master/scripts

This disposition deliberately mixes owners. Termux:API is selected for simple operations where its
existing fixed command is adequate and avoids maintaining a second Android implementation. Native
Kotlin remains selected where Gallery needs its own app identity, typed framework access, richer
lifecycle/session control, user-mediated flows, or security-sensitive app-owned state. The extra
Termux companion/process hop is accepted only for simpler Termux-owned capabilities. This is a
provisional selection: command availability, permission behavior, output quality, and Android
version compatibility must be verified on-device before an operation is marked implemented.

## Implementation waves

### Wave 1 — Capability vocabulary and ownership
- Expand AndroidCapability into stable, granular capability IDs.
- Add one owner and permission/availability contract for each capability.
- Separate path-based shell file operations from SAF document-tree operations.
- Register a dedicated TERMUX_API owner for selected bounded command capabilities.
- Keep Termux shell as a unified-shell execution transport, distinct from the TERMUX_API owner.
- Keep Phase 4A battery/volume/flashlight operations as explicit overlap diagnostics only; native
  remains the selected owner for those existing integrated operations.
- Add catalog completeness tests: every capability has exactly one selected owner; every owner
  declares the capability; no duplicate route priorities or accidental duplicate owners.

### Wave 2 — System and connectivity providers
- [x] Audio device information, brightness, vibration, bounded wake lock (Phase 4C first slice).
- [x] First Termux:API read-only slice: sensor listing/finite reads, location once/last, Wi-Fi
  connection information, and last-scan information (Phase 5A code; local validation/device
  acceptance pending).
- [ ] Remaining Termux:API operations: none of camera capture, file-based microphone recording,
  or wallpaper; these have been reassigned to native Kotlin in Phases 6B–6C.
- [x] Phase 5D historical draft: read-only camera metadata and one-shot Termux speech recognition.
  Phase 9A removed the Termux speech route from the model-facing tool and assigned
  SPEECH_RECOGNITION to Gallery's native AsrServiceProvider. The microphone remains user-initiated
  through the existing hold-to-dictate UI; no background or model-triggered recording is exposed.
- [x] Phase 5C draft: one-shot full NDEF reads and text-only NDEF writes through Termux:API,
  with explicit scoped approvals, 512-character payload bounds, and a 60-second supervised
  interaction timeout. Keep NFC capabilities Planned until local validation and consolidated
  device acceptance, including cancellation/UI cleanup, are complete.
- [x] Phase 5B historical draft: bounded infrared plus Termux TTS command handlers. Phase 9A
  removed the duplicate Termux TTS route from the model-facing tool and assigned TEXT_TO_SPEECH
  to Gallery's existing TtsServiceProvider (platform, ML Kit GenAI, and MediaPipe engines).
- [x] Phase 6A draft: native Bluetooth bonded-device listing and bounded BLE discovery, plus
  native USB host device/interface enumeration. Bluetooth permissions are requested only after
  scoped approval; bonded-device listing and BLE scanning have separate capability IDs and
  permission states. BLE scans stop in a cancellation-safe finally block. GATT sessions are handled in PRs #26–27; PR #46 adds read-only USB descriptor inspection with per-device permission and short-lived handles.
- [x] Add bounded native Bluetooth GATT inspection and one-shot characteristic I/O in draft PRs #26–27; local validation and S21 acceptance remain pending. [x] Add read-only USB descriptor inspection in PR #46 with per-device permission, one-shot approval, and bounded handles; arbitrary endpoint data transfers and writes remain unsupported.
  keep Wi-Fi changes user-mediated through Android Settings.
- [ ] Add per-command companion/package/permission probes without prompting.
- Bound stream duration, output, and process lifetime; sensor and microphone operations need
  explicit cancellation and cleanup semantics.
- Use generic Bluetooth and USB abstractions; no device-specific names or profiles.

### Wave 2B — Native Bluetooth GATT inspection (Phase 8A draft; acceptance pending)

- Adds `bluetooth_gatt_services` to the existing `android_control` tool. It accepts only a Bluetooth MAC address returned by the paired-device or BLE-discovery operations and a bounded 5–20 second timeout (15 seconds by default).
- Requests the existing BLUETOOTH_CONNECT runtime permission only after explicit one-time approval. The approval summary includes the exact address and connection duration.
- Opens one non-auto-connect LE GATT connection, discovers services, and returns bounded service UUIDs plus characteristic UUIDs, property flags (read/write/write-without-response/notify/indicate), and descriptor counts.
- Caps output at 32 services and 128 characteristics. No characteristic values are read, no writes or subscriptions are performed, and no pairing/bonding is initiated.
- Disconnects and closes the GATT client after success, failure, timeout, or coroutine cancellation. Real accessory interoperability and cleanup behavior remain part of the consolidated device acceptance pass.

### Wave 2C — Native Bluetooth GATT characteristic I/O (Phase 8B draft; acceptance pending)

- Extends the short-lived GATT session with one-shot characteristic reads and writes addressed by full service and characteristic UUIDs discovered from the connected device.
- Reads return binary data as Base64 with an explicit byte count; writes accept unwrapped Base64 payloads from 1–512 bytes.
- Every connection/read/write requires one-time scoped approval. Write approval displays the exact Base64 payload, target address, service UUID, characteristic UUID, and bounded session duration.
- The adapter checks the characteristic's advertised READ, WRITE, or WRITE_WITHOUT_RESPONSE property before dispatch. It uses Android 13+ typed GATT APIs where available and the compatible legacy APIs on Android 12. A write-without-response result explicitly states that remote receipt cannot be confirmed.
- Each operation has a 5–20 second overall connection/discovery/operation timeout and closes the GATT client on success, error, timeout, or cancellation. No notifications, indications, subscriptions, background sessions, automatic pairing, or retained connections are exposed.
- Real accessory behavior and write safety remain device-acceptance concerns; only devices/profiles whose UUID semantics are known should be operated on.

### Wave 3 — Gallery-owned and privacy-sensitive integrations
### Native speech ownership audit — existing Gallery services

- The Gallery chat input already routes hold-to-dictate through `AsrServiceProvider`, which can resolve Android platform SpeechRecognizer, ML Kit ASR, or MediaPipe SpeechProcessor engines. The user initiates recording through the microphone control; no background transcription or model-triggered microphone start is added.
- Gallery already provides `TtsServiceProvider` with Android platform `TextToSpeech`, ML Kit GenAI TTS, and MediaPipe TTS engines, plus persisted engine/voice/language selection and chat playback controls.
- `SPEECH_RECOGNITION` and `TEXT_TO_SPEECH` are reassigned to the native provider. Their provider status is observational; no duplicate Termux:API model-facing operations remain. A future agent-triggered TTS action should call the existing playback/service stack rather than create a second TTS engine instance.
- Termux shell commands may still exist in a user's Termux installation, but they are not selected capability owners or registered Gallery model operations.

### Wave 3A — Native contacts and clipboard (Phase 9A draft; acceptance pending)

- Adds `contacts_search` using ContactsContract's filtered search URI. The query is limited to 80 characters and results to 20 contacts; each result includes a display name, at most three phone numbers, and at most three email addresses.
- READ_CONTACTS is requested only after one-time approval that identifies the search query and result limit. Provider discovery reports permission state without prompting.
- Adds `clipboard_read` for plain-text items only, limited to five items and 8192 characters. Reads require Gallery to be foregrounded before and after approval to respect Android clipboard privacy rules.
- Adds `clipboard_write` for text up to 2048 characters. The exact text (or clear-clipboard action) is shown in the approval request, and writes also require Gallery to remain foregrounded.
- No clipboard URI, intent, image, or binary content is read or written. No background clipboard scraping or broad contact export is exposed.


- Contacts, call logs, telephony metadata, SMS composer/inbox where platform policy permits.
- Clipboard, notifications/listener, biometric prompt, Gallery-owned Keystore.
- Advanced camera/live audio sessions only if Termux commands prove insufficient.
- Downloads, media scan/playback, sharing, app-owned scheduling.
- Keep native user-mediated SMS/call/share flows; never expose silent send/call behavior.

### Wave 3B — Native notifications and scheduling (PR #30; local validation passed)

- Reuses Gallery's existing `NotificationScheduleManager`, `NotificationReceiver`, `BootReceiver`, `NotificationPendingIntentHelper`, and `scheduled_notification.proto`; does not add a competing scheduler.
- Exposes immediate app-owned notification posting, bounded listing/dismissal of active Gallery notifications, scheduled reminder creation/listing/cancellation, and bounded notification-channel listing/creation through `android_control`.
- Posting/scheduling requires exact one-time scoped approval. On Android 13+, `POST_NOTIFICATIONS` is requested only after approval; global notification disablement and blocked channels return explicit failures. Provider discovery never prompts.
- Schedule arguments are bounded to 100-character titles and 1,000-character messages, at most 50 persisted reminders, and all-or-none date components. Invalid dates and past dated reminders fail closed; the resolved local date is persisted for accurate listing and reboot recovery. One-shot alarms use `setAndAllowWhileIdle`; daily reminders are re-armed as one-shot alarms at the requested local wall-clock time after each delivery, avoiding DST drift without requesting exact-alarm special access.
- Fixes the existing scheduler's asynchronous load/save race by making DataStore the authoritative source and serializing schedule/cancel operations. Failed alarm registration rolls persisted state back; boot recovery reads through DataStore. Repeating notifications reuse a stable notification ID.
- `NOTIFICATION_READ` is implemented in draft PR #43 as an explicit on-demand snapshot of up to 20 active notifications, requiring manually enabled NotificationListenerService access and per-read approval; no history, callbacks, background monitoring, or persistence.

### Wave 3C — Native telephony metadata and user-mediated actions (PR #31; local validation passed)

- Adds read-only telephony metadata limited to the device telephony feature, phone type, and SIM state. It does not read phone numbers, subscriber identifiers, IMEI/MEID, carrier identifiers, cell IDs, or location. PR #31 unit tests and debug build passed (user-confirmed); device acceptance remains pending.
- Adds dial_phone through ACTION_DIAL. The exact destination is shown in scoped approval, then Android opens the dialer; the user must review and place the call. No CALL_PHONE permission or direct call is used.
- Adds compose_sms through ACTION_SENDTO with an smsto: URI and a bounded prefilled message. Scoped approval displays the exact recipient and message; Android opens the SMS composer and the user must press Send. No SEND_SMS permission or silent send is used.
- Phone numbers are limited to 3–20 digits, an optional leading plus, and common formatting punctuation. SMS composer text is limited to 1,600 characters. Provider discovery checks whether suitable system activities exist and never launches them.
- PR #44 adds bounded call-log reads with `READ_CALL_LOG` requested only after one-time approval; Android/installer restrictions fail closed and Gallery does not change the default dialer role. PR #45 adds bounded received-SMS inbox reads with `READ_SMS` requested only after one-time approval; Android/installer/default-SMS-role restrictions fail closed and Gallery does not change the default SMS role.


### Wave 3D — Biometric authentication and Gallery-owned Keystore (PR #32; local validation passed)

- Adds a foreground `BiometricPrompt` action handled by the existing Agent Chat Activity/action channel. Provider discovery only checks authenticator availability and never displays a prompt.
- Standalone biometric authentication returns only a success/failure result. No fingerprint, face image, template, sensor data, or biometric identifier is exposed to the model.
- Adds app-namespaced Android Keystore AES-256 keys using GCM. Key material is non-exportable; aliases are restricted to 1–32 lowercase letters, digits, underscores, and hyphens under a Gallery-owned prefix.
- Keys require strong biometric authentication for each encrypt/decrypt operation and are invalidated when biometric enrollment changes. Ciphertext is returned as Base64 IV and ciphertext; plaintext is processed in memory only and is returned to the local agent only on explicit decrypt.
- Key create/list/delete and each encrypt/decrypt operation require scoped approval. Deleting a key makes its existing ciphertext permanently unrecoverable.
- Plaintext is bounded to 4,096 characters; ciphertext input and GCM IV sizes are bounded and validated. No key material is exported and no plaintext is persisted by the adapter.
- Biometric UI behavior, hardware-backed Keystore behavior, and cancellation on the target device remain device-acceptance concerns. User-confirmed local `:app:testShizukuDebugUnitTest` and `:app:assembleShizukuDebug` both passed after the FragmentActivity host fix. PR #32 remains draft and unmerged.

### Wave 3E — Downloads, media, sharing, and Gallery TTS integration (in progress)
- Add bounded, Gallery-owned download management; reuse Android DownloadManager where it provides reliable lifecycle and status semantics, and keep any app-owned transfer implementation narrowly scoped.
- Add explicit media scanning/index lookup and app-initiated playback using Gallery's existing media stack where available; do not duplicate media infrastructure without a demonstrated gap.
- Treat active media-session control as a separate capability with explicit access-state reporting. Do not silently request NotificationListenerService special access; unsupported access remains unavailable until the user enables it.
- Add text/file sharing through Android ACTION_SEND and the system chooser. The user selects the destination and completes the share; no silent cross-app content delivery.
- Route agent-triggered speech through Gallery's existing TtsServiceProvider and playback/service lifecycle, respecting the user's persisted engine, voice, and language selection. Do not add a parallel TTS engine or Termux TTS route.
- Agent `speak_text` is implemented on `wave-3e-media-sharing-tts`: text is limited to 1,000 characters, exact text is shown in one-time scoped approval, and Agent Chat dispatches playback through its existing `TtsPlaybackViewModel` / `TtsPlaybackController`. The saved Gallery TTS engine, voice, and language selection are preserved. No parallel TTS engine or Termux route is added. Unit coverage for argument bounds and action-channel result/timeout behavior passed in workflow #118; device acceptance remains pending.
- Discovery remains observational; downloads, playback changes, and sharing use scoped approval and bounded arguments/results. Media URIs must be content URIs with ownership/grant handling, not arbitrary filesystem paths.
- Local unit tests and debug build validation will be recorded per implementation slice; consolidated S21 device acceptance remains deferred until onboarding is complete.


### Wave 4 — Files and removable storage
- SAF document-tree picker and persisted URI grants.
- Generic list/read/write/create/copy/move/rename/delete/search/metadata over granted trees.
- USB removable storage through SAF document providers.
- Keep raw path operations on the unified shell router.

### Wave 5 — Accessibility and UI automation
- User-enabled AccessibilityService.
- Live semantic UI tree with strict node/output limits.
- Screen capture, semantic gestures, text entry, and cancellation.
- Coordinate shell gestures remain an explicitly separate low-level shell action.

### Wave 6 — Provider verification and acceptance
- Build and unit-test each implementation wave.
- Then perform one consolidated device acceptance pass.
- Measure availability, permission burden, data quality, latency, reliability, and resource impact.
- Update this ownership document only when evidence justifies a change.
- Do not merge any PR without explicit user approval.

## Non-goals

- No root, bootloader changes, or Knox-sensitive modifications.
- No Ollama or llama.cpp installation in Termux.
- No duplicate model-facing tools for operations already covered by the universal shell router.
- No arbitrary generated Kotlin/Python execution or hot-loaded executable skills.
- No hard-coded support for particular Bluetooth accessories, USB devices, or phone models.
- No silent SMS sending, calling, camera/microphone capture, clipboard scraping, or notification
  access.


### Wave 2A — Termux:API first slice (implemented in code; acceptance pending)

- Prioritize read-only, bounded sensors, location `once`/`last`, Wi-Fi connection information,
  and last-scan information before NFC, infrared, camera, microphone, speech, TTS, or wallpaper.
- The upstream wrappers were inspected: `termux-sensor` supports `-l`, `-s`, `-d`, finite
  `-n`, and explicit `-c`; without `-n` it streams continuously and its signal trap attempts
  sensor cleanup. Do not default to all sensors.
- `termux-location` accepts only `gps/network/passive` providers and `once/last/updates`
  request modes. Implement `once`/`last` first; keep `updates` unavailable until bounded
  cancellation/cleanup is verified.
- `termux-wifi-connectioninfo` and `termux-wifi-scaninfo` take no arguments; the latter reads
  the last scan and does not initiate a scan.
- Each probe must be observational and distinguish companion/binary presence from actual
  permission and operation availability. Unknown permission state remains unknown; discovery
  must not prompt.
- Fixed command builders only. Test malformed arguments, denial, output bounds, timeout,
  cancellation, cleanup, and no-fallback. Keep every capability Planned until local tests/build
  and target-device acceptance are complete.
- Detailed verified wrapper contracts and upstream links: `docs/TERMUX_API_CAPABILITY_OWNERSHIP.md`.


### Wave 2B — Infrared and text-to-speech (implemented in draft code; acceptance pending)

- Verified upstream wrappers: infrared frequency discovery accepts no arguments; transmission
  requires a carrier frequency and comma-separated on/off intervals, and upstream limits the
  pattern to less than two seconds. Gallery validates the frequency against a fresh hardware
  frequency query, bounds the numeric pattern, and requires scoped approval before transmission.
- TTS engine discovery accepts no arguments. Speech uses a fixed command, safely quoted text,
  a 500-character cap, a 30-second execution timeout, and scoped approval because it produces
  audible output.
- Neither operation assumes hardware/engine availability from command presence. Keep both
  capabilities Planned until current-head local tests/build and consolidated target-device
  acceptance are complete.


### Wave 2C — NFC NDEF interactions (implemented in draft code; acceptance pending)

- Verified upstream wrapper supports `termux-nfc -r [short|full]` for reads and `-w -t <text>`
  for text NDEF writes; read and write modes are mutually exclusive.
- Gallery exposes only full reads and text-only writes, bounds write text to 512 characters,
  quotes it safely, and requires explicit one-time approval for both operations. Write approval
  describes the exact text and warns that existing tag content may be replaced.
- Operations are one-shot and supervised with a 60-second timeout. No background NFC session or
  raw binary/arbitrary record payload is exposed. Device acceptance must verify tag interaction,
  permission behavior, and that cancellation/timeouts dismiss the external NFC UI cleanly.


### Wave 2D — Camera metadata and one-shot speech recognition (historical Termux draft; speech superseded by Phase 9A)

- `termux-camera-info` is a fixed no-argument metadata query; it does not capture images.
- The Phase 5D draft briefly exposed `termux-speech-to-text` as a fixed one-shot request. Phase 9A
  removed this operation from the current Termux tool and reassigned speech recognition to the
  existing native Gallery AsrServiceProvider and user-operated hold-to-dictate UI.
- Cancellation/timeout must be checked on-device to ensure the recognition UI and microphone
  session end. File-based microphone recording and photo capture remain deferred pending a
  dedicated app-owned output/URI and lifecycle design.


### Wave 3A — Native Bluetooth and USB discovery (draft code; acceptance pending)

- Bluetooth is native because Android exposes the adapter, bonded devices, runtime Nearby devices
  permissions, BLE scanner callbacks, and scan cancellation directly. Gallery requests only
  BLUETOOTH_CONNECT/BLUETOOTH_SCAN after one-time scoped approval; discovery never prompts.
- Bonded-device output and BLE scan output are bounded to 32 devices. BLE scan duration is 1–10
  seconds, uses low-power scan mode, and always calls stopScan in a cancellation-safe finally block.
- USB discovery is native through UsbManager and reports bounded device/interface descriptors
  without serial numbers. PR #46 adds read-only standard device/configuration/interface/endpoint
  descriptor retrieval through a fresh 30-second one-shot handle, separate approval, and Android's
  per-device permission prompt. Connections close immediately and raw descriptor bytes are cleared.
- Generic USB endpoint transfers and writes remain unsupported because there is no safe, device-agnostic
  protocol contract. USB mass-storage files remain exclusively on the user-granted SAF path.


### Wave 3B — Native photo capture and wallpaper (draft code; acceptance pending)

- Photo capture is moved from Termux:API to native Kotlin because Gallery needs a Gallery-owned
  MediaStore URI. The app creates a pending JPEG row, launches the system camera through an
  ActivityResult action, and publishes the item only after successful capture; cancellation or
  failure deletes the pending row. Gallery does not request camera permission because capture is
  delegated to the user's camera activity.
- Wallpaper setting is moved from Termux:API to native WallpaperManager because the app can safely
  consume user-granted or Gallery-owned content URIs. Only image content URIs and SYSTEM/LOCK/BOTH
  targets are accepted; every change requires one-time approval. No arbitrary filesystem paths or
  network URLs are accepted.


### Wave 3C — Native bounded microphone recording (draft code; acceptance pending)

- File-based microphone recording is moved from Termux:API to native Kotlin because Gallery needs
  an app-owned MediaStore URI and deterministic start/stop/release behavior. The implementation
  records AAC in an MPEG-4 container under Music/EdgeGallery for 1–30 seconds (default 5 seconds).
- A one-time MICROPHONE_RECORD approval occurs before requesting RECORD_AUDIO. Provider discovery
  reports permission state without prompting. Recording cancellation stops/releases MediaRecorder
  under NonCancellable cleanup and deletes an unpublished MediaStore row.
- The result is a local content URI; the audio is not automatically uploaded or transcribed. Basic
  speech recognition is handled by Gallery's native user-initiated ASR flow.


## Artifact generation architecture — Stage 10 planned extension

- One Native Kotlin Artifact Generation Engine owns request validation, format dispatch, structured content models, approvals, cancellation, temporary staging, output verification, and typed results. Format-specific generators are adapters, not independent model-facing tools.
- Planned formats: PDF, DOCX, XLSX, PPTX, ZIP creation/extraction, and image processing. CSV/JSON/Markdown/plain text continue using existing text-file facilities where appropriate; office files must be genuine OOXML packages, not text files renamed with office extensions.
- PDF candidate: Android PdfDocument. DOCX/XLSX/PPTX require an Android-compatible OOXML writer/library review covering license, maintenance, memory, APK size, and feature coverage. ZIP candidate: streaming JDK ZIP APIs. No heavyweight office suite or persistent service.
- Image processing is local and bounded. Generative image creation is an optional provider interface; Gemma 4 E2B image-input support is not image-generation support. Remote generation is opt-in with explicit disclosure/approval before prompt or source-image transfer; on-device generation is optional and resource-gated.
- Destinations are Gallery-granted SAF document trees and paths accessible to the explicitly selected unified shell-router backend. SAF and shell are distinct security identities; no implicit fallback, grant sharing, or privilege inference. SAF binary output streams are not subject to the 256 KiB UTF-8 text-operation cap. The 100 MiB cross-tree transfer cap is not a universal generation cap.
- Staging is app-private, quota-bounded, and temporary. Stream where possible; clean partial files on failure/cancellation; never silently overwrite. Require scoped approval for exact destination/name and replacement. Validate actual MIME/signature/package structure, not extension alone.
- ZIP extraction must defend against path traversal, absolute paths, symlink escapes, duplicate names, excessive entry counts, expanded-size/compression-ratio bombs, low storage, and cancellation. Spreadsheet formulas are emitted only as inert workbook data; Gallery never evaluates formulas/macros/code.
- Numeric per-format limits await dependency/resource preflight; do not assume current text or cross-tree transfer limits apply to generated artifacts.

### Wave 4A — SAF document-tree core (draft code in PRs #23–25; local validation and S21 acceptance pending)

- Adds a dedicated `document_tree` tool backed by Android's Storage Access Framework. The user
  explicitly chooses a folder or attached USB drive through the system picker; Gallery persists
  only the returned tree URI grant. Tool operations require that exact persisted tree URI and use
  document IDs returned by the provider, never model-supplied filesystem paths.
- Implemented across Phases 7A–7C: grant listing, picker-based access, bounded child listing (100
  entries), metadata reads, UTF-8 text reads/writes capped at 256 KiB, one-step generated text-file
  creation, directory creation, rename, delete, same-tree copy/move, cross-tree file copy/move
  between separately granted trees (100 MiB maximum), and bounded name-only search (500 visited
  entries, depth 4, 50 results). Cross-tree move deletes the source only after a complete destination
  copy; directory transfer remains unsupported. Mutations and private reads require scoped approval.
- General metadata writes remain unsupported. USB mass-storage file access uses SAF; UsbManager
  enumeration does not grant raw filesystem access.


### Wave 4B — SAF copy, move, and bounded name search (draft code; acceptance pending)

- Copy and move operate only within the same persisted tree URI and require explicit approval.
  Provider capability flags are checked when supplied; providers that do not support the requested
  operation fail closed.
- Search matches document names only, never file contents. It is bounded to 500 visited entries,
  depth 4, and 50 results, and reports when the result set may be truncated.
- General metadata writes remain unsupported; rename remains a separate, explicitly approved
  operation. No arbitrary paths or cross-tree URI operations are accepted.

### Wave 4C — SAF cross-tree file transfer and generated text files (draft code; acceptance pending)

- Adds `create_text_file` to create and populate an allowlisted UTF-8 text document in one approved operation, capped at 256 KiB.
- Adds `copy_between_trees` and `move_between_trees` for files between two separately persisted Gallery tree grants, such as internal storage and an attached USB drive.
- Transfers stream in 32 KiB chunks, enforce a 100 MiB maximum even when provider metadata omits size, and remove incomplete destination files when cleanup succeeds. Cleanup failures and partial moves are reported explicitly.
- Cross-tree move deletes the source only after the destination stream completes. Directory transfer, arbitrary MIME types, raw paths, and arbitrary URIs remain unsupported.



**Gallery download implementation (2026-11-17; PR #34):**
- Reuses Gallery's existing `DefaultDownloadRepository`, `DownloadWorker`, and WorkManager. No Android `DownloadManager`, duplicate downloader, or parallel queue was introduced.
- Adds a bounded snapshot API for at most 20 active Gallery-tagged downloads. It returns a short-lived opaque handle, phase/state, received/total bytes, and optional rate/ETA clamped to bounded ranges. It never returns WorkManager IDs, raw tags, worker input/output data, URLs, access tokens, or filesystem paths.
- Display names resolve through the currently loaded Gallery allowlist catalog. Imported or unrecognized jobs receive the generic label “Gallery model download”.
- Adds exact cancellation by opaque handle. The repository consumes each handle once, enforces a two-minute lifetime, re-queries WorkManager, verifies the same Gallery model/extra-data/task/version tags and active state, marks an observed job as user-cancelled, and calls `cancelWorkById` for that one UUID. The agent never accepts a caller-supplied worker ID and never uses model-wide tag cancellation.
- Wires the existing Hilt-provided singleton DownloadRepository into the Agent runtime. `download_status` is read-only; `cancel_download` requires one-time approval showing the selected model label, phase, and opaque download reference.
- Adds unit tests for read-only status, handle freshness, exact approval, and denial; the combined unit suite passed in workflow #118. S21 device acceptance remains pending.
