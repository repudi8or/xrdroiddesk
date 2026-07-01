---
source_file: "CLAUDE.md"
type: "code"
community: "Architecture & Gesture Docs"
location: "lines 348-349"
tags:
  - graphify/code
  - graphify/EXTRACTED
  - community/Architecture__Gesture_Docs
---

# GestureRecognizer — converts HandData into named gestures (Pinch, SwipeLeft, SwipeRight)

## Connections
- [[Gesture to Desktop Action mapping — Pinch=click, OpenPalm=right-click, Swipe=window switch, PinchOut=zoom, Fist=drag]] - `implements` [EXTRACTED]
- [[GestureActionDispatcher — maps named gestures to DesktopAction (Click, etc.)]] - `references` [EXTRACTED]
- [[HandData — isTracked, pinchStrength, pointerPose — data struct between landmarker and gesture layer]] - `shares_data_with` [EXTRACTED]
- [[KMP shared module — GestureRecognizer + GestureActionDispatcher shared across Android and macOS]] - `references` [EXTRACTED]
- [[OpenXR XR_EXT_hand_tracking — Khronos standard 26-joint hand tracking extension]] - `semantically_similar_to` [INFERRED]

#graphify/code #graphify/EXTRACTED #community/Architecture__Gesture_Docs
