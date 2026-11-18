# Android MCP — Master Project Roadmap and Progress Ledger

> **Roadmap authority:** This section is the end-to-end project tracker. The capability ownership
> matrix, Termux:API disposition, detailed provider contracts, and wave-specific notes below remain
> the technical source of truth. If a legacy status below conflicts with this ledger, update both;
> this ledger states the current project sequence and readiness.
>
> **Last reconciled:** 2026-11-17 (PR #35 opened; per-slice test/build execution deferred by owner instruction)  
> **Repository:** [bv9522/gallery](https://github.com/bv9522/gallery)  
> **Current implementation branch:** `wave-3e-media-library-playback` (stacked on `wave-3e-gallery-download-management`)  
> **Current change:** [PR #35 — Wave 3E media discovery, visual indexing, and Gallery playback](https://github.com/bv9522/gallery/pull/35), draft/open/unmerged, stacked on [PR #34 — safe Gallery download controls](https://github.com/bv9522/gallery/pull/34). PRs #33–#35 remain draft/open/unmerged.  
> **Overall state:** Capability onboarding is in progress. Do not begin consolidated S21 acceptance until
> Waves 1–5 implementation and the final ownership audit are complete.

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

**Owner-directed validation deferral (2026-11-17):** continue implementing capability slices and add their tests, but do not run Gradle tests, builds, or S21/device acceptance per slice. Save execution of the accumulated test suite and builds until all planned capabilities are onboarded. Previously user-confirmed test/build results remain historical evidence and must not be repeated unless requested.

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

### Stage 3 — Capability vocabulary, inventory, and ownership
- [x] Create stable granular Android capability IDs and typed provider declarations.
- [x] Create one selected owner per capability and enforce registry/catalog completeness.
- [x] Separate Termux:API capability ownership from Termux shell execution transport.
- [x] Record the official Termux:API command inventory and decide which commands are reused, replaced by native APIs, or not exposed.
- [x] Separate shell path-based filesystem operations from Gallery-owned SAF document-tree operations.
- [x] Audit speech, notification, telephony, biometric, and media ownership to prevent duplicate routes.
- [ ] Final audit across every catalog ID against implementation, Android permission, provider discovery, approval, and actual device behavior.
- [ ] Remove or explicitly label any transitional diagnostics/legacy routes that no longer belong to the selected owner.

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
- [CODE / VALIDATION PENDING] Native Bluetooth GATT service/characteristic inspection and bounded one-shot read/write (PRs #26–#27).
- [ ] Decide and implement user-mediated Wi-Fi enable/settings flow if still required; do not promise forbidden silent toggles.
- [ ] Verify every selected Termux:API command exists on the installed companion/package version, has correct permission behavior, bounded output, and cleanup.
- [ ] Device-test Bluetooth permission states, scan cancellation, GATT compatibility, NFC tag UX, infrared hardware availability, sensor/location/Wi-Fi data quality, and USB enumeration.

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
- [DEFERRED] Reading other apps' notifications: requires user-enabled NotificationListenerService special access; never silently enabled.

#### 5D — Telephony and user-mediated communication
- [x] PR #31 local unit tests and Shizuku debug build confirmed by user.
- [CODE / DEVICE ACCEPTANCE PENDING — PR #31] Non-identifying telephony metadata only (phone type/SIM state); ACTION_DIAL and ACTION_SENDTO SMS composer; user places call or presses Send.
- [DEFERRED] Call-log reads and SMS inbox reads pending separate restricted-permission/default-role/policy review. No silent calls or SMS sending.

#### 5E — Biometric authentication and secure keys
- [x] PR #32 local unit tests and Shizuku debug build confirmed by user after FragmentActivity host correction.
- [CODE / DEVICE ACCEPTANCE PENDING — PR #32] Foreground strong BiometricPrompt; boolean result only; app-namespaced non-exportable AES-256/GCM Android Keystore keys; fresh strong biometric per encrypt/decrypt; invalidation on enrollment change.
- [ ] Device-test strong biometric prompt, cancel/failure paths, hardware-backed key availability, enrollment invalidation, key deletion, and ciphertext recovery expectations.

#### 5F — Downloads, media, and sharing (remaining Wave 3E)
- [x] Gallery-owned download status/progress and exact-worker cancellation through the existing DownloadRepository/WorkManager path (PR #34); bounded opaque handles and scoped cancellation approval. User confirmed the local unit-test task and Shizuku debug build passed; device acceptance remains pending.
- [CODE / VALIDATION DEFERRED — PR #35] Bounded `media_search` across MediaStore-visible internal/removable media and persisted user-granted SAF trees. AUTO combines filename search with the existing EmbeddingGemma-2 Smart Album visual index; NAME is filename-only; VISUAL requests semantic visual search. Results contain five-minute opaque handles, not paths or content URIs.
- [CODE / VALIDATION DEFERRED — PR #35] `media_index` reuses the existing SmartAlbumIndexingWorker and EmbeddingGemma-2 vector database. It indexes permitted MediaStore photos/videos and up to 1,000 media files discovered per pass in already-granted SAF trees; indexing requires one-time approval and remains on-device. SAF discovery is bounded to depth 8 / 10,000 visited nodes.
- [CODE / VALIDATION DEFERRED — PR #35] `media_play` requires a fresh search handle and one-time approval, then opens the selected video in Gallery's own Media3 player. It does not launch an external ACTION_VIEW intent and does not expose cross-app media-session controls.
- [ ] Active media-session discovery/control remains distinct from Gallery-initiated playback; do not expose it unless the user explicitly grants any required notification-listener special access.
- [ ] User-mediated text/file sharing through ACTION_SEND/chooser; user selects target app and completes share.
- [ ] Add/maintain tests and documentation for each operation. Per-slice test/build execution is deferred until all capability onboarding is complete; consolidated S21 acceptance remains later.

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
- [ ] Define minimal AccessibilityService capability contracts and explicit user enable/disable lifecycle.
- [ ] Implement bounded screen capture subject to Android restrictions and user-visible service state.
- [ ] Implement semantic UI tree inspection with limits on node count, text, depth, and sensitive fields.
- [ ] Implement semantic tap/click/long-press/swipe using node identity and verified bounds.
- [ ] Implement semantic text entry and selection with explicit target/context validation.
- [ ] Keep coordinate gesture fallback on the existing unified shell route only when explicitly requested/authorized; do not duplicate semantic operations.
- [ ] Add per-action approval rules, cancellation, stale-node handling, foreground/app-change checks, and sensitive-screen exclusions.
- [ ] Add tests for service unavailable/disabled, permission denial, node churn, app transitions, timeouts, and bounded results.
- [ ] Device-test accessibility enablement, screen capture restrictions, multiple apps, keyboard behavior, and safe failure.

**Exit gate:** user-enabled accessibility is transparent, controllable, bounded, and demonstrably safer than generic coordinate automation.

### Stage 8 — Consolidated provider verification and S21 acceptance (Wave 6)
**This stage starts only after Stages 4–7 implementation and the final ownership audit are complete.**
- [ ] Freeze an acceptance build from the complete stacked implementation chain.
- [ ] Run full unit tests and Shizuku debug build from a clean, reproducible Windows/Android Studio JBR environment.
- [ ] Verify app install/update/signature behavior and model/runtime startup on the Galaxy S21 (Android 15 / One UI 7).
- [ ] Verify Termux, Termux:API, Shizuku Next/Rish execution identities and permissions without root.
- [ ] Verify every capability's discovery state, permission prompt timing, approval text/scope, execution, result, timeout, cancellation, and cleanup.
- [ ] Verify all read/write and destructive operations, including exact user-mediated handoffs (dialer, SMS composer, camera, share chooser, SAF picker).
- [ ] Verify Bluetooth/BLE/GATT, USB/OTG, NFC, infrared, sensors, location, Wi-Fi, camera, microphone, notifications, media, TTS, biometrics/Keystore, and AccessibilityService on actual hardware where supported.
- [ ] Verify negative paths: missing companion app, missing permission, unsupported hardware, disabled special access, denied approval, app backgrounding, process death, timeout, and cancellation.
- [ ] Measure latency, CPU/GPU behavior, RAM, storage, battery drain, thermal impact, and background process count during representative workloads.
- [ ] Check no-root/Knox integrity and ensure no unsupported privilege escalation or device-specific assumptions.
- [ ] Record per-capability results as PASS / LIMITED / UNAVAILABLE / FAIL with device/build details and evidence.
- [ ] Fix failures, update ownership/status docs, rerun affected tests, and repeat acceptance until exit criteria pass.

**Exit gate:** complete capability matrix with evidence; no unexplained failures; unsupported capabilities honestly marked; resource and security constraints met.

### Stage 9 — Integration, release, and maintenance
- [ ] Review the complete stacked PR chain for dependency order, duplicate changes, stale branches, and documentation consistency.
- [ ] Obtain explicit owner approval before merging any PR; merge in dependency order only after validation/acceptance criteria are met.
- [ ] Reconcile the final integration branch with the canonical ownership plan and provider matrix.
- [ ] Produce a reproducible signed release APK/build artifact with version/build metadata and documented install/update path.
- [ ] Perform clean install and upgrade testing on the S21; verify model download/use, settings persistence, and app data behavior.
- [ ] Publish user-facing setup, permissions, capability availability, limitations, recovery, and troubleshooting documentation.
- [ ] Establish regression checks for Android/API changes, Termux:API compatibility, Shizuku/Rish behavior, Gallery upstream changes, and model/runtime updates.
- [ ] Maintain the roadmap after each future capability or compatibility change.

**Project completion definition:** Stages 0–9 have passed their exit gates, the final app is integrated and reproducibly built,
the target S21 acceptance report is complete, and all remaining unsupported/deferred capabilities are explicitly documented.

## Current progress snapshot (2026-11-17)

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
| Wave 3C telephony/user-mediated actions | LOCAL VALIDATION DONE; device acceptance pending | User confirmed tests/build for PR #31 |
| Wave 3D biometric/Keystore | LOCAL VALIDATION DONE; device acceptance pending | User confirmed tests/build for PR #32 |
| Wave 3E agent TTS | LOCAL VALIDATION DONE; device acceptance pending | PR #33 draft/open; user-confirmed unit tests and Shizuku debug build; downloads/media/sharing remain |
| Wave 3E Gallery downloads | IMPLEMENTATION ADDED; local validation pending | PR #34 adds bounded repository snapshots, allowlist-backed labels, opaque handles, exact cancellation, agent operations, approval, and unit tests; no Gradle/device validation claimed |
| Wave 4 SAF/removable storage | CODE / VALIDATION PENDING | PR #23–#25 |
| Wave 5 Accessibility/UI automation | PLANNED | No implementation PR in current chain |
| Wave 6 consolidated S21 acceptance | NOT STARTED — intentionally gated | Begin only after onboarding and ownership audit |
| Integration/release | NOT STARTED | Requires acceptance and explicit merge approval |

## Active PR dependency chain

All entries below were observed open and unmerged on 2026-11-17. Draft status is intentional; it is not a release status.

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
- PR #34 — Gallery-owned download status and exact cancellation (**Kotlin implementation and unit tests added; local validation pending**).

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
| Sensor listing and finite sensor reads | Termux:API (termux-sensor) | Planned; draft handlers in PR #16 enforce listing-gated names, 1–10 samples, 100–5000 ms delay, and 8 KiB output; current-head tests/build and device acceptance pending; not production-available |
| Continuous sensor streams | Termux:API (termux-sensor) | Planned; not exposed until cancellation and sensor cleanup are verified |
| Location once/last | Termux:API (termux-location) | Planned; draft handler in PR #16 uses provider allowlist, 45-second timeout, and scoped user approval; current-head tests/build and device acceptance pending; not production-available |
| Continuous location updates | Termux:API (termux-location) | Planned; not exposed until bounded-session cancellation and cleanup are verified |
| Wi-Fi connection state | Termux:API (termux-wifi-connectioninfo) | Planned; draft handler in PR #16 uses a fixed no-argument command and scoped user approval; current-head tests/build and device acceptance pending; not production-available |
| Wi-Fi last-scan results | Termux:API (termux-wifi-scaninfo) | Planned; draft handler in PR #16 reads only the last scan with a fixed no-argument command and scoped user approval; current-head tests/build and device acceptance pending; not production-available |
| Wi-Fi enable/disable | Native Kotlin user-mediated settings panel | Planned; no silent toggle where Android forbids it |
| Bluetooth discovery / bonded devices | Native Kotlin (BluetoothAdapter / BLUETOOTH_CONNECT) | Draft implementation in the preceding onboarding branch; current-head local validation and S21 acceptance pending |
| Bluetooth LE scan | Native Kotlin (BluetoothLeScanner / BLUETOOTH_SCAN + CONNECT) | Draft implementation in the preceding onboarding branch; separate capability ID, 1–10 second low-power scan, bounded results, cancellation-safe stop; validation pending |
| Bluetooth GATT operations | Native Kotlin (BluetoothGatt) | Draft in PRs #26–27: short-lived connection, bounded service/characteristic discovery, one-shot characteristic read/write by full UUID, 512-byte value cap, explicit approval and cancellation-safe close; local validation and device acceptance pending |

| NFC read/write | Termux:API (termux-nfc) | Planned; basic NDEF read/write; foreground/tag interaction requires device verification |
| Infrared capability / transmit | Termux:API (termux-infrared-frequencies / termux-infrared-transmit) | Planned; bounded carrier frequency and pulse pattern |
| USB host device discovery / permission / I/O | Native Kotlin (UsbManager) | USB device/interface enumeration is draft code in the preceding onboarding branch; raw USB device I/O is not exposed. Removable-storage file operations are separately implemented through SAF in PRs #23–25; validation and device acceptance pending |
| App discovery | Native Kotlin (PackageManager launcher queries) | Implemented |
| App launch | Native Kotlin (launch intents) | Implemented |
| URL open | Native Kotlin (ACTION_VIEW) | Implemented |
| Contacts read | Native Kotlin (ContactsContract) | Draft in PR #28: scoped name/text search, READ_CONTACTS requested only after approval, maximum 20 results with at most 3 phone numbers and 3 email addresses each; local validation and device acceptance pending |
| Call-log read | Native Kotlin (CallLog provider) | Planned; restricted permission and privacy review |
| Telephony/device/network metadata | Native Kotlin (TelephonyManager) | Draft in PR #31: phone type and SIM state only; excludes subscriber IDs, phone numbers, carrier identifiers, and cell-location data; local validation pending |
| SMS inbox/list | Native Kotlin (Telephony provider where permitted) | Planned; not implemented; Android restricts READ_SMS to qualifying/default SMS roles |
| SMS compose/send | Native Kotlin (ACTION_SENDTO composer) | Draft in PR #31: scoped approval opens a prefilled composer; user must press Send; no SEND_SMS permission or silent send |
| Place a phone call | Native Kotlin (ACTION_DIAL) | Draft in PR #31: scoped approval opens dialer with number; user must place call; no CALL_PHONE permission |
| Clipboard read/write | Native Kotlin (ClipboardManager) | Draft in PR #28: foreground-only text reads (5 items / 8192 characters), writes capped at 2048 characters, both require scoped approval; local validation and device acceptance pending |
| Notifications created by this app | Native Kotlin (NotificationManager) | Draft in PR #30: immediate posting with scoped approval and POST_NOTIFICATIONS permission/global-enabled checks; user-confirmed unit tests and debug build passed; device acceptance pending |
| Notification listener / read other apps' notifications | Native Kotlin NotificationListenerService | Planned; explicit user-enabled special access |
| Notification channel management | Native Kotlin (NotificationManager) | Draft in PR #30: bounded channel listing and approval-gated channel creation; user-confirmed unit tests and debug build passed; channel settings remain user-controlled in Android settings |
| Camera info | Termux:API (termux-camera-info) | Phase 5D draft; fixed read-only metadata query |
| Camera photo capture | Native Kotlin (MediaStore + user-mediated camera intent) | Phase 6B draft; app-owned pending URI, explicit approval, publish only after successful capture |
| Microphone recording to file | Native Kotlin (MediaRecorder + MediaStore) | Phase 6C draft; 1–30 seconds, app-owned audio URI, RECORD_AUDIO requested only after scoped approval, cancellation-safe stop/release |
| Basic speech recognition | Native Kotlin (Gallery AsrServiceProvider: Android SpeechRecognizer, ML Kit, MediaPipe) | Phase 9A: existing hold-to-dictate UI already uses native ASR engines; model-initiated recording is intentionally not exposed |
| Text-to-speech / engine information | Native Kotlin (Gallery TtsServiceProvider: Android platform, ML Kit GenAI, MediaPipe) | Existing native engine selection, voice management, and chat playback; model-triggered speech action remains a separate integration task |
| Biometric authentication | Native Kotlin (BiometricPrompt) | PR #32: foreground strong-biometric prompt via Gallery action channel; returns only success/failure; no biometric data is exposed; user-confirmed local unit tests and debug build passed; device acceptance pending |
| Secure key operations | Native Kotlin (Android Keystore) | PR #32: non-exportable AES-256-GCM keys; key use requires strong biometric authentication; user-confirmed local unit tests and debug build passed; device acceptance pending |
| Download management | Native Kotlin (DownloadManager or app-owned downloader) | Planned |
| Media scan / index | Native Kotlin (MediaScannerConnection / MediaStore) | Planned |
| Media playback initiated by the app | Native Kotlin (existing media stack or platform MediaPlayer) | Planned |
| Active media-session control | Native Kotlin (MediaSessionManager) | Planned; notification-listener access may be required |
| Share text/files | Native Kotlin (ACTION_SEND / chooser) | Planned; user-mediated |
| Wallpaper set | Native Kotlin (WallpaperManager + content URI) | Phase 6B draft; Gallery-readable image URI only, SYSTEM/LOCK/BOTH target allowlist, explicit approval |
| App-owned notification scheduling | Native Kotlin (AlarmManager + NotificationScheduleManager / DataStore) | Draft in PR #30: approved schedule/list/cancel operations reuse Gallery's existing scheduler; user-confirmed unit tests and debug build passed; WorkManager remains for deferrable jobs, not exact-time notification alarms |
| File/directory list, read, write, create, copy, move, rename, delete, search, metadata | Unified shell router (Shizuku/Rish or Termux execution identity) | General shell already supports these; no duplicate file-command tool. Mutations remain approval-gated |
| User-selected document trees / document metadata / SAF read-write-delete | Native Kotlin SAF (DocumentsContract / DocumentFile) | Planned; separate URI-grant capability scope |
| Removable USB storage files | Native Kotlin SAF document-tree URI | Planned; do not treat UsbManager as a mass-storage filesystem |
| Screen capture | Android AccessibilityService | Planned; user-enabled service and Android screenshot restrictions |
| Live semantic UI tree | Android AccessibilityService | Planned |
| Semantic tap / click / long-press / swipe | Android AccessibilityService | Planned |
| Semantic text entry / selection | Android AccessibilityService | Planned |
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
| notification, notification-channel, notification-list, notification-remove | Native Kotlin NotificationManager for Gallery-owned posting, active notification listing/dismissal, channels, and scheduled reminders; reading other apps' notifications requires a separate future NotificationListenerService |
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
  permission states. BLE scans stop in a cancellation-safe finally block. GATT sessions and USB
  I/O remain deferred to dedicated lifecycle/permission phases.
- [x] Add bounded native Bluetooth GATT inspection and one-shot characteristic I/O in draft PRs #26–27; local validation and S21 acceptance remain pending. [ ] Implement native USB host permission/open/read/write sessions;
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
- `NOTIFICATION_READ` remains unavailable. Reading notifications from other apps requires a separate, explicitly user-enabled NotificationListenerService and is not included in this phase.

### Wave 3C — Native telephony metadata and user-mediated actions (PR #31; local validation passed)

- Adds read-only telephony metadata limited to the device telephony feature, phone type, and SIM state. It does not read phone numbers, subscriber identifiers, IMEI/MEID, carrier identifiers, cell IDs, or location. PR #31 unit tests and debug build passed (user-confirmed); device acceptance remains pending.
- Adds dial_phone through ACTION_DIAL. The exact destination is shown in scoped approval, then Android opens the dialer; the user must review and place the call. No CALL_PHONE permission or direct call is used.
- Adds compose_sms through ACTION_SENDTO with an smsto: URI and a bounded prefilled message. Scoped approval displays the exact recipient and message; Android opens the SMS composer and the user must press Send. No SEND_SMS permission or silent send is used.
- Phone numbers are limited to 3–20 digits, an optional leading plus, and common formatting punctuation. SMS composer text is limited to 1,600 characters. Provider discovery checks whether suitable system activities exist and never launches them.
- Call-log reads and SMS inbox reads remain Planned pending separate permission, default-role, and policy review. No READ_CALL_LOG or READ_SMS permission is declared by this slice.


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
- Agent `speak_text` is implemented on `wave-3e-media-sharing-tts`: text is limited to 1,000 characters, exact text is shown in one-time scoped approval, and Agent Chat dispatches playback through its existing `TtsPlaybackViewModel` / `TtsPlaybackController`. The saved Gallery TTS engine, voice, and language selection are preserved. No parallel TTS engine or Termux route is added. Unit coverage includes argument bounds and action-channel result/timeout behavior; local Gradle validation is pending.
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
  without serial numbers or opening devices. USB permission requests and actual I/O sessions are
  deliberately deferred; enumeration is not treated as USB mass-storage filesystem access.
- GATT sessions and USB I/O require separate lifecycle, user permission, cancellation, and typed
  transfer contracts before being exposed.


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
- Adds unit tests for read-only status, handle freshness, exact approval, and denial. Local Gradle validation and S21 device acceptance remain pending.
