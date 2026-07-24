# Graph Report - xrdroiddesk  (2026-06-30)

## Corpus Check
- 45 files · ~32,644 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 487 nodes · 724 edges · 41 communities (23 shown, 18 thin omitted)
- Extraction: 95% EXTRACTED · 5% INFERRED · 0% AMBIGUOUS · INFERRED: 39 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `520653c8`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- [[_COMMUNITY_USB Setup & Dialog Automation|USB Setup & Dialog Automation]]
- [[_COMMUNITY_HID Frame Building & UVC Enable|HID Frame Building & UVC Enable]]
- [[_COMMUNITY_Gesture Recognition & Config|Gesture Recognition & Config]]
- [[_COMMUNITY_Hand Tracking Pipeline|Hand Tracking Pipeline]]
- [[_COMMUNITY_Android UI Components|Android UI Components]]
- [[_COMMUNITY_UVC Camera & TCP Pilot|UVC Camera & TCP Pilot]]
- [[_COMMUNITY_Desktop Input Injection|Desktop Input Injection]]
- [[_COMMUNITY_HID RE Findings & Docs|HID RE Findings & Docs]]
- [[_COMMUNITY_Architecture & Gesture Docs|Architecture & Gesture Docs]]
- [[_COMMUNITY_MediaPipe Landmark Conversion|MediaPipe Landmark Conversion]]
- [[_COMMUNITY_Gesture Action Dispatcher|Gesture Action Dispatcher]]
- [[_COMMUNITY_Camera Device Tests|Camera Device Tests]]
- [[_COMMUNITY_Hand Landmarker Helper|Hand Landmarker Helper]]
- [[_COMMUNITY_UVC Stream Control Tests|UVC Stream Control Tests]]
- [[_COMMUNITY_Frida HID Hook|Frida HID Hook]]
- [[_COMMUNITY_USB Permission Activities|USB Permission Activities]]
- [[_COMMUNITY_Desktop Action Tests|Desktop Action Tests]]
- [[_COMMUNITY_UVC Frame Images|UVC Frame Images]]
- [[_COMMUNITY_Dev Tooling & Config|Dev Tooling & Config]]
- [[_COMMUNITY_Desktop AVD Setup|Desktop AVD Setup]]
- [[_COMMUNITY_Frame Image 1436-A|Frame Image 1436-A]]
- [[_COMMUNITY_Frame Image 1436-B|Frame Image 1436-B]]
- [[_COMMUNITY_Frame Image 1436-C|Frame Image 1436-C]]
- [[_COMMUNITY_Frame Image 1436-D|Frame Image 1436-D]]
- [[_COMMUNITY_Frame Image 1447-A|Frame Image 1447-A]]
- [[_COMMUNITY_Frame Image 1447-B|Frame Image 1447-B]]
- [[_COMMUNITY_Frame Image 1447-C|Frame Image 1447-C]]
- [[_COMMUNITY_Frame Image 1447-D|Frame Image 1447-D]]
- [[_COMMUNITY_Frame Image 1457-A|Frame Image 1457-A]]
- [[_COMMUNITY_Frame Image 1457-B|Frame Image 1457-B]]
- [[_COMMUNITY_Frame Image 1457-C|Frame Image 1457-C]]
- [[_COMMUNITY_Pre-commit Hook Config|Pre-commit Hook Config]]
- [[_COMMUNITY_Project README|Project README]]
- [[_COMMUNITY_Community 36|Community 36]]
- [[_COMMUNITY_Community 37|Community 37]]
- [[_COMMUNITY_Community 38|Community 38]]
- [[_COMMUNITY_Community 39|Community 39]]
- [[_COMMUNITY_Community 40|Community 40]]

