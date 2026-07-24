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

## Building

```bash
# Prerequisites: Android Studio, JDK 17+, adb

# Download MediaPipe hand landmarker model (~6 MB, not committed)
make download-model

# Build and install
make install

# Enable the accessibility service:
# Settings → Accessibility → xrdroiddesk → Enable

# Connect glasses — UVC enables automatically on second plug-in
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
