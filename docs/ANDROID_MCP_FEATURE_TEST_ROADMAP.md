# Android MCP — Feature-by-Feature Test Roadmap

**Status:** Chunked validation is in progress on the Galaxy S21. Chunk 00 is active; no later chunks have started. Preserve the existing 12-chunk sequence and do not advance until the current chunk's exit criteria are evidenced.  
**Owner workflow:** Review and execute one chunk at a time with Brian. Do not run the entire device matrix in one session.  
**Implementation baseline:** Stage 10A–10I onboarding is implemented on the current stacked branch. GitHub Actions workflow #130 passed unit tests, Shizuku debug build, signing-certificate verification, release build, and artifact upload on PR #60 head `e839f0a6ed612820ee44d550a1606e81de828604`. Workflow #118 remains historical CI evidence (308 unit tests). CI is not proof of device acceptance or exhaustive feature coverage.  
**Target:** unrooted Samsung Galaxy S21 SM-G991U, Android 15 / One UI 7, package `com.google.aiedge.gallery.shizuku`. Keep Knox intact.  
**Related authority:** [Master project roadmap and progress ledger](ANDROID_CAPABILITY_OWNERSHIP_AND_IMPLEMENTATION_PLAN.md), [provider matrix](ANDROID_CAPABILITY_PROVIDER_MATRIX.md).

## 1. How this suite is executed

### Test layers

- **Unit/policy:** pure Kotlin argument parsing, bounds, registries, ownership, approval decisions, format validation, and deterministic fake-provider behavior.
- **Android instrumentation:** framework interactions that cannot be meaningfully verified with local JVM Android stubs (SAF, Bitmap/Exif, ContentResolver, MediaStore, Activity/Service lifecycle, PFD, RUN_COMMAND).
- **Device acceptance:** actual Android permissions, special access, companion apps, hardware, user-facing approval, model/tool loop, file openability, and resource behavior on the S21.
- **Regression:** rerun the smallest affected unit/instrumentation group after a fix, then the full CI gate at the agreed checkpoint.

### Rules

1. Work one numbered chunk at a time. Review its scope and prerequisites before running it.
2. Do not wipe app data, remove Gemma models, uninstall the existing app, or reset Termux/Shizuku as a routine precondition.
3. Record exact app version, commit SHA, Android build, provider/companion versions, test case ID, expected result, actual result, and evidence.
4. Classify each case as **PASS**, **FAIL**, **LIMITED**, **UNAVAILABLE**, **BLOCKED**, or **NOT APPLICABLE**. Explain why for every non-PASS.
5. A missing hardware feature or Android eligibility restriction is not automatically a product defect; it must return an honest, bounded unavailable/permission-required result.
6. Discovery must not trigger permission dialogs. Sensitive reads and mutations must obtain the exact scoped approval before requesting Android permission or performing the operation.
7. Denial, dismissal, timeout, cancellation, stale/replayed handles, and provider errors must fail closed. No silent provider/backend fallback.
8. Do not use model narration as raw evidence. Inspect actual tool results, files, command output, Android UI, and logs.
9. Device tests that require a second accessory (USB drive, NFC tag, BLE peripheral, IR hardware, etc.) are conditional. Mark them UNAVAILABLE with the missing hardware noted rather than inventing a pass.
10. Do not mark a chunk complete until its exit criteria and evidence are recorded in the master roadmap.

### Shared result record

For every executed case record:

| Field | Required value |
|---|---|
| Case ID / chunk | Stable ID below |
| Build | Version name/code and commit SHA |
| Environment | S21 model, Android/API, One UI, relevant companion versions |
| Preconditions | Permissions, special access, hardware, network, model |
| Action | Exact user prompt or operation and inputs |
| Expected | Observable success or fail-closed behavior |
| Actual | Exact result, including error/state |
| Evidence | Screenshot with private content redacted, raw output, file hash/openability, or log excerpt |
| Status | PASS / FAIL / LIMITED / UNAVAILABLE / BLOCKED / N/A |
| Follow-up | Defect, regression test, or explicit limitation |

---

## 2. Execution chunks

## Chunk 00 — Build, install, lifecycle, and test-harness baseline

**Goal:** Prove the tested APK is the intended build and can be installed/launched without damaging existing user data.

- **T00-01 — Artifact provenance:** verify workflow run, commit SHA, debug APK artifact, package ID, version name/code, APK SHA-256, and signing certificate. Confirm debug and release APKs are distinct expected outputs.
- **T00-02 — Safe update/install:** attempt update with the expected signing identity. If Android reports signature incompatibility, stop; do not uninstall or clear data as a workaround.
- **T00-03 — Launch smoke:** cold launch, foreground/background, rotate if supported, return from system picker/settings, and relaunch. No crash, blank screen, or stuck loading overlay.
- **T00-04 — Model lifecycle:** verify Gemma 4 E2B remains installed, selects and loads, and returns to chat without a white screen or stale spinner. Verify manual unload/reload only if the app exposes a user-facing unload control; otherwise record that subtest as NOT APPLICABLE.
- **T00-05 — Agent Skills lifecycle:** open/close Agent Skills, select the model, navigate back, reopen chat, and verify content remains rendered.
- **T00-06 — Persistence:** verify app settings and persisted SAF grants survive ordinary process death/relaunch; do not infer persistence after uninstall.
- **T00-07 — Permission observation:** open provider status/discovery for every capability family and verify no Android runtime permission or special-access prompt appears merely from discovery.
- **T00-08 — Baseline footprint:** record free storage, installed APK size, model storage, idle RAM/process state, battery percentage/temperature, and initial thermal state.

**Execution method:** Use PowerShell/ADB for artifact/package/signature/install state, process lifecycle, permissions, persisted grants, storage, battery/thermal, model-file inventory, and logs wherever the platform exposes the evidence. Manual S21 interaction is still required for visual rendering, actual navigation, model selection/loading, Agent Skills rendering, and provider-discovery screens. Test unloading only if the app exposes a user-facing unload control; otherwise record the unload/reload subtest as NOT APPLICABLE rather than deleting model files or forcing process termination. ADB evidence does not substitute for visual acceptance; manual observations do not replace machine-readable state.

**Live execution record (updated 2026-11-21):**

