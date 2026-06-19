package com.repudi8or.xrdroiddesk.camera

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class XRealGlassesCamera(
    private val usbManager: UsbManager,
    private val onFrame: (ByteArray) -> Unit,
) {
    constructor(context: Context, onFrame: (ByteArray) -> Unit) : this(
        usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager,
        onFrame = onFrame,
    )

    private var connection: UsbDeviceConnection? = null
    private var streamingIface: UsbInterface? = null
    private var readJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        const val VENDOR_ID = 0x3318
        const val PRODUCT_ID = 0x0436

        private const val TAG = "XRealGlassesCamera"
        private const val UVC_CLASS = 14
        private const val UVC_STREAMING_SUBCLASS = 2
        private const val UVC_SET_CUR: Byte = 0x01
        private const val UVC_GET_CUR = 0x81
        private const val VS_PROBE_CONTROL = 0x0100
        private const val VS_COMMIT_CONTROL = 0x0200
        private const val TIMEOUT_MS = 2000
        private const val BULK_BUF_SIZE = 16384

        // UVC payload header BFH bits
        private const val BFH_EOF = 0x02
        private const val BFH_ERR = 0x40
    }

    fun findDevice(): UsbDevice? {
        val devices = usbManager.deviceList
        Log.d(TAG, "USB devices present: ${devices.size}")
        devices.values.forEach { d ->
            Log.d(TAG, "  ${d.deviceName} VID=0x${d.vendorId.toString(16)} PID=0x${d.productId.toString(16)}")
        }
        return devices.values.firstOrNull { it.vendorId == VENDOR_ID && it.productId == PRODUCT_ID }
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    fun open(device: UsbDevice): Boolean {
        close() // Ensure any prior session is torn down before opening a new connection
        val iface =
            findStreamingInterface(device) ?: run {
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
        startReading(conn, endpoint)
        Log.i(TAG, "UVC stream open")
        return true
    }

    fun close() {
        readJob?.cancel()
        connection?.releaseInterface(streamingIface)
        connection?.close()
        connection = null
        streamingIface = null
        readJob = null
    }

    private fun findStreamingInterface(device: UsbDevice): UsbInterface? {
        Log.d(TAG, "Device has ${device.interfaceCount} interfaces:")
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val epSummary =
                (0 until iface.endpointCount).joinToString {
                    val ep = iface.getEndpoint(it)
                    "ep$it(type=${ep.type} dir=${ep.direction})"
                }
            Log.d(
                TAG,
                "  [$i] class=${iface.interfaceClass} sub=${iface.interfaceSubclass}" +
                    " proto=${iface.interfaceProtocol} eps=$epSummary",
            )
        }
        return (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UVC_CLASS && it.interfaceSubclass == UVC_STREAMING_SUBCLASS }
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
}
