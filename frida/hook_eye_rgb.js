/**
 * hook_eye_rgb.js — capture Eye RGB camera init sequence from Nebula / CG
 *
 * Hooks both the Java USB Host layer and the native libusb layer inside
 * libnr_rgb_camera.so / libnr_libusb.so, so we capture every command
 * sent to the glasses when the Eye camera is activated.
 *
 * Run against the Nebula process (ai.nreal.nebula.universal) which owns
 * the Eye camera pipeline:
 *
 *   adb -s <DEVICE> shell am start -n ai.nreal.nebula.universal/.MainActivity
 *   frida -U -n ai.nreal.nebula.universal -l hook_eye_rgb.js
 *
 * Then plug in glasses. Watch for:
 *   [EYE] BULK-OUT ep=0x01 msgId=RGB_SWITCH(0x0068) payload=[01 00 00 00]
 *   [EYE] openDevice ... ifaces=13  ← UVC re-enum with xreal1 active
 *   [EYE] LIBUSB_BULK vid=0x3318 pid=... ep=0x89 ← Eye UVC stream endpoint
 *
 * Can also run against CG (com.xreal.glassescontrol.store) — if CG ever
 * sends 0x68 it will be captured here too.
 *
 * Key findings from static analysis of libnr_rgb_camera.so:
 *   CMD_RW_RGB_SWITCH = 0x68  (same msgId space as HOST_TYPE=0x60)
 *   uvc_fmt=0 for Eye UVC stream; expected resolution TBD (1280x720@30 in older devices)
 *   UsbConfigList bit 7 = uvc1 — may need SET_USB_CONFIG with uvc0|uvc1|enable = 0x1C0
 */

'use strict';