**Tested environment:** Samsung Galaxy S21 SM-G991U; Android 15/API 35; One UI 7; package `com.google.aiedge.gallery.shizuku`; installed versionName `1.0.23`, versionCode `52`. The app was updated in place; no uninstall, app-data clear, model deletion, or Termux/Shizuku reset was performed.

| Case | Current status | Evidence / remaining work |
|---|---|---|
| T00-01 | PASS | GitHub Actions workflow #130 succeeded (run ID `44445805976`; artifact ID `14495336561`) on PR #60 head `e839f0a6ed612820ee44d550a1606e81de828604`. Job log confirms `assembleShizukuDebug` and `assembleShizukuRelease` completed and the upload step included both `Android/src/app/build/outputs/apk/shizuku/debug/*.apk` and `.../release/*.apk`; GitHub reported exactly 2 files uploaded. Artifact ZIP: 145,780,692 bytes; SHA-256 `2a0062f6d9e197cc9aaec277c0da234f91391fbe53da0a99e502085e3350241d`. Downloaded debug APK: `C:\Users\bvell\Downloads\app-shizuku-debug.apk`; SHA-256 `AD25C82A6F228F7C6EDE8C22B72444CD0DC1F58A455DB524B23738D5471C0C72`. Package/version are correct: `com.google.aiedge.gallery.shizuku`, `1.0.23`/52. Downloaded APK and installed base APK signing-certificate SHA-256 match: `ad74215cfa10f3c8d9d99bca96512d52eb26abb83a379c7e479e3af26d0853e4` (SHA-1 `3fd97253dd8664b718d1607d9d6d962bea830f6e`). Debug/release output paths and the two-file artifact are confirmed; no reinstall is needed. |
| T00-02 | PASS | `adb install -r` succeeded with the expected signing identity. `firstInstallTime=2026-11-05 21:12:13` remained unchanged; `lastUpdateTime=2026-11-18 22:22:58`. Existing app data was preserved; no uninstall or data clear. |
| T00-03 | PASS | ADB launch created MainActivity and the app rendered normally; no fatal exception/fatal signal was found in the captured launch logs. Brian confirmed prompt/smooth navigation and passed background/foreground via Recents, return from Settings, rotation recovery, close, and relaunch from the app icon. |
| T00-04 | PASS with N/A subtest | Gemma 4 E2B-it is installed (model directory under `/sdcard/Android/data/com.google.aiedge.gallery.shizuku/files/Gemma_4_E2B_it/`; approximately 3.2 GB) and Brian confirmed successful selection/initialization, including after app restart. The app UI exposes no manual unload-from-memory control. Therefore manual unload/reload is NOT APPLICABLE to the current UI, not a failure; no model files were deleted. |
| T00-05 | PASS | Brian opened Agent Skills with Gemma loaded and confirmed normal rendering. He re-entered Agent Skills, confirmed it behaved normally, then navigated back to Agent Chat without a blank screen or freeze. |
| T00-06 | PARTIAL — theme PASS; SAF grant N/A | Theme was changed Auto→Light, the app was closed/reopened, and Light persisted; Brian restored Auto and confirmed it. Persisted-URI inspection found no existing SAF grant owned by app UID 10447 and no URI/persisted/content-URI references in the package dump. There is therefore no existing app grant with which to test persistence across process death. Mark that subtest N/A pending a legitimate SAF workflow that creates a grant; do not manufacture an unrelated grant. |
| T00-07 | PARTIAL | Package permission audit is complete. Core Shizuku API (`moe.shizuku.manager.permission.API_V23`) and Termux RUN_COMMAND (`com.termux.permission.RUN_COMMAND`) are granted. Latest audit also showed grants for notifications, microphone, SMS, calendar, call log, contacts, accounts, camera, media images/video/visual-selected, media location, and Bluetooth connect/scan; `READ_EXTERNAL_STORAGE` remained false. No permission audit needs repeating. Remaining: manually inspect provider status/discovery screens for the capability families and confirm discovery alone does not trigger runtime or special-access prompts. |
| T00-08 | PARTIAL — measurements captured; evidence consolidation remains | Captured baseline: `/data` 105 GiB total, 55 GiB used, 50 GiB available (53%); installed base APK 198,976,637 bytes (189.76 MiB); Gemma model storage approximately 3.2 GB. With app process running, `dumpsys meminfo` reported Native Heap 45,332 KB, total PSS 237,053 KB (~231.5 MiB), total RSS 329,204 KB (~321.5 MiB), swap PSS 134 KB. Thermal status 0; AP 27.5°C, battery 26.8°C, USB 26.8°C, skin 27.8°C, path 28.0°C. Battery snapshot: 58%, 3936 mV, 27.6°C, discharging. App PID 18738 was observed during capture. No fatal exception/ANR match was found in the inspected last 3,000 logcat lines. Consolidate these captured values into the evidence record; do not recapture solely to replace earlier snapshots. |

**Execution notes:** The initial T00-08 snapshot had no running app process, so its blank `pidof`/`dumpsys meminfo` output was not a crash; a later capture with the app running supplied the RAM/process measurements above. No user data or model assets were removed during Chunk 00.



**Exit:** correct artifact and package are confirmed; app launches; model/Agent Skills UI is usable; no destructive setup action was needed.

## Chunk 01 — Unified shell execution and backend identity

**Goal:** Verify the universal shell route remains reliable and never changes execution identity silently.

- **T01-01 — TERMUX identity:** run a harmless identity command and verify backend metadata and UID correspond to the Termux app-shell identity.
- **T01-02 — SHIZUKU identity:** run the same command and verify shell UID/backend metadata through Shizuku UserService.
- **T01-03 — TERMUX_RISH identity:** verify Rish succeeds only when its manager/application identity and bridge are available; missing manager, timeout, or unavailable bridge returns an explicit failure.
- **T01-04 — Backend isolation:** force one backend unavailable and confirm the request does not silently execute through another backend.
- **T01-05 — Argument/quoting safety:** exercise spaces, quotes, Unicode, shell metacharacters, empty arguments, and rejected malformed requests; confirm arguments cannot escape their intended boundary.
- **T01-06 — Output contract:** verify stdout/stderr separation, exit code, truncation marker, maximum output, and stable result formatting.
- **T01-07 — Timeout:** execute a bounded long-running harmless command; verify deadline, termination, and timeout result.
- **T01-08 — Cancellation:** cancel during execution; verify prompt cancellation, process termination, and no false success.
- **T01-09 — Process cleanup:** repeat success/failure/cancellation and verify no orphan processes or retained descriptors.- **T01-10 — Termux RUN_COMMAND prerequisites:** revoke/deny RUN_COMMAND permission or disable external apps in a controlled test; verify clear unavailable/permission failure and recovery after restoring setup.
- **T01-11 — Shizuku lifecycle:** stop/revoke Shizuku service during a request; verify binder death is surfaced and a later fresh request can recover.
- **T01-12 — Rish setup errors:** test unset application ID, wrong manager identity, timeout, and unavailable manager without a fallback.

