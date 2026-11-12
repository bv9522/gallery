# Android capability provider matrix

The canonical inventory, provider ownership decisions, Termux:API command disposition, security
contracts, and implementation waves are maintained in
[ANDROID_CAPABILITY_OWNERSHIP_AND_IMPLEMENTATION_PLAN.md](ANDROID_CAPABILITY_OWNERSHIP_AND_IMPLEMENTATION_PLAN.md).

## Single-owner policy

Every model-independent capability has exactly one selected owner in the capability catalog:

| Scope | Owner | Boundary |
|---|---|---|
| Typed Android device/framework operations | Native Android Kotlin | Gallery app UID; typed Android APIs; explicit permissions and approvals |
| Path-based file and directory operations | Unified shell router | Existing TERMUX / SHIZUKU / TERMUX_RISH execution backends; no duplicate file tool |
| User-granted document trees and removable storage files | Android Storage Access Framework | Persisted user-granted document URI permissions |
| Screen capture and semantic UI interaction | Android AccessibilityService | Explicitly enabled service; bounded screen/tree/gesture operations |

Termux, Shizuku, and Rish are execution transports, not separate capability owners. Termux:API is
a command wrapper around Android capabilities; its Phase 4A overlapping operations are retained
only as transitional explicit-comparison code while native ownership is completed. It is not
registered as a second automatic owner.

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

The canonical ownership document enumerates the remaining planned scope, including sensors, location,
Wi-Fi, Bluetooth, NFC, infrared, USB, telephony, contacts, call logs, SMS composition, clipboard,
notifications, camera, microphone, speech, TTS, biometrics, keystore, downloads, media, sharing,
wallpaper, scheduling, file operations, SAF document trees, screen capture, and accessibility.

## Security and implementation rules

1. Provider discovery is observational and never prompts or mutates.
2. One capability cannot be declared by multiple providers or assigned multiple owners.
3. Availability is not authorization. Mutations require the existing approval boundary and fail
   closed on denial, timeout, or cancellation.
4. Path-based file mutations and SAF document mutations are separately scoped and approval-gated.
5. No root, broad package visibility, arbitrary generated-code execution, or device-specific hacks.
6. Do not change ownership based on assumptions about availability. Record device acceptance
   evidence for permission burden, data quality, reliability, latency, and resource impact.
