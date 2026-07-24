---
source_file: "frida/README.md"
type: "concept"
community: "HID RE Findings & Docs"
location: "lines 91-106"
tags:
  - graphify/concept
  - graphify/EXTRACTED
  - community/HID_RE_Findings__Docs
---

# UsbConfigList bitmask — bit layout ncm(0)..uvc0(6)..uvc1(7)..enable(8), CG OR's 0x140 with current config

## Connections
- [[SET payload 0x140 (uvc0=1 + enable=1) — LE bytes 40 01 00 00 — confirmed by Frida]] - `references` [EXTRACTED]
- [[UsbConfigList — 9-field bitmask (ncmecmuachid_ctrlmtpmass_storageuvc0uvc1enable) 4-byte LE]] - `references` [EXTRACTED]

#graphify/concept #graphify/EXTRACTED #community/HID_RE_Findings__Docs
