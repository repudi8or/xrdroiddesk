---
source_file: "CLAUDE.md"
type: "code"
community: "HID RE Findings & Docs"
location: "lines 82-84"
tags:
  - graphify/code
  - graphify/EXTRACTED
  - community/HID_RE_Findings__Docs
---

# GlassesUvcEnabler — sends HID USB commands to glasses MCU to enable UVC mode autonomously

## Connections
- [[Autonomous UVC enablement flow — fast path (hasPermission=true) and slow path (first plug)]] - `implements` [EXTRACTED]
- [[HID frame format — Report ID 0xfd, CRC32, innerLen, msgId (RE'd from cmd_build_sdk 0xa9ec4)]] - `references` [EXTRACTED]
- [[SET payload 0x140 (uvc0=1 + enable=1) — LE bytes 40 01 00 00 — confirmed by Frida]] - `references` [EXTRACTED]
- [[TCP pilot path — 169.254.1.150180 via USB NCM, secondary fallback for HID config]] - `references` [EXTRACTED]
- [[UsbConfigList — 9-field bitmask (ncmecmuachid_ctrlmtpmass_storageuvc0uvc1enable) 4-byte LE]] - `references` [EXTRACTED]
- [[libnr_glasses_api.so — native library from Control Glasses APK, RE source for HID protocol]] - `references` [EXTRACTED]

#graphify/code #graphify/EXTRACTED #community/HID_RE_Findings__Docs
