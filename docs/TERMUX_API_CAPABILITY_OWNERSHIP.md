# Termux:API capability ownership decisions

## Decision

Use the installed Termux:API companion app and the in-Termux termux-api package for simple,
bounded Android operations when the existing command surface is adequate. Do not reimplement
these operations in Kotlin solely to keep all Android calls inside Gallery.

The Termux:API owner executes through the existing supervised Termux RUN_COMMAND client.
This is separate from the unified shell router's TERMUX shell backend: one is a fixed-command
Android capability provider, the other is a general shell execution transport.

## Selected Termux:API capabilities

| Android capability | Command(s) | Scope and constraints |
|---|---|---|
| SENSOR_READ / SENSOR_STREAM | termux-sensor | List sensors, finite samples with -n, selected sensors and delay; continuous streams require bounded lifetime and cleanup |
| LOCATION_READ / LOCATION_UPDATES | termux-location | once, last, and updates; constrain provider, duration, output, and cancellation |
| WIFI_CONNECTION_INFO | termux-wifi-connectioninfo | Read current connection metadata; respect Android location/nearby-device privacy restrictions |
| WIFI_SCAN | termux-wifi-scaninfo | Read last scan only; scanning is subject to Android throttling and permissions |
| NFC_READ / NFC_WRITE | termux-nfc | Basic NDEF read/write; require explicit user intent and validate tag interaction |
| INFRARED_CONTROL | termux-infrared-frequencies, termux-infrared-transmit | Report hardware support; bound carrier frequency and pulse pattern (upstream script documents patterns shorter than two seconds) |
| CAMERA_INFO / CAMERA_CAPTURE | termux-camera-info, termux-camera-photo | Basic camera metadata and still JPEG capture to an approved path; advanced sessions remain native if needed |
| MICROPHONE_RECORD | termux-microphone-record | File-based recording only; explicit approval, bounded duration, output path, and stop/cleanup |
| SPEECH_RECOGNITION | termux-speech-to-text | Basic interactive speech recognition and text output; not a persistent background transcription service |
| TEXT_TO_SPEECH | termux-tts-engines, termux-tts-speak | Ordinary speech synthesis; native only if Gallery needs richer playback/session control |
| WALLPAPER_CONTROL | termux-wallpaper | Basic image-file wallpaper setting; explicit approval |

These are selected owners in the catalog but remain planned until fixed handlers, probes,
validation, cancellation/cleanup, tests, and device acceptance are implemented.

## Keep these capabilities native

| Capability group | Why native remains preferable |
|---|---|
| Device/build, battery, volume, flashlight | Already implemented in Gallery with typed results and the existing approval/security boundary; switching would add a process hop without enough benefit |
| Audio endpoint discovery | termux-audio-info reports audio configuration/state, not the complete typed input/output endpoint inventory |
| Brightness and vibration | Already implemented natively with scoped approval and Gallery-owned control; no demonstrated advantage from switching |
| Wake lock | No equivalent Termux:API command; Gallery needs a bounded app-owned partial wake lock |
| Bluetooth / BLE / GATT | No equivalent Termux:API command family; native Android APIs are required |
| Wi-Fi enable/disable | Android version restrictions make legacy direct toggles unreliable; use native settings-mediated user flow |
| USB host I/O | Termux USB exposes file descriptors through a Termux callback process; native USB keeps discovery, permission, and session lifecycle inside Gallery |
| Contacts, call logs, telephony, SMS | Keep privacy-sensitive reads and user-mediated dialer/SMS flows within Gallery's permission and approval model |
| Clipboard | Foreground and Android clipboard privacy rules favor Gallery's own foreground-aware adapter |
| Notifications | Gallery-owned notification channels and notification-listener access are app-identity-specific |
| Biometrics and Keystore | Authentication and keys must be owned by Gallery; Termux app identity is not interchangeable |
| Downloads, media sessions, sharing, scheduling | Gallery-owned lifecycle, content URIs, media sessions, user-visible work, and app callbacks favor native integration |
| SAF document trees / removable storage files | Persisted SAF grants are application-identity scoped; Termux's grants do not become Gallery's grants |
| Accessibility / semantic UI | No equivalent Termux:API command family |

## Implementation contract

- Fixed command allowlist. Never interpolate model-generated shell source or command names.
- Validate every argument before command construction; quote path and text arguments safely.
- Use existing Termux RUN_COMMAND supervision, timeout, bounded stdout/stderr, and cancellation.
- Read operations never trigger permission prompts automatically. Report PERMISSION_REQUIRED,
  NOT_INSTALLED, UNSUPPORTED, or UNKNOWN distinctly.
- Mutations and privacy-sensitive capture require the existing explicit approval boundary.
- Sensor/location/microphone streams must have explicit maximum duration, cancellation, and cleanup.
- Do not implement automatic fallback to Native Kotlin when the selected Termux owner is unavailable.
- Do not mark a capability available based only on command binary presence. Probe the companion
  package and validate actual operation behavior.
- Keep Termux:API companion permissions distinct from Gallery runtime permissions in availability.
- No new long-lived process or service; do not leave streaming commands running after cancellation.

## Verification gates

1. Confirm companion app and command binary presence independently.
2. Verify the command's exact argument contract against the upstream script.
3. Unit-test argument validation, quoting, timeouts, output bounds, cancellation, cleanup, denial,
   and no-fallback behavior.
4. Device-test each operation on the target Android version, including denied permissions.
5. Compare latency, reliability, output quality, battery/thermal impact, and permission burden.
6. Change ownership only with evidence and a corresponding catalog/documentation/test update.

## Upstream command reference

https://github.com/termux/termux-api-package/tree/master/scripts