**Note:** TERMUX_RISH remains a supported shell execution transport for ordinary commands where validated; its binary artifact-output path is intentionally rejected and tested separately in Chunk 10.
## Chunk 02 — Gemma, AgentCoordinator, tool calling, approvals, and result integrity

**Goal:** Prove the model invokes the real tool runtime rather than merely printing tool-call-looking text.

- **T02-01 — Natural-language dispatch:** ask Gemma 4 E2B for a harmless read-only device capability; verify structured tool call, registry dispatch, actual provider result, and final model response.
- **T02-02 — Literal/malformed tool syntax:** exercise malformed JSON, literal JSON in assistant text, incomplete tool-call markers, and recoverable protocol output; ensure no accidental execution.
- **T02-03 — Argument rejection:** unknown tool, unknown argument, wrong type, missing required field, oversized values, and invalid enum all fail before provider invocation.
- **T02-04 — Multiple calls:** request two independent harmless operations; verify intended ordering, distinct results, and no cross-contamination.
- **T02-05 — Duplicate suppression:** simulate repeated/duplicated tool-call IDs and verify a mutation is not dispatched twice.
- **T02-06 — Authoritative results:** verify model narration cannot rewrite a provider failure into success or fabricate a value absent from the tool result.
- **T02-07 — Approval exactness:** inspect approval title/summary for exact capability, target, value, destination, and consequence. Approve once and verify only that operation proceeds.
- **T02-08 — Denial/dismissal:** deny and dismiss; verify provider is never invoked and no permission prompt appears afterward.
- **T02-09 — Approval timeout/channel closure:** close chat or remove the action collector during approval; verify fail-closed behavior.
- **T02-10 — Cancellation propagation:** cancel while awaiting approval and while provider is running; verify no late mutation or duplicate continuation.
- **T02-11 — Result/context budgets:** return large bounded provider output and verify truncation/budget handling preserves authoritative fields and does not exhaust model context.- **T02-12 — Tool-loop ceiling:** provoke repeated tool requests; verify loop limits, cancellation, and clear termination.
- **T02-13 — Lifecycle recovery:** background/foreground chat during model generation, approval, and tool result delivery; verify no lost approval or stale response.
- **T02-14 — Multimodal screenshot result:** approved screenshot bytes reach Gemma as a multimodal tool result; verify correct image dimensions/encoding, bounded payload, no path/URI leakage, and byte/reference cleanup after the turn.

**Existing seams to extend/review:** `AgentCoordinatorTest`, `AgentToolResultBudgetTest`, `LiteRtLmResponseProtocolTest`, and screenshot/accessibility policy tests.

## Chunk 03 — System controls and Termux:API capabilities

**Goal:** Verify selected provider ownership, read-only discovery, bounded operations, and permission/approval ordering.

- **T03-01 — Device/build info:** manufacturer/model/Android/API/security patch fields are accurate and omit identifiers not in the contract.
- **T03-02 — Battery:** percentage, charging/plugged state, temperature, and voltage units are plausible and bounded; unavailable fields are omitted/typed unavailable.
- **T03-03 — Volume:** read each supported stream; set a valid value only after approval; reject out-of-range values; denial leaves volume unchanged.
- **T03-04 — Flashlight:** availability discovery does not prompt; permission is requested only after approval; verify on/off, missing camera permission, no flash hardware, and camera-in-use failure.
- **T03-05 — Brightness:** read current setting/mode; verify special access required only after approval; confirm mode side effect is disclosed; denial/revocation fails closed.
- **T03-06 — Vibration:** verify approval, maximum one-second pulse, unsupported vibrator state, cancellation, and no persistent vibration.
- **T03-07 — Wake lock:** verify approval, bounded duration (maximum five minutes), automatic release on timeout/cancellation/process lifecycle, and no indefinite hold.
- **T03-08 — App discovery/launch:** only launcher-visible apps are listed; launch accepts only a listed package and requires approval; invalid package and missing app fail safely.
- **T03-09 — URL open:** only absolute HTTP(S) URLs with host accepted; mutation requires approval; invalid schemes/host rejected.
- **T03-10 — Termux:API status:** companion app and each command binary are reported separately; status does not assume permission/hardware availability.
- **T03-11 — Sensors:** list available sensors; finite reads enforce 1–10 samples, 100–5000 ms delay, and 8 KiB output; reject unknown sensor names and continuous streaming.
- **T03-12 — Location:** once/last only; approval precedes location access; test granted, denied, provider disabled, timeout, stale/unavailable fix, and bounded output.
- **T03-13 — Wi-Fi information:** connection and last-scan reads require scoped approval; test no Wi-Fi, disabled Wi-Fi, empty/stale scan, and redaction/bounds.
- **T03-14 — Infrared:** frequency discovery is read-only; transmit validates frequency/pattern and requires approval; no IR hardware returns unavailable; cancellation stops operation.
- **T03-15 — NFC:** full NDEF read and text-only write; exact text/replacement warning in approval; test no tag, incompatible tag, existing content, denial, cancellation, and UI dismissal.
- **T03-16 — Camera metadata:** fixed metadata query only; no image capture or camera stream; test no camera and multiple camera IDs.
- **T03-17 — Photo capture:** explicit approval before launching the user-mediated camera activity; verify Gallery-owned pending MediaStore URI is published only after successful capture and deleted on cancel/failure; no unnecessary CAMERA permission prompt.
- **T03-18 — Wallpaper:** accept only Gallery-readable image content URIs and SYSTEM/LOCK/BOTH targets; exact one-time approval; reject arbitrary paths, URLs, unsupported MIME, revoked grants, and denial without changing wallpaper.
- **T03-19 — Microphone recording:** approval before RECORD_AUDIO permission; enforce 1–30 seconds; verify AAC/MPEG-4 MediaStore output, stop/release on cancel/background, delete unpublished row on failure, and no automatic upload/transcription or background recording.
- **T03-20 — Audio route discovery:** list at most 24 input/output endpoints with bounded metadata; test wired/Bluetooth route changes, disconnect, no devices, and no sensitive identifiers.
- **T03-21 — Ownership/no duplicate route:** verify each catalog capability resolves to exactly one provider and legacy Termux battery/volume/torch probes remain diagnostic-only.