## God Nodes (most connected - your core abstractions)
1. `GestureAccessibilityService` - 27 edges
2. `UsbSetupAutomator` - 27 edges
3. `XRealGlassesCamera` - 24 edges
4. `xrdroiddesk` - 24 edges
5. `MainActivity` - 17 edges
6. `GestureRecognizerTest` - 17 edges
7. `For the extended input format (hints, constraints, hardware state), see prompt_template.md.` - 17 edges
8. `GlassesUvcEnabler` - 13 edges
9. `landmarkToHandData()` - 12 edges
10. `Location: /xrdroiddesk/AGENTS.md` - 12 edges

## Surprising Connections (you probably didn't know these)
- `ktlint pre-commit hook (Gradle ktlintCheck on staged .kt files)` --semantically_similar_to--> `Makefile — wraps common dev tasks (build, install, run, logcat, emulator, adb-wifi, etc.) with JAVA_HOME`  [INFERRED] [semantically similar]
  .pre-commit-config.yaml → CLAUDE.md
- `Key unknowns for full CG removal — messages before HOST_TYPE, between SDK_VERSION and GET, post re-enum CG behavior` --conceptually_related_to--> `Autonomous UVC enablement flow — fast path (hasPermission=true) and slow path (first plug)`  [INFERRED]
  frida/README.md → CLAUDE.md
- `Frida RE scripts` --references--> `Control Glasses app (com.xreal.glassescontrol.store) — enables UVC mode via HID, preferred USB handler`  [EXTRACTED]
  frida/README.md → CLAUDE.md
- `Frida RE scripts` --references--> `libnr_glasses_api.so — native library from Control Glasses APK, RE source for HID protocol`  [EXTRACTED]
  frida/README.md → CLAUDE.md
- `Capture workflow` --references--> `Makefile — wraps common dev tasks (build, install, run, logcat, emulator, adb-wifi, etc.) with JAVA_HOME`  [EXTRACTED]
  frida/README.md → CLAUDE.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Hand Tracking Pipeline — full data flow from UVC camera to gesture dispatch** — claude_md_xrealglassescamera, claude_md_handlandmarkerhelper, claude_md_handtrackingpipeline, claude_md_gesturerecognizer, claude_md_gestureactiondispatcher, claude_md_accessibilitydesktopcontroller, claude_md_gestureaccessibilityservice [EXTRACTED 1.00]
- **Autonomous UVC Enablement Protocol — HID frame format, UsbConfigList bitmask, SET payload 0x140** — claude_md_glassesuvcenabler, claude_md_hid_frame_format, claude_md_usbconfiglist, claude_md_set_payload_0x140, claude_md_autonomous_uvc_flow [EXTRACTED 1.00]
- **Frida RE Verification — scripts, known msgIds, and bitmask confirming HID protocol details** — frida_readme_frida_re_scripts, frida_readme_hook_cg_hid_js, frida_readme_known_msgids, frida_readme_usbconfiglist_bitmask [EXTRACTED 1.00]

## Communities (41 total, 18 thin omitted)

### Community 0 - "USB Setup & Dialog Automation"
Cohesion: 0.14
Nodes (6): AccessibilityNodeInfo, Intent, UsbSetupAutomator, String, UsbDevice, intentDevice()

### Community 1 - "HID Frame Building & UVC Enable"
Cohesion: 0.08
Nodes (18): AutoCloseable, Bitmap, Byte, ByteArray, GlassesUvcEnabler, H264FrameDecoder, isAnnexB(), toScaledBitmap() (+10 more)

### Community 2 - "Gesture Recognition & Config"
Cohesion: 0.13
Nodes (5): Float, GestureConfig, GestureRecognizer, GestureRecognizerTest, HandData

### Community 3 - "Hand Tracking Pipeline"
Cohesion: 0.11
Nodes (5): AccessibilityEvent, HandTrackingPipeline, HandTrackingPipelineTest, Job, GestureAccessibilityService

### Community 4 - "Android UI Components"
Cohesion: 0.13
Nodes (9): android, AppCompatActivity, Array, Bundle, Button, IntArray, ScrollView, TextView (+1 more)

