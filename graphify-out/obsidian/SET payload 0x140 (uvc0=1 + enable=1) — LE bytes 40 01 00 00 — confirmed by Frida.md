---
source_file: "CLAUDE.md"
type: "concept"
community: "HID RE Findings & Docs"
location: "lines 123-125"
tags:
  - graphify/concept
  - graphify/EXTRACTED
  - community/HID_RE_Findings__Docs
---

# SET payload 0x140 (uvc0=1 + enable=1) — LE bytes 40 01 00 00 — confirmed by Frida

## Connections
- [[GlassesUvcEnabler — sends HID USB commands to glasses MCU to enable UVC mode autonomously]] - `references` [EXTRACTED]
- [[UsbConfigList bitmask — bit layout ncm(0)..uvc0(6)..uvc1(7)..enable(8), CG OR's 0x140 with current config]] - `references` [EXTRACTED]

#graphify/concept #graphify/EXTRACTED #community/HID_RE_Findings__Docs
