# xrdroiddesk

Use hand gestures from XReal One Pro AR glasses to control the Android Desktop on a Pixel 10 Pro — no touch, no controller, just your hands.

## What it does

When XReal One Pro glasses are connected via USB-C, Android offers **Mirror mode** or **Desktop mode**. This project targets **Desktop mode only** — the glasses become an independent AR desktop display and your hands become the input device.

- The glasses' built-in RGB camera streams hand video over UVC
- On-device MediaPipe detects 21 hand landmarks per frame
- Pinch (index + thumb) fires a click; swipes switch windows
- A crosshair cursor overlays the glasses desktop and tracks your wrist in real time
- Everything runs as a native Android `AccessibilityService` — no root, no ADB after setup

## Architecture

```
XReal One Pro (USB-C)
        │  UVC bulk frames (HEVC)
        ▼
XRealGlassesCamera          USB Host API — no Camera2, no proprietary SDK
        │
        ▼
H264FrameDecoder            async MediaCodec (hardware HEVC) → 640×480 NV21
        │
        ▼
HandLandmarkerHelper        MediaPipe LIVE_STREAM — 21 joints, ~4 fps
        │  HandData(isTracked, pinchStrength, pointerPose)
        ▼
GestureRecognizer           Pinch / SwipeLeft / SwipeRight
        │
        ▼
GestureActionDispatcher     DesktopAction → normalised → pixel coords
        │
        ▼
AccessibilityDesktopController
        │  dispatchGesture()
        ▼
GestureAccessibilityService + CursorOverlay
```

## Hardware