**Existing seams:** `TermuxApiCapabilityToolTest`, `TermuxApiReadCommandTest`, `AndroidNativeCapabilityToolTest`, `AndroidControlArgumentsTest`, `CapabilityRegistryTest`, `BatteryPercentResolverTest`.

## Chunk 04 — Bluetooth, Wi-Fi handoff, and USB

**Goal:** Verify connectivity inspection and explicitly bounded I/O without hidden hardware access.

- **T04-01 — Bluetooth bonded devices:** permission-required state is observable without prompting; approval then permission; bounded list; empty list and Bluetooth-off states.
- **T04-02 — BLE scan:** scan duration stays within 1–10 seconds and low-power mode; result count is bounded; cancellation always stops scan; test permission denial and Bluetooth disabled.
- **T04-03 — GATT discovery:** connect to a known test peripheral; list bounded services/characteristics; handle unsupported/duplicate UUIDs, connection timeout, and disconnect.
- **T04-04 — GATT read:** exact UUID and one-shot approval; verify value length and type; test unreadable characteristic, stale connection, timeout, and disconnect cleanup.
- **T04-05 — GATT write:** exact payload and target shown in approval; 512-byte cap; denial causes no write; unsupported write properties rejected; verify post-write value only where peripheral supports it.
- **T04-06 — Wi-Fi settings handoff:** approval precedes opening Settings Panel; fallback to Wi-Fi settings if panel unavailable; Gallery does not toggle Wi-Fi; user cancellation returns cleanly.
- **T04-07 — USB enumeration:** connect a supported USB host device; enumerate bounded device/configuration/interface/endpoint descriptors; no serial number leakage.
- **T04-08 — USB permission:** per-device Android permission prompt only after explicit approval; denial and timeout return unavailable; permission is not reused for another device.
- **T04-09 — USB descriptor handle:** fresh one-shot handle expires within 30 seconds and is consumed on use; reject stale/replayed/foreign handles.
- **T04-10 — USB disconnect:** unplug during permission or descriptor read; close connection, clear raw descriptor bytes, and report disconnect.
- **T04-11 — USB safety boundary:** verify no arbitrary control/bulk/interrupt/isochronous endpoint transfer or write is exposed.
- **T04-12 — USB mass storage:** files are accessed through the SAF picker/grant path, not UsbManager raw endpoints.

**Existing seams:** `UsbDescriptorParserTest`, `AndroidControlArgumentsTest`, Bluetooth/GATT argument and dispatch tests; add instrumentation around Android permission and connection lifecycles as required.

## Chunk 05 — SAF, file operations, and removable storage

**Goal:** Verify Gallery identity-scoped SAF grants and safe document operations across internal storage, SD, and USB document providers.

- **T05-01 — Grant creation:** choose a folder using the system picker; persist only the returned grant; verify displayed tree identity and allowed read/write flags.
- **T05-02 — Grant persistence:** close/relaunch/process death; verify valid grant remains usable.
- **T05-03 — Grant revocation:** revoke access externally or remove media; verify permission-required/unavailable result and no fallback to shell.
- **T05-04 — Tree isolation:** try a document ID from another tree and a fabricated ID; reject both.
- **T05-05 — Listing/metadata:** list up to 100 children and bounded metadata; handle provider omissions, duplicate names, empty folder, and inaccessible entries.
- **T05-06 — Text read/write:** UTF-8 round trip up to 256 KiB; reject oversized/invalid encodings and verify private reads require approval.
- **T05-07 — Create folder/text file:** exact name/parent in approval; validate safe names and allowed MIME types; handle collision and provider failure.
- **T05-08 — Rename/delete:** exact target approval; deny without mutation; test unsupported provider operations and name collision.
- **T05-09 — Same-tree copy/move:** provider capability flags respected; verify content hash; source deletion only after successful move.
- **T05-10 — Name search:** name-only search, maximum 500 visited entries/depth 4/50 results; verify truncation indication and no content search.
- **T05-11 — Cross-tree copy:** independently granted source/destination; stream in chunks; enforce 100 MiB cap even if size metadata is absent; verify SHA-256.
- **T05-12 — Cross-tree move:** source remains until destination copy verifies; cancellation/failure leaves source intact; destination partial output is cleaned or cleanup failure reported.
- **T05-13 — Removable media:** repeat core grant/list/read/write/copy tests on SD and USB mass storage where available; test unplug/replug and provider remount.
- **T05-14 — Identity boundary:** Gallery SAF grant cannot be used by Termux; shell access does not imply SAF access; no arbitrary filesystem path accepted by SAF tool.
- **T05-15 — Hostile provider behavior:** null metadata, incorrect MIME/size, exceptions, zero-byte streams, short writes, and provider death fail safely.

**Existing seams:** `DocumentTreeCapabilityToolTest`, SAF artifact destination tests, cross-tree/file policy tests. Add provider-backed instrumentation for persisted grants and document IDs.

## Chunk 06 — Media, downloads, media sessions, and sharing

**Goal:** Verify bounded media discovery/indexing/playback and user-mediated sharing.

