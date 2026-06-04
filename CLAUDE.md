# xrdroiddesk

App that uses XReal One Pro glasses hand gestures to control the Android Desktop (not mirror mode) on a phone, with macOS as a stretch goal.

## Goal

Use hand gestures detected by XReal One Pro glasses to control the **Android Desktop** environment on a Google Pixel 10 Pro. Designed to be extensible to other XR glasses and Android phones with desktop mode support.

When XReal glasses are plugged into the Pixel 10 Pro via USB-C, Android prompts for either **Mirror mode** (phone screen duplicated) or **Desktop mode** (independent windowed desktop environment). **This project targets Desktop mode only.** Mirror mode is out of scope.

## Feature Goals (including stretch)

| Priority | Feature |
|---|---|
| Core | Hand gesture control of Android Desktop via UVC + MediaPipe |
| Core | Phone primary screen off while glasses continue showing desktop + hand tracking |
| Core | Voice keyboard input support in glasses desktop (accessibility service integration) |
| Stretch | macOS companion app (KMP + Swift/CGEvent) |

### Phone screen off — glasses desktop remains active

The user wants to use the glasses as a standalone desktop display with the phone screen powered off (not just dimmed), while hand tracking and voice keyboard continue to work. This requires:
- Keeping the external display (glasses) alive when the phone screen turns off — Android normally kills external displays on screen-off; this may need `WakeLock` on the display or a `PowerManager` flag
- Keeping the accessibility service (and UVC camera loop) running with screen off — `AccessibilityService` survives screen-off if the service is bound; the UVC coroutine should keep reading frames
- Voice keyboard: the Android on-screen keyboard should still function on the external display; `InputMethodService` integration may be needed if the system IME doesn't follow the external display
- Investigation: `PowerManager.SCREEN_BRIGHT_WAKE_LOCK` on the external display, or `DisplayManager` `VirtualDisplay` approaches

## Target Hardware

