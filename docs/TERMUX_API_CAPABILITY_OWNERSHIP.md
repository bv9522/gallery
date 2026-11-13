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
| INFRARED_CONTROL | termux-infrared-frequencies, termux-infrared-transmit | Report hardware support; bound carrier frequency and pulse pattern (upstream script documents patterns shorter than two seconds); Phase 5B draft handler checks a fresh frequency query and requires approval to transmit |
| CAMERA_INFO / CAMERA_CAPTURE | termux-camera-info, termux-camera-photo | Basic camera metadata and still JPEG capture to an approved path; advanced sessions remain native if needed |
| MICROPHONE_RECORD | termux-microphone-record | File-based recording only; explicit approval, bounded duration, output path, and stop/cleanup |
| SPEECH_RECOGNITION | termux-speech-to-text | Basic interactive speech recognition and text output; not a persistent background transcription service |
| TEXT_TO_SPEECH | termux-tts-engines, termux-tts-speak | Ordinary speech synthesis; Phase 5B draft handler bounds speech text to 500 characters and requires approval; native only if Gallery needs richer playback/session control |
| WALLPAPER_CONTROL | termux-wallpaper | Basic image-file wallpaper setting; explicit approval |

These are selected owners in the catalog. The Phase 5A draft contains fixed handlers for sensor
listing/finite reads, location once/last, and Wi-Fi connection/last-scan reads, but all four
capabilities remain **Planned and not production-available** until current-head unit tests, the
debug build, and target-device acceptance pass. The remaining command families are also planned.

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
- Discovery never triggers permission prompts. The status operation independently reports
  companion-app presence and command-binary presence; permission state remains UNKNOWN until a
  real operation establishes otherwise. Location and Wi-Fi reads require scoped user approval.
  Report PERMISSION_REQUIRED, COMMAND_NOT_INSTALLED, TIMEOUT, PROCESS_CLEANUP_UNCONFIRMED, or
  OPERATION_FAILED distinctly.
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


## Verified upstream contracts — first implementation slice

The following contracts were checked against the current upstream command wrappers in
`termux/termux-api-package/scripts` (not inferred from command names). They are an
implementation reference, not evidence of Gallery integration or device availability.

| Command | Verified wrapper contract | Implementation implications |
|---|---|---|
| `termux-sensor` | Requires an explicit option; `-l` lists sensors, `-s <comma-delimited names>` selects sensors, `-a` selects all sensors, `-d <milliseconds>` sets delay, `-n <count>` makes the read finite, and `-c` requests cleanup. Without `-n`, reads are continuous. | Prefer `-l` and finite `-n` reads first. Validate sensor names against the observed list or a conservative character/length allowlist; never accept arbitrary option text. Bound count and delay. If a stream is later exposed, cancellation must terminate the supervised process group so the script's SIGTERM/SIGINT trap can call its sensor cleanup routine. Also provide best-effort explicit `-c` cleanup where lifecycle ownership permits. Do not use `-a` by default because upstream warns of battery impact. |
| `termux-location` | `-p` accepts only `gps`, `network`, or `passive`; `-r` accepts only `once`, `last`, or `updates`. Defaults are `gps` and `once`. The wrapper rejects trailing arguments. | Start with `once` and `last`; constrain provider to the three exact values. Treat `updates` as a long-lived operation, not a normal read: require an explicit bounded session, cancellation, and process cleanup before exposing it. The wrapper has no separate cleanup subcommand. |
| `termux-wifi-connectioninfo` | Accepts no arguments and invokes the current-connection information API. | Construct a constant command with no model-supplied parameters. Distinguish unavailable/disconnected/permission-restricted results from malformed output. |
| `termux-wifi-scaninfo` | Accepts no arguments and returns information from the last Wi-Fi scan; it does not initiate a new scan. | Construct a constant command with no model-supplied parameters. Report stale/empty last-scan data as such; do not describe it as a fresh scan. Android permission and scan-throttling behavior still needs device verification. |

### First-slice sequencing and readiness

1. [x] Implement read-only sensor listing and finite sensor samples, location `once`/`last`, Wi-Fi
   connection information, and last-scan information using fixed command builders (Phase 5A).
2. [x] Extend the status diagnostic to report companion-app presence and fixed command-binary
   presence independently. Command presence is only a transport prerequisite, not proof that
   permissions or the operation are usable. Permission state remains unknown until an operation
   runs; discovery never launches a permission prompt.
3. Keep sensor streaming and location `updates` unavailable until bounded-session semantics,
   cancellation, cleanup, and device behavior have dedicated tests. Their catalog ownership does
   not make them callable or available.
4. [x] Add pure unit coverage for malformed/extra sensor and location arguments, exact fixed
   command construction, permission/command-not-installed/timeout/cleanup error classification,
   and truncated/empty output handling. [x] Add fake-client tests for fixed dispatch, malformed
   per-operation arguments, approval denial/closed-channel failure, output truncation, timeout,
   and cancellation propagation through the tool to the executor's cleanup `finally` block.
   The no-fallback registry test remains in `CapabilityRegistryTest`. The cancellation fake proves
   tool-level propagation, not real-device process-group termination. Finite sensor reads use
   `-n`; streams are not exposed.
