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
 * Enables UVC mode on XReal glasses via HID commands on interface 0.
 *
 * All HID commands (init + USB config GET/SET) go via interface 0 (ep_01 OUT / ep_81 IN,
 * maxPkt=1024). Interface 8 (ep_05/ep_88, maxPkt=3) is Consumer Control HID — it does
 * NOT accept USB config frames.
 *
 * Flow: open USB → claim iface 0 → HOST_TYPE=2 + SDK_VERSION → GET (0xD2) →
 * SET (0xD3, payload=0x143 = ncm|ecm|uvc0|enable) → glasses re-enumerate with UVC active.
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

        // G-series HID protocol (RE'd from libnr_glasses_api.so cmd_build_sdk / hid_write)
        private const val HID_REPORT_ID: Byte = 0xfd.toByte()
        private const val MSG_W_HOST_TYPE = 0x0060
        private const val MSG_W_SDK_VERSION = 0x0031

        // RE'd from NRBSPGetUsbConfigAll (0xbbe54): MOVZ w1, #0xd2
        private const val MSG_USB_CONFIG_GET = 0x00D2

        // RE'd from NRBSPSetUsbConfigAll (0xbb498): MOVZ w1, #0xd3
        // SET vs GET is distinguished purely by msgID (0xD3 vs 0xD2) -- no cmdType field in frame.
        private const val MSG_USB_CONFIG_SET = 0x00D3
        private const val HID_HOST_TYPE_ANDROID = 2 // SDK mode required for glasses to process USB config SET
        private const val HID_SDK_VERSION = "3.3.0"
        private const val HID_TRANSFER_TIMEOUT_MS = 1000
        private const val HID_MSG_DELAY_MS = 100L

        private const val HID_POST_INIT_DELAY_MS = 200L
        private const val HID_RESPONSE_TIMEOUT_MS = 500

        // bInterfaceNumber of the config HID interface (ep_05/ep_88) seen in sysfs 1-1:1.8
        private const val HID_CONFIG_IFACE_NUMBER = 8
    }

    /**
     * Full UVC enable flow via HID (RE'd from NRBSPSetUsbConfigAll):
     * 1. Open USB device
     * 2. Claim both HID interfaces + send init (HOST_TYPE=1 to avoid SDK-mode display blank)
     * 3. Send GET (0x00D2) + SET (0x00D3) UVC config
     *
     * Init IS required — glasses need it before processing USB config SET commands
     * (NRBSPSetUsbConfigAll calls wait_for_service_ready before send_xreal_usb_msg).
     */
    suspend fun enableUvc(): Boolean =
        withContext(Dispatchers.IO) {
            openUsbDevice()
            claimAndSendHidInit()
            sendUsbConfigViaHid()
        }

    /** Send MSG_USB_CONFIG_GET then MSG_USB_CONFIG_SET via iface 0 (ep_01) to enable UVC. */
    private suspend fun sendUsbConfigViaHid(): Boolean {
        val conn =
            usbConnection ?: run {
                Log.e(TAG, "No USB connection for HID config commands")
                return false
            }
        val epOut = hidInitEpOut ?: return false
        val epIn = hidInitEpIn
        val ifaceId = hidInitIface?.id ?: -1

        // 4-byte uint32 LE bitmask: ncm(bit0)|ecm(bit1)|uvc0(bit6)|enable(bit8) = 0x143.
        // Includes ncm+ecm bits for fresh-boot where CG's GET returns ncm=0, ecm=0.
        // Previously tried 0x140 (uvc0|enable only) — ACKed but no re-enumeration.
        val uvcPayload =
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).run {
                putInt(0x143)
                array()
            }

        val getFrame = buildHidFrame(MSG_USB_CONFIG_GET, ByteArray(0))
        val setFrame = buildHidFrame(MSG_USB_CONFIG_SET, uvcPayload)

        logHex("HID GET on iface $ifaceId", getFrame)
        val getR = conn.bulkTransfer(epOut, getFrame, getFrame.size, HID_TRANSFER_TIMEOUT_MS)
        Log.i(TAG, "HID GET: sent $getR/${getFrame.size}")

        // Drain ep_81 until we see a real USB config response (innerLen > 17) or 2s timeout.
        // MCU continuously streams heartbeats (msgId=0x0001, innerLen=17, no payload) on ep_81;
        // the actual GET response has innerLen=53 (36-byte USB config payload).
        if (epIn != null) {
            val deadline = System.currentTimeMillis() + 2000L
            var foundGetResponse = false
            while (System.currentTimeMillis() < deadline) {
                val gBuf = ByteArray(1024)
                val gr = conn.bulkTransfer(epIn, gBuf, gBuf.size, HID_RESPONSE_TIMEOUT_MS)
                if (gr <= 0) break
                val innerLen = (gBuf[5].toInt() and 0xFF) or ((gBuf[6].toInt() and 0xFF) shl 8)
                val msgId = (gBuf[15].toInt() and 0xFF) or ((gBuf[16].toInt() and 0xFF) shl 8)
                logHex("HID GET ep_81 (innerLen=$innerLen msgId=0x${msgId.toString(16)})", gBuf.copyOf(gr))
                if (innerLen > 17) {
                    foundGetResponse = true
                    Log.i(TAG, "Found real GET response (innerLen=$innerLen) — payload follows above")
                    break
                }
            }
            if (!foundGetResponse) {
                Log.w(TAG, "GET: only heartbeats received within 2s — MCU may not have processed GET")
            }
        }

        delay(HID_MSG_DELAY_MS)

        logHex("HID SET on iface $ifaceId", setFrame)
        val sent = conn.bulkTransfer(epOut, setFrame, setFrame.size, HID_TRANSFER_TIMEOUT_MS)
        Log.i(TAG, "HID SET: sent $sent/${setFrame.size}")

        // Read up to 3 frames after SET — look for SET ACK (msgId=0xD3 or innerLen > 17)
        if (epIn != null) {
            repeat(3) { i ->
                val inBuf = ByteArray(1024)
                val rIn = conn.bulkTransfer(epIn, inBuf, inBuf.size, HID_RESPONSE_TIMEOUT_MS)
                if (rIn > 0) {
                    val innerLen = (inBuf[5].toInt() and 0xFF) or ((inBuf[6].toInt() and 0xFF) shl 8)
                    val msgId = (inBuf[15].toInt() and 0xFF) or ((inBuf[16].toInt() and 0xFF) shl 8)
                    logHex("HID SET ep_81[$i] (innerLen=$innerLen msgId=0x${msgId.toString(16)})", inBuf.copyOf(rIn))
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

        Log.i(TAG, "Found ${hidInterfaces.size} HID interface(s): ${hidInterfaces.map { "id=${it.id} eps=${it.endpointCount}" }}")

        if (hidInterfaces.isEmpty()) {
            Log.w(TAG, "No HID interfaces found -- skipping init")
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

        Log.i(
            TAG,
            "Config HID iface id=${configIface.id} ep_out=0x${configEpOut?.address?.toString(16) ?: "none"}" +
                " ep_in=0x${configEpIn?.address?.toString(16) ?: "none"}" +
                " maxPktOut=${configEpOut?.maxPacketSize} maxPktIn=${configEpIn?.maxPacketSize}",
        )
        hidConfigIface = configIface
        hidConfigEpOut = configEpOut
        hidConfigEpIn = configEpIn

        // Read interface 8 HID descriptor to understand valid report IDs and sizes
        val descBuf = ByteArray(256)
        val dr = conn.controlTransfer(0x81, 0x06, 0x2200, configIface.id, descBuf, descBuf.size, 2000)
        if (dr > 0) {
            logHex("HID descriptor iface ${configIface.id} ($dr bytes)", descBuf.copyOf(dr))
        } else {
            Log.w(TAG, "HID descriptor iface ${configIface.id}: r=$dr")
        }

        // Send init messages on the init interface
        if (initEpOut == null) {
            Log.w(TAG, "Init HID interface has no OUT endpoint -- cannot send init messages")
        } else {
            // MSG_W_HOST_TYPE (0x0060): host type Android (2) as int32_le
            val hostTypePayload = byteArrayOf(HID_HOST_TYPE_ANDROID.toByte(), 0, 0, 0)
            val hostTypeFrame = buildHidFrame(MSG_W_HOST_TYPE, hostTypePayload)
            logHex("HID MSG_W_HOST_TYPE (iface ${initIface.id})", hostTypeFrame)
            val r1 = conn.bulkTransfer(initEpOut, hostTypeFrame, hostTypeFrame.size, HID_TRANSFER_TIMEOUT_MS)
            Log.i(TAG, "HID MSG_W_HOST_TYPE: sent $r1/${hostTypeFrame.size} bytes")

            delay(HID_MSG_DELAY_MS)

            // MSG_W_SDK_VERSION (0x0031): null-terminated version string
            val versionPayload = HID_SDK_VERSION.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
            val sdkVersionFrame = buildHidFrame(MSG_W_SDK_VERSION, versionPayload)
            logHex("HID MSG_W_SDK_VERSION (iface ${initIface.id})", sdkVersionFrame)
            val r2 = conn.bulkTransfer(initEpOut, sdkVersionFrame, sdkVersionFrame.size, HID_TRANSFER_TIMEOUT_MS)
            Log.i(TAG, "HID MSG_W_SDK_VERSION: sent $r2/${sdkVersionFrame.size} bytes")

            // Read any immediate HID IN response (MCU may send a session token or ACK)
            if (initEpIn != null) {
                val inBuf = ByteArray(initEpIn.maxPacketSize.coerceAtLeast(64))
                val rIn = conn.bulkTransfer(initEpIn, inBuf, inBuf.size, 500)
                if (rIn > 0) {
                    logHex("HID IN after SDK_VERSION (iface ${initIface.id})", inBuf.copyOf(rIn))
                } else {
                    Log.d(TAG, "HID IN: no immediate response (r=$rIn)")
                }
            }
        }

        Log.i(TAG, "HID init done -- waiting ${HID_POST_INIT_DELAY_MS}ms for MCU to settle")
        delay(HID_POST_INIT_DELAY_MS)

        // Drain any HID IN data that arrived during the wait
        if (initEpIn != null) {
            val inBuf = ByteArray(initEpIn.maxPacketSize.coerceAtLeast(64))
            repeat(3) {
                val rIn = conn.bulkTransfer(initEpIn, inBuf, inBuf.size, 200)
                if (rIn > 0) logHex("HID IN (post-wait, iface ${initIface.id})", inBuf.copyOf(rIn))
            }
        }
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
        if (usbConnection != null) return
        val device =
            usbManager.deviceList.values
                .firstOrNull { it.vendorId == XRealGlassesCamera.VENDOR_ID && it.productId == XRealGlassesCamera.PRODUCT_ID }
                ?: run {
                    Log.w(TAG, "XReal device not found -- skipping USB open")
                    return
                }

        if (!usbManager.hasPermission(device)) {
            Log.w(TAG, "No USB permission -- pilot TCP may still work via NCM kernel driver")
            return
        }
        val conn = usbManager.openDevice(device)
        if (conn != null) {
            usbDevice = device
            usbConnection = conn
            Log.i(TAG, "USB device opened (fd=${conn.fileDescriptor})")
        } else {
            Log.w(TAG, "openDevice failed")
        }
    }

    private suspend fun waitForPilot(): Socket? {
        repeat(PILOT_WAIT_RETRIES) { attempt ->
            for (ip in PILOT_IPS) {
                try {
                    val socket = Socket()
                    // Android's policy routing sends unmarked sockets to the default network
                    // (wlan0), which has no route to 169.254.x.x. Bind to the eth0/eth1
                    // Network so the kernel marks the socket for the correct routing table.
                    findNetworkForIp(ip)?.bindSocket(socket)
                    socket.connect(InetSocketAddress(ip, PILOT_PORT), CONNECT_TIMEOUT_MS)
                    Log.i(TAG, "Pilot daemon connected at $ip:$PILOT_PORT (attempt ${attempt + 1})")
                    return socket
                } catch (_: IOException) {
                }
            }
            Log.d(TAG, "Pilot not reachable (attempt ${attempt + 1}/$PILOT_WAIT_RETRIES)")
            delay(PILOT_WAIT_DELAY_MS)
        }
        Log.e(TAG, "Pilot daemon never became reachable after ${PILOT_WAIT_RETRIES * (CONNECT_TIMEOUT_MS * 2 + PILOT_WAIT_DELAY_MS)}ms")
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