### Community 5 - "UVC Camera & TCP Pilot"
Cohesion: 0.23
Nodes (7): Boolean, XRealGlassesCamera, ConnectivityManager, Socket, UsbDeviceConnection, UsbEndpoint, UsbInterface

### Community 6 - "Desktop Input Injection"
Cohesion: 0.10
Nodes (12): AccessibilityService, AccessibilityDesktopController, AccessibilityDesktopControllerTest, Click, DesktopAction, Swipe, SwipeDirection, DesktopController (+4 more)

### Community 7 - "HID RE Findings & Docs"
Cohesion: 0.09
Nodes (26): Autonomous UVC enablement flow — fast path (hasPermission=true) and slow path (first plug), Control Glasses app (com.xreal.glassescontrol.store) — enables UVC mode via HID, preferred USB handler, GlassesUvcEnabler — sends HID USB commands to glasses MCU to enable UVC mode autonomously, HID frame format — Report ID 0xfd, CRC32, innerLen, msgId (RE'd from cmd_build_sdk 0xa9ec4), libnr_glasses_api.so — native library from Control Glasses APK, RE source for HID protocol, Makefile — wraps common dev tasks (build, install, run, logcat, emulator, adb-wifi, etc.) with JAVA_HOME, Rationale: Native Kotlin over Unity — AccessibilityService must run as persistent background service; Unity controls process lifecycle making this very difficult, Google Pixel 10 Pro (target phone — Android Desktop mode host) (+18 more)

### Community 8 - "Architecture & Gesture Docs"
Cohesion: 0.12
Nodes (17): AccessibilityDesktopController — Android AccessibilityService input injection, Gesture to Desktop Action mapping — Pinch=click, OpenPalm=right-click, Swipe=window switch, PinchOut=zoom, Fist=drag, GestureAccessibilityService — dispatchGesture() entry point for desktop control, GestureActionDispatcher — maps named gestures to DesktopAction (Click, etc.), GestureRecognizer — converts HandData into named gestures (Pinch, SwipeLeft, SwipeRight), HandData — isTracked, pinchStrength, pointerPose — data struct between landmarker and gesture layer, HandLandmarkerHelper — MediaPipe 21-joint hand landmarker wrapper, HandTrackingPipeline — orchestrates XRealGlassesCamera + HandLandmarkerHelper (+9 more)

### Community 9 - "MediaPipe Landmark Conversion"
Cohesion: 0.22
Nodes (7): toHandData(), LandmarkIndex, landmarkToHandData(), LandmarkToHandDataTest, Pose, List, Triple

### Community 10 - "Gesture Action Dispatcher"
Cohesion: 0.05
Nodes (43): Abstraction recommendation for this project, Android Desktop Control Approach, Architecture (implemented), Autonomous UVC Enablement (bypassing Control Glasses), Build Setup, Chosen approach: Native Android (Kotlin + UVC + MediaPipe) → Kotlin Multiplatform for Mac, Cross-Platform Hand Gesture Abstraction, Current status (+35 more)

### Community 11 - "Camera Device Tests"
Cohesion: 0.21
Nodes (4): Activity, XRealGlassesCameraTest, UsbManager, GrantUsbPermissionActivity

### Community 12 - "Hand Landmarker Helper"
Cohesion: 0.06
Nodes (30): 1. Architect Agent  *(full loop only)*, 2. Builder Agent, 3. Device Tester Agent  *(full loop only)*, 4. Critic Agent, 5. Sync Agent, 6. Orchestrator, Agent Role Definitions & Loop Structure, Agent Roles (+22 more)

### Community 15 - "USB Permission Activities"
Cohesion: 0.67
Nodes (4): Android 16 CAMERA permission requirement for UVC devices — UsbUserPermissionManager blocks without it, GrantUsbPermissionActivity — explicit requestPermission() for UVC device USB access, local.properties — environment-specific config (SDK keys, feature flags) via BuildConfig at build time, MainActivity — requirements state logger, CAMERA permission request, USB permission dialog

### Community 17 - "UVC Frame Images"
Cohesion: 0.67
Nodes (4): MJPEG Decoding Corruption Artifact, UVC Bulk Transfer Pipeline, XReal One Pro UVC Camera Frame (xr_frame.jpg), Vehicle Interior Scene (camera field of view)

### Community 18 - "Dev Tooling & Config"
Cohesion: 0.09
Nodes (22): Claude Code Terminal Shorthand, For the extended input format (hints, constraints, hardware state), see prompt_template.md., Input is a plain-text feature description + done definition — no Jira/GitHub required., Location: /xrdroiddesk/session-boot-template.md, Quick Reference — State File Locations, Quick Reference — Which Template to Use, session-boot-template.md — xrdroiddesk Orchestrator Boot Templates, TEMPLATE 10 — Requirement Correction (output was wrong, you know what you need) (+14 more)

### Community 36 - "Community 36"
Cohesion: 0.17
Nodes (6): GestureActionDispatcher, GestureActionDispatcherTest, Gesture, Pinch, SwipeLeft, SwipeRight

### Community 38 - "Community 38"
Cohesion: 0.10
Nodes (20): Done when, Done when, Feature: [title — one line, verb-noun form], Feature: Wire gesture pipeline — pinch/swipe → desktop click, Hardware state going in, Hardware state going in *(Full loop only — delete section for Quick loop)*, Hints, Hints *(optional — Architect will verify these against graphify)* (+12 more)

### Community 39 - "Community 39"
Cohesion: 0.14
Nodes (13): Done when, Feature: [title — one line, verb-noun form], Hardware state going in *(Full loop only — delete section for Quick loop)*, Hints *(optional — Architect will verify these against graphify)*, How to use, Known constraints *(optional)*, Loop type decision, Scope (+5 more)

### Community 40 - "Community 40"
Cohesion: 0.25
Nodes (7): Done when, Feature: Dev-loop fast-path — startup camera scan + debug reset broadcast, Hardware state going, Hints, Known constraints, Scope, WhatDevelopment iteration on Mpermission loss on everyreinstall

## Knowledge Gaps
- **148 isolated node(s):** `LandmarkIndex`, `SwipeDirection`, `AGENTS.md — xrdroiddesk`, `Agent Role Definitions & Loop Structure`, `Version: 1.1` (+143 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **18 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `GestureAccessibilityService` connect `Hand Tracking Pipeline` to `USB Setup & Dialog Automation`, `HID Frame Building & UVC Enable`, `Gesture Recognition & Config`, `Community 36`, `UVC Camera & TCP Pilot`, `Desktop Input Injection`?**
  _High betweenness centrality (0.071) - this node is a cross-community bridge._
- **Why does `XRealGlassesCamera` connect `UVC Camera & TCP Pilot` to `USB Setup & Dialog Automation`, `Camera Device Tests`, `Hand Tracking Pipeline`?**
  _High betweenness centrality (0.052) - this node is a cross-community bridge._
- **Why does `UsbSetupAutomator` connect `USB Setup & Dialog Automation` to `Hand Tracking Pipeline`?**
  _High betweenness centrality (0.032) - this node is a cross-community bridge._
- **What connects `LandmarkIndex`, `SwipeDirection`, `AGENTS.md — xrdroiddesk` to the rest of the system?**
  _149 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `USB Setup & Dialog Automation` be split into smaller, more focused modules?**
  _Cohesion score 0.14260249554367202 - nodes in this community are weakly interconnected._
- **Should `HID Frame Building & UVC Enable` be split into smaller, more focused modules?**
  _Cohesion score 0.0797979797979798 - nodes in this community are weakly interconnected._
- **Should `Gesture Recognition & Config` be split into smaller, more focused modules?**
  _Cohesion score 0.12561576354679804 - nodes in this community are weakly interconnected._
