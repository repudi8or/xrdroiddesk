# xrdroiddesk

App that uses XReal One Pro glasses hand gestures to control the Android Desktop (not mirror mode) on a phone, with macOS as a stretch goal.

## Goal

Use hand gestures detected by XReal One Pro glasses to control the **Android Desktop** environment on a Google Pixel 10 Pro. Designed to be extensible to other XR glasses and Android phones with desktop mode support.

When XReal glasses are plugged into the Pixel 10 Pro via USB-C, Android prompts for either **Mirror mode** (phone screen duplicated) or **Desktop mode** (independent windowed desktop environment). **This project targets Desktop mode only.** Mirror mode is out of scope.

## Target Hardware

- **Glasses**: XReal One Pro (primary), extensible to other XReal/NRSDK-compatible glasses
- **Phone**: Google Pixel 10 Pro (primary), extensible to any Android phone supporting desktop mode
- **Mac** *(stretch goal)*: MacBook — glasses connect via USB-C; macOS control via a companion Mac app
- **Connection**: USB-C or wireless depending on XReal SDK support

## Key SDKs & Dependencies

- **Android SDK** — minSdk 29 required by XREAL SDK; targetSdk latest stable
- **XREAL SDK 3.1.0** (current, replaces NRSDK) — spatial computing, hand tracking, gesture recognition
  - Docs: https://docs.xreal.com
  - Download: https://developer.xreal.com/download
  - **Important:** XREAL SDK 3.x is Unity-only (Unity 2021.3+, XR Plugin umbrella). Native Android path is unclear — may require staying on NRSDK or contacting XReal developer support.
- **NRSDK (legacy, still documented)** — older proprietary Android SDK; same hand tracking API surface but not actively developed
  - Docs: https://xreal.gitbook.io/nrsdk
  - Migration guide: https://docs.xreal.com/MigratingFromNRSDKToXREALSDK/intro

### Hand Tracking API (NRSDK / XREAL SDK)

**Core classes:**

| Class | Purpose |
|---|---|
| `NRInput` | Main input manager; switch between hand and controller modes |
| `NRHand` | Represents one hand (left or right) |
| `HandState` | Per-frame snapshot of a hand's tracking data |
| `HandJointID` | Enum of 23 tracked joint points |

**Key methods & properties:**

```csharp
// Switch to hand tracking input
NRInput.SetInputSource(InputSourceEnum.Hands); // InputSourceEnum: Hands | Controller

// Check tracking state
bool active = NRInput.Hands.IsRunning;
bool systemGesture = NRInput.Hands.IsPerformingSystemGesture();

// Get per-hand state (HandEnum: RightHand | LeftHand)
HandState state = NRInput.Hands.GetHandState(HandEnum.RightHand);

bool   isTracked     = state.isTracked;
bool   isPinching    = state.isPinching;
float  pinchStrength = state.pinchStrength;   // 0.0 – 1.0
var    gesture       = state.currentGesture;  // HandGesture enum (6 types)
Pose   pointer       = state.pointerPose;
bool   pointerValid  = state.pointerPoseValid;

// Get pose of a specific joint
Pose thumbTip  = state.GetJointPose(HandJointID.ThumbTip);
Pose indexTip  = state.GetJointPose(HandJointID.IndexTip);
```

**HandJointID enum — 23 joints tracked:**
Wrist, Palm, ThumbMetacarpal, ThumbProximal, ThumbDistal, ThumbTip,
IndexProximal, IndexMiddle, IndexDistal, IndexTip,
MiddleProximal, MiddleMiddle, MiddleDistal, MiddleTip,
RingProximal, RingMiddle, RingDistal, RingTip,
PinkyMetacarpal, PinkyProximal, PinkyMiddle, PinkyDistal, PinkyTip

**HandGesture enum — 6 recognized poses** (exact names TBD from API reference; confirmed types include):
- Pinch/Select — index + thumb contact (any other finger pose counts)
- System gesture — hold 1.2 s to invoke home menu
- Open palm, thumbs-up, grab, and others (confirm from full enum)

**XREAL SDK 3.x interaction model (Unity):**
- `Poke Interactor` — index fingertip-driven contact interaction
- `Near-Far Interactor` — seamless close/distant interaction transitions
- `Teleport Interactor` — far-field / indirect input

**XRI integration:** Edit > Project Settings > XR Plug-in Management > XREAL, set Input source = Hands

## Cross-Platform Hand Gesture Abstraction

Writing gesture code against these layers instead of XReal-proprietary APIs means the same code runs on Meta Quest, HoloLens, PICO, HTC Vive Focus, Magic Leap 2, Varjo, and any other OpenXR-conformant device.

### Layer 1 — OpenXR `XR_EXT_hand_tracking` (Khronos standard)

