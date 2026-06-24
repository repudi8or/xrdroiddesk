package com.repudi8or.xrdroiddesk.camera

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Enables UVC mode on XReal One Pro glasses via HID USB commands.
 *
 * Matches CG's `prepareCheckNcm` path (libota-lib.so / NRBSPGetUsbConfigAll + NRBSPSetUsbConfigAll):
 *   1. Open USB device (permission must already be granted)
 *   2. Claim HID init interface (iface 0, ep_01 OUT / ep_81 IN, maxPkt=1024)
 *   3. Send GET (0xD2) — NO HOST_TYPE=2 first (that puts MCU in SDK mode, breaks GET)
 *   4. If GET returns real config (innerLen > 17): SET (0xD3) with currentConfig | uvc0 | enable
 *   5. Glasses re-enumerate with UVC interfaces in ~2s
 *
 * RE sources: libota-lib.so (XrealGlasses class in CG APK), jadx + Frida on Control Glasses.
 */
class GlassesUvcEnabler(
    context: Context,
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbConnection: UsbDeviceConnection? = null
    private var usbDevice: UsbDevice? = null
    private var hidInitIface: UsbInterface? = null

    companion object {
        private const val TAG = "GlassesUvcEnabler"

        // RE'd from NRBSPGetUsbConfigAll / NRBSPSetUsbConfigAll in libota-lib.so
        private const val MSG_USB_CONFIG_GET = 0x00D2
        private const val MSG_USB_CONFIG_SET = 0x00D3

        // RE'd from XREALManager.java / libnr_glasses_api.so (same HID channel, iface 0)
        private const val MSG_W_HOST_TYPE = 0x0060
        private const val MSG_W_SDK_VERSION = 0x0031
        private const val HID_HOST_TYPE_ANDROID = 2 // SDK mode — required for MCU to process GET/SET
        private const val HID_SDK_VERSION = "3.3.0"
        private const val HID_POST_INIT_DELAY_MS = 200L

        // UsbConfigList bitmask fields (order from UsbConfigList.java):
        // ncm(0), ecm(1), uac(2), hid_ctrl(3), mtp(4), mass_storage(5), uvc0(6), uvc1(7), enable(8)
        // OR mask to add to current config: uvc0(bit6) | enable(bit8) = 0x140
        private const val UVC_ENABLE_MASK = 0x140

        private const val HID_REPORT_ID: Byte = 0xfd.toByte()
        private const val HID_TRANSFER_TIMEOUT_MS = 1000
        private const val HID_RESPONSE_TIMEOUT_MS = 500

        // Wait up to this long for a real GET response (innerLen > 17).
        // CG waits up to 5s for the ping gate; we poll GET directly instead.
        private const val GET_RESPONSE_WAIT_MS = 3000L
    }

    /**
     * Enable UVC by sending GET (0xD2) → SET (0xD3) without any HOST_TYPE=2 init.
     *
     * Returns true if SET was sent after a real GET response, indicating re-enum is in progress.
     * Returns false if GET only returned heartbeats (MCU not responding to config commands).
     */
    suspend fun enableUvc(): Boolean =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "═══ enableUvc START (GET→SET, no HOST_TYPE) ═══")
            openUsbDevice()

            val conn =
                usbConnection ?: run {
                    Log.e(TAG, "No USB connection")
                    return@withContext false
                }
            val device = usbDevice ?: return@withContext false

            val hidIface =
                (0 until device.interfaceCount)
                    .map { device.getInterface(it) }
                    .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
                    ?: run {
                        Log.e(TAG, "No HID interface found")
                        return@withContext false
                    }

            conn.claimInterface(hidIface, true)
            hidInitIface = hidIface

            val epOut =
                (0 until hidIface.endpointCount)
                    .map { hidIface.getEndpoint(it) }
                    .firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
                    ?: run {
                        Log.e(TAG, "No HID OUT endpoint")
                        return@withContext false
                    }
            val epIn: UsbEndpoint? =
                (0 until hidIface.endpointCount)
                    .map { hidIface.getEndpoint(it) }
                    .firstOrNull { it.direction == UsbConstants.USB_DIR_IN }

            Log.i(TAG, "HID iface ${hidIface.id} ep_out=0x${epOut.address.toString(16)} ep_in=0x${epIn?.address?.toString(16) ?: "none"}")

            // GET current config — no HOST_TYPE=2 beforehand
            val getFrame = buildHidFrame(MSG_USB_CONFIG_GET, ByteArray(0))
            logHex("GET frame", getFrame)
            val gSent = conn.bulkTransfer(epOut, getFrame, getFrame.size, HID_TRANSFER_TIMEOUT_MS)
            Log.i(TAG, "GET sent $gSent/${getFrame.size}")

            // Wait for real GET response (innerLen > 17 = has payload beyond header)
            var currentConfig = 0
            var gotConfig = false
            if (epIn != null) {
                val deadline = System.currentTimeMillis() + GET_RESPONSE_WAIT_MS
                while (System.currentTimeMillis() < deadline) {
                    val buf = ByteArray(1024)
                    val r = conn.bulkTransfer(epIn, buf, buf.size, HID_RESPONSE_TIMEOUT_MS)
                    if (r < 7) continue
                    val innerLen = (buf[5].toInt() and 0xFF) or ((buf[6].toInt() and 0xFF) shl 8)
                    val msgId = (buf[15].toInt() and 0xFF) or ((buf[16].toInt() and 0xFF) shl 8)
                    if (innerLen > 17 && r >= 26) {
                        currentConfig =
                            (buf[22].toInt() and 0xFF) or
                            ((buf[23].toInt() and 0xFF) shl 8) or
                            ((buf[24].toInt() and 0xFF) shl 16) or
                            ((buf[25].toInt() and 0xFF) shl 24)
                        Log.i(
                            TAG,
                            "GET real response innerLen=$innerLen msgId=0x${msgId.toString(16)}" +
                                " currentConfig=0x${currentConfig.toString(16)} ✓",
                        )
                        logHex("GET response", buf.copyOf(r))
                        gotConfig = true
                        break
                    } else {
                        Log.d(TAG, "GET heartbeat innerLen=$innerLen msgId=0x${msgId.toString(16)}")
                    }
                }
            } else {
                Log.w(TAG, "No IN endpoint — cannot read GET response")
            }

            if (!gotConfig) {
                // CG hasn't initialized the MCU (Acceptor missed its window or is blocked).
                // Fall back: send HOST_TYPE=2 + SDK_VERSION to put MCU in SDK/config mode,
                // then retry GET. The display may go dark briefly, but it's already dark here.
                Log.i(TAG, "GET heartbeats — HOST_TYPE=2 fallback: initializing MCU ourselves")
                val htFrame = buildHidFrame(MSG_W_HOST_TYPE, byteArrayOf(HID_HOST_TYPE_ANDROID.toByte(), 0, 0, 0))
                logHex("HOST_TYPE frame", htFrame)
                val htSent = conn.bulkTransfer(epOut, htFrame, htFrame.size, HID_TRANSFER_TIMEOUT_MS)
                Log.i(TAG, "HOST_TYPE sent $htSent/${htFrame.size}")

                val versionPayload = HID_SDK_VERSION.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
                val vFrame = buildHidFrame(MSG_W_SDK_VERSION, versionPayload)
                logHex("SDK_VERSION frame", vFrame)
                val vSent = conn.bulkTransfer(epOut, vFrame, vFrame.size, HID_TRANSFER_TIMEOUT_MS)
                Log.i(TAG, "SDK_VERSION sent $vSent/${vFrame.size}")

                delay(HID_POST_INIT_DELAY_MS)

                // Retry GET after HOST_TYPE=2
                val getFrame2 = buildHidFrame(MSG_USB_CONFIG_GET, ByteArray(0))
                logHex("GET retry frame", getFrame2)
                val gSent2 = conn.bulkTransfer(epOut, getFrame2, getFrame2.size, HID_TRANSFER_TIMEOUT_MS)
                Log.i(TAG, "GET retry sent $gSent2/${getFrame2.size}")

                if (epIn != null) {
                    val deadline2 = System.currentTimeMillis() + GET_RESPONSE_WAIT_MS
                    while (System.currentTimeMillis() < deadline2) {
                        val buf2 = ByteArray(1024)
                        val r2 = conn.bulkTransfer(epIn, buf2, buf2.size, HID_RESPONSE_TIMEOUT_MS)
                        if (r2 < 7) continue
                        val innerLen2 = (buf2[5].toInt() and 0xFF) or ((buf2[6].toInt() and 0xFF) shl 8)
                        val msgId2 = (buf2[15].toInt() and 0xFF) or ((buf2[16].toInt() and 0xFF) shl 8)
                        if (innerLen2 > 17 && r2 >= 26) {
                            currentConfig =
                                (buf2[22].toInt() and 0xFF) or
                                ((buf2[23].toInt() and 0xFF) shl 8) or
                                ((buf2[24].toInt() and 0xFF) shl 16) or
                                ((buf2[25].toInt() and 0xFF) shl 24)
                            Log.i(
                                TAG,
                                "GET real response (after HOST_TYPE) innerLen=$innerLen2 msgId=0x${msgId2.toString(
                                    16,
                                )} currentConfig=0x${currentConfig.toString(16)} ✓",
                            )
                            gotConfig = true
                            break
                        } else {
                            Log.d(TAG, "GET heartbeat (after HOST_TYPE) innerLen=$innerLen2 msgId=0x${msgId2.toString(16)}")
                        }
                    }
                }

                if (!gotConfig) {
                    Log.w(TAG, "═══ enableUvc END result=false (GET heartbeats even after HOST_TYPE=2) ═══")
                    return@withContext false
                }
            }

            // SET: current config OR'd with uvc0(bit6)|enable(bit8)
            val newConfig = currentConfig or UVC_ENABLE_MASK
            Log.i(
                TAG,
                "SET currentConfig=0x${currentConfig.toString(
                    16,
                )} → newConfig=0x${newConfig.toString(16)} (mask=0x${UVC_ENABLE_MASK.toString(16)})",
            )
            val setPayload =
                ByteBuffer
                    .allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .apply { putInt(newConfig) }
                    .array()
            val setFrame = buildHidFrame(MSG_USB_CONFIG_SET, setPayload)
            logHex("SET frame", setFrame)
            val sSent = conn.bulkTransfer(epOut, setFrame, setFrame.size, HID_TRANSFER_TIMEOUT_MS)
            Log.i(TAG, "SET sent $sSent/${setFrame.size} — expecting re-enum in ~2s")

            val ok = sSent == setFrame.size
            Log.i(TAG, "═══ enableUvc END result=$ok ═══")
            ok
        }

    private fun openUsbDevice() {
        if (usbConnection != null) {
            Log.i(TAG, "USB device already open — skipping")
            return
        }
        val device =
            usbManager.deviceList.values.firstOrNull {
                it.vendorId == XRealGlassesCamera.VENDOR_ID &&
                    it.productId == XRealGlassesCamera.PRODUCT_ID
            } ?: run {
                Log.w(TAG, "XReal device not found")
                return
            }
        if (!usbManager.hasPermission(device)) {
            Log.w(TAG, "USB permission not granted")
            return
        }
        val conn =
            usbManager.openDevice(device) ?: run {
                Log.e(TAG, "openDevice failed")
                return
            }
        usbDevice = device
        usbConnection = conn
        Log.i(TAG, "USB opened fd=${conn.fileDescriptor}")
    }

    /**
     * Build a G-series HID frame (RE from cmd_build_sdk at 0xa9ec4 in libnr_glasses_api.so):
     *   [0]      0xfd  — Report ID
     *   [1..4]   CRC32 LE — init=~innerLen&0xff, reflected poly 0xEDB88320, no final XOR
     *   [5..6]   innerLen LE = totalLen-5 = 17+payload.size
     *   [7..14]  zeros
     *   [15..16] msgId LE
     *   [17..21] zeros
     *   [22+]    payload
     * CRC covers buf[6..totalLen] inclusive (one trailing zero beyond frame).
     */
    private fun buildHidFrame(
        msgId: Int,
        payload: ByteArray,
    ): ByteArray {
        val totalLen = 22 + payload.size
        val innerLen = totalLen - 5
        val buf = ByteArray(totalLen + 1)
        buf[0] = HID_REPORT_ID
        buf[5] = (innerLen and 0xFF).toByte()
        buf[6] = ((innerLen shr 8) and 0xFF).toByte()
        buf[15] = (msgId and 0xFF).toByte()
        buf[16] = ((msgId shr 8) and 0xFF).toByte()
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, buf, 22, payload.size)
        val crc = crc32Hid(buf, 6, innerLen, innerLen)
        buf[1] = (crc and 0xFF).toByte()
        buf[2] = ((crc shr 8) and 0xFF).toByte()
        buf[3] = ((crc shr 16) and 0xFF).toByte()
        buf[4] = ((crc shr 24) and 0xFF).toByte()
        return buf.copyOf(totalLen)
    }

    private fun crc32Hid(
        data: ByteArray,
        offset: Int,
        length: Int,
        innerLen: Int,
    ): Int {
        var crc = innerLen.inv() and 0xff
        for (i in offset until offset + length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
            }
        }
        return crc
    }

    private fun logHex(
        label: String,
        data: ByteArray,
    ) {
        Log.i(TAG, "$label (${data.size}B): ${data.joinToString(" ") { "%02x".format(it) }}")
    }

    fun release() {
        val conn = usbConnection
        hidInitIface?.let {
            try {
                conn?.releaseInterface(it)
            } catch (_: Exception) {
            }
        }
        hidInitIface = null
        try {
            conn?.close()
        } catch (_: Exception) {
        }
        usbConnection = null
        usbDevice = null
    }
}
