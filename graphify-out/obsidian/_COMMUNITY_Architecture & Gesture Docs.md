---
type: community
cohesion: 0.12
members: 17
---

# Architecture & Gesture Docs

**Cohesion:** 0.12 - loosely connected
**Members:** 17 nodes

## Members
- [[AccessibilityDesktopController — Android AccessibilityService input injection]] - code - CLAUDE.md
- [[Gesture to Desktop Action mapping — Pinch=click, OpenPalm=right-click, Swipe=window switch, PinchOut=zoom, Fist=drag]] - concept - CLAUDE.md
- [[GestureAccessibilityService — dispatchGesture() entry point for desktop control]] - code - CLAUDE.md
- [[GestureActionDispatcher — maps named gestures to DesktopAction (Click, etc.)]] - code - CLAUDE.md
- [[GestureRecognizer — converts HandData into named gestures (Pinch, SwipeLeft, SwipeRight)]] - code - CLAUDE.md
- [[HandData — isTracked, pinchStrength, pointerPose — data struct between landmarker and gesture layer]] - concept - CLAUDE.md
- [[HandLandmarkerHelper — MediaPipe 21-joint hand landmarker wrapper]] - code - CLAUDE.md
- [[HandTrackingPipeline — orchestrates XRealGlassesCamera + HandLandmarkerHelper]] - code - CLAUDE.md
- [[KMP shared module — GestureRecognizer + GestureActionDispatcher shared across Android and macOS]] - concept - CLAUDE.md
- [[MRTK3 (Microsoft Mixed Reality Toolkit) — higher-level XR interaction framework]] - concept - CLAUDE.md
- [[MediaPipe Tasks Vision (com.google.mediapipetasks-vision) — on-device hand landmarking]] - concept - CLAUDE.md
- [[OpenXR XR_EXT_hand_tracking — Khronos standard 26-joint hand tracking extension]] - concept - CLAUDE.md
- [[Phone screen off with glasses desktop active — WakeLockPowerManager investigation]] - concept - CLAUDE.md
- [[UVC camera access via Android USB Host API — VID=0x3318 PID=0x0436, bulkTransfer MJPEG frames]] - concept - CLAUDE.md
- [[Unity XR Hands package (com.unity.xr.hands ≥1.6) — cross-platform hand tracking subsystem]] - concept - CLAUDE.md
- [[XRealGlassesCamera — Android USB Host API UVC camera implementation]] - code - CLAUDE.md
- [[macOS companion app (stretch) — Swift + CGEventAXUIElement desktop control via KMP interop]] - concept - CLAUDE.md

## Live Query (requires Dataview plugin)

```dataview
TABLE source_file, type FROM #community/Architecture__Gesture_Docs
SORT file.name ASC
```