The lowest-level portable standard. Any OpenXR-conformant runtime (Meta, Microsoft, HTC, PICO, etc.) that ships this extension exposes the same joint data structure.

- Spec: https://registry.khronos.org/OpenXR/specs/1.1/man/html/XR_EXT_hand_tracking.html
- 26 joints per hand (`XrHandJointEXT` enum), position + orientation + radius per joint
- Create a tracker with `xrCreateHandTrackerEXT`, poll with `xrLocateHandJointsEXT`
- XREAL SDK 3.x exposes 26 joints (up from NRSDK's 23), aligning with this spec
- Android XR platform also supports this extension (plus `XR_ANDROID_hand_mesh` for mesh data)

### Layer 2 — Unity XR Hands package (`com.unity.xr.hands` ≥ 1.6)

Unity's cross-platform subsystem that sits on top of OpenXR (and other providers). **This is the recommended abstraction layer if the project uses Unity.**

- Docs: https://docs.unity3d.com/Packages/com.unity.xr.hands@1.6/manual/index.html
- Defines the API; device-specific provider plugins (installed separately) implement it
- Currently the only shipping provider is OpenXR — so any OpenXR device works
- Key API:

| Class | Purpose |
|---|---|
| `XRHandSubsystem` | Subsystem interface; start/stop tracking, query hands |
| `XRHand` | Struct holding data for one tracked hand |
| `XRHandJoint` | Per-joint position, rotation, tracking state |
| `MetaAimHand` | Pinch + aim gesture data via Meta Aim OpenXR feature |

```csharp
// Get the subsystem
var subsystem = XRGeneralSettings.Instance.Manager
    .activeLoader.GetLoadedSubsystem<XRHandSubsystem>();

// Read a joint
XRHand rightHand = subsystem.rightHand;
if (rightHand.isTracked) {
    rightHand.GetJoint(XRHandJointID.IndexTip)
             .TryGetPose(out Pose indexTip);
}
```

- XREAL SDK 3.x is built on this package — `NRInput.Hands` maps to `XRHandSubsystem` under the hood
- To swap glasses: change the OpenXR provider plugin, gesture logic stays untouched

### Layer 3 — MRTK3 (Microsoft Mixed Reality Toolkit)

Higher-level interaction framework; XREAL SDK explicitly supports MRTK3 integration.

- Abstracts pinch, poke, gaze, ray interactions uniformly across HoloLens, Quest, PICO, XReal, etc.
- Useful if the project needs rich UI interaction primitives beyond raw gesture → input event mapping
- XREAL MRTK3 docs: https://docs.xreal.com/MRTK3_Integration

### Abstraction recommendation for this project

This project uses **native Kotlin + NRSDK** (not Unity), so the relevant layers are:

```
NRSDK (XReal-specific, native Android)
        |
GestureRecognizer (KMP shared module)  ← raw joints → named gestures
        |
GestureActionMapper (KMP shared module) ← named gestures → abstract actions
        |
DesktopController (platform-specific)  ← Android AccessibilityService / macOS CGEvent
```

To support other glasses in future: swap NRSDK for OpenXR via the Android NDK OpenXR loader — the shared KMP gesture logic above it stays untouched.

## Architecture (planned)

```
XReal Glasses
        |
        v
NRSDKHandTracker (Android, Kotlin)
        |  joint poses + pinch data
        v
GestureRecognizer (KMP shared module)
        |  named gestures: Pinch, Swipe, Palm, Fist, …
        v
GestureActionMapper (KMP shared module)
        |  abstract actions: Click, RightClick, Scroll, Drag, …
        v
DesktopController
  ├── AndroidDesktopController → AccessibilityService
  └── MacDesktopController (stretch) → CGEvent / AXUIElement
```

## Gesture → Desktop Action Mapping (initial targets)

| Gesture | Desktop Action |
|---|---|
| Pinch (index + thumb) | Left click / select |
| Open palm push | Right-click / context menu |
| Swipe left/right | Switch windows / virtual desktop |
| Two-hand pinch-out | Zoom |
| Fist | Drag initiate |

## Android Desktop Control Approach

- **AccessibilityService** — preferred; can inject click/scroll/gesture events without root
- **InputManager injection** — may require system-level permissions or ADB-enabled debug mode
- Investigate `UiAutomation` or `android.hardware.input.InputManager#injectInputEvent` for pointer injection

## macOS Control Approach (stretch goal)

The glasses connect to the Mac via USB-C; a native Mac companion app (Swift/SwiftUI) reads the NRSDK gesture stream and drives the macOS desktop.

- **CGEvent / CoreGraphics** — `CGEventCreateMouseEvent`, `CGEventPost` for pointer/click injection; no special permissions needed for basic input
- **Accessibility API** (`AXUIElement`) — for window management, focus control, and semantic actions beyond raw input
- **IOKit HID** — alternative low-level input injection if CGEvent proves insufficient
- Architecture mirror: same GestureInputService abstraction, platform-specific DesktopController implementation
- Companion app could be a menubar agent (no dock icon) that listens over USB/local socket for gesture events forwarded from the Android side, or talks directly to NRSDK if a macOS SDK is available

## Development Framework Decision

### Why not Unity

XREAL SDK 3.x is Unity-only, but `AccessibilityService` is a native Android component that must be declared in the manifest and run as a persistent background service. Unity controls the process lifecycle and manifest, making a well-behaved Android background service very difficult. Unity is the wrong tool for a system-level input-injection app.

### Chosen approach: Native Android (Kotlin + NRSDK) → Kotlin Multiplatform for Mac

**Phase 1 — Native Android**
- Language: Kotlin
- XReal SDK: NRSDK (native Android, legacy but fully functional)
- Desktop control: `AccessibilityService` (natural fit in native Kotlin)
- Build: Gradle, Android Studio

**Phase 2 — Mac stretch goal via Kotlin Multiplatform (KMP)**

Extract the gesture recognition and gesture→action mapping into a **KMP shared module**. The Android app calls it from Kotlin directly; a macOS companion app calls it from Swift via KMP's Swift/Objective-C interop (stable; full Swift export targeting 2026).

Each platform keeps its own input injection:
- Android: `AccessibilityService`
- macOS: Swift + `CGEvent` / `AXUIElement`

```
shared/ (KMP module — Kotlin)
  GestureRecognizer       ← raw joint data → named gestures
  GestureActionMapper     ← named gestures → abstract desktop actions

androidApp/ (Kotlin)
  NRSDKHandTracker        ← NRSDK → joint data → shared module
  AndroidDesktopController← AccessibilityService input injection

macosApp/ (Swift + KMP interop)
  XRealHandTracker        ← NRSDK/OpenXR → joint data → shared module
  MacDesktopController    ← CGEvent / AXUIElement input injection
```

**Why not Flutter / React Native:** No XReal SDK bindings exist, and both make system-level input injection (AccessibilityService, CGEvent) awkward via platform channels — more indirection for no benefit.

### KMP references
- Kotlin Multiplatform: https://kotlinlang.org/multiplatform/
- KMP macOS targets: https://kotlinlang.org/docs/native-overview.html
- KMP Swift interop: https://kotlinlang.org/docs/native-swift-export.html

## Build Setup

- **Phase 1 — Android app**: Kotlin, Gradle, Android Studio, NRSDK
- **Phase 2 — KMP shared module + macOS companion**: Kotlin Multiplatform, Swift/SwiftUI, Xcode

## Git Workflow

- **Remote**: git@github.com:repudi8or/xrdroiddesk.git
- **Main branch**: `main` — always deployable/releasable; direct commits only for initial project setup
- **All other work**: feature branches (`feature/`, `fix/`, `patch/`) — branch off `main`, merge back after tests pass
- Branch naming: `feature/<short-description>`, `fix/<short-description>`, `patch/<short-description>`
- PRs require passing pre-commit hooks and tests before merge

## Test-Driven Development

- Write the test first, make it fail, then write the minimum code to pass it
- Android: JUnit 4/5 + Mockk for unit tests; Espresso or UI Automator for instrumented tests
- KMP shared module: `commonTest` source set with `kotlin.test`; platform-specific tests in `androidTest` / `macosTest`
- Test coverage targets: gesture recognition logic (unit), gesture→action mapping (unit), AccessibilityService injection (instrumented)
- No PR merged to `main` without corresponding tests for new behaviour

## Local Environment Configuration

Environment-specific values (SDK keys, feature flags, device settings) are stored in `local.properties` — the Android community standard. No extra library required; values are read at build time via `java.util.Properties` in `app/build.gradle.kts` and exposed as `BuildConfig` constants.

**Setup (one-time per machine):**
```bash
cp local.properties.example local.properties
# Edit local.properties and fill in your values
```

`local.properties` is git-ignored and must never be committed. `local.properties.example` is the committed template showing all available keys.

**Adding a new config value:**
1. Add the key to `local.properties.example` with a comment explaining it
2. Add your real value to your local `local.properties`
3. Add a `buildConfigField(...)` entry in `app/build.gradle.kts` under `defaultConfig`
4. Access it in code as `BuildConfig.YOUR_KEY_NAME`

**Example — reading a value in Kotlin:**
```kotlin
val licenseKey = BuildConfig.NRSDK_LICENSE_KEY
```

**Current keys:**

| Key | BuildConfig field | Purpose |
|---|---|---|
| `sdk.dir` | *(build system only)* | Android SDK path — set automatically by Android Studio |
| `nrsdk.license.key` | `NRSDK_LICENSE_KEY` | XReal NRSDK license (obtain from developer.xreal.com) |

## Linting & Pre-Commit Hooks

Pre-commit hooks are managed via the [`pre-commit`](https://pre-commit.com) framework. Config: `.pre-commit-config.yaml`.

**One-time setup (each dev machine):**
```bash
pip install pre-commit   # or: brew install pre-commit
pre-commit install       # installs hooks into .git/hooks/pre-commit
```

**What the hooks enforce:**
- Trailing whitespace, end-of-file newlines, merge conflict markers
- YAML / JSON syntax validity
- File size limit (500 KB)
- Kotlin formatting via ktlint (active once Gradle project is initialised)

**Running hooks manually:**
```bash
pre-commit run --all-files   # run against entire repo
pre-commit run               # run against staged files only
```

**Kotlin linting (Gradle, once Android project exists):**
```bash
./gradlew ktlintCheck        # check only
./gradlew ktlintFormat       # auto-fix
```

## Local Dev Commands

All common tasks are wrapped in the `Makefile` with `JAVA_HOME` pre-set. Run `make help` for the full list.

```bash
make build             # assemble debug APK
make check             # unit tests + ktlint (CI gate)
make test              # unit tests only
make lint / make fmt   # ktlint check / auto-fix
make install           # build + adb install
make run               # install + launch MainActivity
make logcat            # filtered logcat for this app
make emulator          # start desktop AVD in background
make desktop-mode      # enable freeform windowing then reboot device
make adb-wifi-enable   # switch Pixel to TCP/IP (run once, plugged in)
make adb-wifi-connect DEVICE_IP=192.168.x.x
make accessibility-check  # verify service is enabled on device
```

## Emulator Setup (M1 Mac)

The Android Emulator is already installed (`emulator 36.5.10`). No separate install needed.

**AVD:** `xrdroiddesk_desktop_api34`
- System image: `android-34;android-desktop;arm64-v8a` — purpose-built Desktop mode image
- Device profile: `desktop_medium`
- Lives in `~/.android/avd/` (not committed to git)

**First-time emulator workflow:**
```bash
make emulator          # starts AVD in background, wait ~30s
make desktop-mode      # applies freeform settings + reboots emulator
make install           # deploy APK
make run               # launch app
# Enable service: Settings > Accessibility > xrdroiddesk
make accessibility-check  # confirm it's enabled
```

## Physical Device Workflow (ADB over WiFi)

```bash
# Once, while Pixel is plugged in via USB:
make adb-wifi-enable

# Then unplug and connect wirelessly:
make adb-wifi-connect DEVICE_IP=192.168.x.x

# All make targets (install, run, logcat) work over WiFi from here
```

Android Studio Device Mirroring (Hedgehog+) streams the Pixel screen directly into the IDE over ADB — useful for watching Desktop mode output without looking at the phone.

## Development Notes

- XReal One Pro requires the phone to be connected via USB-C; test on physical device only (no emulator for XR input)
- On connect, Android prompts Mirror or Desktop — the user must select **Desktop mode**; this app targets that mode exclusively
- Android Desktop mode on Pixel 10 Pro may need developer options enabled (Settings > Developer options > Force desktop mode)
- In Desktop mode, the glasses display an independent windowed environment separate from the phone's touchscreen
- AccessibilityService must be declared in manifest and enabled by user in Settings > Accessibility
- Hand tracking accuracy and latency will be a primary UX concern

## Useful References

- Kotlin Multiplatform: https://kotlinlang.org/multiplatform/
- KMP native/macOS targets: https://kotlinlang.org/docs/native-overview.html
- KMP Swift interop (export): https://kotlinlang.org/docs/native-swift-export.html


- XReal Developer Portal: https://developer.xreal.com
- XREAL SDK Docs (current): https://docs.xreal.com
- NRSDK Docs (legacy): https://xreal.gitbook.io/nrsdk
- XREAL SDK → NRSDK migration guide: https://docs.xreal.com/MigratingFromNRSDKToXREALSDK/intro
- XREAL MRTK3 integration: https://docs.xreal.com/MRTK3_Integration
- OpenXR `XR_EXT_hand_tracking` spec: https://registry.khronos.org/OpenXR/specs/1.1/man/html/XR_EXT_hand_tracking.html
- Unity XR Hands package docs: https://docs.unity3d.com/Packages/com.unity.xr.hands@1.6/manual/index.html
- Android XR OpenXR extensions: https://developer.android.com/develop/xr/openxr/extensions
- Android AccessibilityService docs: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- Android Desktop mode (taskbar/freeform): introduced in Android 12L, refined in 14+
- macOS CGEvent reference: https://developer.apple.com/documentation/coregraphics/cgevent
- macOS Accessibility API: https://developer.apple.com/documentation/applicationservices/accessibility_application_programming_interface
