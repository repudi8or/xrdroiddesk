package com.repudi8or.xrdroiddesk.camera

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class XRealGlassesCamera(
    private val usbManager: UsbManager,
    private val connectivityManager: ConnectivityManager? = null,
    private val onFrame: (ByteArray) -> Unit,
) {
    constructor(context: Context, onFrame: (ByteArray) -> Unit) : this(
        usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager,
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager,
        onFrame = onFrame,
    )

    private var connection: UsbDeviceConnection? = null
    private var streamingIface: UsbInterface? = null
    private var hidInitIface: UsbInterface? = null
    private var hidInitEpOut: UsbEndpoint? = null
    private var readJob: Job? = null
    private var keepAliveJob: Job? = null
    private var tcpKeepaliveJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    var isOpen: Boolean = false
        private set

    companion object {
        const val VENDOR_ID = 0x3318
        const val PRODUCT_ID = 0x0436

        // Eye RGB camera — may enumerate as separate device after uvc1 enable on some phones.
        // On Pixel 10 Pro it stays on the XREAL composite (same VID/PID, extra VS interface).
        const val EYE_VENDOR_ID = 0x0817
        const val EYE_PRODUCT_ID = 0x0909
        const val EYE_PRODUCT_ID_ALT = 0x0910

        private const val TAG = "XRealGlassesCamera"
        private const val UVC_CLASS = 14
        private const val UVC_STREAMING_SUBCLASS = 2
        private const val UVC_SET_CUR: Byte = 0x01
        private const val UVC_GET_CUR = 0x81
        private const val VS_PROBE_CONTROL = 0x0100
        private const val VS_COMMIT_CONTROL = 0x0200
        private const val TIMEOUT_MS = 2000
        private const val BULK_BUF_SIZE = 65536 // 64KB: IDR frames are ~24KB; 16KB truncates them
        private const val PILOT_PORT = 50180

        // UVC payload header BFH bits
        private const val BFH_EOF = 0x02
        private const val BFH_ERR = 0x40
    }

    fun findDevice(preferEye: Boolean = false): UsbDevice? {
        val devices = usbManager.deviceList
        Log.d(TAG, "USB devices present: ${devices.size}")
        devices.values.forEach { d ->
            Log.d(TAG, "  ${d.deviceName} VID=0x${d.vendorId.toString(16)} PID=0x${d.productId.toString(16)}")
        }
        if (preferEye) {
            // On some phones the Eye enumerates as a separate device after uvc1 enable
            val eyeDev =
                devices.values.firstOrNull {
                    it.vendorId == EYE_VENDOR_ID &&
                        (it.productId == EYE_PRODUCT_ID || it.productId == EYE_PRODUCT_ID_ALT)
                }
            if (eyeDev != null) {
                Log.i(
                    TAG,
                    "Eye camera found as separate device: VID=0x${eyeDev.vendorId.toString(16)} PID=0x${eyeDev.productId.toString(16)}",
                )
                return eyeDev
            }
            // On Pixel 10 Pro it stays on the XREAL composite — fall through to find by VID/PID
        }
        return devices.values.firstOrNull { it.vendorId == VENDOR_ID && it.productId == PRODUCT_ID }
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun open(
        device: UsbDevice,
        preferEye: Boolean = false,
    ): Boolean {
        close() // Ensure any prior session is torn down before opening a new connection
        val iface =
            findStreamingInterface(device, preferEye) ?: run {
                val hasAnyVideoIface =
                    (0 until device.interfaceCount)
                        .any { device.getInterface(it).interfaceClass == UVC_CLASS }
                if (hasAnyVideoIface) {
                    Log.e(TAG, "VideoControl interface found but no VideoStreaming interface")
                } else {
                    Log.e(
                        TAG,
                        "No UVC interfaces at all — UVC mode is OFF in Control Glasses. " +
                            "Open Control Glasses, enable the UVC toggle, then replug the glasses.",
                    )
                }
                return false
            }
        val conn =
            usbManager.openDevice(device) ?: run {
                Log.e(TAG, "openDevice failed — is permission granted?")
                return false
            }
        if (!conn.claimInterface(iface, true)) {
            Log.e(TAG, "claimInterface failed")
            conn.close()
            return false
        }
        val endpoint =
            findBulkInEndpoint(iface) ?: run {
                Log.e(TAG, "No bulk-in endpoint in VideoStreaming interface")
                conn.releaseInterface(iface)
                conn.close()
                return false
            }
        if (!negotiateFormat(conn, iface)) {
            Log.e(TAG, "UVC format negotiation failed")
            conn.releaseInterface(iface)
            conn.close()
            return false
        }
        connection = conn
        streamingIface = iface
        // Claim HID init iface (class=3, first HID) to block CG from sending HOST_TYPE=2
        // which would put the glasses in SDK-mode and darken the display. We don't send
        // anything on it — we just hold the claim so CG's HID init fails silently.
        val hidIface =
            (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
        if (hidIface != null && conn.claimInterface(hidIface, true)) {
            hidInitIface = hidIface
            Log.i(TAG, "HID init iface ${hidIface.id} claimed — sending HOST_TYPE=1 to restore display")
            // Send HOST_TYPE=1: display-on mode. UVC is already enabled by this point so
            // switching out of SDK mode (HOST_TYPE=2) restores the display without losing UVC.
            val epOut =
                (0 until hidIface.endpointCount)
                    .map { hidIface.getEndpoint(it) }
                    .firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
            if (epOut != null) {
                hidInitEpOut = epOut
                val frame = buildHostTypeFrame(1)
                val sent = conn.bulkTransfer(epOut, frame, frame.size, 1000)
                Log.i(TAG, "HOST_TYPE=1 sent $sent/${frame.size} bytes via HID iface ${hidIface.id}")
            }
        } else {
            Log.w(TAG, "HID init iface not claimed — CG may darken display")
        }
        startReading(conn, endpoint)
        startHostTypeKeepalive(conn)
        startTcpHostTypeKeepalive()
        isOpen = true
        Log.i(TAG, "UVC stream open")
        return true
    }

    fun close() {
        isOpen = false
        tcpKeepaliveJob?.cancel()
        keepAliveJob?.cancel()
        readJob?.cancel()
        connection?.releaseInterface(streamingIface)
        connection?.releaseInterface(hidInitIface)
        connection?.close()
        connection = null
        streamingIface = null
        hidInitIface = null
        hidInitEpOut = null
        readJob = null
        keepAliveJob = null
        tcpKeepaliveJob = null
    }

    // preferEye=true picks the SECOND VideoStreaming interface (xreal1 = Eye RGB camera).
    // preferEye=false picks the first (xreal0 = built-in stereo camera).
    private fun findStreamingInterface(
        device: UsbDevice,
        preferEye: Boolean = false,
    ): UsbInterface? {
        Log.d(TAG, "Device has ${device.interfaceCount} interfaces (preferEye=$preferEye):")
        val vsInterfaces = mutableListOf<UsbInterface>()
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val epSummary =
                (0 until iface.endpointCount).joinToString {
                    val ep = iface.getEndpoint(it)
                    "ep$it(type=${ep.type} dir=${ep.direction} addr=0x${ep.address.toString(16)})"
                }
            Log.d(
                TAG,
                "  [$i] class=${iface.interfaceClass} sub=${iface.interfaceSubclass}" +
                    " proto=${iface.interfaceProtocol} eps=$epSummary",
            )
            if (iface.interfaceClass == UVC_CLASS && iface.interfaceSubclass == UVC_STREAMING_SUBCLASS) {
                vsInterfaces.add(iface)
            }
        }
        Log.i(TAG, "Found ${vsInterfaces.size} VideoStreaming interface(s)")
        return when {
            vsInterfaces.isEmpty() -> null
            preferEye && vsInterfaces.size >= 2 -> vsInterfaces[1] // xreal1 = Eye RGB
            else -> vsInterfaces[0] // xreal0 = built-in stereo
        }
    }

    private fun findBulkInEndpoint(iface: UsbInterface): UsbEndpoint? {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN) {
                return ep
            }
        }
        return null
    }

    private fun negotiateFormat(
        conn: UsbDeviceConnection,
        iface: UsbInterface,
    ): Boolean {
        val probe = UvcStreamControl.DEFAULT.toBytes()
        // GET_CUR — read what camera currently has, then send our preference
        val response = ByteArray(UvcStreamControl.SIZE)
        val getCur = conn.controlTransfer(0xA1, UVC_GET_CUR, VS_PROBE_CONTROL, iface.id, response, response.size, TIMEOUT_MS)
        Log.i(
            TAG,
            "VS_PROBE GET_CUR: $getCur bytes — " +
                if (getCur >= 4) "fmt=${response[2].toInt() and 0xFF} frame=${response[3].toInt() and 0xFF}" else "short/error",
        )
        // SET_CUR probe
        val setProbe = conn.controlTransfer(0x21, UVC_SET_CUR.toInt(), VS_PROBE_CONTROL, iface.id, probe, probe.size, TIMEOUT_MS)
        Log.i(TAG, "VS_PROBE SET_CUR: $setProbe (fmt=1 frame=1 ~15fps)")
        if (setProbe < 0) {
            Log.e(TAG, "VS_PROBE SET_CUR failed: $setProbe")
            return false
        }
        // GET_CUR again — read negotiated params (maxPayloadTransferSize etc.) to use in commit
        val negotiated = ByteArray(UvcStreamControl.SIZE)
        conn.controlTransfer(0xA1, UVC_GET_CUR, VS_PROBE_CONTROL, iface.id, negotiated, negotiated.size, TIMEOUT_MS)
        val ctrl = UvcStreamControl.fromBytes(negotiated)
        Log.i(
            TAG,
            "VS_PROBE negotiated: fmt=${ctrl.formatIndex} frame=${ctrl.frameIndex} " +
                "maxFrame=${ctrl.maxVideoFrameSize} maxPayload=${ctrl.maxPayloadTransferSize}",
        )
        // SET_CUR commit with negotiated params
        val commit = conn.controlTransfer(0x21, UVC_SET_CUR.toInt(), VS_COMMIT_CONTROL, iface.id, negotiated, negotiated.size, TIMEOUT_MS)
        Log.i(TAG, "VS_COMMIT SET_CUR: $commit")
        if (commit < 0) {
            Log.e(TAG, "VS_COMMIT SET_CUR failed: $commit")
            return false
        }
        return true
    }

    // Sends HOST_TYPE=1 every 200ms to keep the glasses display on.
    // Re-claims iface 0 on every cycle (not just on failure) so CG cannot hold it
    // between iterations and send HOST_TYPE=2 (SDK-mode/display-dark).
    // After each force-reclaim, sends CLEAR_FEATURE(ENDPOINT_HALT) on ep_01 to
    // clear the stall that accumulates from CG/us fighting over the endpoint.
    private fun startHostTypeKeepalive(conn: UsbDeviceConnection) {
        keepAliveJob =
            scope.launch {
                // Short startup delay: open() already sent HOST_TYPE=1 synchronously.
                // CG's Acceptor fires ~0.5s after camera open; start keepalive before that.
                delay(500)
                var successCount = 0
                var failCount = 0
                while (isActive) {
                    val iface = hidInitIface ?: break
                    val ep = hidInitEpOut ?: break
                    val frame = buildHostTypeFrame(1)

                    // Aggressively re-claim on every cycle to prevent CG from holding
                    // the interface between iterations.
                    val claimed = conn.claimInterface(iface, true)
                    if (!claimed) {
                        failCount++
                        if (failCount <= 3 || failCount % 20 == 0) {
                            Log.d(TAG, "HID keepalive: re-claim FAILED (fail=$failCount)")
                        }
                        delay(200)
                        continue
                    }
                    // Clear any stall/halt on ep_01 OUT that accumulates from contention.
                    // bmRequestType=0x02 (endpoint), bRequest=0x01 (CLEAR_FEATURE),
                    // wValue=0x0000 (ENDPOINT_HALT), wIndex=ep address.
                    conn.controlTransfer(0x02, 0x01, 0x0000, ep.address, null, 0, 100)

                    val sent = conn.bulkTransfer(ep, frame, frame.size, 500)
                    if (sent > 0) {
                        successCount++
                        if (successCount == 1 || successCount % 20 == 0) {
                            Log.i(TAG, "HID keepalive: HOST_TYPE=1 sent $sent bytes (ok=$successCount fail=$failCount)")
                        }
                        failCount = 0
                    } else {
                        failCount++
                        if (failCount <= 5 || failCount % 20 == 0) {
                            Log.d(TAG, "HID keepalive: tx=$sent after clear-halt (ok=$successCount fail=$failCount)")
                        }
                    }
                    delay(200)
                }
            }
    }

    private fun startReading(
        conn: UsbDeviceConnection,
        endpoint: UsbEndpoint,
    ) {
        Log.i(TAG, "startReading: ep=0x${endpoint.address.toString(16)} maxPkt=${endpoint.maxPacketSize}")
        readJob =
            scope.launch {
                val buf = ByteArray(BULK_BUF_SIZE)
                val frame = ByteArrayOutputStream(BULK_BUF_SIZE * 4)
                var prevFid = -1
                var transferCount = 0
                var timeoutCount = 0

                while (isActive) {
                    val read = conn.bulkTransfer(endpoint, buf, buf.size, TIMEOUT_MS)
                    transferCount++
                    if (read < 2) {
                        timeoutCount++
                        if (timeoutCount <= 5 || timeoutCount % 500 == 0) {
                            Log.d(TAG, "bulkTransfer #$transferCount: read=$read (timeouts=$timeoutCount)")
                        }
                        continue
                    }
                    val headerLen = (buf[0].toInt() and 0xFF).coerceAtLeast(2)
                    val bfh = buf[1].toInt() and 0xFF
                    val fid = bfh and 0x01
                    val eof = bfh and BFH_EOF
                    val err = bfh and BFH_ERR

                    if (err != 0) {
                        frame.reset()
                        continue
                    }
                    // New frame started before previous EOF — discard stale data
                    if (fid != prevFid && prevFid != -1 && frame.size() > 0) {
                        frame.reset()
                    }
                    prevFid = fid

                    val payloadStart = headerLen.coerceAtMost(read)
                    if (payloadStart < read) {
                        frame.write(buf, payloadStart, read - payloadStart)
                    }
                    if (eof != 0 && frame.size() > 0) {
                        onFrame(frame.toByteArray())
                        frame.reset()
                    }
                }
            }
    }

    // Sends HOST_TYPE=1 every 50ms via TCP to the pilot daemon at 169.254.1.1:50180.
    // This path is independent of USB interface claims — CG cannot block it.
    private fun startTcpHostTypeKeepalive() {
        val cm = connectivityManager ?: return
        tcpKeepaliveJob =
            scope.launch {
                delay(200)
                while (isActive) {
                    val socket = connectTcpPilot(cm)
                    if (socket == null) {
                        Log.i(TAG, "TCP HOST_TYPE keepalive: pilot not reachable — retry in 500ms")
                        delay(500)
                        continue
                    }
                    Log.i(TAG, "TCP HOST_TYPE keepalive: connected")
                    try {
                        val out = socket.getOutputStream()
                        var txCount = 0
                        while (isActive) {
                            val frame = buildHostTypeFrame(1)
                            out.write(frame)
                            out.flush()
                            txCount++
                            if (txCount == 1 || txCount % 200 == 0) {
                                Log.d(TAG, "TCP HOST_TYPE=1 sent (tx=$txCount)")
                            }
                            delay(50)
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "TCP HOST_TYPE keepalive: disconnected (${e.message}) — reconnecting")
                    } finally {
                        try {
                            socket.close()
                        } catch (_: IOException) {
                        }
                    }
                }
            }
    }

    private fun connectTcpPilot(cm: ConnectivityManager): Socket? {
        val allNets = cm.allNetworks
        val ncmNets =
            allNets.mapNotNull { n ->
                val addrs =
                    cm
                        .getLinkProperties(n)
                        ?.linkAddresses
                        ?.mapNotNull { it.address.hostAddress } ?: emptyList()
                if (addrs.any { it.startsWith("169.254.") }) "$n:$addrs" else null
            }
        Log.d(TAG, "TCP pilot: ${allNets.size} networks, NCM candidates: $ncmNets")
        for (ip in listOf("169.254.1.1", "169.254.2.1")) {
            try {
                val prefix = ip.substringBeforeLast(".")
                val network =
                    allNets.firstOrNull { n ->
                        cm
                            .getLinkProperties(n)
                            ?.linkAddresses
                            ?.any { la -> la.address.hostAddress?.startsWith(prefix) == true } == true
                    }
                val s = Socket()
                network?.bindSocket(s)
                s.connect(InetSocketAddress(ip, PILOT_PORT), 1000)
                return s
            } catch (e: Exception) {
                Log.d(TAG, "TCP pilot: $ip unreachable (${e.message})")
            }
        }
        Log.i(TAG, "TCP pilot: not reachable on any 169.254.x.x network")
        return null
    }

    // Builds a minimal G-series HID frame for HOST_TYPE message (msgId=0x60, 4-byte payload).
    // Same format as GlassesUvcEnabler.buildHidFrame — duplicated here to avoid coupling.
    private fun buildHostTypeFrame(hostType: Int): ByteArray {
        val payload = byteArrayOf(hostType.toByte(), 0, 0, 0)
        val totalLen = 22 + payload.size
        val innerLen = totalLen - 5
        val buf = ByteArray(totalLen + 1)
        buf[0] = 0xfd.toByte()
        buf[5] = (innerLen and 0xFF).toByte()
        buf[6] = ((innerLen shr 8) and 0xFF).toByte()
        buf[15] = 0x60.toByte() // MSG_W_HOST_TYPE
        buf[16] = 0x00
        System.arraycopy(payload, 0, buf, 22, payload.size)
        var crc = innerLen.inv() and 0xFF
        for (i in 6 until 6 + innerLen) {
            crc = crc xor (buf[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1 }
        }
        buf[1] = (crc and 0xFF).toByte()
        buf[2] = ((crc shr 8) and 0xFF).toByte()
        buf[3] = ((crc shr 16) and 0xFF).toByte()
        buf[4] = ((crc shr 24) and 0xFF).toByte()
        return buf.copyOf(totalLen)
    }
}