| Component | Device |
|---|---|
| Glasses | XReal One Pro |
| Phone | Google Pixel 10 Pro |
| Connection | USB-C |
| Required app | [Glasses Control](https://play.google.com/store/apps/details?id=com.xreal.glassescontrol.store) — UVC mode must be enabled once |

## Key technical findings

- **UVC over USB Host** — XReal One Pro streams H.265 over standard UVC bulk transfer. No proprietary SDK needed. Accessed via `UsbManager` + `bulkTransfer()`.
- **Autonomous UVC enablement** — xrdroiddesk enables UVC mode on the glasses itself via HID bulk transfer to the MCU (reverse-engineered from `libnr_glasses_api.so`), so the user never needs to open Glasses Control after the first install.
- **Android 16 + secondary display overlays** — `TYPE_ACCESSIBILITY_OVERLAY` windows on a secondary display require `createWindowContext(display, TYPE_ACCESSIBILITY_OVERLAY, null)` (API 30+). `createDisplayContext()` has no accessibility token and silently fails.
- **Android 16 USB Host** — `android.permission.CAMERA` is now required for USB `requestPermission()` on UVC devices (new in Android 16; previous versions did not require it).

## Building and installing

### Prerequisites

- **JDK 17+** — `java -version` should report 17 or higher
- **Android SDK** — install via [Android Studio](https://developer.android.com/studio) or the standalone SDK tools; `ANDROID_HOME` must be set
- **adb** — included with the Android SDK platform-tools; must be on your `PATH`
- **Connected Pixel 10 Pro** with USB debugging enabled (`Settings → Developer options → USB debugging`)

Verify your setup:

```bash
adb devices          # should list your phone as "device"
make help            # lists all available make targets
```

### 1. Clone and set up

```bash
git clone git@github.com:repudi8or/xrdroiddesk.git
cd xrdroiddesk

# Download the MediaPipe hand landmarker model (~6 MB, not committed to git)
make download-model

# Copy local config template (Android SDK path etc.)
cp local.properties.example local.properties
# Edit local.properties if your sdk.dir differs from the Android Studio default
```

### 2. Build the APK

```bash
make build
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### 3. Install on device

```bash
make install          # builds + installs via adb
# or with a specific device if multiple are connected:
make install DEVICE=192.168.x.x:PORT
```

### 4. Grant permissions and exempt from Doze

Run once after install — sets runtime permissions, exempts the app from battery optimization (prevents background kills during long sessions), and revokes Camera from Glasses Control to reduce its Android 16 lifecycle aggression:

```bash
make setup-device          # recommended: runs all commands below in one shot
```

Or individually:

```bash
# Camera — required on Android 16+ for USB Host UVC access
adb shell pm grant com.repudi8or.xrdroiddesk android.permission.CAMERA

# Notifications — used for "glasses disconnected" and watchdog alerts
adb shell pm grant com.repudi8or.xrdroiddesk android.permission.POST_NOTIFICATIONS

# Doze exemption — prevents Android from killing capture in the background
adb shell dumpsys deviceidle whitelist +com.repudi8or.xrdroiddesk

# Revoke Camera from Glasses Control — reduces CG's Pause/Resume aggression on Android 16
# (CG's HID USB channel is unaffected; this only stops CG from starting its own camera pipeline)
adb shell pm revoke com.xreal.glassescontrol.store android.permission.CAMERA
# To restore: make restore-cg-camera
```

### 5. Enable the accessibility service

The service must be enabled once in Android system settings — this cannot be done via adb.

**On the phone:**
```
Settings → Accessibility → Downloaded apps → xrdroiddesk → Enable
```

Verify it's running:

```bash
make accessibility-check
```

### 6. Install and configure Glasses Control

Install [Glasses Control](https://play.google.com/store/apps/details?id=com.xreal.glassescontrol.store) from the Play Store.

Enable UVC mode **once** in the app:
```
Glasses Control → Settings → Enable UVC mode
```

This only needs to be done once. After that, xrdroiddesk autonomously re-enables UVC on every plug-in via HID — you do not need to open Glasses Control again.

### 7. Connect the glasses

1. Plug XReal One Pro into the phone via USB-C
2. Android shows a USB chooser — select **Glasses Control** (Just once)
3. Glasses Control enables UVC (~2 seconds), glasses re-enumerate
4. A second USB permission dialog appears — select **xrdroiddesk** (Just once)
5. Camera opens, cursor appears on the glasses desktop

> **First plug after a fresh install** — the USB permission dialog shows. Tap Allow, then unplug and replug. The second plug uses the fast path (permission already stored) and autonomous UVC enable succeeds within ~100ms.

> **Desktop mode** — when prompted by Android after connecting the glasses, select **Desktop mode** (not Mirror mode). This project only targets Desktop mode.

### ADB over WiFi (optional, for wireless development)

```bash
# Once, while the phone is plugged in via USB:
make adb-wifi-enable

# Then unplug and connect wirelessly:
make adb-wifi-connect DEVICE_IP=192.168.x.x

# All make targets work over WiFi from here
make logcat          # stream filtered logs for this app
```

### Useful ADB commands

```bash
# One-shot post-install setup (permissions + Doze exemption + CG camera revoke)
make setup-device

# Restore Camera permission to Glasses Control
make restore-cg-camera

# Stream logs (filtered to this app)
make logcat

# Check accessibility service status
make accessibility-check

# USB permission diagnostics
make usb-permission-status

# Clear USB permissions (forces fresh permission dialogs on next plug)
adb shell pm clear com.xreal.glassescontrol.store
```

See `Makefile` for the full list of dev commands (`make help`).

## Roadmap

- [x] Autonomous UVC enablement
- [x] HEVC decode + MediaPipe hand tracking
- [x] Pinch-to-click on glasses desktop
- [x] Cursor overlay on glasses display
- [ ] One Euro Filter for adaptive cursor smoothing
- [ ] Relative (trackpad-style) cursor movement
- [ ] Phone screen off — glasses desktop stays alive
- [ ] Voice keyboard on glasses desktop
- [ ] macOS companion app (KMP + Swift/CGEvent)

---

## Acknowledgements

Special thanks to **Raphael Pereira** ([@nudou350](https://github.com/nudou350)) whose project [**Xreal-tools**](https://github.com/nudou350/Xreal-tools) covers remarkably similar ground — hands-free mouse control for XReal One Pro on Samsung DeX using the same UVC + MediaPipe + AccessibilityService stack.

Raphael's generosity in sharing his work publicly has been invaluable. His implementations of the **One Euro Filter** for adaptive pointer smoothing and a **relative cursor mapper** (trackpad-style movement) are directly informing the next phase of this project. His approach to display monitoring and the UVC enablement protocol sequence are also worth studying closely.

If you are working on XReal glasses control on Android, his repository is essential reading:
[https://github.com/nudou350/Xreal-tools](https://github.com/nudou350/Xreal-tools)