- **T06-01 — Media search NAME:** filename-only search, bounded results, no content/URI/path disclosure.
- **T06-02 — Media search AUTO:** combine filename and available visual index; handle index absent/stale without claiming visual success.- **T06-03 — Media search VISUAL:** semantic query returns only permitted indexed media; test no index, empty results, and index failure.
- **T06-04 — Media index:** one-time approval; only MediaStore/photo-picker/previously granted SAF content; depth 8/10,000 visited nodes/1,000 discovered SAF media per pass; repeated run skips indexed URIs.
- **T06-05 — Index privacy/storage:** verify embeddings remain on device, no source upload, bounded database growth, and cleanup of revoked-source entries where supported.
- **T06-06 — Media play:** fresh search handle and approval; open in Gallery Media3 player; reject stale/replayed handle and inaccessible media.
- **T06-07 — Downloads status:** list at most 20 active Gallery-tagged downloads; labels come from allowlist; no URLs/tokens/WorkManager IDs/paths.- **T06-08 — Exact download cancel:** fresh one-use handle, exact job revalidation, approval, cancel only selected UUID; reject stale/replayed/foreign/completed job.
- **T06-09 — Download lifecycle:** queued/running/paused/succeeded/failed/cancelled snapshots, process restart, progress bounds, unknown imported jobs generic label.
- **T06-10 — Media sessions list:** only after manually enabled notification-listener access and per-read approval; maximum 10; bounded metadata; no notification history/callback persistence.
- **T06-11 — Media session control:** only advertised PLAY/PAUSE/NEXT/PREVIOUS/SEEK; exact approval; seek requires known duration/in-range position; refresh/consume opaque handle and reject changed session.
- **T06-12 — Listener settings:** explicit settings request opens Android settings but does not grant access; disabled listener returns permission-required.
- **T06-13 — Share text:** exact content and receiver chooser; denial does not launch; cancel chooser and successful handoff both return truthful state.
- **T06-14 — Share file:** only file ID from Gallery-granted SAF tree, known size <=25 MiB; temporary read grant; reject arbitrary URI/path, oversized/unknown-size file, and inaccessible source.
- **T06-15 — Share cleanup:** temporary URI permission is scoped; no silent send or direct receiver targeting; no persistent copied duplicate.

**Existing seams:** `ActiveMediaSessionCapabilityToolTest`, `ShareContentArgumentsTest`, `NotificationContentPolicyTest`, media/download policy tests.

## Chunk 07 — Privacy-sensitive information and user-mediated communication

**Goal:** Verify sensitive data is read only after clear intent, exact approval, and permission; no background collection or persistence.
- **T07-01 — Contacts search:** approval precedes READ_CONTACTS prompt; return at most 20 bounded name/phone/email fields; test denial, empty database, missing fields, and revoked permission.
- **T07-02 — Clipboard read:** foreground-only plain-text snapshot, maximum five items/8,192 characters; test background, sensitive/empty clipboard, Android access restrictions, and no history collection.
- **T07-03 — Clipboard write:** exact content shown before approval, maximum 2,048 characters; denial leaves clipboard unchanged; verify no background write.
- **T07-04 — Active notifications:** explicit user request plus one-time NOTIFICATION_READ approval and manually enabled listener; at most 20 active entries; app label fallback; private/empty content; no history/callback capture/storage.
- **T07-05 — Notification listener state:** disabled, revoked, disconnected, reconnecting, and enabled states reported honestly; settings launch never enables it.
- **T07-05a — Gallery notifications:** posting requires POST_NOTIFICATIONS permission/global notifications enabled and exact approval; test denied permission, disabled app notifications, channel disabled, bounded listing/dismissal, and no false posted state.
- **T07-05b — Notification channels:** list bounded channels, create only allowlisted/bounded channels with approval, and verify users retain control of channel settings.
- **T07-05c — Reminder scheduling:** schedule/list/cancel through Gallery's existing AlarmManager/DataStore scheduler; verify exact local wall-clock time, reboot recovery, daylight/time-zone changes, cancellation, duplicate requests, and 50-reminder cap.
- **T07-06 — Call log:** approval before READ_CALL_LOG prompt; maximum 20 recent entries; order, type, timestamp, duration, cached contact names; installer/Android eligibility restrictions fail closed.
- **T07-07 — Call-log edge cases:** denied/revoked permission, empty log, private/blocked number, malformed fields, truncation, and no background read.
- **T07-08 — SMS inbox:** approval before READ_SMS prompt; maximum 20 received inbox entries; bounded previews, sender, timestamp, read state; no sending or role change.
- **T07-09 — SMS edge cases:** installer/default-SMS-role eligibility, denied permission, empty/private sender, long body truncation, empty inbox, and no background read.
- **T07-10 — Telephony metadata:** report only non-identifying phone/SIM state; test no SIM, locked/absent SIM, unsupported telephony, and dual-SIM ambiguity.
- **T07-11 — Dialer handoff:** ACTION_DIAL only; verify number validation and that Gallery never places a call itself.
- **T07-12 — SMS composer handoff:** ACTION_SENDTO only; verify recipient/body prefill and user must press Send; Gallery never transmits silently.
- **T07-13 — Privacy retention:** inspect logs, model context, crash reports, and app storage for unintended persistence of contacts, clipboard, notifications, call logs, or SMS content.

**Existing seams:** `NotificationContentPolicyTest`, `AndroidControlArgumentsTest`, `AndroidNativeCapabilityToolTest`, contact/clipboard/call-log/SMS argument and dispatch tests.

## Chunk 08 — Speech, biometric authentication, and secure keys

**Goal:** Verify Gallery-owned speech engines and user-mediated biometric/Keystore operations.

- **T08-01 — ASR hold-to-dictate:** microphone starts only from explicit user gesture; verify supported engines/languages, empty result, recognition error, cancellation, and release on background.
- **T08-02 — ASR privacy:** no model-triggered microphone activation; no recording after UI release/cancel; permission denied is explicit.
- **T08-03 — TTS settings:** enumerate configured engines/voices/languages; selected settings persist across process restart.
- **T08-04 — Agent speak_text:** exact text (1–1,000 chars) appears in one-time approval; denial produces no audible output; approve and verify selected voice/language.
- **T08-05 — TTS cancellation/failure:** stop playback, background app, missing engine, engine crash, and audio route change; state remains truthful.
- **T08-06 — Biometric prompt:** strong biometric requirement, foreground host, success/cancel/failure/lockout/no-enrollment/no-hardware states; boolean result only.
- **T08-07 — Keystore key creation:** app-namespaced non-exportable AES-256/GCM key; verify alias isolation and no key bytes returned.
- **T08-08 — Encrypt/decrypt:** fresh strong biometric required for each operation; ciphertext round-trip and authentication-tag failure.
- **T08-09 — Enrollment invalidation:** add/remove biometric enrollment; verify invalidated key fails closed and recovery instructions are clear.
- **T08-10 — Key deletion/recovery:** delete key, verify old ciphertext cannot be decrypted, and ensure no hidden backup/export path exists.

**Existing seams:** TTS playback/approval tests, biometric/Keystore policy tests, Gallery ASR/TTS service tests.

## Chunk 09 — AccessibilityService, semantic UI control, and screenshot understanding

**Goal:** Verify explicitly enabled AccessibilityService provides bounded semantic inspection/actions and approved multimodal screenshots.