5. Brian confirmed that the current Phase 5A head passed both
   `:app:testShizukuDebugUnitTest` and `:app:assembleShizukuDebug` locally. Target-device
   acceptance remains intentionally deferred until capability onboarding is complete. Keep the
   Phase 5A capabilities **Planned and unavailable** in production provider observations until
   the consolidated device acceptance gate passes.

Upstream wrapper references:
- https://github.com/termux/termux-api-package/blob/master/scripts/termux-sensor.in
- https://github.com/termux/termux-api-package/blob/master/scripts/termux-location.in
- https://github.com/termux/termux-api-package/blob/master/scripts/termux-wifi-connectioninfo.in
- https://github.com/termux/termux-api-package/blob/master/scripts/termux-wifi-scaninfo.in


## Verified upstream contracts — Phase 5B draft

- `termux-infrared-frequencies` accepts no arguments and queries supported carrier frequencies.
- `termux-infrared-transmit` requires `-f <frequency>` and one comma-separated pulse pattern;
  upstream documents that patterns shorter than two seconds are transmitted. Gallery accepts
  only numeric intervals, checks the requested frequency against a fresh frequency query, caps
  the pattern at 256 intervals, and requires one-time approval.
- `termux-tts-engines` accepts no arguments and lists available engines.
- `termux-tts-speak` accepts optional engine/language/rate/pitch/stream controls and text (or
  stdin). The first Gallery slice exposes only bounded text with default engine settings, uses
  shell-safe quoting, caps text at 500 characters, and requires one-time approval for audible
  output. The operation is supervised with a 30-second timeout.

Phase 5B operations are draft implementations. Command presence does not prove hardware support,
engine availability, permission state, or successful operation. Keep their catalog availability
unverified until local tests/build and the consolidated device acceptance pass.


## Verified upstream contracts — Phase 5C draft

- `termux-nfc` requires an explicit operation: `-r [short|full]` reads NDEF content, while
  `-w -t <text>` writes a text payload. Read and write modes are mutually exclusive; the wrapper
  rejects trailing arguments.
- Gallery exposes only full reads and text-only writes, bounds payloads to 512 characters,
  shell-quotes the payload, requires scoped approval for both operations, and uses a 60-second
  supervised timeout. NFC write approval includes the exact text and warns that existing tag
  content may be replaced.
- The wrapper delegates to an NFC interaction activity. Cancellation and timeout must be checked
  on-device to ensure the external interaction UI does not remain active after the supervised
  command is terminated. No continuous reader mode or raw NDEF payload is exposed.


## Verified upstream contracts — Phase 5D draft

- `termux-camera-info` accepts no arguments and returns camera metadata; it does not capture
  images. Gallery exposes this as a read-only fixed command.
- `termux-speech-to-text` accepts no arguments (optional `-p` exposes partial matches). The
  wrapper emits the final recognition result by default. Gallery exposes only the fixed final
  result command, requires scoped approval, and bounds execution to 90 seconds. Recognition may
  use the configured Android speech service and is not guaranteed to be on-device.
- The speech wrapper delegates to an interactive recognition flow. Device acceptance must verify
  that cancellation/timeouts terminate the microphone session and dismiss any external UI.


## Ownership rebalance — Phase 6B draft

- `termux-camera-info` remains the Termux:API owner for read-only camera metadata.
- `termux-camera-photo` is no longer selected for Gallery's camera capture capability. Native
  capture writes to a Gallery-owned MediaStore URI and uses the system camera activity; this avoids
  Termux-private output paths and cross-app file access assumptions.
- `termux-wallpaper` is no longer selected for wallpaper changes. Native WallpaperManager accepts
  only Gallery-readable image content URIs, avoiding Termux path/URI ownership ambiguity.


## Ownership rebalance — Phase 6C draft

- `termux-microphone-record` is no longer selected for Gallery's microphone recording capability.
  Native MediaRecorder writes a bounded AAC recording to a Gallery-owned MediaStore URI and
  explicitly stops/releases the recorder on completion or cancellation. This avoids Termux-owned
  background recording services and cross-app file-path assumptions.
- `termux-speech-to-text` remains the selected Termux:API owner for one-shot interactive speech
  recognition; it is a separate capability and may use the configured Android recognition service.


## SAF ownership clarification — Phase 7A draft

- Removable USB storage file access belongs to the Android Storage Access Framework provider,
  not Termux:API or raw UsbManager device I/O. Gallery opens the system folder picker, persists the
  user's exact tree URI grant, and uses DocumentsContract document IDs for bounded file operations.
- SAF grants are scoped to the Gallery app identity and are not shared with Termux. Shell commands
  must not assume they can access a tree URI or a path from the Gallery document provider.
