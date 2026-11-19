# Android capability provider matrix

The canonical inventory, provider ownership decisions, Termux:API command disposition, security
contracts, end-to-end roadmap, and live progress ledger are maintained in
[ANDROID_CAPABILITY_OWNERSHIP_AND_IMPLEMENTATION_PLAN.md](ANDROID_CAPABILITY_OWNERSHIP_AND_IMPLEMENTATION_PLAN.md).
The chunked feature-by-feature verification sequence is documented in
[ANDROID_MCP_FEATURE_TEST_ROADMAP.md](ANDROID_MCP_FEATURE_TEST_ROADMAP.md).

## Current progress snapshot (2026-11-19)

- Active branch: `stage-10h-image-followups`, stacked on PR #58 (Stage 10I binary output), PR #57 (Stage 10H image processing), PR #56–#48 (Stage 10A–10G architecture and format adapters).
- Active change: final Stage 10 image integration PR, stacked on [PR #58 — Stage 10I binary artifact output](https://github.com/bv9522/gallery/pull/58), [PR #57 — Stage 10H local image processing and provider boundary](https://github.com/bv9522/gallery/pull/57), and [PR #56 — Stage 10G guarded SAF ZIP extraction](https://github.com/bv9522/gallery/pull/56), then [PR #55 — Stage 10G bounded ZIP archive creation](https://github.com/bv9522/gallery/pull/55), then [PR #54 — Stage 10F editable PPTX OOXML generation](https://github.com/bv9522/gallery/pull/54), then [PR #53 — Stage 10E editable XLSX OOXML generation](https://github.com/bv9522/gallery/pull/53), then [PR #52 — Stage 10D editable DOCX OOXML generation](https://github.com/bv9522/gallery/pull/52), then [PR #51 — Stage 10C native PDF generation and streaming SAF output](https://github.com/bv9522/gallery/pull/51), then [PR #50 — Stage 10B shared artifact contracts and approval boundary](https://github.com/bv9522/gallery/pull/50), then [PR #49 — Stage 10A artifact-generation dependency/API preflight](https://github.com/bv9522/gallery/pull/49), then [PR #48 — Stage 10 artifact-generation architecture](https://github.com/bv9522/gallery/pull/48),
- PR #42 adds separately approved entry into empty ordinary single-line plain-text fields and bounded text selection. Current field contents are never returned. Unit tests passed in workflow #118's 308-test suite; device acceptance remains pending.
- Agent `speak_text` is implemented through Gallery's existing `TtsServiceProvider` and playback controller; user confirmed `:app:testShizukuDebugUnitTest` and `:app:assembleShizukuDebug` both completed with `BUILD SUCCESSFUL` on head `7ac3bdb3904ea105ee7dad6574f8b92a40230a5b`. Device acceptance remains pending.
- PR #34 reuses `DefaultDownloadRepository` / `DownloadWorker` / WorkManager and adds bounded snapshots, allowlist-backed labels, opaque handles, exact-worker cancellation, scoped approval, and unit tests. The user confirmed its local tests and Shizuku debug build passed; device acceptance remains pending.
- PR #35 adds bounded filename/visual search, EmbeddingGemma-2 / Smart Album index reuse, SAF-granted storage discovery/indexing, and Gallery-owned Media3 video playback. Unit tests passed in workflow #118's 308-test suite; device acceptance remains pending.
- PR #36 adds `share_content.share_text` (exact approved text, 1–4,000 characters) and `share_content.share_file` (a document ID from a Gallery-granted SAF tree, known file size up to 25 MiB). Both launch Android's ACTION_SEND chooser; the user selects the receiver and completes or cancels. File sharing grants temporary read access only; no arbitrary URI/path, direct receiver targeting, or silent send is exposed. Tests are added but have not been executed.
- PR #37 adds `media_sessions.list`, `control`, and explicit `open_settings`. Listing requires the user-enabled notification-listener special access and one-time approval; it returns at most 10 cross-app sessions with bounded metadata and 2-minute opaque handles. Controls are limited to advertised PLAY/PAUSE/NEXT/PREVIOUS/SEEK actions, revalidate the current active session, require one-time approval, and consume the handle after dispatch. SEEK is bounded to the known track duration. Settings launch never grants access automatically. Tests are added but have not been executed.
- PR #46 adds `android_control.usb_read_descriptors` using a fresh one-shot handle from `usb_devices`. It requires one-time `USB_DEVICE_IO` approval and Android's per-device USB permission prompt when needed, then returns only standard device/configuration/interface/endpoint descriptor metadata. Handles expire after 30 seconds and are consumed on use. Arbitrary control/bulk/interrupt/isochronous transfers and writes remain unsupported; USB mass-storage files continue through SAF. Unit tests passed in workflow #118's 308-test suite; instrumentation/device acceptance remains pending.
- PR #45 adds `android_control.read_sms_inbox`, a one-shot snapshot of at most 20 recent received SMS entries with sender address, bounded message preview, timestamp, and read/unread state. Each read requires explicit user intent, one-time `SMS_READ` approval, and `READ_SMS` permission requested only after approval. Android/installer/default-SMS-role eligibility restrictions fail closed; Gallery does not change the default SMS role. Tests are added but not run.
- PR #44 adds `android_control.read_call_log`, a one-shot snapshot of at most 20 recent call-log entries with caller name/number, call type, timestamp, and duration. Each read requires explicit user intent, one-time `CALL_LOG_READ` approval, and `READ_CALL_LOG` permission requested only after approval. Android/installer eligibility restrictions fail closed; Gallery does not change the default dialer role. Tests are added but not run.
- PR #43 adds `android_control.read_notifications`, an explicit one-shot snapshot of up to 20 currently active notifications from other apps. It requires manually enabled notification-listener special access plus separate one-time approval for each read; it returns only app labels and bounded title/body text, does not read history or process callbacks, and stores no notification content. The shared listener does not collect callbacks or retain notification history. Tests are added but not run.
- PR #30, #31, and #32 have user-confirmed local unit-test and debug-build validation; device acceptance remains pending.
- **Validation status:** workflow #118 passed the 308-test `:app:testShizukuDebugUnitTest` suite, `:app:assembleShizukuDebug`, and `:app:assembleRelease` on commit `7bacd681bdd8d5b35d255933e21e66c6a3780b09`. Android instrumentation and Galaxy S21 acceptance remain pending; use the chunked feature test roadmap.
- PR #23–#29 and #16–#27 still require consolidated S21 acceptance recorded in the master roadmap.
- PR #47 adds `android_control.wifi_open_settings`, which requires one-time `WIFI_CONTROL` approval and opens `Settings.Panel.ACTION_WIFI` with `Settings.ACTION_WIFI_SETTINGS` fallback. The user makes any Wi-Fi change; Gallery never attempts a silent toggle. Tests are added but not run.
- Source-level ownership audit: `CapabilityCatalog` derives one provider declaration and one route preference for every `AndroidCapability` enum value. `CapabilityRegistry` enforces complete ownership and rejects duplicate/missing/mismatched routes. Device permissions, approval timing, and actual behavior remain unverified until Stage 8.
- Consolidated S21 acceptance is unlocked. Automated unit tests and both APK builds passed in workflow #118; device acceptance remains pending and will be executed one reviewed chunk at a time.

## Artifact generation ownership and storage integration — Stage 10 active

**Status: IMPLEMENTATION COMPLETE — Stage 10A–10I implementation and documentation are onboarded. Stage 10J consolidated validation and S21 acceptance are the remaining gate.**

- **Generation owner:** one Native Kotlin Artifact Generation Engine. It validates structured requests, dispatches format adapters, applies approvals/limits, manages temporary outputs/cancellation, verifies results, and returns typed artifact metadata.
- **Formats:** PDF, DOCX, XLSX, PPTX, ZIP create/extract, local image processing, bounded SAF image embedding in PDF/DOCX/PPTX, and conditionally configured Firebase Gemini remote image generation behind explicit per-request consent. No on-device generative provider is registered; Gemma 4 E2B image input is not image generation.
- **Destination adapters:** both Gallery-granted SAF document trees and explicitly selected paths through the unified shell router. SAF grants remain Gallery-identity scoped; shell access is determined by selected TERMUX / SHIZUKU / TERMUX_RISH identity. No silent fallback or identity substitution.
- **Selected baseline:** no new third-party document/image dependency. Use platform `PdfDocument`, JDK `java.util.zip`, Android bitmap/image APIs, and existing ExifInterface; implement a minimal bounded OOXML package writer with strict supported features. AndroidX PDF dependencies already present are not PDF authoring APIs. See `docs/ARTIFACT_GENERATION_DEPENDENCY_REVIEW.md`.
- **Image strategy:** local bounded processing plus optional generative providers. Remote providers disclose data transfer and require explicit opt-in/approval; on-device generation is optional and resource-gated.
- **Shell binary output (Stage 10I / PR #58):** Termux now sends base64-encoded 96 KiB raw chunks through `RUN_COMMAND_STDIN`, below a 128 KiB character bound (about 256 KiB in the UTF-16 Intent string), and verifies destination length/SHA-256 before same-directory finalization. Shizuku now receives bytes through a dedicated one-shot `ParcelFileDescriptor` pipe and its shell-UID UserService verifies length/SHA-256 before rename. `TERMUX_RISH` is intentionally rejected for binary output until end-to-end stdin forwarding and cancellation are proven. These are code-level implementations only; tests, builds, and S21 acceptance remain deferred to Stage 10J.
- **Shared contract:** one `artifact_create` model-facing operation envelope with strict `format`, `file_name`, `destination_type`, and typed `payload`; SAF takes a persisted Gallery `tree_uri` plus optional parent document ID, while shell requires an explicit backend and absolute path. Mixed SAF/shell arguments, unknown keys, path traversal, mismatched extensions, oversized/deep payloads, and non-boolean overwrite values are rejected.
- **Approval/lifecycle:** exact format, filename, destination, estimated size, and replacement behavior are presented in a one-time approval dialog. The engine propagates cancellation, has a bounded operation timeout, and requires each format adapter to declare a positive output cap. Destination adapters own partial-file cleanup and atomic finalization.
- **Safety:** app-private quota-bounded staging, streaming, cancellation/partial-output cleanup, explicit overwrite approval, MIME/signature/package validation, per-format limits, and ZIP traversal/decompression-bomb defenses. Do not execute generated code or evaluate workbook formulas/macros.
- The SAF 256 KiB text I/O cap applies only to existing text operations. The 100 MiB limit applies only to existing SAF cross-tree file transfers. Neither is a universal artifact-generation limit; binary SAF writes use bounded streaming and separately defined format limits.

## Single-owner policy

Every model-independent capability has exactly one selected owner in the capability catalog:

| Scope | Owner | Boundary |
|---|---|---|
| Typed/app-owned Android operations | Native Android Kotlin | Gallery app UID; typed APIs; richer lifecycle, app-owned state, and explicit approvals |
| Bounded Android command capabilities | Termux:API | Fixed allowlisted commands through supervised Termux RUN_COMMAND; companion permissions and process boundary |
| Path-based file and directory operations | Unified shell router | Existing TERMUX / SHIZUKU / TERMUX_RISH execution backends; no duplicate file tool |
| User-granted document trees and removable storage files | Android Storage Access Framework | Persisted user-granted document URI permissions |
| Artifact generation and format dispatch | Native Kotlin Artifact Generation Engine | One shared structured artifact contract with PDF/DOCX/XLSX/PPTX/ZIP/image-processing adapters; SAF, Termux stdin chunks, and Shizuku PFD pipes are destination adapters; TERMUX_RISH binary output remains unavailable pending proof |
| Semantic UI inspection, bounded window capture, approved semantic actions, and safe text input | Android AccessibilityService | Explicitly enabled service; bounded tree reads, API 34+ window-only screenshots, per-action approved click/long-click/scroll, and UI_TEXT_INPUT-gated entry into empty plain-text fields plus bounded selection with fresh node revalidation; coordinate gesture fallback remains on the existing unified shell route only when explicitly requested/authorized |

Termux shell, Shizuku, and Rish remain execution transports inside the unified shell router.
Termux:API is a distinct capability owner only for the bounded command families assigned in the
canonical plan. Phase 4A battery/volume/flashlight overlap operations remain diagnostic only;
native remains their selected owner. No capability has both owners.

## Existing native capability implementations

The list below reflects previously integrated Gallery-native operations; it is not a claim that every later onboarding PR has passed current-head validation or device acceptance.

- Device/build information
- Battery status
- Flashlight availability and control
- Audio volume read/control
- Audio input/output device and route discovery (bounded to 24 endpoints)
- Display brightness setting read/control (write requires Modify system settings special access)
- Vibration (approval-gated, maximum one-second pulse)
- Bounded partial CPU wake lock (approval-gated, maximum five-minute timeout with automatic release)
- Launcher-visible app discovery and app launch
- HTTP(S) URL opening

## Onboarding readiness

The capability onboarding chain includes draft PRs #23–#32, Wave 3E PRs #33–#37, Accessibility PRs #39–#42, optional capability PRs #43–#46, and Wi-Fi/audit PR #47. SAF document-tree and cross-tree file operations are in PRs #23–#25; Bluetooth GATT inspection/I/O is in #26–#27; contacts and clipboard plus speech ownership correction are in #28; ownership/readiness reconciliation is in #29; Gallery-owned notifications and scheduling are in #30; telephony metadata and user-mediated dialer/SMS composition are in #31. Biometric authentication and Gallery-owned Keystore are in draft PR #32; the user confirmed `:app:testShizukuDebugUnitTest` and `:app:assembleShizukuDebug` both passed after the FragmentActivity host correction. Device acceptance remains pending. Wave 3E now includes downloads/status/cancellation (PR #34), media search/indexing/Gallery-owned playback (draft PR #35), and user-mediated text/SAF file sharing (draft PR #36). PR #36 uses the Android chooser and does not claim delivery when the chooser opens. The `speak_text` operation reuses the existing TTS playback controller. Per-slice tests/builds were deferred by owner instruction until all capability onboarding was complete. That gate is now satisfied; consolidated validation is planned in Stage 8.

The capability catalog assigns exactly one owner to every declared capability. This is enforced by catalog construction and registry ownership tests; it does not imply that every owner is implemented or available. PR #47's source-level audit also reconciles each current native operation with its capability ID and scoped approval path; no tests/builds have been run for this audit.

## Native notifications and scheduling — Phase 9B draft

- Reuses Gallery's existing AlarmManager/DataStore scheduler, scheduled-notification receiver (which re-arms daily reminders at local wall-clock time), boot recovery receiver, and PendingIntent helper; no second scheduling subsystem is introduced.
- `android_control` exposes approved immediate notification posting, bounded active-notification listing/dismissal, scheduled reminder creation/listing/cancellation, and bounded channel listing/creation. Mutations require scoped approval; Android 13+ notification permission is requested only after approval.
- Provider discovery reports permission/global-notification state without prompting. Blocked channels and globally disabled notifications are explicit failures. Active notification and channel listings are bounded; at most 50 reminders are persisted. Schedule persistence and alarm updates are serialized, and boot recovery reads authoritative DataStore state.
- PR #43 adds on-demand reads of currently active notifications from other apps through the same manually enabled NotificationListenerService. Each read requires separate approval; no history, background callback collection, or persistence is introduced.

## Accessibility provider — Wave 5A semantic inspection (PR #39 draft)

- `screen_inspect status` reports the AccessibilityService lifecycle without reading screen content. `PERMISSION_REQUIRED` is returned until the user manually enables Gallery in Android Accessibility settings; an enabled but disconnected service reports `SERVICE_STOPPED`.
- `screen_inspect inspect` requires one-time approval and reads only the active visible application window on demand. The service subscribes only to window-state changes and ignores the event payload; it stores no event history or screen tree. Inspection reads only the active window at invocation. Since Agent Chat is normally foreground, this usually means Gallery's own window unless the user has arranged another active window (for example, split-screen); app switching is not automated.
- Traversal is breadth-first, capped at 120 nodes and depth 12. A changing, incomplete, or over-limit tree is rejected in full. Output is limited to 32 semantic elements; each sanitized label is capped at 120 characters.
- Every editable/password field, sensitive-screen keyword, detected email/phone/long numeric identifier, protected app category, Android Settings/system/permission window, or missing active window blocks the whole inspection. This is a conservative heuristic, not a guarantee that every third-party app labels sensitive content correctly.
- Results contain semantic roles, bounded labels, basic visible/enabled/selected/checkable state, and random opaque `ui_` handles with a 30-second lifetime. Handles are not coordinates or node IDs and cannot trigger actions in this slice.
- The semantic `inspect` response omits package names, resource/view IDs, coordinates, screenshot bytes, hidden/off-screen content, notification text, and editable field values. Screenshot delivery is a separate Wave 5B `capture` operation. No taps, gestures, typing, or background monitoring is implemented.
- The Android Accessibility special-access screen warns about the broad capability Android grants. Gallery does not enable or disable the service automatically; the user manually controls it in Settings. The settings handoff itself requires scoped one-time approval.
- Pure policy tests were added; all tests/builds and S21 acceptance remain deferred until the capability onboarding campaign is complete.

## Accessibility provider — Wave 5B bounded visual understanding (PR #40 draft)

- Adds `screen_inspect capture` as an explicit one-shot operation. It requires API 34+, manually enabled Gallery AccessibilityService, one-time `SCREEN_CAPTURE` approval, and exactly one visible non-Gallery application window alongside Gallery; split-screen is the expected user arrangement.
- Interactive application windows are enumerated only during the request. Missing roots, unknown package identity, multiple external windows, no external window, empty/truncated/incomplete hierarchies, sensitive packages, editable/password fields, sensitive text, and detected personal identifiers fail closed.
- Captures only the selected window ID through `takeScreenshotOfWindow`. It never calls the API 30 display-wide screenshot method, MediaProjection, or any full-display fallback. Older Android versions report unsupported.
- The screenshot is bounded to 1,280 px on the longest side, 2 MiB JPEG, and a 10-second capture timeout. The hardware buffer and temporary bitmaps are released; no file is written.
- The agent loop adds a bounded image field to tool results and tool messages. LiteRT-LM receives `Content.ImageBytes` and `Content.ToolResponse` in the same `Message.tool(Contents)`, allowing Gemma to reason over actual screenshot pixels during the tool-result turn. The existing Gemma 4 E2B allowlist declares image support; Gallery already configures the vision backend and uses `Content.ImageBytes` for user attachments.
- The fork's `model_allowlists/1_0_23.json` declares `Gemma-4-E2B-it` with `llmSupportImage: true`, `llm_agent_chat` and `llm_ask_image` task support, a GPU vision-accelerator default, and an 8 GiB minimum-device-memory requirement. The tool schema exposes `capture` only when both the selected model and runtime configuration support image input; text-only models and pre-API-34 devices do not see that operation.
- Image budgets reject oversized payloads instead of truncating them. Raw screenshot bytes are zeroed and references removed after inference and on failure/reset/turn-limit paths. LiteRT-LM may retain the visual representation in the in-memory conversation until that conversation is reset; no persistent screenshot file or background capture is created.
- The heuristic sensitive-screen gate is deliberately conservative but cannot guarantee recognition of every sensitive visual in every third-party app. The approval prompt tells the user to verify the target screen is appropriate. No tap, gesture, typing, or app-switching capability is added.
- Unit tests are added for multimodal coordinator delivery, image-budget enforcement, and transient-byte erasure. Tests/builds and S21 device acceptance remain deferred under the consolidated validation plan.

## Accessibility provider — Wave 5C approved semantic UI actions (PR #41 draft)

- Adds `screen_inspect inspect_external`, which reads exactly one visible non-Gallery application window alongside Gallery (normally split-screen), applies the same conservative sensitive-screen and complete-hierarchy checks, and issues opaque action handles that expire after 30 seconds.
- Adds `screen_inspect act` for CLICK, LONG_CLICK, SCROLL_FORWARD, and SCROLL_BACKWARD only. Each action requires a separate one-time `UI_GESTURE` approval describing the exact action and sanitized target label.
- Immediately before dispatch, the service rechecks that it remains connected, the same single external package/window is visible, the hierarchy is complete and safe, and exactly one node matches the stored package/window/class/label/bounds fingerprint and enabled/action flags. It also checks the node currently advertises the requested AccessibilityNodeInfo action.
- Handles are consumed before dispatch. Stale, expired, ambiguous, changed, disabled, sensitive, or unsupported targets fail closed. No coordinate fallback, swipe gesture, text entry, selection, keyboard automation, or app switching is exposed.
- A successful result confirms only that Android accepted the semantic action; Gallery does not claim the app's resulting state changed. Argument and pure action-eligibility tests are added but not run. All tests/builds and S21 acceptance remain deferred until capability onboarding is complete.

## Accessibility provider — Wave 5D approved semantic text entry and selection (PR #42 draft)

- Adds `screen_inspect inspect_inputs`, a separate UI_TREE_READ-approved operation for exactly one visible external application window alongside Gallery. It returns only safe field labels, enabled state, and current character count; field contents are never returned.
- Only ordinary single-line `EditText` fields with normal plain-text Android input type are eligible. Password, numeric, email, URI, multiline, unsupported/custom, sensitive, uncertain, empty/incomplete, or ambiguous screens fail closed. The existing read-only inspection and screenshot paths continue to reject all editable fields.
- Adds `enter_text` for at most 256 non-sensitive, single-line characters, only into a fresh enabled empty field. Existing content is never overwritten. Each entry requires separate `UI_TEXT_INPUT` approval showing the exact target and text.
- Adds `select_text` for a non-empty end-exclusive character range within the current safe field length. Each selection requires separate `UI_TEXT_INPUT` approval showing the exact target and range; field contents are not returned.
- Input handles are opaque, expire after 30 seconds, and are consumed before dispatch. Immediately before dispatch, the service revalidates the same external package/window, complete safe hierarchy, exact node class/label/bounds, normal input type, enabled state, current text length, and currently advertised ACTION_SET_TEXT or ACTION_SET_SELECTION.
- No passwords, clipboard access, arbitrary keyboard events, multiline entry, coordinate gestures, hidden typing, or app switching are exposed. A success result confirms Android accepted the accessibility action only, not that the app state changed.
- Pure policy and argument tests are added but not run. All tests/builds and S21 acceptance are queued for the unlocked Stage 8 consolidated validation.

## Capability ownership summary

The four optional capability families are onboarded in draft PRs #43–#46: active notification content, call-log reads, received-SMS inbox reads, and read-only USB descriptor inspection. PR #47 adds the user-mediated Wi-Fi settings handoff. These are CODE / VALIDATION PENDING only; tests/builds and S21 acceptance are queued for Stage 8. USB mass-storage files remain on SAF; arbitrary non-storage endpoint transfers and writes are deliberately unsupported. The broader capability set remains owned by Termux:API (bounded sensors, location, Wi-Fi connection/last-scan, NFC, infrared, camera metadata), native Kotlin (Bluetooth/GATT, speech, contacts, clipboard, notifications, telephony, media, biometrics, microphone/camera capture, wallpaper, USB descriptor inspection and settings handoffs), SAF (user-granted removable-storage files), AccessibilityService (bounded semantic inspection/actions/text input), and the unified shell router (general shell/path operations).

## Security and implementation rules

1. Provider discovery is observational and never prompts or mutates.
2. One capability cannot be declared by multiple providers or assigned multiple owners.
3. Availability is not authorization. Mutations require the existing approval boundary and fail
   closed on denial, timeout, or cancellation.
4. Path-based file mutations and SAF document mutations are separately scoped and approval-gated.
5. No root, broad package visibility, arbitrary generated-code execution, or device-specific hacks.
6. Do not change ownership based on assumptions about availability. Record device acceptance
   evidence for permission burden, data quality, reliability, latency, and resource impact.


## Native connectivity onboarding — Phase 6A draft

- Bluetooth bonded-device discovery and bounded BLE scanning are separate native Android
  capabilities with separate permission observations. The Gallery app requests BLUETOOTH_CONNECT
  for paired-device reads and BLUETOOTH_SCAN plus CONNECT for BLE scans, only after explicit
  one-time approval; discovery probes remain observational. BLE scan windows are bounded to 1–10
  seconds and stop scanning in a cancellation-safe cleanup path.
- USB host discovery uses UsbManager and reports bounded VID/PID/class/interface metadata. PR #46
  adds read-only standard descriptor retrieval after per-device Android permission and separate
  approval; connections close immediately. USB mass storage remains on SAF, not raw UsbManager I/O.
- Wi-Fi control is a user-mediated Settings panel/settings handoff in PR #47; the agent cannot silently toggle Wi-Fi.
- Phase 8A adds generic Bluetooth GATT service inspection through a short-lived native LE
  connection. Phase 8B adds one-shot characteristic reads/writes using full UUIDs, with reads
  returned as Base64 and writes limited to 512 bytes with exact payload approval. Each operation
  requires scoped approval and BLUETOOTH_CONNECT, uses a 5–20 second overall timeout, and closes
  the GATT client on success/failure/timeout/cancellation. No subscriptions or retained sessions
  are exposed. PR #46 adds descriptor-only USB inspection; arbitrary endpoint data transfers/writes remain unsupported.


## Native speech services (existing Gallery ownership)

- Speech input in chat is hold-to-dictate and resolves through the native `AsrServiceProvider`: Android
  platform SpeechRecognizer, ML Kit ASR, or MediaPipe SpeechProcessor. The microphone is user-started;
  there is no background recording or model-triggered microphone start.
- Speech output already uses `TtsServiceProvider`: Android platform TextToSpeech, ML Kit GenAI TTS,
  and MediaPipe TTS. Gallery owns engine/voice/language selection and chat playback. Agent-triggered
  `speak_text` now reuses the existing playback controller in draft PR #33; the user confirmed local tests/build passed, with device acceptance pending.
- The capability catalog assigns both speech capabilities to the native provider. Termux:API TTS
  and speech-to-text operations are removed from the Gallery model-facing Termux tool to prevent
  duplicate ownership. Agent-triggered TTS reuses the existing playback controller in PR #33.

## Native contacts and clipboard onboarding — Phase 9A draft

- Contacts use the Gallery app's ContactsContract provider. Search is query-based and bounded to 20
  results, with at most three phone numbers and three email addresses per contact. READ_CONTACTS
  is requested only after scoped approval; provider discovery never prompts.
- Clipboard reads use ClipboardManager and are allowed only while Gallery is foregrounded. The
  implementation reads plain-text items only, returns at most five items and 8192 characters,
  and requires explicit approval. Clipboard writes are limited to 2048 characters, require
  foreground state and explicit approval displaying the exact text. No URI, image, intent, or
  background clipboard access is exposed.

## Native media ownership rebalance — Phase 6B draft

- Camera metadata remains a bounded Termux:API query. Photo capture is native and writes to a
  Gallery-owned MediaStore URI through a user-mediated camera activity; no Termux-private output
  path is involved.
- Wallpaper changes are native through WallpaperManager and accept only Gallery-readable image
  content URIs with an explicit SYSTEM/LOCK/BOTH target. This keeps content URI permissions under
  Gallery's identity and avoids passing paths or URLs into Termux:API.


## Gallery media discovery, visual indexing, and playback — Wave 3E draft

- `android_control.media_search` searches MediaStore-visible photos/videos by filename and searches persisted user-granted SAF trees (including USB/OTG storage) by filename. AUTO combines filename results with semantic visual matches; NAME is filename-only; VISUAL requests semantic search. Results are capped at 20 and expose short-lived opaque handles only—never raw content URIs or filesystem paths.
- Visual search reuses the existing EmbeddingGemma-2 model, Smart Album user vector database, and `SemanticRetrievalService`. It does not load the chat LLM to inspect each video and does not create a second embedding model or scanner. Videos use the existing Smart Album keyframe indexing path.
- `media_index` requires one-time scoped approval describing permitted MediaStore media and persisted read-granted SAF scopes; its result reports visible MediaStore count and discovered SAF file count. It reuses `SmartAlbumIndexingWorker`, indexing permitted MediaStore photos/videos, media previously selected through Gallery's photo picker, and up to 1,000 discovered media documents total from granted SAF trees per pass. Subsequent runs skip already indexed SAF document URIs and advance through the bounded scan. SAF traversal is capped at depth 8 and 10,000 visited nodes. Media stays on-device.
- SAF document URIs are resolved only beneath persisted tree grants. The photo-library URI restoration path reads MIME type and display name from the provider so USB videos remain recognized as videos after process restart.
- `media_play` accepts only a fresh opaque handle from the latest search result, requires one-time approval for the exact video, and opens Gallery's own Media3 PlayerView/ExoPlayer dialog. It does not dispatch arbitrary ACTION_VIEW intents. Image playback is not exposed by this operation. This slice covers photos/videos; audio discovery and playback are not included.
- Android MediaStore permission gaps are reported; Gallery does not silently broaden access. A user-granted SAF tree remains independently searchable when MediaStore access is unavailable.
- Active media-session discovery/control is a separate capability from Gallery-owned playback. PR #37 declares a `NotificationListenerService` solely so Android can authorize `MediaSessionManager.getActiveSessions()`. The user must manually enable Gallery in Android's notification-listener settings; provider status reports `PERMISSION_REQUIRED` until then. The service does not inspect, store, or summarize notifications. Session listing is capped at 10, excludes Gallery's own player, and requires one-time approval. Results expose app labels and bounded track metadata plus two-minute opaque handles, never package names or session tokens.
- `media_sessions.control` supports only PLAY, PAUSE, NEXT, PREVIOUS, and SEEK when advertised by the active session. It rechecks the active session and supported action immediately before dispatch, requires one-time approval, and consumes the handle after successful dispatch. SEEK requires a known duration and a position within that duration. The result confirms command dispatch only, not that playback changed.
- `media_sessions.open_settings` opens Android notification-listener settings only after an explicit tool request and one-time approval; opening settings does not grant access. No runtime permission is requested and no notification content is read.

## Native microphone ownership — Phase 6C draft

- Bounded microphone-to-file recording is native through MediaRecorder and MediaStore. Gallery
  requests RECORD_AUDIO only after one-time scoped approval, records 1–30 seconds, publishes an
  app-owned audio content URI, and stops/releases the recorder on cancellation. The output remains
  local; it is not automatically uploaded or transcribed.


## SAF document-tree implementation — Phase 7A draft

- Gallery owns a dedicated `document_tree` tool backed by DocumentsContract and user-persisted
  tree grants. It supports grant listing, folder picker, bounded listing, metadata, text read/write,
  one-step generated text-file creation, directory creation, rename, delete, same-tree copy/move,
  cross-tree file copy/move between separately granted folders, and bounded name-only search.
  Cross-tree transfer is file-only and capped at 100 MiB; a move deletes the source only after the
  destination copy completes.
- Tree access is scoped to exact persisted Gallery URI grants. Model-supplied raw paths and arbitrary
  URIs are rejected. Text I/O is capped at 256 KiB and listings at 100 entries. Search is limited to
  500 visited entries, depth 4, and 50 results; it never searches file contents. General metadata
  writes remain unsupported.
- Attached USB mass storage is accessed through SAF, not through UsbManager bulk transfers. UsbManager
  is used for peripheral enumeration and read-only descriptor inspection; arbitrary non-storage endpoint
  transfers and writes remain unsupported until a safe protocol-specific contract exists.

## Native telephony onboarding — Wave 3 draft

- Telephony metadata is deliberately limited to whether the device exposes telephony, phone type, and SIM state. No subscriber IDs, phone numbers, IMEI/MEID, carrier identifiers, cell IDs, or location are read.
- ACTION_DIAL opens the system dialer after scoped approval. The user reviews the destination and initiates the call; no CALL_PHONE permission is requested.
- ACTION_SENDTO with an smsto: URI opens a prefilled SMS composer after scoped approval showing the exact recipient and message. The user presses Send; no SEND_SMS permission is requested and the app does not send directly.
- PR #44 adds bounded call-log reads and PR #45 adds bounded received-SMS inbox reads. Each requires explicit user intent, one-time approval, and the corresponding Android runtime permission requested only after approval; Android installer/default-role restrictions fail closed.


## Biometric and Android Keystore onboarding — Wave 3 draft

- Biometric authentication is presented by AndroidX BiometricPrompt from the foreground Gallery Activity through the existing Agent Chat action channel. The provider adapter does not attempt to display UI from its application context.
- Only strong biometric authentication is accepted. The model receives a boolean authentication result only; no biometric image, template, fingerprint/face data, or sensor identifiers are exposed.
- Android Keystore aliases are app-namespaced and restricted to 1–32 lowercase letters, digits, underscores, and hyphens. Key material is non-exportable.
- Keys use AES-256/GCM and require strong biometric authentication for each encrypt/decrypt operation. New biometric enrollment invalidates existing keys.
- Encryption returns an IV and ciphertext as Base64. Plaintext is processed in memory only; decrypt returns plaintext to the local agent and does not persist it.
- Key creation, listing, deletion, encryption, and decryption require scoped approval. Deleting a key permanently prevents recovery of ciphertext encrypted with it.
- Plaintext is limited to 4,096 characters. IV and ciphertext lengths are bounded and validated. No CALL_PHONE, SEND_SMS, or additional Android runtime permission is introduced by this slice.

## Downloads, media, sharing, and TTS integration — Wave 3E (downloads in PR #34; media in PR #35; sharing in PR #36)

- Reuse Gallery's existing WorkManager-backed `DefaultDownloadRepository` and `DownloadWorker`; do not introduce Android DownloadManager or a parallel queue.
- The agent reuses Gallery's WorkManager-backed repository. `download_status` returns at most 20 active Gallery downloads with opaque handles, state/phase, received/total bytes, and bounded optional rate/ETA. It does not expose WorkManager IDs, raw tags, worker data, URLs, tokens, or paths.
- Display names resolve only through Gallery's currently loaded allowlist catalog; imported or unknown jobs use the generic label “Gallery model download”.
- `cancel_download` accepts only a fresh handle from the latest status result, consumes it once, revalidates the exact active Gallery job and its ownership tags, marks observed work as user-cancelled, and cancels only that WorkManager UUID. The operation requires the existing one-time scoped approval, which shows the model label, phase, and exact opaque download reference. Model-wide cancellation is not used.
- Unit tests cover status without approval, rejection of handles not first issued by status, exact approval, and cancellation denial. The user confirmed PR #34 local tests and Shizuku debug build passed; device acceptance remains pending.
- [x] PR #35 reuses the existing MediaStore/SAF access boundary, EmbeddingGemma-2 Smart Album vector database/indexing worker, and Gallery-owned Media3 player; no duplicate scanner, embedder, or external ACTION_VIEW playback path is introduced.
- Active media-session control is distinct from playback initiated by Gallery. PR #37 implements the opt-in flow and bounded operations described above; special access is never silently enabled or requested. Report `PERMISSION_REQUIRED` until the user manually grants it.
- `share_content.share_text` accepts 1–4,000 characters and requires one-time approval displaying the exact text. It opens `ACTION_SEND` with `text/plain`; the system chooser is the only receiver-selection path.
- `share_content.share_file` accepts only a `tree_uri` and `document_id` from the existing `document_tree` grant/list flow. It rechecks Gallery's persisted read grant, queries metadata, rejects directories/unknown sizes/files over 25 MiB, and requires one-time approval showing the exact file name, MIME type, and size. It opens `ACTION_SEND` with the SAF document URI, `ClipData`, and temporary `FLAG_GRANT_READ_URI_PERMISSION`; no persistent grant is added.
- The share action returns only that the chooser was launched, not that the receiver accepted or delivered content. No SMS/email app is targeted, no direct send is performed, and no arbitrary model-supplied URI/path is accepted.
- Agent-triggered speech must use Gallery's existing TtsServiceProvider and playback lifecycle, preserving persisted engine/voice/language selection. No duplicate TTS implementation or Termux TTS model-facing route.
- Discovery is observational; mutations require scoped approval and bounded inputs/results. Device acceptance remains deferred until onboarding is complete.