- **T09-01 — Service enable/disable:** provider discovery reports permission-required until user enables service; disable/revoke and verify operations stop immediately.
- **T09-02 — Semantic tree:** bounded node count/depth/text; stable opaque handles; no raw node object or unbounded hierarchy.
- **T09-03 — Sensitive-screen policy:** password/secure/payment/credential screens and explicitly sensitive nodes are rejected or redacted according to policy.
- **T09-04 — Window identity:** split-screen/multiple windows; verify target package/window is explicit and no cross-window action.
- **T09-05 — Screenshot policy:** only supported API 34+ window-scoped capture; enforce size/dimension/byte bounds; deny unsupported full-display capture.
- **T09-06 — Screenshot approval:** exact target/window and purpose shown; denial means no capture; no hidden background capture.
- **T09-07 — Multimodal delivery:** approved screenshot reaches Gemma as image bytes in tool result; verify visual reasoning uses that image rather than a stale/other window image.
- **T09-08 — Screenshot cleanup:** clear byte arrays/temporary references after tool turn, cancellation, model failure, and chat close; no screenshot persisted by default.
- **T09-09 — Click/long-click/scroll:** each action requires scoped approval; target handle revalidated immediately before dispatch; verify action bounds and result.
- **T09-10 — Text entry:** only empty ordinary single-line text fields; TEXT_INPUT approval; current field content is never read back; reject password, multiline, contenteditable, and non-empty fields.
- **T09-11 — Text selection:** bounded selection range and exact target; fresh node revalidation; reject stale/foreign handles.
- **T09-12 — Stale handles:** change screen/tree after inspection; old handles must fail rather than target a new node.
- **T09-13 — Cancellation/process death:** stop action and release nodes/screenshots on cancellation, service disconnect, app background, and process death.
- **T09-14 — Coordinate boundary:** coordinate gesture fallback is not silently used; it remains a separate explicitly requested/authorized shell action.

**Existing seams:** `AccessibilityScreenInspectionArgumentsTest`, `AccessibilityScreenInspectionPolicyTest`, plus agent multimodal/result-budget tests. Instrumentation is required for actual service/window/screenshot lifecycle.

## Chunk 10 — Artifact generation, binary destinations, and image providers

**Goal:** Verify every implemented format, exact approval, genuine file structure, embedded image fidelity, destination identity, and provider disclosure.

### Shared engine and approval

- **T10-01 — Format registry:** exactly one generator for each registered format; missing/duplicate/unknown generator fails before output.
- **T10-02 — Request parser:** reject unknown keys, wrong types, missing fields, mixed destination identity, extension mismatch, unsafe names, relative/dot-segment paths, excessive JSON bytes/depth/nodes.
- **T10-03 — Approval:** exact format/name/destination/backend/overwrite and estimated size; denial/dismissal/timeout creates no output.
- **T10-04 — Cancellation/deadline:** cancellation at validation, generation, streaming, and finalization; enforce 120-second deadline; clean partial output and report uncertain cleanup.
- **T10-05 — Integrity metadata:** byte count, SHA-256, MIME, extension, and actual signature/package structure agree; no raw binary payload enters model context.
- **T10-06 — Overwrite:** default false; exact replacement approval; provider collision/unknown state fails closed.

### PDF

- **T10-07 — Valid PDF:** paragraphs, headings, bullets, tables, pagination, and multi-page output open in Android PDF viewer and desktop reader.
- **T10-08 — PDF boundaries:** page/character/output-byte limits; malformed blocks, unsupported content, zero content, and oversized strings rejected.
- **T10-09 — PDF images:** up to eight images/document; image orientation, aspect ratio, alpha behavior, image bounds, and image ordering verified.
- **T10-10 — PDF failures:** low storage, provider write failure, cancellation, and partial-document cleanup.

### DOCX

- **T10-11 — Editable DOCX:** paragraphs, heading styles, bullets, tables, page layout, optional headers/footers open in Word-compatible reader and remain editable.
- **T10-12 — DOCX package:** required content types/relationships/media entries present; all XML is well-formed; relationship targets resolve.
- **T10-13 — DOCX images:** up to four embedded images; drawing relationships resolve; image is embedded, not externally linked.- **T10-14 — DOCX safety:** XML-invalid characters, injection-like text, malformed payloads, and output limits fail safely.

### XLSX
- **T10-15 — Editable workbook:** multiple named sheets, string/numeric/boolean/blank cells, styles, column widths, header styling, and filters open in spreadsheet reader.
- **T10-16 — XLSX bounds:** 20 sheets, 500 rows/sheet, 32 columns/row, 10,000 cells, 100,000 text chars, 15 significant numeric digits, 20 MiB output.
- **T10-17 — Formula safety:** values beginning with =, +, -, or @ remain inert text where specified; no formula/macro execution or macro parts.
- **T10-18 — XLSX package:** workbook/sheet/style relationships resolve and all XML parses.

### PPTX

- **T10-19 — Editable presentation:** 16:9 deck with title/body text, paragraphs, bullets, multiple slides opens in PowerPoint-compatible reader and remains editable.
- **T10-20 — PPTX package:** presentation, slide, master, layout, theme, relationships, and content types resolve; all XML parses.
- **T10-21 — PPTX images:** one image per slide, four per presentation; embedded picture shapes and media relationships resolve.
- **T10-22 — PPTX bounds:** slide/block/text/output limits and malformed layout data rejected.

### ZIP create/extract
- **T10-23 — ZIP creation:** up to 100 UTF-8 entries, per-entry/total limits, safe nested paths, duplicate/case collisions, file-directory collisions, and valid archive integrity.
- **T10-24 — ZIP hostile paths:** traversal, absolute paths, drive prefixes, backslashes, control chars, reserved names, empty/dot segments, and duplicate targets rejected.
- **T10-25 — ZIP extraction preflight:** 500-entry, 50 MiB compressed/expanded, 10 MiB per-file, and 100:1 ratio limits; unknown sizes and malformed central directory handled safely.
- **T10-26 — ZIP TOCTOU:** hash archive before approval and verify unchanged after approval; changed source aborts.
- **T10-27 — ZIP destination:** separate source/destination SAF grants; existing target rejection; no symlink creation/following; cancellation and reverse-order partial-tree cleanup.

### Local image processing and embedded sources