Java.perform(function () {
    var TAG = '[EYE]';
    var t0 = Date.now();

    function ts() { return '+' + (Date.now() - t0) + 'ms'; }

    function toHex(arr, offset, length) {
        var out = [];
        for (var i = offset; i < offset + length; i++) {
            out.push(('0' + (arr[i] & 0xff).toString(16)).slice(-2));
        }
        return out.join(' ');
    }

    function msgIdName(id) {
        switch (id) {
            case 0x0001: return 'HEARTBEAT';
            case 0x0026: return 'PING_26';
            case 0x0031: return 'SDK_VERSION';
            case 0x0060: return 'HOST_TYPE';
            case 0x0068: return 'RGB_SWITCH';   // CMD_RW_RGB_SWITCH — Eye camera on/off
            case 0x00D2: return 'GET_USB_CONFIG';
            case 0x00D3: return 'SET_USB_CONFIG';
            default:     return '0x' + id.toString(16).toUpperCase().padStart(4, '0');
        }
    }

    function decodeSetUsbConfig(payload4) {
        // UsbConfigList bitmask (4 bytes LE):
        //   bit 0=ncm  1=ecm  2=uac  3=hid_ctrl  4=mtp  5=mass_storage
        //   bit 6=uvc0  7=uvc1  8=enable
        var bits = (payload4[0] & 0xff) | ((payload4[1] & 0xff) << 8) |
                   ((payload4[2] & 0xff) << 16) | ((payload4[3] & 0xff) << 24);
        var flags = [];
        if (bits & (1 << 6)) flags.push('uvc0');
        if (bits & (1 << 7)) flags.push('uvc1');  // ← Eye camera UVC stream
        if (bits & (1 << 8)) flags.push('enable');
        if (bits & (1 << 0)) flags.push('ncm');
        if (bits & (1 << 1)) flags.push('ecm');
        if (bits & (1 << 2)) flags.push('uac');
        return '0x' + bits.toString(16) + ' [' + (flags.join('|') || 'none') + ']';
    }

    function parseHidFrame(buf, offset, length) {
        if (length < 17) return null;
        if ((buf[offset] & 0xff) !== 0xfd) return null;
        var innerLen = (buf[offset + 5] & 0xff) | ((buf[offset + 6] & 0xff) << 8);
        var msgId    = (buf[offset + 15] & 0xff) | ((buf[offset + 16] & 0xff) << 8);
        var payloadStart = offset + 22;
        var payloadLen   = Math.max(0, length - 22);
        var payloadHex   = payloadLen > 0 ? toHex(buf, payloadStart, Math.min(payloadLen, 64)) : '(empty)';
        var extra = '';
        if (msgId === 0x00D3 && payloadLen >= 4) {
            extra = ' config=' + decodeSetUsbConfig(buf.slice ? buf.slice(payloadStart, payloadStart + 4) :
                [buf[payloadStart], buf[payloadStart+1], buf[payloadStart+2], buf[payloadStart+3]]);
        }
        if (msgId === 0x0068 && payloadLen >= 1) {
            extra = ' switch=' + ((buf[payloadStart] & 0xff) === 1 ? 'ON' : 'OFF');
        }
        return { innerLen: innerLen, msgId: msgId, payloadHex: payloadHex, extra: extra,
                 frameHex: toHex(buf, offset, Math.min(length, 64)) };
    }

    // ── Java USB Host hooks (same target as hook_cg_hid.js) ──────────────────

    var Conn = Java.use('android.hardware.usb.UsbDeviceConnection');

    Conn.bulkTransfer.overload(
        'android.hardware.usb.UsbEndpoint', '[B', 'int', 'int'
    ).implementation = function (ep, buf, length, timeout) {
        var result = this.bulkTransfer(ep, buf, length, timeout);
        var addr = ep.getAddress() & 0xff;
        var isOut = (addr & 0x80) === 0;
        if (isOut && buf !== null && length > 0) {
            var frame = parseHidFrame(buf, 0, length);
            if (frame !== null) {
                console.log(TAG + ' ' + ts() + ' BULK-OUT ep=0x' + addr.toString(16) +
                    ' msgId=' + msgIdName(frame.msgId) +
                    ' innerLen=' + frame.innerLen +
                    ' payload=[' + frame.payloadHex + ']' + frame.extra +
                    ' ret=' + result + '/' + length);
            } else if (length > 0) {
                console.log(TAG + ' ' + ts() + ' BULK-OUT ep=0x' + addr.toString(16) +
                    ' len=' + length + ' ret=' + result +
                    ' raw=[' + toHex(buf, 0, Math.min(length, 32)) + ']');
            }
        } else if (!isOut && result > 0) {
            var frame2 = parseHidFrame(buf, 0, result);
            if (frame2 !== null) {
                console.log(TAG + ' ' + ts() + ' BULK-IN  ep=0x' + addr.toString(16) +
                    ' msgId=' + msgIdName(frame2.msgId) +
                    ' innerLen=' + frame2.innerLen +
                    ' payload=[' + frame2.payloadHex + ']' + frame2.extra);
            } else if (result > 0) {
                console.log(TAG + ' ' + ts() + ' BULK-IN  ep=0x' + addr.toString(16) +
                    ' len=' + result + ' raw=[' + toHex(buf, 0, Math.min(result, 32)) + ']');
            }
        }
        return result;
    };

    Conn.controlTransfer.overload('int', 'int', 'int', 'int', '[B', 'int', 'int').implementation = function (
        rt, req, val, idx, buf, len, timeout
    ) {
        var result = this.controlTransfer(rt, req, val, idx, buf, len, timeout);
        var data = (buf !== null && len > 0) ? toHex(buf, 0, Math.min(len, 32)) : '(null)';
        console.log(TAG + ' ' + ts() + ' CTRL reqType=0x' + (rt & 0xff).toString(16) +
            ' req=0x' + req.toString(16) + ' val=0x' + val.toString(16) +
            ' idx=' + idx + ' len=' + len + ' ret=' + result + ' data=[' + data + ']');
        return result;
    };

    var UsbManager = Java.use('android.hardware.usb.UsbManager');

    UsbManager.openDevice.implementation = function (device) {
        var result = this.openDevice(device);
        var vid    = device.getVendorId() & 0xffff;
        var pid    = device.getProductId() & 0xffff;
        var ifaces = device.getInterfaceCount();
        var cfg    = device.getConfiguration(0);
        var cfgName = cfg !== null ? cfg.getName() : '?';
        console.log(TAG + ' ' + ts() + ' openDevice vid=0x' + vid.toString(16) +
            ' pid=0x' + pid.toString(16) + ' ifaces=' + ifaces +
            ' cfg="' + cfgName + '"' +
            ' dev="' + device.getDeviceName() + '"' +
            ' → ' + (result !== null ? 'OK' : 'FAIL'));
        if (result !== null) {
            t0 = Date.now();
            console.log(TAG + ' timer reset at openDevice');
            // Log all interfaces to spot new Eye UVC interfaces
            for (var i = 0; i < ifaces; i++) {
                try {
                    var iface = device.getInterface(i);
                    console.log(TAG + '   iface[' + i + '] id=' + iface.getId() +
                        ' class=' + iface.getInterfaceClass() +
                        '/' + iface.getInterfaceSubclass() +
                        ' eps=' + iface.getEndpointCount() +
                        ' name="' + iface.getName() + '"');
                } catch(e) {}
            }
        }
        return result;
    };

    UsbManager.requestPermission.overload(
        'android.hardware.usb.UsbDevice', 'android.app.PendingIntent'
    ).implementation = function (device, intent) {
        var vid = device.getVendorId() & 0xffff;
        var pid = device.getProductId() & 0xffff;
        console.log(TAG + ' ' + ts() + ' requestPermission vid=0x' + vid.toString(16) +
            ' pid=0x' + pid.toString(16) + ' ifaces=' + device.getInterfaceCount());
        return this.requestPermission(device, intent);
    };

    // ── Native libusb hooks (libnr_rgb_camera.so uses libusb directly) ────────
    // These fire for the camera stream endpoint (ep 0x89 for xreal0; Eye may be 0x8A or new ep)

    var libusbMod = Process.findModuleByName('libnr_libusb.so') ||
                    Process.findModuleByName('libusb1.0.so');
    if (libusbMod) {
        var bulkSym = libusbMod.findExportByName('libusb_bulk_transfer');
        if (bulkSym) {
            Interceptor.attach(bulkSym, {
                onEnter: function (args) {
                    // libusb_bulk_transfer(handle, ep, data, length, transferred, timeout)
                    this.ep     = args[1].toInt32() & 0xff;
                    this.data   = args[2];
                    this.length = args[3].toInt32();
                    this.isOut  = (this.ep & 0x80) === 0;
                },
                onLeave: function (retval) {
                    var ret = retval.toInt32();
                    if (this.isOut && this.length > 0) {
                        var raw = [];
                        for (var i = 0; i < Math.min(this.length, 48); i++) {
                            raw.push(('0' + (this.data.add(i).readU8()).toString(16)).slice(-2));
                        }
                        console.log(TAG + ' ' + ts() + ' LIBUSB BULK-OUT ep=0x' +
                            this.ep.toString(16) + ' len=' + this.length +
                            ' ret=' + ret + ' raw=[' + raw.join(' ') + ']');
                    } else if (!this.isOut && ret > 0) {
                        var raw2 = [];
                        for (var j = 0; j < Math.min(ret, 48); j++) {
                            raw2.push(('0' + (this.data.add(j).readU8()).toString(16)).slice(-2));
                        }
                        console.log(TAG + ' ' + ts() + ' LIBUSB BULK-IN  ep=0x' +
                            this.ep.toString(16) + ' read=' + ret +
                            ' raw=[' + raw2.join(' ') + ']');
                    }
                }
            });
            console.log(TAG + ' libusb_bulk_transfer hooked in ' + libusbMod.name);
        } else {
            console.log(TAG + ' WARNING: libusb_bulk_transfer not found in ' + libusbMod.name);
        }

        var openSym = libusbMod.findExportByName('libusb_open');
        if (openSym) {
            Interceptor.attach(openSym, {
                onEnter: function (args) {
                    // libusb_open(dev, handle) — dev is libusb_device*
                    // descriptor not easily readable without libusb_get_device_descriptor call,
                    // but we can log the address for correlation
                    console.log(TAG + ' ' + ts() + ' LIBUSB libusb_open dev=' + args[0]);
                },
                onLeave: function (retval) {
                    console.log(TAG + ' ' + ts() + ' LIBUSB libusb_open ret=' + retval.toInt32());
                }
            });
            console.log(TAG + ' libusb_open hooked');
        }
    } else {
        console.log(TAG + ' libnr_libusb.so not loaded yet — native hooks will fire once module loads');
        // Retry when the module is loaded (Nebula lazy-loads native libs)
        var observer = new ApiResolver('module');
        // Fallback: just log; user can re-inject after Nebula init
    }

    console.log(TAG + ' ── Eye RGB hooks installed ──');
    console.log(TAG + ' Watching for: RGB_SWITCH(0x68), SET_USB_CONFIG with uvc1(bit7), new UVC interfaces');
    console.log(TAG + ' Expected Eye enable sequence:');
    console.log(TAG + '   1. HOST_TYPE=2 → SDK_VERSION → GET_USB_CONFIG');
    console.log(TAG + '   2. SET_USB_CONFIG uvc0|uvc1|enable = 0x1C0 [C0 01 00 00]  (hypothesis)');
    console.log(TAG + '   3. Re-enum with new VideoStreaming interface for xreal1');
    console.log(TAG + '   4. RGB_SWITCH(0x68) payload=[01 00 00 00]  (hypothesis)');
    console.log(TAG + '   5. libusb_open on new UVC device → LIBUSB BULK-IN frames from Eye camera');
});
