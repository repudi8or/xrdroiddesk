---
type: community
cohesion: 0.14
members: 18
---

# HID RE Findings & Docs

**Cohesion:** 0.14 - loosely connected
**Members:** 18 nodes

## Members
- [[Autonomous UVC enablement flow — fast path (hasPermission=true) and slow path (first plug)]] - concept - CLAUDE.md
- [[Control Glasses app (com.xreal.glassescontrol.store) — enables UVC mode via HID, preferred USB handler]] - concept - CLAUDE.md
- [[Frida RE scripts — hooks CG (com.xreal.glassescontrol.store) USB traffic to capture full HID init sequence]] - document - frida/README.md
- [[GlassesUvcEnabler — sends HID USB commands to glasses MCU to enable UVC mode autonomously]] - code - CLAUDE.md
- [[Google Pixel 10 Pro (target phone — Android Desktop mode host)]] - concept - CLAUDE.md
- [[HID frame format — Report ID 0xfd, CRC32, innerLen, msgId (RE'd from cmd_build_sdk 0xa9ec4)]] - concept - CLAUDE.md
- [[Key unknowns for full CG removal — messages before HOST_TYPE, between SDK_VERSION and GET, post re-enum CG behavior]] - concept - frida/README.md
- [[Known msgIds table — HEARTBEAT(0x01), PING_26(0x26), SDK_VERSION(0x31), HOST_TYPE(0x60), GET_USB_CONFIG(0xD2), SET_USB_CONFIG(0xD3)]] - concept - frida/README.md
- [[Rationale Native Kotlin over Unity — AccessibilityService must run as persistent background service; Unity controls process lifecycle making this very difficult]] - rationale - CLAUDE.md
- [[SET payload 0x140 (uvc0=1 + enable=1) — LE bytes 40 01 00 00 — confirmed by Frida]] - concept - CLAUDE.md
- [[TCP pilot path — 169.254.1.150180 via USB NCM, secondary fallback for HID config]] - concept - CLAUDE.md
- [[TDD approach — JUnit45+Mockk for unit, EspressoUIAutomator for instrumented; no PR without tests]] - concept - CLAUDE.md
- [[UsbConfigList bitmask — bit layout ncm(0)..uvc0(6)..uvc1(7)..enable(8), CG OR's 0x140 with current config]] - concept - frida/README.md
- [[UsbConfigList — 9-field bitmask (ncmecmuachid_ctrlmtpmass_storageuvc0uvc1enable) 4-byte LE]] - concept - CLAUDE.md
- [[XReal One Pro glasses (primary hardware — USB-C UVC camera source)]] - concept - CLAUDE.md
- [[hook_cg_hid.js — Frida script hooking bulkTransfer, controlTransfer, openDevice, requestPermission in CG]] - code - frida/README.md
- [[libnr_glasses_api.so — native library from Control Glasses APK, RE source for HID protocol]] - concept - CLAUDE.md
- [[xrdroiddesk — Android app using XReal One Pro hand gestures to control Android Desktop]] - document - CLAUDE.md

## Live Query (requires Dataview plugin)

```dataview
TABLE source_file, type FROM #community/HID_RE_Findings__Docs
SORT file.name ASC
```