- **T10-28 — Image formats:** PNG/JPEG/WebP input/output; verify actual MIME and file signature.
- **T10-29 — Transform correctness:** resize, crop, 0/90/180/270 rotation, quality bounds, dimensions, and pixel content.
- **T10-30 — EXIF:** all orientation values including mirrored/rotated cases normalize pixels correctly; verify portrait/landscape output.
- **T10-31 — Image limits:** 20 MiB compressed source, 8,192 px side, 20 MP source, 8 MP decode sample, 4,096 px output side, 16 MP output, 20 MiB output; oversized/invalid decode and OOM fail safely.
- **T10-32 — Composition/annotation:** source count, source grants/document IDs, labels/alt text, annotation bounds, and ordering validated.
- **T10-33 — Embedded image grants:** only current document IDs under selected persisted Gallery tree; arbitrary URI/path, foreign tree, revoked grant, and missing consent label rejected before provider invocation.
- **T10-34 — Alpha:** PNG alpha preserved; JPEG transparency flattened to white; verify actual pixels.
- **T10-35 — PDF/DOCX/PPTX embedded-image caps:** PDF eight/document, DOCX four/document, PPTX one/slide and four/presentation; overflow rejected.

### Remote image generation and destinations

- **T10-36 — Firebase registration:** provider appears only when Firebase is configured; absent configuration reports unavailable without retry loops.
- **T10-37 — Consent disclosure:** exact provider ID, prompt, output MIME, source count, and visible label for every source are shown before upload.
- **T10-38 — Consent denial:** deny/dismiss/timeout/missing label/missing grant prevents provider invocation and any network upload.
- **T10-39 — Remote bounds/errors:** per-source 8 MiB, aggregate 12 MiB, output 4 MiB; offline, timeout, provider error, malformed output, and cancellation fail safely.
- **T10-40 — SAF binary output:** persisted grant and parent tree identity, stream close/finalize, provider MIME, hash, cancellation cleanup, and overwrite behavior.
- **T10-41 — Termux binary output:** APP_SHELL stdin chunk bounds (96 KiB raw chunks / <=128 KiB base64 text), multiple chunks, byte count/hash, exact TERMUX identity, same-directory finalization, cancellation, and .part cleanup.
- **T10-42 — Shizuku PFD output:** one-shot PFD pipe, shell UID, 50 MiB ceiling, deadline, byte count/hash, close on cancellation, staging cleanup, and atomic rename.
- **T10-43 — Rish binary rejection:** TERMUX_RISH is rejected before creating output; no hidden fallback.
- **T10-44 — External openability:** open each generated format in at least one independent compatible reader. ZIP is independently extracted and inspected. Verify embedded images remain present and editable formats are editable.
- **T10-45 — Resource profile:** representative small/large artifact and image tasks; record peak memory, generation time, CPU, storage, battery, and thermal state.

**Existing seams:** `ArtifactCreateArgumentsTest`, `ArtifactEmbeddedImageArgumentsTest`, `ArtifactGenerationEngineTest`, `PdfArtifactGeneratorTest`, `DocxArtifactGeneratorTest`, `XlsxArtifactGeneratorTest`, `PptxArtifactGeneratorTest`, `ZipArtifactGeneratorTest`, `ZipExtractionPathPolicyTest`, `ImageTransformArgumentsTest`, `ImageGenerationArgumentParserTest`, `ImageGenerationProvidersTest`, `ShellArtifactDestinationAdapterTest`, `ShellArtifactTransferPolicyTest`, and `ShellBinaryTransportInstrumentedTest`.

## Chunk 11 — Cross-cutting security, resilience, performance, and release regression

**Goal:** Exercise failure combinations and prove the no-root, local-first, fail-closed design remains intact.

- **T11-01 — Missing dependencies:** Termux app/package missing, Shizuku stopped, notification listener disabled, unsupported hardware, Firebase unconfigured, and model unavailable; each returns a precise availability state.
- **T11-02 — Permission revocation:** revoke runtime permission/special access between discovery, approval, and operation; verify fresh permission check and no mutation.
- **T11-03 — Background/process death:** background app, kill process, force-stop companion, or disconnect provider during operation; verify cancellation and cleanup.
- **T11-04 — Low storage:** run bounded output with insufficient free space; no corrupt final file, staging leak, or false success.
- **T11-05 — Race/replay:** concurrent identical requests, stale handles, reused approval, repeated callbacks, and late provider responses; one operation at most.
- **T11-06 — Input fuzz/bounds:** malformed Unicode, deeply nested JSON, huge arrays/strings, invalid IDs, path traversal, malformed XML/ZIP/image payloads, and integer overflow boundaries.
- **T11-07 — Sensitive-data hygiene:** inspect logs, crash diagnostics, persistent storage, model context, and temp directories for access tokens, raw notification/clipboard/contact/SMS data, screenshot bytes, or image uploads beyond explicit consent.
- **T11-08 — No-root/Knox:** verify no root requirement, no bootloader/system modification, no Knox-sensitive change, no arbitrary generated-code execution, and no unapproved shell fallback.
- **T11-09 — Thermal/battery:** representative 10–15 minute agent and image/artifact sessions; record battery delta, temperature, thermal throttling, and recovery to idle.
- **T11-10 — Memory/process:** model loaded/unloaded, repeated screenshots/images, repeated artifact creation, cancellation, and idle; verify bounded memory and no leaked processes/services/descriptors.
- **T11-11 — Storage growth:** repeated generation/indexing/download tasks; verify bounded caches, cleanup, and no unexplained persistent growth.
- **T11-12 — Repeatability:** repeat critical cases after app restart and after device reboot; compare outputs/status and ensure no hidden one-time state dependency.
- **T11-13 — Upgrade regression:** update from the previous installed build using same signing identity; preserve user model/settings/grants where Android permits; verify migration and launch.
- **T11-14 — Release parity:** compare debug and release behavior for package configuration, permissions, manifest declarations, min/target SDK, and feature registration.
- **T11-15 — Final regression gate:** rerun complete `:app:testShizukuDebugUnitTest`, `:app:assembleShizukuDebug`, and `:app:assembleRelease` on the final integration head; archive logs, APK hashes, signing fingerprint, and device acceptance report.

**Exit:** no unexplained FAIL; all LIMITED/UNAVAILABLE cases have explicit reasons and user-facing behavior; no privacy/security boundary regressions; resource use is acceptable for the S21.

