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

1. **Native Android Kotlin** owns typed Android framework capabilities when the app can perform
   the operation directly with a well-defined Android API and permission contract.
2. **Android AccessibilityService** owns semantic screen inspection and UI interaction.
3. **Android Storage Access Framework (SAF)** owns user-granted document trees and removable
   storage documents. It is not a replacement for path-based shell operations.
4. **Unified shell router** owns general shell execution and path-based filesystem operations.
   Shizuku/Rish is the preferred execution identity for broad shared-storage paths when available;
   Termux shell remains useful inside its own environment. These are execution backends, not
   separate model-facing implementations of every filesystem operation.
5. **Termux:API** is not automatically selected merely because a command exists. Its commands
   wrap Android framework operations and often overlap native Kotlin. It should only own a
   capability if a documented comparison shows a concrete advantage that justifies its extra
   companion-app/process boundary. The Phase 4A overlap commands are transitional comparison
   code, not a reason to maintain duplicate production providers.
6. **One capability, one owner.** Do not register duplicate native/Termux:API routes for the same
   operation. Distinct scopes (for example, path-based files vs user-granted document trees)
   must have distinct capability names and contracts.
7. Discovery is observational only. It never prompts for permissions or mutates state.
8. Mutations require explicit, scoped approval; destructive file operations require stronger
   confirmation than reversible controls. Cancellation, timeout, and denial fail closed.
9. Every operation has a typed result contract, bounded output, explicit units, and deterministic
   validation before the model sees it.
10. Do not add root requirements, arbitrary generated-code execution, or new background services
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
| Sensors and bounded sensor streams | Native Kotlin (SensorManager) | Planned |
| Location and bounded location updates | Native Kotlin (LocationManager) | Planned; foreground permission and duration limits |
| Wi-Fi connection state | Native Kotlin (ConnectivityManager / WifiManager) | Planned |
| Wi-Fi scan results | Native Kotlin (WifiManager) | Planned; Android permission and scan-throttling constraints |
| Wi-Fi enable/disable | Native Kotlin user-mediated settings panel | Planned; no silent toggle where Android forbids it |
| Bluetooth discovery / bonded devices | Native Kotlin (BluetoothAdapter / permissions) | Planned |
| Bluetooth LE scan and GATT operations | Native Kotlin (BluetoothLeScanner / BluetoothGatt) | Planned; generic profiles, no device-specific hacks |
| NFC read/write | Native Kotlin (NfcAdapter) | Planned; tag operations require explicit user intent |
| Infrared capability / transmit | Native Kotlin (ConsumerIrManager) | Planned; report unsupported when no IR emitter exists |
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
| Camera info / photo capture | Native Kotlin (Camera2; reuse existing app camera dependencies if suitable) | Planned |
| Microphone recording | Native Kotlin (MediaRecorder / AudioRecord) | Planned; explicit permission and recording indicator |
| Speech recognition | Native Kotlin (SpeechRecognizer; prefer on-device recognizer when available) | Planned |
| Text-to-speech / engine information | Native Kotlin (TextToSpeech) | Planned |
| Biometric authentication | Native Kotlin (BiometricPrompt) | Planned; authentication result only, never expose biometric data |
| Secure key operations | Native Kotlin (Android Keystore) | Planned; keys remain non-exportable |
| Download management | Native Kotlin (DownloadManager or app-owned downloader) | Planned |
| Media scan / index | Native Kotlin (MediaScannerConnection / MediaStore) | Planned |
| Media playback initiated by the app | Native Kotlin (existing media stack or platform MediaPlayer) | Planned |
| Active media-session control | Native Kotlin (MediaSessionManager) | Planned; notification-listener access may be required |
| Share text/files | Native Kotlin (ACTION_SEND / chooser) | Planned; user-mediated |
| Wallpaper set/read | Native Kotlin (WallpaperManager) | Planned; explicit approval |
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
| camera-info, camera-photo | Native Kotlin Camera2 |
| clipboard-get, clipboard-set | Native Kotlin ClipboardManager |
| contact-list | Native Kotlin ContactsContract |
| dialog | Existing Agent Chat/UI interaction; not a device capability tool |
| download | Native Kotlin DownloadManager |
| fingerprint | Native Kotlin BiometricPrompt |
| infrared-frequencies, infrared-transmit | Native Kotlin ConsumerIrManager |
| job-scheduler | Native Kotlin WorkManager |
| keystore | Native Kotlin Android Keystore |
| location | Native Kotlin LocationManager |
| media-player, media-scan | Native Kotlin media APIs |
| microphone-record | Native Kotlin MediaRecorder / AudioRecord |
| nfc | Native Kotlin NfcAdapter |
| notification, notification-channel, notification-list, notification-remove | Native Kotlin NotificationManager / NotificationListenerService |
| sensor | Native Kotlin SensorManager |
| share | Native Kotlin Android share intents |
| sms-inbox, sms-list, sms-send | Native Kotlin telephony/content APIs; sending remains user-mediated |
| speech-to-text | Native Kotlin SpeechRecognizer |
| storage-get, saf-* | Native Kotlin Storage Access Framework |
| telephony-call, telephony-cellinfo, telephony-deviceinfo | Native Kotlin dialer intents / TelephonyManager |
| toast | Existing Agent Chat UI feedback; not a device capability tool |
| tts-engines, tts-speak | Native Kotlin TextToSpeech |
| usb | Native Kotlin UsbManager |
| vibrate | Native Kotlin Vibrator / VibratorManager |
| wallpaper | Native Kotlin WallpaperManager |
| wifi-connectioninfo, wifi-enable, wifi-scaninfo | Native Kotlin ConnectivityManager / WifiManager and user-mediated settings |
| api-start, api-stop, api-broadcast, callback | Termux:API transport internals; not model-facing capabilities |

The command inventory is based on the official Termux API package scripts:
https://github.com/termux/termux-api-package/tree/master/scripts

This disposition intentionally chooses native Kotlin for Android framework operations because it
provides typed APIs, direct permission handling, deterministic results, fewer cross-process hops,
and avoids depending on Gemma to reconstruct command JSON. It does not claim that every API is
available on every Android device or that every permission is granted.

## Implementation waves

### Wave 1 — Capability vocabulary and ownership
- Expand AndroidCapability into stable, granular capability IDs.
- Add one owner and permission/availability contract for each capability.
- Separate path-based shell file operations from SAF document-tree operations.
- Remove duplicate Termux:API routes from the selected capability registry.
- Keep Phase 4A Termux:API code isolated as transitional comparison/diagnostic code until the
  native ownership is implemented and the redundant model-facing operations can be retired.
- Add catalog completeness tests: every capability has exactly one selected owner; every owner
  declares the capability; no duplicate route priorities or accidental duplicate owners.

### Wave 2 — Native system and connectivity
- [x] Audio device information, brightness, vibration, bounded wake lock (Phase 4C first slice).
- [ ] Sensors, location, Wi-Fi, Bluetooth, NFC, infrared, USB.
- Implement observational probes separately from mutations.
- Use generic Bluetooth and USB abstractions; no device-specific names or profiles.

### Wave 3 — Privacy-sensitive system integrations
- Contacts, call logs, telephony metadata, SMS composer/inbox where platform policy permits.
- Clipboard, notifications/listener.
- Camera, microphone, speech recognition, TTS, biometric prompt, keystore.
- Downloads, media scan/playback, sharing, wallpaper, scheduled work.

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
