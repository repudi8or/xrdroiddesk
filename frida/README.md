# Frida RE scripts

## Goal

Enumerate every HID message Control Glasses (`com.xreal.glassescontrol.store`) sends to the
XReal One Pro glasses during a fresh plug-in, so xrdroiddesk can replicate the full init
sequence and remove the CG dependency entirely.

## hook_cg_hid.js

Hooks inside the CG process:
- `UsbDeviceConnection.bulkTransfer` (both OUT frames and IN responses)
- `UsbDeviceConnection.controlTransfer` (UVC VS_PROBE/VS_COMMIT, any DP AltMode cmds)
- `UsbManager.openDevice` (shows when CG opens non-UVC vs post-UVC device)
- `UsbManager.requestPermission`

Every HID frame decoded: `msgId`, `innerLen`, payload hex, full frame hex.

## One-time setup

### 1. Download frida-server

Device is an arm64 Android. Get the matching frida-server from:
```
https://github.com/frida/frida/releases
```
File: `frida-server-<version>-android-arm64.xz`

Check your Frida CLI version first: `frida --version`
The server version must match.

```bash
xz -d frida-server-*-android-arm64.xz
make frida-server-push FRIDA_SERVER=frida-server-*-android-arm64
```

### 2. Start frida-server on device

```bash
make frida-server-start
```

Or manually:
```bash
adb -s 192.168.1.xxx:PORT shell "nohup /data/local/tmp/frida-server &"
```

The Pixel 10 Pro can run frida-server without root in most cases via USB debugging.
If you get permission errors, check the device has USB debugging enabled and authorized.

## Capture workflow

```bash
# 1. Make sure glasses are UNPLUGGED
# 2. Start CG (so Acceptor runs in the hooked process when USB attaches):
make frida-hook-cg
# OR to spawn fresh:
make frida-hook-cg-spawn

# 3. Plug in glasses — watch the log
# 4. Look for lines like:
#    [CG-USB] +123ms BULK-OUT ep=0x01 msgId=HOST_TYPE innerLen=17 payload=[02 00 00 00]
#    [CG-USB] +145ms BULK-OUT ep=0x01 msgId=SDK_VERSION innerLen=22 payload=[33 2e 33 2e 30 00]
#    [CG-USB] +147ms BULK-OUT ep=0x01 msgId=GET_USB_CONFIG innerLen=17 payload=(empty)
#    [CG-USB] +320ms BULK-IN  ep=0x81 msgId=GET_USB_CONFIG innerLen=21 payload=[00 01 01 00]
#    [CG-USB] +321ms BULK-OUT ep=0x01 msgId=SET_USB_CONFIG innerLen=21 payload=[40 01 00 00]
```

## What to look for

The key unknowns beyond the known HOST_TYPE→SDK_VERSION→GET→SET sequence:

1. **Are there messages BEFORE HOST_TYPE?** — any ping, status, or handshake
2. **What is between SDK_VERSION and GET?** — any intermediate command
3. **After UVC re-enum, does CG open the new device?** — `openDevice` with 15 interfaces
4. **Does CG send anything to the UVC device after re-enum?** — display config, camera config
5. **Any controlTransfer calls?** — DP AltMode negotiation or UVC streaming config from CG side

## Known msgIds

| msgId  | Name            | Payload |
|--------|-----------------|---------|
| 0x0001 | HEARTBEAT       | MCU response when not in config mode |
| 0x0026 | PING_26         | Possible ping/keep-alive |
| 0x0031 | SDK_VERSION     | ASCII string `"3.3.0\0"` |
| 0x0060 | HOST_TYPE       | `[01 00 00 00]`=display, `[02 00 00 00]`=SDK-mode |
| 0x00D2 | GET_USB_CONFIG  | Empty request; response = 4-byte bitmask |
| 0x00D3 | SET_USB_CONFIG  | 4-byte LE bitmask: uvc0(6)\|enable(8) = 0x140 = `40 01 00 00` |

## UsbConfigList bitmask

Bits (from `UsbConfigList.java` field order):
```
bit 0: ncm
bit 1: ecm
bit 2: uac
bit 3: hid_ctrl
bit 4: mtp
bit 5: mass_storage
bit 6: uvc0     ← set this
bit 7: uvc1
bit 8: enable   ← set this
```

CG sends `uvc0=1, enable=1` → `0x140` → LE bytes `40 01 00 00`.
Current config (GET response) is OR'd with `0x140` before SET.
