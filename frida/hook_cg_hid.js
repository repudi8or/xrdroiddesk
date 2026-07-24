/**
 * hook_cg_hid.js — enumerate every USB message CG sends to XReal One Pro glasses
 *
 * Run INSIDE the com.xreal.glassescontrol.store process:
 *
 *   # Spawn CG first (so Acceptor is in our hooked process when USB attach fires):
 *   adb -s <DEVICE> shell am start -n com.xreal.glassescontrol.store/.ui.main.MainActivity
 *   frida -U -n com.xreal.glassescontrol.store -l hook_cg_hid.js
 *
 *   # Then plug in glasses. Watch stdout for every HID frame + response.
 *   # Filter to just the key events:
 *   #   frida ... 2>&1 | grep -E 'CG-USB|BULK-OUT|BULK-IN|openDevice'
 *
 * What this captures:
 *   - Every UsbDeviceConnection.bulkTransfer (both OUT frames and IN responses)
 *   - Every UsbDeviceConnection.controlTransfer (DP Alt Mode / UVC control commands)
 *   - Every UsbManager.openDevice call (shows when CG opens non-UVC vs UVC device)
 *   - HID frames decoded: msgId, innerLen, payload hex
 *
 * Known msgIds (from RE of libota-lib.so / libnr_glasses_api.so):
 *   0x0031 SDK_VERSION
 *   0x0060 HOST_TYPE   payload[0]: 1=display, 2=SDK-mode (display dark)
 *   0x00D2 GET_USB_CONFIG
 *   0x00D3 SET_USB_CONFIG  payload: uint32 LE bitmask uvc0(6)|enable(8)=0x140
 *   0x0001 HEARTBEAT   (MCU response when not in config mode)
 */

'use strict';

