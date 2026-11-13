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
| Bluetooth discovery / bonded devices | Native Kotlin (BluetoothAdapter / permissions) | Planned |
| Bluetooth LE scan and GATT operations | Native Kotlin (BluetoothLeScanner / BluetoothGatt) | Planned; generic profiles, no device-specific hacks |
| NFC read/write | Termux:API (termux-nfc) | Planned; basic NDEF read/write; foreground/tag interaction requires device verification |
| Infrared capability / transmit | Termux:API (termux-infrared-frequencies / termux-infrared-transmit) | Planned; bounded carrier frequency and pulse pattern |
| USB host device discovery / permission / I/O | Native Kotlin (UsbManager) | Planned |
| App discovery | Native Kotlin (PackageManager launcher queries) | Implemented |
| App launch | Native Kotlin (launch intents) | Implemented |
| URL open | Native Kotlin (ACTION_VIEW) | Implemented |
| Contacts read | Native Kotlin (ContactsContract) | Planned; explicit runtime permission |
| Call-log read | Native Kotlin (CallLog provider) | Planned; restricted permission and privacy review |
| Telephony/device/network metadata | Native Kotlin (TelephonyManager) | Planned; return only permitted, non-secret metadata |
| SMS inbox/list | Native Kotlin (Telephony provider where permitted) | Planned; permission/role restrictions may make this unavailable |
| SMS compose/send | Native Kotlin (ACTION_SENDTO composer) | Planned; user-mediated send, no silent bulk sending |
| Place a phone call | Native Kotlin (ACTION_DIAL) | Planned; user confirms call in dialer |
| Clipboard read/write | Native Kotlin (ClipboardManager) | Planned; foreground/Android clipboard privacy restrictions |
| Notifications created by this app | Native Kotlin (NotificationManager) | Planned |
| Notification listener / read other apps' notifications | Native Kotlin NotificationListenerService | Planned; explicit user-enabled special access |
| Notification channel management | Native Kotlin (NotificationManager) | Planned |
| Camera info / basic photo capture | Termux:API (termux-camera-info / termux-camera-photo) | Planned; still JPEG capture to an approved path; advanced sessions remain native if needed |
| Microphone recording to file | Termux:API (termux-microphone-record) | Planned; explicit approval, bounded duration, output path, and stop/cleanup |
| Basic speech recognition | Termux:API (termux-speech-to-text) | Planned; native only if on-device/lifecycle control is required |
| Text-to-speech / engine information | Termux:API (termux-tts-engines / termux-tts-speak) | Planned; native only for Gallery-owned playback/session control |
| Biometric authentication | Native Kotlin (BiometricPrompt) | Planned; authentication result only, never expose biometric data |
| Secure key operations | Native Kotlin (Android Keystore) | Planned; keys remain non-exportable |
| Download management | Native Kotlin (DownloadManager or app-owned downloader) | Planned |
| Media scan / index | Native Kotlin (MediaScannerConnection / MediaStore) | Planned |
| Media playback initiated by the app | Native Kotlin (existing media stack or platform MediaPlayer) | Planned |
| Active media-session control | Native Kotlin (MediaSessionManager) | Planned; notification-listener access may be required |
| Share text/files | Native Kotlin (ACTION_SEND / chooser) | Planned; user-mediated |
| Wallpaper set/read | Termux:API (termux-wallpaper) | Planned; basic file-based wallpaper setting; explicit approval |
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
| microphone-record | Termux:API for bounded file recording; native for live/in-app audio capture |
| nfc | Termux:API for basic NDEF read/write; native for foreground reader-mode integration |
| notification, notification-channel, notification-list, notification-remove | Native Kotlin NotificationManager / NotificationListenerService |
| sensor | Termux:API for bounded reads/streams with explicit cleanup |
| share | Native Kotlin Android share intents |
| sms-inbox, sms-list, sms-send | Native Kotlin telephony/content APIs; sending remains user-mediated |
| speech-to-text | Termux:API for basic recognition; native for on-device/lifecycle-specific control |
| storage-get, saf-* | Native Kotlin Storage Access Framework |
| telephony-call, telephony-cellinfo, telephony-deviceinfo | Native Kotlin dialer intents / TelephonyManager |
| toast | Existing Agent Chat UI feedback; not a device capability tool |
| tts-engines, tts-speak | Termux:API for ordinary speech; native for app-owned playback sessions |
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
- [ ] Remaining Termux:API operations: NFC, infrared, basic camera capture, file-based microphone
  recording, speech-to-text, TTS, and wallpaper.
- [ ] Implement native Bluetooth discovery/BLE/GATT and USB host sessions; keep Wi-Fi changes
  user-mediated through Android Settings.
- [ ] Add per-command companion/package/permission probes without prompting.
- Bound stream duration, output, and process lifetime; sensor and microphone operations need
  explicit cancellation and cleanup semantics.
- Use generic Bluetooth and USB abstractions; no device-specific names or profiles.

### Wave 3 — Gallery-owned and privacy-sensitive integrations
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