---

## 3. Recommended review and execution order

Run chunks in this order, one at a time:

1. **Chunk 00** — install/build baseline and protect existing app data.
2. **Chunk 01** — shell execution transports and backend identity.
3. **Chunk 02** — Gemma tool-call loop, approvals, result integrity, screenshots.
4. **Chunk 03** — system controls and Termux:API.
5. **Chunk 04** — Bluetooth/Wi-Fi/USB connectivity.
6. **Chunk 05** — SAF and removable storage.
7. **Chunk 06** — media/downloads/sharing.
8. **Chunk 07** — privacy-sensitive reads and communication.
9. **Chunk 08** — speech and biometric/Keystore.
10. **Chunk 09** — AccessibilityService and multimodal UI control.
11. **Chunk 10** — artifact generation (can be split further into shared engine, each format family, images/consent, and each destination).
12. **Chunk 11** — cross-cutting stress/security/release regression.

The first review session should be Chunk 00 only. Do not ask Brian to perform later chunks until the current chunk's prerequisites and expected observations are clear.

## 4. Known intentional limitations (not automatic failures)

- TERMUX_RISH binary artifact output is deliberately unavailable until an end-to-end binary stdin/cancellation/hash protocol is independently proven.
- On-device generative image creation is not registered; no resource-qualified provider is selected.
- Generic USB endpoint transfers/writes are not exposed. USB mass-storage files use SAF.
- Continuous sensor streams and continuous location updates are not exposed.
- NFC, infrared, BLE/GATT, USB descriptor, and other hardware-dependent checks may be UNAVAILABLE if the S21/accessory does not support them.
- Call-log/SMS access can be blocked by Android installer/default-role eligibility; Gallery must not change roles or bypass platform policy.
- Firebase Gemini remote generation is applicable only when Firebase is configured and must remain opt-in with explicit per-request disclosure/consent.
These limitations must remain documented in the result record and must not be represented as passed features.
## 5. Current evidence ledger

| Item | Status | Evidence |
|---|---|---|
| Stage 10A–10I implementation onboarding | PASS — implementation scope | Current Stage 10 branch/PR #60 and roadmap |
| Unit-test CI | PASS | Workflow #118, 308 tests, commit `7bacd681bdd8d5b35d255933e21e66c6a3780b09` |
| Shizuku debug APK build | PASS | Workflow #118 |
| Release APK build | PASS | Workflow #118 |
| Termux APP_SHELL / Shizuku PFD instrumentation execution | NOT YET VERIFIED | Instrumentation source exists; workflow #118 did not run instrumentation task |
| Galaxy S21 acceptance | IN PROGRESS | Chunk 00 underway; see updated 2026-11-21 live execution record above |
| Chunk 00 | IN PROGRESS | T00-01, T00-02, T00-03, T00-05 PASS; T00-04 PASS with manual unload/reload N/A; T00-06, T00-07, T00-08 PARTIAL for documented follow-ups |
| Chunks 01–11 | NOT STARTED | Remain blocked until Chunk 00 exit criteria are met |
| Stage 9 integration/release | NOT STARTED | Requires S21 acceptance and explicit owner approval |

## 6. Native Gemma input-token budget regression (discovered 2026-11-21)

During pre-Chunk-02 Gemma capability-discovery testing, LiteRT-LM rejected the native request with `Input token ids are too long`: 10,992 input token IDs against a 4,000-token ceiling. Inspection found that the app-authored `buildAgentSystemInstruction` was 22,411 characters before the user's current request and registered tool definitions were added. This was a major confirmed prompt-footprint contributor; character-to-token conversion is approximate and the native tokenizer remains authoritative.

**Fix committed on `stage-10h-image-followups`:**
- Replaced the oversized repetitive agent policy text with a compact policy prompt while retaining approval, privacy, routing, fresh-handle, accessibility, SAF, remote-consent, and fail-closed requirements.
- Compressed the descriptions registered for native Android, accessibility, SAF, artifact, active-media-session, sharing, and Termux:API tools. Operation schemas and dispatch implementations were not removed.
- Added `AgentSystemInstructionTest` to enforce a 3,500-character ceiling for the built-in policy prompt, preserve the existing assistant instruction, and guard critical safety clauses.

**Validation state:** GitHub Actions PR workflow #142 (run ID `44958060069`) was in progress when this record was updated. No S21 retest has yet been performed. The regression is not considered device-accepted until the workflow passes and Gemma successfully handles repeated short user turns and a tool-call round trip without the 4,000-token error. Keep T02-11 (result/context budgets) unverified until that device evidence exists; do not advance the chunk sequence while Chunk 00 remains active.


## 7. Native agent context budgeting and compaction (implementation update)

The native agent now reads LiteRT-LM's actual conversation KV-cache token count after each completed model turn. When the configured context reaches 75% of its effective ceiling (capped at the observed 4,000-token native input limit), it rebuilds the native conversation from retained complete recent turns until the cache is at or below 60% of the configured ceiling, or only the latest turn remains. Compaction removes whole turns, never a partial tool-call/result transaction. The recreated conversation uses the same system instruction, registered tools, sampler, and original initial messages, and pre-fills its preface so token accounting is immediately available.

Replay history deliberately omits transient image/audio payloads: user attachments and screenshot bytes are not retained in the compaction journal. A tool response that originally carried an image retains its bounded text result plus an omission note after compaction. This avoids replaying zeroed screenshot buffers or extending raw image retention.

If LiteRT-LM rejects a single oversized inference with its native input-token overflow, the agent reports a bounded, actionable message instead of surfacing the raw JNI exception or retrying a potentially partial operation. No tool is executed for an inference rejected before a structured tool call is returned.

The built-in agent policy prompt is reduced to a 2,500-character enforced ceiling, targeting Google's upstream 2,044-character default while retaining this fork's Android permission, routing, privacy, accessibility, SAF, USB, remote-upload, sharing, and download safeguards.

**Validation:** Unit tests cover prompt size/safety clauses, compaction threshold, atomic retention of complete turns/tool transactions, protection of incomplete/current turns, and native overflow classification. GitHub Actions and Galaxy S21 repeated-turn/tool-round-trip validation are required before marking the token-budget regression resolved. A single oversized user turn or a latest turn that alone consumes the context ceiling still requires a shorter prompt or a fresh agent conversation; the compactor deliberately does not destroy the current turn to conceal that limit.
