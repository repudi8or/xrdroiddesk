package com.repudi8or.xrdroiddesk.camera

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Enables UVC mode on XReal glasses via HID commands.
 *
 * Primary path: TCP pilot daemon at 169.254.1.1:50180 (USB NCM, no USB permission needed).
 * Call [enableUvcViaTcp] immediately on USB_DEVICE_ATTACHED — before the USB permission
 * dialog — so HOST_TYPE=2 reaches the MCU within its ~2s init window.
 *
 * Fallback path: USB bulk (iface 0, ep_01/ep_81, maxPkt=1024). All HID commands go here;
 * iface 8 (ep_05/ep_88, maxPkt=3) is Consumer Control only — NOT a config channel.
 */
class GlassesUvcEnabler(
    context: Context,
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var usbConnection: UsbDeviceConnection? = null
    private var usbDevice: UsbDevice? = null

    // Init HID interface (bInterfaceNumber=0, ep_01 OUT / ep_81 IN) -- HOST_TYPE + SDK_VERSION
    private var hidInitIface: UsbInterface? = null
    private var hidInitEpOut: UsbEndpoint? = null
    private var hidInitEpIn: UsbEndpoint? = null

    // Config HID interface (bInterfaceNumber=8, ep_05 OUT / ep_88 IN) -- GET/SET USB config
    private var hidConfigIface: UsbInterface? = null
    private var hidConfigEpOut: UsbEndpoint? = null
    private var hidConfigEpIn: UsbEndpoint? = null

    companion object {
        private const val TAG = "GlassesUvcEnabler"
        private val PILOT_IPS = listOf("169.254.1.1", "169.254.2.1")
        private const val PILOT_PORT = 50180
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val PILOT_WAIT_RETRIES = 20
        private const val PILOT_WAIT_DELAY_MS = 500L
        private const val READ_TIMEOUT_MS = 5000
        private const val MAX_RESPONSE_BYTES = 512

        // Early TCP path (enableUvcViaTcp) — aggressive retry to hit the MCU init window.
        // Pilot is reachable as soon as NCM comes up (~0.5-1s after USB attach). Retry every
        // 200ms so we connect as early as possible and send HOST_TYPE before the window closes.
        private const val TCP_CONNECT_TIMEOUT_MS = 1000
        private const val TCP_RETRIES = 15
        private const val TCP_RETRY_DELAY_MS = 200L
        private const val TCP_READ_TIMEOUT_MS = 1000

        // After HOST_TYPE=2, wait this long for the MCU to enter SDK mode before sending 0x26.
        private const val TCP_SDK_MODE_WAIT_MS = 300L

        // G-series HID protocol (RE'd from libnr_glasses_api.so cmd_build_sdk / hid_write)
        private const val HID_REPORT_ID: Byte = 0xfd.toByte()
        private const val MSG_W_HOST_TYPE = 0x0060
        private const val MSG_W_SDK_VERSION = 0x0031

        // RE'd from NRBSPGetUsbConfigAll (0xbbe54): MOVZ w1, #0xd2
        private const val MSG_USB_CONFIG_GET = 0x00D2

        // RE'd from NRBSPSetUsbConfigAll (0xbb498): MOVZ w1, #0xd3
        // SET vs GET is distinguished purely by msgID (0xD3 vs 0xD2) -- no cmdType field in frame.
        private const val MSG_USB_CONFIG_SET = 0x00D3

        // RE'd from wait_for_service_ready_with_handle (0xae488): MOVZ w1, #0x26
        // NRBSPGetUsbConfigAll AND NRBSPSetUsbConfigAll both call wait_for_service_ready (up to 6x)
        // before sending GET/SET. This ping puts the MCU into SDK config mode. Without it, GET
        // returns only heartbeats and SET has no effect.
        private const val MSG_SERVICE_READY = 0x0026
        private const val SERVICE_READY_RETRIES = 6
        private const val SERVICE_READY_DELAY_MS = 200L
        private const val HID_HOST_TYPE_ANDROID = 2 // SDK mode required for glasses to process USB config SET
        private const val HID_SDK_VERSION = "3.3.0"
        private const val HID_TRANSFER_TIMEOUT_MS = 1000
        private const val HID_MSG_DELAY_MS = 100L

        private const val HID_POST_INIT_DELAY_MS = 200L
        private const val HID_RESPONSE_TIMEOUT_MS = 500

        // bInterfaceNumber of the config HID interface (ep_05/ep_88) seen in sysfs 1-1:1.8
        private const val HID_CONFIG_IFACE_NUMBER = 8

        // TCP retry params for enableUvc() after HID init wakes the pilot.
        // Pilot starts within ~1-2s of HOST_TYPE; 20 retries × 1.5s = 30s window.
        private const val TCP_HID_RETRIES = 20
        private const val TCP_HID_CONNECT_MS = 1000
        private const val TCP_HID_RETRY_DELAY_MS = 500L
    }

    /**
     * USB-bulk HID init → 0x26 service-ready ping → HID GET+SET to enable UVC.
     *
     * RE finding (2026-06-17): both NRBSPGetUsbConfigAll (0xbc9a0) and NRBSPSetUsbConfigAll
     * (0xbb498) call wait_for_service_ready (sends msgId=0x26) up to 6× before their respective
     * commands. Without this ping, MCU returns only heartbeats to GET and ignores SET. We now
     * replicate this sequence. Falls back to TCP pilot path if HID GET returns only heartbeats
     * (MCU timing window may still apply).
     */
    suspend fun enableUvc(): Boolean =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "═══ enableUvc START ═══")
            Log.i(TAG, "  Step 1: open USB device")
            openUsbDevice()
            Log.i(TAG, "  Step 2: HID init (HOST_TYPE=2 + SDK_VERSION + 0x26 service-ready ping)")
            claimAndSendHidInit()
            Log.i(TAG, "  Step 3: HID GET+SET (native SDK path)")
            val hidOk = sendUsbConfigViaHid()
            if (hidOk) {
                Log.i(TAG, "  Step 3 ✓ HID path complete — expect re-enumeration in ~2s")
                Log.i(TAG, "═══ enableUvc END (HID path) ═══")
                true
            } else {
                Log.w(TAG, "  Step 3 ✗ HID GET only heartbeats — MCU not in config mode")
                Log.i(TAG, "  Step 4: TCP pilot (diagnostic only — TCP cannot trigger re-enum)")
                val tcpOk = sendUsbConfigViaTcp()
                Log.i(TAG, "  Step 4 ${if (tcpOk) "TCP SET sent (no re-enum expected)" else "✗ TCP also failed"}")
                Log.i(TAG, "═══ enableUvc END (result=false — HID failed; Control Glasses required) ═══")
                false
            }
        }

    /**
     * After HID init has woken the pilot, connect via TCP and send GET + SET(0x140).
     * Sends 0x26 service-ready pings first — same requirement as the HID path.
     */
    private suspend fun sendUsbConfigViaTcp(): Boolean {
        val socket =
            connectToPilot(TCP_HID_RETRIES, TCP_HID_CONNECT_MS, TCP_HID_RETRY_DELAY_MS)
                ?: run {
                    Log.w(TAG, "  4: TCP pilot unreachable after HID init (${TCP_HID_RETRIES} retries)")
                    return false
                }
        return try {
            val out = socket.getOutputStream()

            // 0x26 service-ready ping via TCP — same requirement as HID GET/SET.
            // Without this the MCU returns heartbeats to GET/SET and ignores the config change.
            Log.i(TAG, "  4a: sending 0x26 service-ready ping(s) via TCP (up to $SERVICE_READY_RETRIES)")
            val pingFrame = buildHidFrame(MSG_SERVICE_READY, ByteArray(0))
            var tcpServiceReady = false
            repeat(SERVICE_READY_RETRIES) { attempt ->
                logHex("  4a: TCP 0x26 ping attempt ${attempt + 1}", pingFrame)
                out.write(pingFrame)
                out.flush()
                val resp = readTcpFrameWithTimeout(socket, SERVICE_READY_DELAY_MS.toInt())
                if (resp != null) {
                    val msgId = (resp[15].toInt() and 0xFF) or ((resp[16].toInt() and 0xFF) shl 8)
                    val innerLen = (resp[5].toInt() and 0xFF) or ((resp[6].toInt() and 0xFF) shl 8)
                    logHex("  4a: TCP 0x26 response attempt ${attempt + 1} (msgId=0x${msgId.toString(16)} innerLen=$innerLen)", resp)
                    if (msgId != 0x0001) {
                        Log.i(TAG, "  4a: MCU in config mode via TCP (attempt ${attempt + 1}) ✓")
                        tcpServiceReady = true
                        return@repeat
                    }
                    Log.d(TAG, "  4a: TCP 0x26 heartbeat attempt ${attempt + 1} — not ready yet")
                } else {
                    Log.d(TAG, "  4a: TCP 0x26 no response attempt ${attempt + 1}")
                }
            }
            if (!tcpServiceReady) {
                Log.w(TAG, "  4a: MCU still not in config mode after $SERVICE_READY_RETRIES TCP 0x26 pings — sending SET anyway")
            }

            Log.i(TAG, "  4b: TCP GET (msgId=0xD2)")
            val getFrame = buildHidFrame(MSG_USB_CONFIG_GET, ByteArray(0))
            logHex("  4b: TCP GET frame", getFrame)
            out.write(getFrame)
            out.flush()
            delay(HID_MSG_DELAY_MS)
            drainTcpIn(socket, "  4b: TCP post-GET")

            Log.i(TAG, "  4c: TCP SET (msgId=0xD3, payload=0x140 uvc0+enable)")
            val setPayload =
                ByteBuffer
                    .allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .apply { putInt(0x140) }
                    .array()
            val setFrame = buildHidFrame(MSG_USB_CONFIG_SET, setPayload)
            logHex("  4c: TCP SET frame", setFrame)
            out.write(setFrame)
            out.flush()
            drainTcpIn(socket, "  4c: TCP post-SET")

            Log.i(TAG, "  4c: TCP SET complete — expecting re-enumeration in ~2s")
            true
        } catch (e: Exception) {
            Log.e(TAG, "enableUvc TCP error: ${e.message}")
            false
        } finally {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    /** Read one frame from TCP with a short timeout; returns null on timeout or error. */
    private fun readTcpFrameWithTimeout(
        socket: Socket,
        timeoutMs: Int,
    ): ByteArray? {
        val prev = socket.soTimeout
        socket.soTimeout = timeoutMs
        return try {
            val buf = ByteArray(1024)
            val r = socket.getInputStream().read(buf)
            if (r > 0) buf.copyOf(r) else null
        } catch (_: Exception) {
            null
        } finally {
            socket.soTimeout = prev
        }
    }

    /**
     * Enable UVC via TCP pilot daemon (169.254.1.1:50180, USB NCM, no USB permission needed).
     *
     * Sends the same G-series HID frames as the USB bulk path, but over TCP. Call this
     * immediately on USB_DEVICE_ATTACHED so HOST_TYPE=2 reaches the MCU within its ~2s
     * init window — before the Android USB permission dialog has even been shown.
     *
     * The MCU re-enumerates ~2s after SET; the caller should then wait for a fresh
     * USB_DEVICE_ATTACHED with UVC interfaces present.
     */
    suspend fun enableUvcViaTcp(): Boolean =
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            Log.i(TAG, "═══ enableUvcViaTcp START (early path, no USB permission needed) ═══")

            val socket =
                connectToPilot(TCP_RETRIES, TCP_CONNECT_TIMEOUT_MS, TCP_RETRY_DELAY_MS)
                    ?: return@withContext false.also {
                        Log.w(TAG, "TCP early: pilot unreachable after $TCP_RETRIES retries (+${System.currentTimeMillis() - t0}ms)")
                    }
            Log.i(TAG, "TCP early: connected at +${System.currentTimeMillis() - t0}ms")

            try {
                val out = socket.getOutputStream()

                // HOST_TYPE=2 + SDK_VERSION — send immediately, must arrive within MCU boot window
                val hostTypeFrame =
                    buildHidFrame(MSG_W_HOST_TYPE, byteArrayOf(HID_HOST_TYPE_ANDROID.toByte(), 0, 0, 0))
                logHex("TCP early HOST_TYPE=2 (+${System.currentTimeMillis() - t0}ms)", hostTypeFrame)
                out.write(hostTypeFrame)
                out.flush()

                val versionFrame =
                    buildHidFrame(MSG_W_SDK_VERSION, HID_SDK_VERSION.toByteArray(Charsets.US_ASCII) + byteArrayOf(0))
                logHex("TCP early SDK_VERSION (+${System.currentTimeMillis() - t0}ms)", versionFrame)
                out.write(versionFrame)
                out.flush()

                // 0x26 service-ready pings — MCU must respond with non-heartbeat before GET/SET
                // No wait between HOST_TYPE and pings: CG sends these back-to-back, and
                // the ~300ms delay we had was pushing us past the MCU's config window.
                Log.i(TAG, "TCP early: 0x26 service-ready (+${System.currentTimeMillis() - t0}ms)")
                val pingFrame = buildHidFrame(MSG_SERVICE_READY, ByteArray(0))
                var serviceReady = false
                repeat(SERVICE_READY_RETRIES) { attempt ->
                    out.write(pingFrame)
                    out.flush()
                    val resp = readTcpFrameWithTimeout(socket, SERVICE_READY_DELAY_MS.toInt())
                    if (resp != null) {
                        val msgId = (resp[15].toInt() and 0xFF) or ((resp[16].toInt() and 0xFF) shl 8)
                        val innerLen = (resp[5].toInt() and 0xFF) or ((resp[6].toInt() and 0xFF) shl 8)
                        if (msgId != 0x0001) {
                            Log.i(
                                TAG,
                                "TCP early: MCU in config mode (attempt ${attempt + 1}) msgId=0x${msgId.toString(
                                    16,
                                )} innerLen=$innerLen ✓ (+${System.currentTimeMillis() - t0}ms)",
                            )
                            serviceReady = true
                            return@repeat
                        }
                        Log.d(TAG, "TCP early: 0x26 heartbeat attempt ${attempt + 1}")
                    } else {
                        Log.d(TAG, "TCP early: 0x26 no response attempt ${attempt + 1}")
                    }
                }
                if (!serviceReady) {
                    Log.w(
                        TAG,
                        "TCP early: MCU not in config mode after $SERVICE_READY_RETRIES pings (+${System.currentTimeMillis() - t0}ms) — sending GET+SET anyway",
                    )
                }

                // GET current config
                val getFrame = buildHidFrame(MSG_USB_CONFIG_GET, ByteArray(0))
                logHex("TCP early GET (+${System.currentTimeMillis() - t0}ms)", getFrame)
                out.write(getFrame)
                out.flush()
                val getResp = readTcpFrameWithTimeout(socket, 500)
                if (getResp != null) {
                    val innerLen = (getResp[5].toInt() and 0xFF) or ((getResp[6].toInt() and 0xFF) shl 8)
                    val msgId = (getResp[15].toInt() and 0xFF) or ((getResp[16].toInt() and 0xFF) shl 8)
                    Log.i(
                        TAG,
                        "TCP early: GET response innerLen=$innerLen msgId=0x${msgId.toString(
                            16,
                        )} ${if (innerLen > 17) "✓ real config" else "(heartbeat)"}",
                    )
                }

                // SET uvc0+enable
                val setPayload =
                    ByteBuffer
                        .allocate(4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .apply { putInt(0x140) }
                        .array()
                val setFrame = buildHidFrame(MSG_USB_CONFIG_SET, setPayload)
                logHex("TCP early SET (+${System.currentTimeMillis() - t0}ms)", setFrame)
                out.write(setFrame)
                out.flush()
                val setResp = readTcpFrameWithTimeout(socket, 500)
                if (setResp != null) {
                    val innerLen = (setResp[5].toInt() and 0xFF) or ((setResp[6].toInt() and 0xFF) shl 8)
                    val msgId = (setResp[15].toInt() and 0xFF) or ((setResp[16].toInt() and 0xFF) shl 8)
                    Log.i(TAG, "TCP early: SET response innerLen=$innerLen msgId=0x${msgId.toString(16)}")
                }

                Log.i(TAG, "═══ enableUvcViaTcp END (+${System.currentTimeMillis() - t0}ms) ═══")
                true
            } catch (e: Exception) {
                Log.e(TAG, "TCP early error: ${e.message}")
                false
            } finally {
                try {
                    socket.close()
                } catch (_: IOException) {
                }
            }
        }

    /** Read and log up to 3 frames from the TCP socket for diagnostics. */
    private fun drainTcpIn(
        socket: Socket,
        label: String,
    ) {
        val prev = socket.soTimeout
        socket.soTimeout = TCP_READ_TIMEOUT_MS
        try {
            val input = socket.getInputStream()
            repeat(3) {
                val buf = ByteArray(1024)
                val r =
                    try {
                        input.read(buf)
                    } catch (_: Exception) {
                        return
                    }
                if (r <= 0) return
                val innerLen = if (r >= 7) (buf[5].toInt() and 0xFF) or ((buf[6].toInt() and 0xFF) shl 8) else -1
                val msgId = if (r >= 17) (buf[15].toInt() and 0xFF) or ((buf[16].toInt() and 0xFF) shl 8) else -1
                logHex("$label (innerLen=$innerLen msgId=0x${msgId.toString(16)})", buf.copyOf(r))
            }
        } finally {
            socket.soTimeout = prev
        }
    }

    /**
     * Send MSG_USB_CONFIG_GET then MSG_USB_CONFIG_SET via iface 0 (ep_01) to enable UVC.
     *
     * Returns true if SET was sent AND GET returned real config (innerLen > 17), indicating
     * the MCU is in SDK config mode. Returns false if GET only returned heartbeats — caller
     * should fall back to TCP path.
     */
    private suspend fun sendUsbConfigViaHid(): Boolean {
        val conn =
            usbConnection ?: run {
                Log.e(TAG, "No USB connection for HID config commands")
                return false
            }
        val epOut = hidInitEpOut ?: return false
        val epIn = hidInitEpIn
        val ifaceId = hidInitIface?.id ?: -1

        // 4-byte uint32 LE bitmask: uvc0(bit6)|enable(bit8) = 0x140.
        // Frida-confirmed: CG sends exactly this (uvc0=1, enable=1 only).
        val uvcPayload =
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).run {
                putInt(0x140)
                array()
            }

        val getFrame = buildHidFrame(MSG_USB_CONFIG_GET, ByteArray(0))
        val setFrame = buildHidFrame(MSG_USB_CONFIG_SET, uvcPayload)

        Log.i(TAG, "  3a: sending HID GET (msgId=0xD2) on iface $ifaceId")
        logHex("HID GET on iface $ifaceId", getFrame)
        val getR = conn.bulkTransfer(epOut, getFrame, getFrame.size, HID_TRANSFER_TIMEOUT_MS)
        Log.i(TAG, "  3a: HID GET sent $getR/${getFrame.size} ${if (getR == getFrame.size) "✓" else "✗"}")

        // Drain ep_81 until we see a real USB config response (innerLen > 17) or 2s timeout.
        // MCU streams heartbeats (msgId=0x0001, innerLen=17) on ep_81 when not in config mode.
        // After 0x26 service-ready, the actual GET response should have innerLen=53 (36-byte payload).
        var foundGetResponse = false
        var heartbeatCount = 0
        if (epIn != null) {
            val deadline = System.currentTimeMillis() + 2000L
            while (System.currentTimeMillis() < deadline) {
                val gBuf = ByteArray(1024)
                val gr = conn.bulkTransfer(epIn, gBuf, gBuf.size, HID_RESPONSE_TIMEOUT_MS)
                if (gr <= 0) continue
                val innerLen = (gBuf[5].toInt() and 0xFF) or ((gBuf[6].toInt() and 0xFF) shl 8)
                val msgId = (gBuf[15].toInt() and 0xFF) or ((gBuf[16].toInt() and 0xFF) shl 8)
                if (innerLen > 17) {
                    logHex("  3a: HID GET real response (innerLen=$innerLen msgId=0x${msgId.toString(16)})", gBuf.copyOf(gr))
                    foundGetResponse = true
                    Log.i(TAG, "  3a: MCU in config mode — GET returned real config (innerLen=$innerLen) ✓")
                    break
                } else {
                    heartbeatCount++
                    Log.d(TAG, "  3a: heartbeat #$heartbeatCount (msgId=0x${msgId.toString(16)} innerLen=$innerLen)")
                }
            }
        }

        if (!foundGetResponse) {
            Log.w(TAG, "  3a: only heartbeats ($heartbeatCount) after 0x26 ping — MCU not in config mode ✗ — falling back to TCP")
            return false
        }

        delay(HID_MSG_DELAY_MS)

        Log.i(TAG, "  3b: sending HID SET (msgId=0xD3, payload=0x140 uvc0+enable) on iface $ifaceId")
        logHex("HID SET on iface $ifaceId", setFrame)
        val sent = conn.bulkTransfer(epOut, setFrame, setFrame.size, HID_TRANSFER_TIMEOUT_MS)
        Log.i(TAG, "  3b: HID SET sent $sent/${setFrame.size} ${if (sent == setFrame.size) "✓ — expect re-enumeration in ~2s" else "✗"}")

        // Read up to 3 frames after SET — look for SET ACK (msgId=0xD3 or innerLen > 17)
        if (epIn != null) {
            repeat(3) { i ->
                val inBuf = ByteArray(1024)
                val rIn = conn.bulkTransfer(epIn, inBuf, inBuf.size, HID_RESPONSE_TIMEOUT_MS)
                if (rIn > 0) {
                    val innerLen = (inBuf[5].toInt() and 0xFF) or ((inBuf[6].toInt() and 0xFF) shl 8)
                    val msgId = (inBuf[15].toInt() and 0xFF) or ((inBuf[16].toInt() and 0xFF) shl 8)
                    logHex("  3b: HID SET ACK[$i] (innerLen=$innerLen msgId=0x${msgId.toString(16)})", inBuf.copyOf(rIn))
                } else {
                    return@repeat
                }
            }
        }
        return sent == setFrame.size
    }

    /**
     * Probe-only: open USB, claim HID + send init, wait for pilot, log greeting and GET response.
     * Does NOT send any SET command. Used for protocol capture and diagnostics.
     */
    suspend fun probeAndLogGreeting(): Boolean =
        withContext(Dispatchers.IO) {
            openUsbDevice()
            claimAndSendHidInit()

            val socket = waitForPilot() ?: return@withContext false
            try {
                socket.soTimeout = READ_TIMEOUT_MS
                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                // Short timeout for initial greeting -- daemon may not send one
                socket.soTimeout = 1000
                val greeting =
                    try {
                        val buf = ByteArray(MAX_RESPONSE_BYTES)
                        val read = input.read(buf)
                        if (read > 0) buf.copyOf(read) else null
                    } catch (_: IOException) {
                        null
                    }
                socket.soTimeout = READ_TIMEOUT_MS

                if (greeting != null) {
                    logHex("Pilot greeting", greeting)
                } else {
                    Log.i(TAG, "Pilot connected -- no unsolicited greeting")
                }

                val getMsg = PilotProtocol.buildGet()
                logHex("Sending GET", getMsg)
                output.write(getMsg)
                output.flush()

                val response = readResponse(input)
                if (response != null) {
                    logHex("GET response raw", response)
                    val payload = PilotProtocol.parseResponse(response)
                    if (payload != null) {
                        Log.i(TAG, "GET response payload (${payload.size} bytes) -- protocol confirmed")
                        logHex("GET payload", payload)
                    } else {
                        Log.w(TAG, "GET response received but failed protocol parse -- wrong format")
                        logHex("GET raw (unparsed)", response)
                    }
                } else {
                    Log.w(TAG, "No response to GET -- trying SET to check if daemon processes commands")
                    logHex("Sending SET", PilotProtocol.buildSetUsbConfig(PilotProtocol.UsbConfig.UVC_ENABLED))
                    output.write(PilotProtocol.buildSetUsbConfig(PilotProtocol.UsbConfig.UVC_ENABLED))
                    output.flush()
                    val setResp = readResponse(input)
                    if (setResp != null) {
                        logHex("SET response raw", setResp)
                    } else {
                        Log.w(TAG, "No response to SET either")
                    }
                }
                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "Probe error: ${e.message}")
                return@withContext false
            } finally {
                try {
                    socket.close()
                } catch (_: IOException) {
                }
            }
        }

    /**
     * Claim both HID interfaces and send G-series init messages on the init interface.
     *
     * Init interface (bInterfaceNumber=0, ep_01/ep_81): HOST_TYPE + SDK_VERSION
     * Config interface (bInterfaceNumber=8, ep_05/ep_88): GET/SET USB config (claimed here,
     *   used later in sendUsbConfigViaHid).
     */
    private fun claimInterfacesNoInit() {
        val conn = usbConnection ?: return
        val device = usbDevice ?: return
        val hidInterfaces =
            (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .filter { it.interfaceClass == UsbConstants.USB_CLASS_HID }
        if (hidInterfaces.isEmpty()) {
            Log.w(TAG, "No HID interfaces found")
            return
        }

        val initIface = hidInterfaces.first()
        conn.claimInterface(initIface, true)
        hidInitIface = initIface
        hidInitEpOut =
            (0 until initIface.endpointCount)
                .map { initIface.getEndpoint(it) }
                .firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
        hidInitEpIn =
            (0 until initIface.endpointCount)
                .map { initIface.getEndpoint(it) }
                .firstOrNull { it.direction == UsbConstants.USB_DIR_IN }

        val configIface = hidInterfaces.firstOrNull { it.id == HID_CONFIG_IFACE_NUMBER } ?: initIface
        if (configIface !== initIface) {
            conn.claimInterface(configIface, true)
            hidConfigEpOut =
                (0 until configIface.endpointCount)
                    .map { configIface.getEndpoint(it) }
                    .firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
            hidConfigEpIn =
                (0 until configIface.endpointCount)
                    .map { configIface.getEndpoint(it) }
                    .firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
        } else {
            hidConfigEpOut = hidInitEpOut
            hidConfigEpIn = hidInitEpIn
        }
        hidConfigIface = configIface
        Log.i(TAG, "Claimed HID ifaces: init=${initIface.id}, config=${configIface.id} (no init msgs sent)")

        // Read interface 8 HID descriptor to understand valid report sizes/IDs
        val ifaceNum = configIface.id
        val descBuf = ByteArray(256)
        // GET_DESCRIPTOR for HID report descriptor: bmReqType=0x81, bReq=0x06, wValue=0x2200, wIndex=ifaceNum
        val r = conn.controlTransfer(0x81, 0x06, 0x2200, ifaceNum, descBuf, descBuf.size, 2000)
        if (r > 0) {
            logHex("HID descriptor iface $ifaceNum ($r bytes)", descBuf.copyOf(r))
        } else {
            Log.w(TAG, "HID descriptor iface $ifaceNum: read failed r=$r")
        }
    }

    private suspend fun claimAndSendHidInit() {
        val conn =
            usbConnection ?: run {
                Log.w(TAG, "HID init skipped -- no USB connection (will try pilot anyway)")
                return
            }
        val device = usbDevice ?: return

        // Find all HID interfaces (class=3)
        val hidInterfaces =
            (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .filter { it.interfaceClass == UsbConstants.USB_CLASS_HID }

        Log.i(TAG, "  2: found ${hidInterfaces.size} HID interface(s): ${hidInterfaces.map { "id=${it.id} eps=${it.endpointCount}" }}")

        if (hidInterfaces.isEmpty()) {
            Log.w(TAG, "  2: no HID interfaces found — skipping init ✗")
            return
        }

        // Init interface: first HID interface (bInterfaceNumber=0, ep_01/ep_81)
        val initIface = hidInterfaces.first()
        val initEpOut =
            (0 until initIface.endpointCount)
                .map { initIface.getEndpoint(it) }
                .firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
        val initEpIn =
            (0 until initIface.endpointCount)
                .map { initIface.getEndpoint(it) }
                .firstOrNull { it.direction == UsbConstants.USB_DIR_IN }

        Log.i(
            TAG,
            "Init HID iface id=${initIface.id} ep_out=0x${initEpOut?.address?.toString(16) ?: "none"}" +
                " ep_in=0x${initEpIn?.address?.toString(16) ?: "none"}" +
                " maxPktOut=${initEpOut?.maxPacketSize} maxPktIn=${initEpIn?.maxPacketSize}",
        )

        if (!conn.claimInterface(initIface, true)) {
            Log.w(TAG, "claimInterface(init iface ${initIface.id}) failed -- will attempt HID send anyway")
        }
        hidInitIface = initIface
        hidInitEpOut = initEpOut
        hidInitEpIn = initEpIn

        // Config interface: prefer bInterfaceNumber=8 (ep_05/ep_88), fall back to init iface
        val configIface = hidInterfaces.firstOrNull { it.id == HID_CONFIG_IFACE_NUMBER } ?: initIface
        val configEpOut: UsbEndpoint?
        val configEpIn: UsbEndpoint?
        if (configIface === initIface) {
            Log.w(TAG, "Config HID interface (id=$HID_CONFIG_IFACE_NUMBER) not found -- falling back to init iface ${initIface.id}")
            configEpOut = initEpOut
            configEpIn = initEpIn
        } else {
            configEpOut =
                (0 until configIface.endpointCount)
                    .map { configIface.getEndpoint(it) }
                    .firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
            configEpIn =
                (0 until configIface.endpointCount)
                    .map { configIface.getEndpoint(it) }
                    .firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
            if (!conn.claimInterface(configIface, true)) {
                Log.w(TAG, "claimInterface(config iface ${configIface.id}) failed -- will attempt GET/SET anyway")
            }
        }
        hidConfigIface = configIface
        hidConfigEpOut = configEpOut
        hidConfigEpIn = configEpIn

        if (initEpOut == null) {
            Log.w(TAG, "  2: Init HID interface has no OUT endpoint -- cannot send init messages")
            return
        }

        // Send HOST_TYPE=2 immediately — this must reach the MCU within its ~300ms config window.
        // All delays are stripped to minimise time from USB permission grant to 0x26 pings.
        // Previously: descriptor read + 100ms delay + 500ms read + 200ms wait + 600ms drain = 1400ms.
        // Now: ~10ms total overhead — 0x26 pings arrive within ~300ms of USB attach.
        val initStart = System.currentTimeMillis()
        Log.i(TAG, "  2a: sending HOST_TYPE=2 (msgId=0x60) on iface ${initIface.id}")
        val hostTypeFrame = buildHidFrame(MSG_W_HOST_TYPE, byteArrayOf(HID_HOST_TYPE_ANDROID.toByte(), 0, 0, 0))
        val r1 = conn.bulkTransfer(initEpOut, hostTypeFrame, hostTypeFrame.size, HID_TRANSFER_TIMEOUT_MS)
        Log.i(
            TAG,
            "  2a: HOST_TYPE sent $r1/${hostTypeFrame.size} bytes ${if (r1 == hostTypeFrame.size) "✓" else "✗"} (+${System.currentTimeMillis() - initStart}ms)",
        )

        // SDK_VERSION immediately after HOST_TYPE — no delay; USB driver serialises the writes.
        Log.i(TAG, "  2b: sending SDK_VERSION=$HID_SDK_VERSION (msgId=0x31) (+${System.currentTimeMillis() - initStart}ms)")
        val versionFrame = buildHidFrame(MSG_W_SDK_VERSION, HID_SDK_VERSION.toByteArray(Charsets.US_ASCII) + byteArrayOf(0))
        val r2 = conn.bulkTransfer(initEpOut, versionFrame, versionFrame.size, HID_TRANSFER_TIMEOUT_MS)
        Log.i(
            TAG,
            "  2b: SDK_VERSION sent $r2/${versionFrame.size} bytes ${if (r2 == versionFrame.size) "✓" else "✗"} (+${System.currentTimeMillis() - initStart}ms)",
        )

        // 0x26 service-ready pings must follow HOST_TYPE=2 within the MCU's config window (~300ms
        // from USB attach). Skip all response reads and drain loops that previously added 1400ms.
        Log.i(TAG, "  2c: polling service-ready (msgId=0x26, up to $SERVICE_READY_RETRIES) (+${System.currentTimeMillis() - initStart}ms)")
        if (initEpIn != null) {
            pollServiceReady(conn, initIface.id, initEpOut, initEpIn)
        } else {
            Log.w(TAG, "  2c: skipping 0x26 poll — epIn missing")
        }

        // Diagnostic: HID descriptor and IN-drain are deferred until after the timing-critical path.
        val descBuf = ByteArray(256)
        val dr = conn.controlTransfer(0x81, 0x06, 0x2200, configIface.id, descBuf, descBuf.size, 500)
        if (dr > 0) logHex("HID descriptor iface ${configIface.id} ($dr bytes)", descBuf.copyOf(dr))
    }

    /**
     * Send MSG_SERVICE_READY (0x26) up to [SERVICE_READY_RETRIES] times. Stop as soon as the
     * MCU replies with msgId != 0x0001 (i.e., something other than a heartbeat).
     *
     * RE: wait_for_service_ready_with_handle at 0xae488 sends msgId=0x26, empty payload, and
     * checks the response. A heartbeat (msgId=0x01) means not-ready; any other response means
     * SDK config mode is active and GET/SET will be processed.
     */
    private fun pollServiceReady(
        conn: UsbDeviceConnection,
        ifaceId: Int,
        epOut: UsbEndpoint,
        epIn: UsbEndpoint,
    ) {
        val pingFrame = buildHidFrame(MSG_SERVICE_READY, ByteArray(0))
        logHex("HID 0x26 service-ready ping (iface $ifaceId)", pingFrame)

        repeat(SERVICE_READY_RETRIES) { attempt ->
            val sent = conn.bulkTransfer(epOut, pingFrame, pingFrame.size, HID_TRANSFER_TIMEOUT_MS)
            if (sent != pingFrame.size) {
                Log.w(TAG, "0x26 ping attempt ${attempt + 1}: bulk send failed (sent=$sent)")
                return@repeat
            }

            // Read response — give MCU up to SERVICE_READY_DELAY_MS to reply
            val buf = ByteArray(epIn.maxPacketSize.coerceAtLeast(64))
            val r = conn.bulkTransfer(epIn, buf, buf.size, SERVICE_READY_DELAY_MS.toInt())
            if (r <= 0) {
                Log.d(TAG, "0x26 ping attempt ${attempt + 1}: no response within ${SERVICE_READY_DELAY_MS}ms")
                return@repeat
            }

            val msgId = (buf[15].toInt() and 0xFF) or ((buf[16].toInt() and 0xFF) shl 8)
            val innerLen = (buf[5].toInt() and 0xFF) or ((buf[6].toInt() and 0xFF) shl 8)
            logHex("0x26 response attempt ${attempt + 1} (msgId=0x${msgId.toString(16)} innerLen=$innerLen)", buf.copyOf(r))

            if (msgId != 0x0001) {
                Log.i(TAG, "MCU service-ready: responded to 0x26 with msgId=0x${msgId.toString(16)} (attempt ${attempt + 1})")
                return // service ready — proceed to GET/SET
            }
            Log.d(TAG, "0x26 attempt ${attempt + 1}: heartbeat (not ready yet)")
        }
        Log.w(TAG, "0x26 service-ready: no non-heartbeat response after $SERVICE_READY_RETRIES attempts")
    }

    /**
     * Build a G-series HID frame. Wire layout (RE from cmd_build_sdk at 0xa9ec4):
     *   [0]      0xfd  -- Report ID
     *   [1..4]   CRC32 LE -- init=~innerLen&0xff, std reflected poly, over bytes[6..totalLen] (inclusive)
     *   [5..6]   innerLen LE = totalLen-5 = 17+payload.size  (NOT totalLen; NOT cmdType)
     *   [7..14]  zeros
     *   [15..16] msgId LE  (0xD2=GET, 0xD3=SET -- no separate cmdType field)
     *   [17..21] zeros
     *   [22+]    payload
     *
     * CRC range includes one trailing zero byte beyond the frame (buf must be allocated +1).
     */
    private fun buildHidFrame(
        msgId: Int,
        payload: ByteArray,
    ): ByteArray {
        val totalLen = 22 + payload.size // wire bytes to send
        val innerLen = totalLen - 5 // stored at frame[5..6]; = 17 + payload.size
        // Allocate +1 so the CRC loop can read the trailing zero at buf[totalLen]
        val buf = ByteArray(totalLen + 1)
        buf[0] = HID_REPORT_ID
        buf[5] = (innerLen and 0xFF).toByte()
        buf[6] = ((innerLen shr 8) and 0xFF).toByte()
        buf[15] = (msgId and 0xFF).toByte()
        buf[16] = ((msgId shr 8) and 0xFF).toByte()
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, buf, 22, payload.size)

        // CRC over buf[6..totalLen] (= innerLen bytes, includes trailing zero at buf[totalLen])
        val crc = crc32Hid(buf, 6, innerLen, innerLen)
        buf[1] = (crc and 0xFF).toByte()
        buf[2] = ((crc shr 8) and 0xFF).toByte()
        buf[3] = ((crc shr 16) and 0xFF).toByte()
        buf[4] = ((crc shr 24) and 0xFF).toByte()

        return buf.copyOf(totalLen) // trim the +1 scratch byte before returning
    }

    /**
     * CRC32 with custom init (RE from cmd_build_sdk):
     * init = ~innerLen & 0xff  (innerLen = totalLen-5, NOT totalLen).
     * Standard reflected polynomial 0xEDB88320. No final XOR.
     */
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

    private fun openUsbDevice() {
        if (usbConnection != null) {
            Log.i(TAG, "  1: USB device already open (fd=${usbConnection!!.fileDescriptor}) — skipping")
            return
        }
        val vid = XRealGlassesCamera.VENDOR_ID
        val pid = XRealGlassesCamera.PRODUCT_ID
        Log.i(TAG, "  1a: scanning USB devices for VID=0x${vid.toString(16)} PID=0x${pid.toString(16)}")
        val allDevices = usbManager.deviceList.values
        Log.i(
            TAG,
            "  1a: ${allDevices.size} USB device(s) connected: ${allDevices.map {
                "VID=0x${it.vendorId.toString(
                    16,
                )}/PID=0x${it.productId.toString(16)}"
            }}",
        )
        val device =
            allDevices.firstOrNull { it.vendorId == vid && it.productId == pid }
                ?: run {
                    Log.w(TAG, "  1a: XReal device NOT found — cannot open USB (TCP path may still work)")
                    return
                }
        Log.i(TAG, "  1a: XReal device found: ${device.deviceName} (${device.manufacturerName} ${device.productName}) ✓")

        if (!usbManager.hasPermission(device)) {
            Log.w(TAG, "  1b: USB permission NOT granted — TCP pilot path via NCM may still work")
            return
        }
        Log.i(TAG, "  1b: USB permission granted ✓")

        val conn = usbManager.openDevice(device)
        if (conn != null) {
            usbDevice = device
            usbConnection = conn
            Log.i(TAG, "  1c: USB device opened (fd=${conn.fileDescriptor}) ✓")
        } else {
            Log.w(TAG, "  1c: openDevice failed ✗")
        }
    }

    private suspend fun waitForPilot(): Socket? =
        connectToPilot(PILOT_WAIT_RETRIES, CONNECT_TIMEOUT_MS, PILOT_WAIT_DELAY_MS)
            .also { if (it == null) Log.e(TAG, "Pilot daemon never became reachable") }

    private suspend fun connectToPilot(
        retries: Int,
        connectTimeoutMs: Int,
        retryDelayMs: Long,
    ): Socket? {
        repeat(retries) { attempt ->
            for (ip in PILOT_IPS) {
                try {
                    val socket = Socket()
                    // Android's policy routing sends unmarked sockets to the default network
                    // (wlan0), which has no route to 169.254.x.x. Bind to the eth0/eth1
                    // Network so the kernel marks the socket for the correct routing table.
                    findNetworkForIp(ip)?.bindSocket(socket)
                    socket.connect(InetSocketAddress(ip, PILOT_PORT), connectTimeoutMs)
                    Log.i(TAG, "Pilot connected at $ip:$PILOT_PORT (attempt ${attempt + 1})")
                    return socket
                } catch (_: IOException) {
                }
            }
            Log.d(TAG, "Pilot not reachable (attempt ${attempt + 1}/$retries)")
            delay(retryDelayMs)
        }
        return null
    }

    /** Find the Network whose link address is on the same /24 as the target IP. */
    private fun findNetworkForIp(targetIp: String): Network? {
        val prefix = targetIp.substringBeforeLast(".")
        return connectivityManager.allNetworks.firstOrNull { network ->
            connectivityManager
                .getLinkProperties(network)
                ?.linkAddresses
                ?.any { la -> la.address.hostAddress?.startsWith(prefix) == true }
                ?: false
        }
    }

    private fun sendAndReceive(
        label: String,
        msg: ByteArray,
        out: OutputStream,
        input: InputStream,
    ): ByteArray? {
        logHex("Sending $label", msg)
        out.write(msg)
        out.flush()
        val response = readResponse(input)
        if (response != null) logHex("$label response", response)
        return if (response != null) PilotProtocol.parseResponse(response) else null
    }

    private fun readResponse(input: InputStream): ByteArray? =
        try {
            val buf = ByteArray(MAX_RESPONSE_BYTES)
            val read = input.read(buf)
            if (read > 0) buf.copyOf(read) else null
        } catch (_: IOException) {
            null
        }

    private fun logHex(
        label: String,
        data: ByteArray,
    ) {
        val hex = data.joinToString(" ") { "%02x".format(it) }
        Log.i(TAG, "$label (${data.size}B): $hex")
    }

    fun release() {
        val conn = usbConnection
        for (iface in listOfNotNull(hidInitIface, hidConfigIface.takeIf { it !== hidInitIface })) {
            try {
                conn?.releaseInterface(iface)
            } catch (_: Exception) {
            }
        }
        hidInitIface = null
        hidInitEpOut = null
        hidInitEpIn = null
        hidConfigIface = null
        hidConfigEpOut = null
        hidConfigEpIn = null
        try {
            conn?.close()
        } catch (_: Exception) {
        }
        usbConnection = null
        usbDevice = null
    }
}
