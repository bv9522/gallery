# Android capability provider matrix

The canonical inventory, provider ownership decisions, Termux:API command disposition, security
contracts, and implementation waves are maintained in
[ANDROID_CAPABILITY_OWNERSHIP_AND_IMPLEMENTATION_PLAN.md](ANDROID_CAPABILITY_OWNERSHIP_AND_IMPLEMENTATION_PLAN.md).

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

## Implemented native capabilities

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

## Remaining capability groups

Termux:API is selected for basic sensors, location requests/updates, Wi-Fi information and last-scan
results, NFC NDEF read/write, infrared, still-photo capture, file-based microphone recording,
speech-to-text, ordinary TTS, and basic wallpaper setting. Native Kotlin retains Bluetooth/BLE,
USB host sessions, app-owned/security-sensitive integrations, advanced camera/audio sessions,
and user-mediated controls. The canonical plan lists the complete mapping and constraints.

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
- Bluetooth GATT and USB device I/O remain planned until their session/permission lifecycles are
  implemented and tested.


## Native media ownership rebalance — Phase 6B draft

- Camera metadata remains a bounded Termux:API query. Photo capture is native and writes to a
  Gallery-owned MediaStore URI through a user-mediated camera activity; no Termux-private output
  path is involved.
- Wallpaper changes are native through WallpaperManager and accept only Gallery-readable image
  content URIs with an explicit SYSTEM/LOCK/BOTH target. This keeps content URI permissions under
  Gallery's identity and avoids passing paths or URLs into Termux:API.


## Native microphone ownership — Phase 6C draft

- Bounded microphone-to-file recording is native through MediaRecorder and MediaStore. Gallery
  requests RECORD_AUDIO only after one-time scoped approval, records 1–30 seconds, publishes an
  app-owned audio content URI, and stops/releases the recorder on cancellation. The output remains
  local; it is not automatically uploaded or transcribed.


## SAF document-tree implementation — Phase 7A draft

- Gallery owns a dedicated `document_tree` tool backed by DocumentsContract and user-persisted
  tree grants. It supports grant listing, folder picker, bounded listing, metadata, text read/write,
  text file and directory creation, rename, and delete.
- Tree access is scoped to exact persisted Gallery URI grants. Model-supplied raw paths and arbitrary
  URIs are rejected. Text I/O is capped at 256 KiB and listings at 100 entries. Copy, move, recursive
  search, and general metadata writes remain unsupported.
- Attached USB mass storage is accessed through SAF, not through UsbManager bulk transfers. UsbManager
  is only used for peripheral enumeration and future non-storage USB protocols.
