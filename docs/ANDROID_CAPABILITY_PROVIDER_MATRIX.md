# Android capability provider matrix

The canonical inventory, provider ownership decisions, Termux:API command disposition, security
contracts, end-to-end roadmap, and live progress ledger are maintained in
[ANDROID_CAPABILITY_OWNERSHIP_AND_IMPLEMENTATION_PLAN.md](ANDROID_CAPABILITY_OWNERSHIP_AND_IMPLEMENTATION_PLAN.md).

## Current progress snapshot (2026-11-17)

- Active branch: `wave-3e-media-library-playback`, stacked on `wave-3e-gallery-download-management`.
- Active PR: [#35 — Wave 3E: add media discovery, visual indexing, and Gallery playback](https://github.com/bv9522/gallery/pull/35), draft/open/unmerged, stacked on [PR #34 — safe Gallery download controls](https://github.com/bv9522/gallery/pull/34); [PR #33 — Gallery TTS](https://github.com/bv9522/gallery/pull/33) remains open beneath the stack.
- Agent `speak_text` is implemented through Gallery's existing `TtsServiceProvider` and playback controller; user confirmed `:app:testShizukuDebugUnitTest` and `:app:assembleShizukuDebug` both completed with `BUILD SUCCESSFUL` on head `7ac3bdb3904ea105ee7dad6574f8b92a40230a5b`. Device acceptance remains pending.
- PR #34 reuses `DefaultDownloadRepository` / `DownloadWorker` / WorkManager and adds bounded snapshots, allowlist-backed labels, opaque handles, exact-worker cancellation, scoped approval, and unit tests. The user confirmed its local tests and Shizuku debug build passed; device acceptance remains pending.
- PR #35 adds bounded filename/visual search, EmbeddingGemma-2 / Smart Album index reuse, SAF-granted storage discovery/indexing, and Gallery-owned Media3 video playback. Tests are added but have not been executed.
- PR #30, #31, and #32 have user-confirmed local unit-test and debug-build validation; device acceptance remains pending.
- **Validation policy:** per the owner's instruction, do not run tests, Gradle builds, or device acceptance per slice. Add tests as implementation proceeds and execute the accumulated suite/build only after all capability onboarding is complete.
- PR #23–#29 and #16–#27 still require consolidated S21 acceptance recorded in the master roadmap.
- Consolidated S21 acceptance is intentionally gated until capability onboarding and the ownership audit are complete.

## Single-owner policy

Every model-independent capability has exactly one selected owner in the capability catalog:

| Scope | Owner | Boundary |
|---|---|---|
| Typed/app-owned Android operations | Native Android Kotlin | Gallery app UID; typed APIs; richer lifecycle, app-owned state, and explicit approvals |
| Bounded Android command capabilities | Termux:API | Fixed allowlisted commands through supervised Termux RUN_COMMAND; companion permissions and process boundary |
| Path-based file and directory operations | Unified shell router | Existing TERMUX / SHIZUKU / TERMUX_RISH execution backends; no duplicate file tool |
| User-granted document trees and removable storage files | Android Storage Access Framework | Persisted user-granted document URI permissions |
| Screen capture and semantic UI interaction | Android AccessibilityService | Explicitly enabled service; bounded screen/tree/gesture operations |

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

The capability onboarding chain through Wave 3D consists of open draft PRs #23–#32. SAF document-tree and cross-tree file operations are in PRs #23–#25; Bluetooth GATT inspection/I/O is in #26–#27; contacts and clipboard plus speech ownership correction are in #28; ownership/readiness reconciliation is in #29; Gallery-owned notifications and scheduling are in #30; telephony metadata and user-mediated dialer/SMS composition are in #31. Biometric authentication and Gallery-owned Keystore are in draft PR #32; the user confirmed `:app:testShizukuDebugUnitTest` and `:app:assembleShizukuDebug` both passed after the FragmentActivity host correction. Device acceptance remains pending. Wave 3E now includes downloads/status/cancellation (PR #34), media search/indexing/Gallery-owned playback (draft PR #35), and user-mediated sharing still planned. The `speak_text` operation reuses the existing TTS playback controller. Per-slice tests/builds are deferred by owner instruction until all capability onboarding is complete. Consolidated S21 device acceptance remains deferred until onboarding is complete.

The capability catalog assigns exactly one owner to every declared capability. This is enforced by catalog construction and registry ownership tests; it does not imply that every owner is implemented or available.

## Native notifications and scheduling — Phase 9B draft

- Reuses Gallery's existing AlarmManager/DataStore scheduler, scheduled-notification receiver (which re-arms daily reminders at local wall-clock time), boot recovery receiver, and PendingIntent helper; no second scheduling subsystem is introduced.
- `android_control` exposes approved immediate notification posting, bounded active-notification listing/dismissal, scheduled reminder creation/listing/cancellation, and bounded channel listing/creation. Mutations require scoped approval; Android 13+ notification permission is requested only after approval.
- Provider discovery reports permission/global-notification state without prompting. Blocked channels and globally disabled notifications are explicit failures. Active notification and channel listings are bounded; at most 50 reminders are persisted. Schedule persistence and alarm updates are serialized, and boot recovery reads authoritative DataStore state.
- Reading notifications from other apps remains unsupported and separate; it requires NotificationListenerService special access and is not silently enabled by this phase.

## Remaining capability groups

Termux:API is selected for basic sensors, location requests/updates, Wi-Fi information and last-scan
results, NFC NDEF read/write, infrared, and read-only camera metadata. Native Kotlin owns Bluetooth/BLE,
USB host sessions, Gallery speech input/output, contacts, clipboard, app-owned/security-sensitive
integrations, camera capture, microphone recording, wallpaper, and user-mediated controls. The SAF
provider owns removable-storage files. USB host enumeration is draft code; raw USB device I/O remains
planned. Gallery-owned notification posting/scheduling is implemented in draft PR #30 and has user-confirmed
local unit-test/build validation; device acceptance remains pending. The current Wave 3 telephony slice
adds non-identifying telephony metadata and user-mediated dialer/SMS composer actions; call-log and SMS
inbox reads remain planned.

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
- USB host discovery uses UsbManager and reports bounded VID/PID/class/interface metadata. It does
  not open a device, request USB device permission, or treat USB mass storage as a raw filesystem.
- Phase 8A adds generic Bluetooth GATT service inspection through a short-lived native LE
  connection. Phase 8B adds one-shot characteristic reads/writes using full UUIDs, with reads
  returned as Base64 and writes limited to 512 bytes with exact payload approval. Each operation
  requires scoped approval and BLUETOOTH_CONNECT, uses a 5–20 second overall timeout, and closes
  the GATT client on success/failure/timeout/cancellation. No subscriptions or retained sessions
  are exposed. USB device I/O remains planned.


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
- `media_index` requires one-time scoped approval describing permitted MediaStore media and persisted read-granted SAF scopes; its result reports visible MediaStore count and discovered SAF file count. It reuses `SmartAlbumIndexingWorker`, indexing permitted MediaStore photos/videos and up to 1,000 discovered media documents total from granted SAF trees per pass. SAF traversal is capped at depth 8 and 10,000 visited nodes. Media stays on-device.
- SAF document URIs are resolved only beneath persisted tree grants. The photo-library URI restoration path reads MIME type and display name from the provider so USB videos remain recognized as videos after process restart.
- `media_play` accepts only a fresh opaque handle from the latest search result, requires one-time approval for the exact video, and opens Gallery's own Media3 PlayerView/ExoPlayer dialog. It does not dispatch arbitrary ACTION_VIEW intents. Image playback is not exposed by this operation.
- Android MediaStore permission gaps are reported; Gallery does not silently broaden access. A user-granted SAF tree remains independently searchable when MediaStore access is unavailable.
- Active media-session discovery/control is still a separate capability and is not implemented by Gallery-owned playback. Any future notification-listener special access remains opt-in and user-granted.

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
  is only used for peripheral enumeration and future non-storage USB protocols.

## Native telephony onboarding — Wave 3 draft

- Telephony metadata is deliberately limited to whether the device exposes telephony, phone type, and SIM state. No subscriber IDs, phone numbers, IMEI/MEID, carrier identifiers, cell IDs, or location are read.
- ACTION_DIAL opens the system dialer after scoped approval. The user reviews the destination and initiates the call; no CALL_PHONE permission is requested.
- ACTION_SENDTO with an smsto: URI opens a prefilled SMS composer after scoped approval showing the exact recipient and message. The user presses Send; no SEND_SMS permission is requested and the app does not send directly.
- Call-log and SMS inbox reads remain unimplemented pending separate sensitive-permission, default-role, and policy review.


## Biometric and Android Keystore onboarding — Wave 3 draft

- Biometric authentication is presented by AndroidX BiometricPrompt from the foreground Gallery Activity through the existing Agent Chat action channel. The provider adapter does not attempt to display UI from its application context.
- Only strong biometric authentication is accepted. The model receives a boolean authentication result only; no biometric image, template, fingerprint/face data, or sensor identifiers are exposed.
- Android Keystore aliases are app-namespaced and restricted to 1–32 lowercase letters, digits, underscores, and hyphens. Key material is non-exportable.
- Keys use AES-256/GCM and require strong biometric authentication for each encrypt/decrypt operation. New biometric enrollment invalidates existing keys.
- Encryption returns an IV and ciphertext as Base64. Plaintext is processed in memory only; decrypt returns plaintext to the local agent and does not persist it.
- Key creation, listing, deletion, encryption, and decryption require scoped approval. Deleting a key permanently prevents recovery of ciphertext encrypted with it.
- Plaintext is limited to 4,096 characters. IV and ciphertext lengths are bounded and validated. No CALL_PHONE, SEND_SMS, or additional Android runtime permission is introduced by this slice.

## Downloads, media, sharing, and TTS integration — Wave 3E (downloads in PR #34; media in PR #35)

- Reuse Gallery's existing WorkManager-backed `DefaultDownloadRepository` and `DownloadWorker`; do not introduce Android DownloadManager or a parallel queue.
- The agent reuses Gallery's WorkManager-backed repository. `download_status` returns at most 20 active Gallery downloads with opaque handles, state/phase, received/total bytes, and bounded optional rate/ETA. It does not expose WorkManager IDs, raw tags, worker data, URLs, tokens, or paths.
- Display names resolve only through Gallery's currently loaded allowlist catalog; imported or unknown jobs use the generic label “Gallery model download”.
- `cancel_download` accepts only a fresh handle from the latest status result, consumes it once, revalidates the exact active Gallery job and its ownership tags, marks observed work as user-cancelled, and cancels only that WorkManager UUID. The operation requires the existing one-time scoped approval, which shows the model label, phase, and exact opaque download reference. Model-wide cancellation is not used.
- Unit tests cover status without approval, rejection of handles not first issued by status, exact approval, and cancellation denial. The user confirmed PR #34 local tests and Shizuku debug build passed; device acceptance remains pending.
- [x] PR #35 reuses the existing MediaStore/SAF access boundary, EmbeddingGemma-2 Smart Album vector database/indexing worker, and Gallery-owned Media3 player; no duplicate scanner, embedder, or external ACTION_VIEW playback path is introduced.
- Active media-session control is distinct from playback initiated by Gallery. Notification-listener special access must never be silently enabled; report unavailable until the user grants it.
- Text/file sharing uses ACTION_SEND and the Android chooser. The user selects the receiving app and completes the action.
- Agent-triggered speech must use Gallery's existing TtsServiceProvider and playback lifecycle, preserving persisted engine/voice/language selection. No duplicate TTS implementation or Termux TTS model-facing route.
- Discovery is observational; mutations require scoped approval and bounded inputs/results. Device acceptance remains deferred until onboarding is complete.
