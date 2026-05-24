package com.repudi8or.xrdroiddesk.camera

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
        private const val ACTION_USB_PERMISSION = "com.repudi8or.xrdroiddesk.USB_PERMISSION"
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

    fun findDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull {
            it.vendorId == VENDOR_ID && it.productId == PRODUCT_ID
        }

    fun requestPermission(
        context: Context,
        device: UsbDevice,
        onGranted: () -> Unit,
        onDenied: () -> Unit,
    ) {
        if (usbManager.hasPermission(device)) {
            onGranted()
            return
        }
        val pi =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    context.unregisterReceiver(this)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        onGranted()
                    } else {
                        onDenied()
                    }
                }
            }
        context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
        usbManager.requestPermission(device, pi)
    }

    fun open(device: UsbDevice): Boolean {
        val iface =
            findStreamingInterface(device) ?: run {
                Log.e(TAG, "No UVC VideoStreaming interface on device")
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
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UVC_CLASS && iface.interfaceSubclass == UVC_STREAMING_SUBCLASS) {
                return iface
            }
        }
        return null
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
        // GET_CUR — ask device what it can offer; ignore result, we send our own preference
        val response = ByteArray(UvcStreamControl.SIZE)
        conn.controlTransfer(0xA1, UVC_GET_CUR, VS_PROBE_CONTROL, iface.id, response, response.size, TIMEOUT_MS)
        // SET_CUR probe
        val setProbe = conn.controlTransfer(0x21, UVC_SET_CUR.toInt(), VS_PROBE_CONTROL, iface.id, probe, probe.size, TIMEOUT_MS)
        if (setProbe < 0) {
            Log.e(TAG, "VS_PROBE SET_CUR failed: $setProbe")
            return false
        }
        // SET_CUR commit
        val commit = conn.controlTransfer(0x21, UVC_SET_CUR.toInt(), VS_COMMIT_CONTROL, iface.id, probe, probe.size, TIMEOUT_MS)
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
        readJob =
            scope.launch {
                val buf = ByteArray(BULK_BUF_SIZE)
                val frame = ByteArrayOutputStream(BULK_BUF_SIZE * 4)
                var prevFid = -1

                while (true) {
                    val read = conn.bulkTransfer(endpoint, buf, buf.size, TIMEOUT_MS)
                    if (read < 2) continue

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