- **Glasses**: XReal One Pro (primary), extensible to other UVC-capable glasses
- **Phone**: Google Pixel 10 Pro (primary), extensible to any Android phone supporting desktop mode
- **Mac** *(stretch goal)*: MacBook — glasses connect via USB-C; macOS control via a companion Mac app
- **Connection**: USB-C
- **Required app on phone**: [Control Glasses](https://play.google.com/store/apps/details?id=ai.nreal.controlglasses) — must be installed and **UVC mode enabled** (toggle in app settings: "enable uvc mode to stream RGB camera data from the glasses")

## Key SDKs & Dependencies

- **Android SDK** — minSdk 29; targetSdk 35
- **XREAL SDK 3.1.0 / NRSDK** — both Unity-only; no native Android AAR published. Not used in this project.
- **MediaPipe Tasks Vision** (`com.google.mediapipe:tasks-vision`) — on-device ML hand landmarking (21 joints) from camera frames. Used to convert UVC frames into `HandData`.
- **Jetpack XR** (`androidx.xr.*`) — Google's Android XR SDK supporting hand tracking on Android XR headsets (e.g., Samsung Galaxy XR). Not applicable to XReal glasses connected to a standard Android phone.
- **Kotlinx Coroutines** (`kotlinx-coroutines-android`) — async UVC frame reading loop.

## XReal One Pro UVC Camera Access

The XReal One Pro exposes its built-in RGB cameras as standard **UVC (USB Video Class)** devices when UVC mode is enabled via the **Control Glasses** app. This is the hand-tracking camera source for this project.

**One-time setup:** Open "Control Glasses" on the phone → enable the UVC toggle ("enable uvc mode to stream RGB camera data from the glasses").

**USB device details** (discovered via ADB while glasses connected):

| Field | Value |
|---|---|
| Manufacturer | XREAL |
| Product | XREAL One Pro |
| Vendor ID | `0x3318` (13080 decimal) |
| Product ID | `0x0436` (1078 decimal) |
| USB path | `/dev/bus/usb/001/003` |
| Configuration | `hid+ncm+ecm+uac1+uvc_bulk_15_xreal0+uvc_bulk_15_xreal1` |
| UVC interfaces | class=14/subclass=1 (VideoControl) + class=14/subclass=2 (VideoStreaming) × 2 |

**Two stereo UVC cameras:** `xreal0` and `xreal1` (bulk transfer, ~15 fps). Either stream works for hand landmarking.

**Access method:** Android USB Host API (`UsbManager.getDeviceList()` → `UsbDeviceConnection.bulkTransfer()`). The camera does **not** appear in the Camera2 API — it must be opened directly via USB Host. Implemented in `XRealGlassesCamera`.

**UVC frame acquisition flow:**
1. Find device by VID/PID via `UsbManager`
2. Claim VideoStreaming interface (class=14, subclass=2)
3. `VS_PROBE_CONTROL` GET_CUR → SET_CUR to negotiate format
4. `VS_COMMIT_CONTROL` SET_CUR to commit
5. `bulkTransfer()` loop; parse 2-byte UVC payload header (FID toggle, EOF bit) to assemble MJPEG frames
6. `BitmapFactory.decodeByteArray()` → `BitmapImageBuilder(bitmap).build()` → MediaPipe `MPImage`

**First connection:** Android shows a one-time USB permission dialog for the app. Permission is stored per-app.

**`android.hardware.usb.host` feature required** in manifest; no `CAMERA` permission needed (USB Host path bypasses Camera2).

## Autonomous UVC Enablement (bypassing Control Glasses)

**Goal:** Enable UVC mode on the glasses from within xrdroiddesk itself, so the user doesn't need to open Control Glasses and toggle the UVC switch manually.

**Implementation:** `GlassesUvcEnabler.kt` — sends HID USB commands directly to the glasses MCU.

**RE source:** `libnr_glasses_api.so` from the Control Glasses APK (`ai.nreal.controlglasses`, jadx-decompiled), plus Java sources from `XREALManager.java`.

### HID interfaces on XReal One Pro

Two HID interfaces (USB class=3) are present even before UVC mode is enabled:

| Interface | bInterfaceNumber | Endpoints | Purpose |
|---|---|---|---|
| Init + Config | 0 | ep_01 OUT / ep_81 IN | Host init AND GET/SET USB config — all HID commands go here |
| Consumer Control | 8 | ep_05 OUT / ep_88 IN | Consumer Control HID (maxPkt=3) — NOT the config channel |

**HID routing confirmed:** `get_xreal_usb_handle` unconditionally stores `1` to offset `0x17` of the handle struct → XReal One Pro always uses the HID path, never the TCP pilot path. TCP pilot is a fallback only used on connection error.

### HID frame format (RE'd from `cmd_build_sdk` at 0xa9ec4)

```
Offset  Size  Field
[0]     1     Report ID = 0xfd
[1..4]  4     CRC32 LE — init = ~innerLen & 0xff, poly 0xEDB88320, no final XOR
               CRC range: bytes [6 .. totalLen] inclusive (includes 1 trailing zero byte
               beyond the frame — allocate totalLen+1 scratch, return copyOf(totalLen))
[5..6]  2     innerLen LE = totalLen - 5 = 17 + payload.size
               (NOT totalLen; NOT cmdType — common RE mistake)
[7..14] 8     zeros
[15..16] 2    msgId LE  (0xD2 = GET, 0xD3 = SET — no separate cmdType field)
[17..21] 5    zeros
[22+]   N     payload
```

**There is no `cmdType` field.** The native code passes `w3=4` (payload size, not cmdType) to `cmd_build_sdk`. SET vs GET is distinguished entirely by msgId.

### UsbConfigList — field order and delta format

Fields (order from `UsbConfigList.java`): `ncm`, `ecm`, `uac`, `hid_ctrl`, `mtp`, `mass_storage`, `uvc0`, `uvc1`, `enable`

**Delta format:** `0` = leave unchanged, `1` = set. Control Glasses (`XREALManager.java`) sends:
- `uvc0 = 1`, `enable = 1`; all other fields = 0 (ncm/ecm already on, others not needed)
- `uvc1` is **never set** by Control Glasses

### SET payload — confirmed 4-byte bitmask

`NRBSPSetUsbConfigAll` (0xbb498) sends **4 bytes** to the glasses: uint32 LE bitmask with UsbConfigList fields as bits: ncm(0), ecm(1), uac(2), hid_ctrl(3), mtp(4), mass_storage(5), uvc0(6), uvc1(7), enable(8). **Confirmed by Frida**: Control Glasses sends `uvc0=1, enable=1` → `0x140` → LE bytes `40 01 00 00`. `GlassesUvcEnabler` sends this exact payload.

### Pilot daemon TCP path (secondary)

- Accessible at `169.254.1.1:50180` via USB NCM (kernel-level Ethernet, no app USB permission needed)
- Glasses present as `eth1` on Pixel with IP `169.254.1.10/24`; glasses IP = `169.254.1.1`
- TCP path sends the **same HID-format frames** via `sendto()` — `PilotProtocol.kt`'s 0xAA-prefix format (`cmd_build_imu`) is for a different message class, not USB config
- TCP path is only needed if HID bulkTransfer fails (e.g., USB permission issues)

### Key function addresses in libnr_glasses_api.so

| Function | Address | Notes |
|---|---|---|
| `send_xreal_usb_msg_timeout` | 0xa684c | Routes to HID or TCP based on handle flag |
| `cmd_build_sdk` | 0xa9ec4 | Builds HID frames (report=0xfd) |
| `cmd_build_imu` | 0xa9fc8 | Builds IMU/pilot frames (STX=0xAA) |
| `get_xreal_usb_handle` | ~0xacf14 | Sets routing flag=1 (HID) at handle+0x17 |
| `NRBSPGetUsbConfigAll` | 0xbbe54 | msgId=0xD2 GET |
| `NRBSPSetUsbConfigAll` | 0xbb498 | msgId=0xD3, payload_size=4 |
| `NRBSPSetUsbConfig` | 0xba978 | Per-field SET, also payload_size=4 |
| `get_socket_connection` | 0xad85c | TCP to 169.254.1.1:50180 |

### Current status

#### Key hardware findings (from live testing 2026-06-04)

**UVC is NOT persistent** — glasses reset to non-UVC config on every power cycle. CG re-enables it fresh on each reconnect (~2 seconds after initial attach).

**Correct USB interface map (from UsbHostManager logs):**
- Interface 0: HID init (ep_01 OUT/ep_81 IN, maxPkt=1024) — init messages (HOST_TYPE, SDK_VERSION)
- Interface 8: HID config (ep_05 OUT/ep_88 IN, **maxPkt=3**) — intended for GET/SET but 3-byte limit prevents our frames
- Interface 9: UVC VideoControl (class=14, subclass=1) — present only when UVC active, no endpoints
- Interface 10: UVC VideoStreaming "Video Streaming 0" (class=14, subclass=2) — bulk IN ep 0x89 (address=137), maxPkt=512 — **this is what to claim for camera frames**

**How CG enables UVC:**
1. Glasses attach (no UVC, 8 interfaces). CG gets USB_DEVICE_ATTACHED.
2. CG sends HOST_TYPE=2 + SDK_VERSION to interface 0 (correct channel, maxPkt=1024).
3. CG reads current USB config via GET (0xD2), then sends SET (0xD3) with 4-byte payload.
4. ~2 seconds after initial attach: glasses re-enumerate WITH UVC (interfaces 9+10 appear).

**Correct package name:** `com.xreal.glassescontrol.store` (not `ai.nreal.controlglasses`). Use this for permission clearing via adb.

**4-byte payload — CONFIRMED `0x140`:**
Frida hook on `NRBSPSetUsbConfigAll` in Control Glasses confirms: `uvc0=1, enable=1` → `0x140` → LE bytes `40 01 00 00`. Prior "no re-enumeration" results were a timing problem (SET was arriving ~5.3 seconds after USB permission grant), not a payload problem. See "Timing fix" below.

**Interface 8 confirmed as Consumer Control HID — not the config channel:**
- EP0 control transfer (SET_REPORT): STALL (-1) immediately
- Bulk transfer to ep_05: always times out — maxPkt=3 is interrupt HID, not bulk
- **All GET+SET config commands go via interface 0** (same ep_01/ep_81 channel as HOST_TYPE/SDK_VERSION). `GlassesUvcEnabler` was updated — all dead iface-8 paths removed.

**send_xreal_usb_msg signature (confirmed from 0xa8608):**
`send_xreal_usb_msg(handle, msgId, JNIEnv*, payload_size, x4, x5, x6, usbConfigList_jobject)`
The "payload" argument is JNIEnv* — packing happens INSIDE the function via JNI GetIntField on the jobject.

**Next step:**
Test full auto-enable flow: plug glasses → service auto-triggers UVC enable (no button needed) → logcat `Requirements` tag shows `uvc_active=true` → `XRealGlassesCamera.open()` succeeds → MediaPipe receives MJPEG frames.

**HOST_TYPE values:**
- HOST_TYPE=1: Display stays on, SET command does NOT trigger re-enumeration
- HOST_TYPE=2: Display goes dark (SDK mode), correct value for CG

**Fallback approach (proven working):** Let CG run to enable UVC, then take over via USB Host. Workflow: `adb shell am force-stop com.xreal.glassescontrol.store` after UVC appears → replug to clear permissions → pick xrdroiddesk from chooser.

Frame format bugs in `GlassesUvcEnabler.buildHidFrame` (5 confirmed, all fixed):
1. ✅ frame[5] = `innerLen & 0xFF` (was: `totalLen`)
2. ✅ frame[6] = `innerLen shr 8` = 0 (was: cmdType)
3. ✅ `cmdType` parameter removed (not in native frame)
4. ✅ CRC init = `~innerLen & 0xff` (was: `~totalLen`)
5. ✅ CRC range = `innerLen` bytes from offset 6, includes trailing zero (was: 1 byte short)

**Timing fix (2026-06-05) — SET now reaches glasses within ~500ms of permission grant:**
Previous attempts sent SET ~5.3 seconds after permission, likely past the glasses' config-change window. Root causes and fixes in `GlassesUvcEnabler`:
- `HID_POST_INIT_DELAY_MS`: 2500ms → 200ms (MCU ACKs init in ~12ms; 200ms is ample)
- Removed iface-8 HOST_TYPE attempt from `claimAndSendHidInit()` (was always timing out at 1000ms)
- `sendUsbConfigViaHid()` simplified to iface-0 GET+SET only — the two failing paths (EP0 ctrl on iface-8, bulk to ep_05) added ~2s of dead time and are now gone
- `HID_RESPONSE_TIMEOUT_MS`: 3000ms → 500ms

**"Always" greyed out in USB chooser — permanent on Android 16 (Pixel 10 Pro):**
Composite USB devices detected as audio headsets (`is_headset=true`) cannot be set as the "always" handler when multiple apps compete. "Just once" is the only option regardless of how many competing apps are cleared. xrdroiddesk handles this via `requestPermission()` in `GrantUsbPermissionActivity` and `MainActivity` — the permission dialog fires on each plug-in.

**Requirements state logger (`MainActivity.logRequirementsState`):**
Logs and displays state of all six prerequisites on every app event (startup, each button press, USB attach, permission result). Filter logcat by tag `Requirements` to see the full sequence. Each line: `a11y_enabled`, `a11y_running`, `usb_found`, `usb_perm`, `uvc_active`, `pending_device`. The phone screen also shows a live summary in `tvStatus`.

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

This project uses **native Kotlin + UVC + MediaPipe** (not Unity, not NRSDK). The relevant layers are:

```
UVC camera (XReal-specific, via USB Host API)
        |
XRealGlassesCamera + HandLandmarkerHelper  ← MJPEG frames → HandData
        |
GestureRecognizer  ← raw HandData → named gestures
        |
GestureActionDispatcher  ← named gestures → DesktopAction
        |
DesktopController (platform-specific)  ← Android AccessibilityService / macOS CGEvent
```

To support other glasses in future: replace `XRealGlassesCamera` (the only XReal-specific class) with a different camera source. Everything above it is camera-agnostic.

## Architecture (implemented)

```
XReal One Pro glasses — USB-C, UVC mode on
        |
        v
XRealGlassesCamera          UVC bulk frames (MJPEG)
        |
        v
HandLandmarkerHelper        MediaPipe 21-joint hand landmarker
        |                   → HandData(isTracked, pinchStrength, pointerPose)
        v
HandTrackingPipeline        orchestrates camera + landmarker
        |
        v
GestureRecognizer           Pinch / SwipeLeft / SwipeRight
        |
        v
GestureActionDispatcher     DesktopAction.Click / …
        |
        v
AccessibilityDesktopController
        |
        v
GestureAccessibilityService.dispatchGesture()
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

### Chosen approach: Native Android (Kotlin + UVC + MediaPipe) → Kotlin Multiplatform for Mac

**Phase 1 — Native Android**
- Language: Kotlin
- Camera input: Android USB Host API → XReal UVC camera (no proprietary SDK)
- Hand tracking: MediaPipe Tasks Vision (`HandLandmarker`, 21 joints)
- Desktop control: `AccessibilityService` (natural fit in native Kotlin)
- Build: Gradle, Android Studio

**Phase 2 — Mac stretch goal via Kotlin Multiplatform (KMP)**

Extract the gesture recognition and gesture→action mapping into a **KMP shared module**. The Android app calls it from Kotlin directly; a macOS companion app calls it from Swift via KMP's Swift/Objective-C interop (stable; full Swift export targeting 2026).

Each platform keeps its own input injection:
- Android: `AccessibilityService`
- macOS: Swift + `CGEvent` / `AXUIElement`

```
shared/ (KMP module — Kotlin)
  GestureRecognizer       ← HandData → named gestures
  GestureActionDispatcher ← named gestures → abstract desktop actions

androidApp/ (Kotlin)
  XRealGlassesCamera      ← USB Host API → UVC frames
  HandLandmarkerHelper    ← MediaPipe → HandData
  HandTrackingPipeline    ← orchestrates above → GestureRecognizer
  AccessibilityDesktopController ← AccessibilityService input injection

macosApp/ (Swift + KMP interop)
  XRealUvcCamera          ← IOKit USB → UVC frames
  MacDesktopController    ← CGEvent / AXUIElement input injection
```

**Why not Flutter / React Native:** No XReal SDK bindings exist, and both make system-level input injection (AccessibilityService, CGEvent) awkward via platform channels — more indirection for no benefit.

### KMP references
- Kotlin Multiplatform: https://kotlinlang.org/multiplatform/
- KMP macOS targets: https://kotlinlang.org/docs/native-overview.html
- KMP Swift interop: https://kotlinlang.org/docs/native-swift-export.html

## Build Setup

- **Phase 1 — Android app**: Kotlin, Gradle, Android Studio, MediaPipe Tasks Vision, Android USB Host API
- **Phase 2 — KMP shared module + macOS companion**: Kotlin Multiplatform, Swift/SwiftUI, Xcode

### Model file

MediaPipe requires `hand_landmarker.task` (~6 MB) in `app/src/main/assets/`. Not committed to git. Download once:

```bash
make download-model
```

(Makefile target fetches from `storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task`)

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

- XReal One Pro requires USB-C connection to phone; test on physical device only (no emulator for UVC input)
- On connect, Android prompts Mirror or Desktop — user must select **Desktop mode**; this app targets that mode exclusively
- Android Desktop mode on Pixel 10 Pro may need developer options enabled (Settings > Developer options > Force desktop mode)
- In Desktop mode, the glasses display an independent windowed environment separate from the phone's touchscreen
- AccessibilityService must be declared in manifest and enabled by user in Settings > Accessibility
- **Control Glasses app must be installed** and UVC mode toggled on before camera access works
- First launch triggers a one-time USB permission dialog for the XReal device (VID=0x3318, PID=0x0436)
- **USB permission prerequisite**: Control Glasses (`com.xreal.glassescontrol.store`) is registered as the default/preferred USB handler for VID=13080/PID=1078. Android auto-denies `requestPermission()` calls from any other app while a preferred handler exists. **One-time fix**: Settings → Apps → "Glasses Control" → Open by default → Clear defaults → unplug and replug glasses → pick xrdroiddesk from the chooser (tap "Just once"). After this, xrdroiddesk has USB permission and Control Glasses keeps working for display. Diagnose with `make usb-permission-status`. Note: `adb shell pm clear com.xreal.glassescontrol.store` clears app data but does NOT clear the system-level USB default handler — use the Settings UI path instead. **"Always" is permanently greyed out on Android 16 (Pixel 10 Pro)**: composite USB devices detected as audio headsets cannot use "always" with multiple competing handlers — "Just once" is the maximum. xrdroiddesk calls `requestPermission()` on each plug-in to show the dialog regardless.
- Hand tracking accuracy and latency will be a primary UX concern
- The glasses camera points forward (eye-level view of user's hands) — ideal geometry for gesture detection

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
