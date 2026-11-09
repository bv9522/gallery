# Android capability provider matrix

This inventory describes preferred implementations, not guaranteed live availability. Phase 3B adds
an observational native Android probe and typed framework adapter. Availability is reported per
capability because a provider can support some operations but not others.

## Brahma-inspired capabilities

| Capability | Preferred implementation | Fallback | Reason |
|---|---|---|---|
| Device information | Native Android APIs | Shizuku/Rish shell, then Termux shell | Typed framework data; shell remains useful for extra system properties. |
| Battery status | Native BatteryManager and battery broadcasts | Termux:API termux-battery-status, then shell | Direct framework access with Termux:API as an independent provider. |
| Flashlight | Native CameraManager | Termux:API termux-torch, then shell | Direct framework control and structured errors; API command is a ready-made alternative. |
| Volume | Native AudioManager | Termux:API termux-volume, then shell | Typed audio APIs avoid shell parsing. |
| App discovery | Native PackageManager and launcher intents | Shizuku/Rish shell | Structured package metadata subject to Android package visibility. |
| App launch | Native launch intents | Shizuku/Rish shell, then Termux shell | Android-supported intent resolution; shell remains a broad fallback. |
| Open URL | Native ACTION_VIEW intent | Shizuku/Rish shell, then Termux shell | Android resolves the browser/app and respects user defaults. |
| File list/read/write | Shizuku/Rish shell for accessible paths | Termux shell; SAF for user-selected locations | Shell is the broad filesystem interface; SAF handles scoped and user-granted documents. |
| File delete | Shizuku/Rish shell for accessible paths | SAF for user-granted documents | Must remain behind approval/risk policy; recursive deletion is never an unguarded convenience. |
| Screen capture | AccessibilityService screenshot API | Shizuku/Rish screencap, then Termux shell | Accessibility can pair an image with semantic UI state; shell capture is a fallback. |
| UI tree / UI dump | Native AccessibilityService | No equivalent live semantic shell fallback | A one-off uiautomator dump is not a live actionable accessibility hierarchy. |
| Tap / swipe | Accessibility node actions and dispatchGesture | Shell input tap / input swipe | Semantic actions are robust; coordinate gestures remain a universal fallback. |
| Text entry | Accessibility node ACTION_SET_TEXT | Shell input text | Native actions target semantic controls; shell text entry needs escaping and is less expressive. |

## Phase 3B native Android adapter

`AndroidNativeCapabilityAdapter` uses the Gallery app's own Android framework identity. It exposes
structured device and battery facts, audio stream state, launcher-visible app discovery, flashlight
availability, and HTTPS handler discovery. Its mutation methods (volume, flashlight, app launch,
open URL) require an injected `NativeCapabilityAuthorizer`; there is no permissive default and the
adapter never requests runtime permissions.

The probe reports per-capability status, including `PERMISSION_REQUIRED` for camera access and
`UNSUPPORTED` when no controllable flash or matching system service is present. It does not prompt,
change settings, start apps, or open links. App visibility is intentionally scoped to launcher
activities through `<queries>`; the app does not request `QUERY_ALL_PACKAGES`. URL handler visibility
is scoped to HTTPS intents. `MODIFY_AUDIO_SETTINGS` is a normal permission used for volume control.

This is a provider/adaptation layer, not yet a new model-facing tool surface. Production wiring must
supply the existing user-approval implementation before exposing mutation methods to Gemma.

## Provider boundaries

- Termux shell runs under Termux's app UID and is constrained by its app sandbox and storage grants.
- Shizuku shell and Termux Rish execute as Android shell UID when Shizuku is available. Rish is a
  Termux-launched transport to that same shell identity, not a separate privilege level.
- Termux:API exposes capabilities through its companion app and installed command set. It
  complements the unified shell router; it does not replace it.
- Native Android APIs run as the Gallery app and are preferred for typed framework operations.
- AccessibilityService is a separately user-enabled service and is the planned source of a live
  semantic UI tree and semantic node actions.
- Storage Access Framework grants access to user-selected document trees and removable storage.

## Deferred capabilities and Skill Forge

Bluetooth discovery/control and USB/OTG storage are deferred until their Android-version and
permission behavior is audited on the target device.

Skill Forge is a workflow-authoring feature, not a device provider. The safe direction is for
Gemma to compose and save declarative workflows from existing approved tools. Do not hot-load
arbitrary generated Kotlin/Python or execute unreviewed generated code.

Implement equivalent behavior independently. Do not copy Brahma source files: its public
repository license is restrictive for derivative redistribution.

## Routing and safety rules

1. Provider discovery is observational only; it must not request permissions or mutate device state.
2. Provider observations may override availability per capability; provider-wide status is only a fallback.
3. Native mutations require an explicit authorizer and fail closed when approval is absent.
4. Capability routing is per operation; there is no global always-use-Rish rule.
5. An explicit backend choice remains authoritative. Automatic selection is separate.
6. Availability is not authorization. Existing approval policy remains the final gate for risky
   operations, especially file writes/deletes and shell commands.
7. A missing provider observation is UNKNOWN, never assumed available.