Java.perform(function () {
    var TAG = '[CG-USB]';
    var t0 = Date.now();

    function ts() {
        return '+' + (Date.now() - t0) + 'ms';
    }

    function toHex(javaByteArray, offset, length) {
        var out = [];
        var end = offset + length;
        for (var i = offset; i < end; i++) {
            var b = javaByteArray[i] & 0xff;
            out.push(('0' + b.toString(16)).slice(-2));
        }
        return out.join(' ');
    }

    function msgIdName(id) {
        switch (id) {
            case 0x0001: return 'HEARTBEAT';
            case 0x0026: return 'PING_26';
            case 0x0031: return 'SDK_VERSION';
            case 0x0060: return 'HOST_TYPE';
            case 0x00D2: return 'GET_USB_CONFIG';
            case 0x00D3: return 'SET_USB_CONFIG';
            default:     return '0x' + id.toString(16).toUpperCase().padStart(4, '0');
        }
    }

    /**
     * Parse a HID frame from a Java byte[] (direct index access, offset from start of frame).
     * Returns { innerLen, msgId, payloadHex, frameHex } or null if not a valid 0xfd frame.
     */
    function parseHidFrame(buf, offset, length) {
        if (length < 17) return null;
        if ((buf[offset] & 0xff) !== 0xfd) return null;
        var innerLen = (buf[offset + 5] & 0xff) | ((buf[offset + 6] & 0xff) << 8);
        var msgId = (buf[offset + 15] & 0xff) | ((buf[offset + 16] & 0xff) << 8);
        var payloadStart = offset + 22;
        var payloadLen = Math.max(0, length - 22);
        var payloadHex = payloadLen > 0 ? toHex(buf, payloadStart, Math.min(payloadLen, 64)) : '(empty)';
        var frameHex = toHex(buf, offset, Math.min(length, 64));
        return { innerLen: innerLen, msgId: msgId, payloadHex: payloadHex, frameHex: frameHex };
    }

    // ── UsbDeviceConnection.bulkTransfer (4-arg: endpoint, buffer, length, timeout) ──
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
                console.log(TAG + ' ' + ts() +
                    ' BULK-OUT ep=0x' + addr.toString(16) +
                    ' msgId=' + msgIdName(frame.msgId) +
                    ' innerLen=' + frame.innerLen +
                    ' payload=[' + frame.payloadHex + ']' +
                    ' sent=' + result + '/' + length +
                    '\n  frame=[' + frame.frameHex + ']');
            } else if (length > 0) {
                console.log(TAG + ' ' + ts() +
                    ' BULK-OUT ep=0x' + addr.toString(16) +
                    ' len=' + length + ' ret=' + result +
                    ' raw=[' + toHex(buf, 0, Math.min(length, 32)) + ']');
            }
        } else if (!isOut && result > 0) {
            var frame2 = parseHidFrame(buf, 0, result);
            if (frame2 !== null) {
                console.log(TAG + ' ' + ts() +
                    ' BULK-IN  ep=0x' + addr.toString(16) +
                    ' msgId=' + msgIdName(frame2.msgId) +
                    ' innerLen=' + frame2.innerLen +
                    ' payload=[' + frame2.payloadHex + ']' +
                    ' read=' + result);
            } else if (result > 0) {
                console.log(TAG + ' ' + ts() +
                    ' BULK-IN  ep=0x' + addr.toString(16) +
                    ' len=' + result +
                    ' raw=[' + toHex(buf, 0, Math.min(result, 32)) + ']');
            }
        }
        return result;
    };

    // ── UsbDeviceConnection.bulkTransfer (5-arg: endpoint, buffer, offset, length, timeout) ──
    Conn.bulkTransfer.overload(
        'android.hardware.usb.UsbEndpoint', '[B', 'int', 'int', 'int'
    ).implementation = function (ep, buf, offset, length, timeout) {
        var result = this.bulkTransfer(ep, buf, offset, length, timeout);
        var addr = ep.getAddress() & 0xff;
        var isOut = (addr & 0x80) === 0;

        if (isOut && buf !== null && length > 0) {
            var frame = parseHidFrame(buf, offset, length);
            if (frame !== null) {
                console.log(TAG + ' ' + ts() +
                    ' BULK-OUT(off) ep=0x' + addr.toString(16) +
                    ' msgId=' + msgIdName(frame.msgId) +
                    ' innerLen=' + frame.innerLen +
                    ' payload=[' + frame.payloadHex + ']' +
                    ' sent=' + result + '/' + length);
            } else {
                console.log(TAG + ' ' + ts() +
                    ' BULK-OUT(off) ep=0x' + addr.toString(16) +
                    ' offset=' + offset + ' len=' + length + ' ret=' + result);
            }
        }
        return result;
    };

    // ── UsbDeviceConnection.controlTransfer — UVC VS_PROBE/VS_COMMIT and any DP AltMode cmds ──
    Conn.controlTransfer.overload('int', 'int', 'int', 'int', '[B', 'int', 'int').implementation = function (
        requestType, request, value, index, buf, length, timeout
    ) {
        var result = this.controlTransfer(requestType, request, value, index, buf, length, timeout);
        var dataHex = (buf !== null && length > 0)
            ? toHex(buf, 0, Math.min(length, 32))
            : '(null)';
        console.log(TAG + ' ' + ts() +
            ' CTRL reqType=0x' + (requestType & 0xff).toString(16) +
            ' req=0x' + request.toString(16) +
            ' val=0x' + value.toString(16) +
            ' idx=' + index +
            ' len=' + length + ' ret=' + result +
            ' data=[' + dataHex + ']');
        return result;
    };

    // ── UsbManager.openDevice — track which device CG opens (non-UVC vs UVC) ──
    var UsbManager = Java.use('android.hardware.usb.UsbManager');
    UsbManager.openDevice.implementation = function (device) {
        var result = this.openDevice(device);
        var vid = device.getVendorId() & 0xffff;
        var pid = device.getProductId() & 0xffff;
        var ifaces = device.getInterfaceCount();
        console.log(TAG + ' ' + ts() +
            ' openDevice vid=0x' + vid.toString(16) +
            ' pid=0x' + pid.toString(16) +
            ' ifaces=' + ifaces +
            ' name="' + device.getDeviceName() + '"' +
            ' → ' + (result !== null ? 'OK' : 'FAIL'));
        if (result !== null) {
            // Reset timer so first transfer timing is relative to openDevice
            t0 = Date.now();
            console.log(TAG + ' timer reset at openDevice');
        }
        return result;
    };

    // ── UsbManager.requestPermission — see when/who CG requests permission for ──
    UsbManager.requestPermission.overload(
        'android.hardware.usb.UsbDevice', 'android.app.PendingIntent'
    ).implementation = function (device, intent) {
        var vid = device.getVendorId() & 0xffff;
        var pid = device.getProductId() & 0xffff;
        console.log(TAG + ' ' + ts() +
            ' requestPermission vid=0x' + vid.toString(16) +
            ' pid=0x' + pid.toString(16) +
            ' ifaces=' + device.getInterfaceCount());
        return this.requestPermission(device, intent);
    };

    console.log(TAG + ' ── hooks installed, waiting for USB attach ──');
    console.log(TAG + ' known msgIds: HOST_TYPE=0x0060, SDK_VERSION=0x0031, GET=0x00D2, SET=0x00D3');
});
