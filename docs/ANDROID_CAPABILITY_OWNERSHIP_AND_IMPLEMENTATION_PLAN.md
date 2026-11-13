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

Status values: **Implemented** means present in the current implementation branch; **Planned** means
identified but not yet implemented as an MCP capability. Shell commands that can perform an
operation do not count as a dedicated native capability implementation.

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
| Bluetooth discovery / bonded devices | Native Kotlin (BluetoothAdapter / BLUETOOTH_CONNECT) | Phase 6A draft; scoped approval and CONNECT runtime permission request |
| Bluetooth LE scan | Native Kotlin (BluetoothLeScanner / BLUETOOTH_SCAN + CONNECT) | Phase 6A draft; separate capability ID, 1–10 second low-power scan, bounded results, cancellation-safe stop |
| Bluetooth GATT operations | Native Kotlin (BluetoothGatt) | Phase 8A–8B draft: short-lived connection, bounded service/characteristic discovery, one-shot characteristic read/write by full UUID, 512-byte value cap, explicit approval and cancellation-safe close |

| NFC read/write | Termux:API (termux-nfc) | Planned; basic NDEF read/write; foreground/tag interaction requires device verification |
| Infrared capability / transmit | Termux:API (termux-infrared-frequencies / termux-infrared-transmit) | Planned; bounded carrier frequency and pulse pattern |
| USB host device discovery / permission / I/O | Native Kotlin (UsbManager) | Planned |
| App discovery | Native Kotlin (PackageManager launcher queries) | Implemented |
| App launch | Native Kotlin (launch intents) | Implemented |
| URL open | Native Kotlin (ACTION_VIEW) | Implemented |
| Contacts read | Native Kotlin (ContactsContract) | Phase 9A draft: scoped name/text search, READ_CONTACTS requested only after approval, maximum 20 results with at most 3 phone numbers and 3 email addresses each |
| Call-log read | Native Kotlin (CallLog provider) | Planned; restricted permission and privacy review |
| Telephony/device/network metadata | Native Kotlin (TelephonyManager) | Planned; return only permitted, non-secret metadata |
| SMS inbox/list | Native Kotlin (Telephony provider where permitted) | Planned; permission/role restrictions may make this unavailable |
| SMS compose/send | Native Kotlin (ACTION_SENDTO composer) | Planned; user-mediated send, no silent bulk sending |
| Place a phone call | Native Kotlin (ACTION_DIAL) | Planned; user confirms call in dialer |
| Clipboard read/write | Native Kotlin (ClipboardManager) | Phase 9A draft: foreground-only text reads (5 items / 8192 characters), writes capped at 2048 characters, both require scoped approval |
| Notifications created by this app | Native Kotlin (NotificationManager) | Planned |
| Notification listener / read other apps' notifications | Native Kotlin NotificationListenerService | Planned; explicit user-enabled special access |
| Notification channel management | Native Kotlin (NotificationManager) | Planned |
| Camera info | Termux:API (termux-camera-info) | Phase 5D draft; fixed read-only metadata query |
| Camera photo capture | Native Kotlin (MediaStore + user-mediated camera intent) | Phase 6B draft; app-owned pending URI, explicit approval, publish only after successful capture |
| Microphone recording to file | Native Kotlin (MediaRecorder + MediaStore) | Phase 6C draft; 1–30 seconds, app-owned audio URI, RECORD_AUDIO requested only after scoped approval, cancellation-safe stop/release |
| Basic speech recognition | Native Kotlin (Gallery AsrServiceProvider: Android SpeechRecognizer, ML Kit, MediaPipe) | Phase 9A: existing hold-to-dictate UI already uses native ASR engines; model-initiated recording is intentionally not exposed |
| Text-to-speech / engine information | Native Kotlin (Gallery TtsServiceProvider: Android platform, ML Kit GenAI, MediaPipe) | Existing native engine selection, voice management, and chat playback; model-triggered speech action remains a separate integration task |
| Biometric authentication | Native Kotlin (BiometricPrompt) | Planned; authentication result only, never expose biometric data |
| Secure key operations | Native Kotlin (Android Keystore) | Planned; keys remain non-exportable |
| Download management | Native Kotlin (DownloadManager or app-owned downloader) | Planned |
| Media scan / index | Native Kotlin (MediaScannerConnection / MediaStore) | Planned |
| Media playback initiated by the app | Native Kotlin (existing media stack or platform MediaPlayer) | Planned |
| Active media-session control | Native Kotlin (MediaSessionManager) | Planned; notification-listener access may be required |
| Share text/files | Native Kotlin (ACTION_SEND / chooser) | Planned; user-mediated |
| Wallpaper set | Native Kotlin (WallpaperManager + content URI) | Phase 6B draft; Gallery-readable image URI only, SYSTEM/LOCK/BOTH target allowlist, explicit approval |
| Background scheduled work | Native Kotlin (WorkManager) | Planned; bounded, user-visible scheduling |
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
| camera-info, camera-photo | Termux:API for basic still-photo capture; native Camera2 reserved for richer sessions |
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
| notification, notification-channel, notification-list, notification-remove | Native Kotlin NotificationManager / NotificationListenerService |
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
| wallpaper | Termux:API for basic file-based wallpaper setting |
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
- [ ] Implement native Bluetooth GATT sessions and USB host permission/open/read/write sessions;
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


### Wave 4A — SAF document-tree core (draft code; acceptance pending)

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

