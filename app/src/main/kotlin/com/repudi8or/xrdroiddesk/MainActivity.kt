package com.repudi8or.xrdroiddesk

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.repudi8or.xrdroiddesk.camera.XRealGlassesCamera
import com.repudi8or.xrdroiddesk.service.GestureAccessibilityService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnPinchTest: Button
    private lateinit var usbManager: UsbManager
    private val logLines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeInstances.add(this)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(USB_SERVICE) as UsbManager
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        btnPinchTest = findViewById(R.id.btnPinchTest)
        btnPinchTest.setOnClickListener {
            Log.i(TAG, "✓ PINCH SUCCESS")
            appendLog("✓ PINCH SUCCESS")
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }

        handleUsbAttached(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "CAMERA permission result: granted=$granted")
            logRequirementsState("camera:granted=$granted")
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleUsbAttached(intent)
    }

    private fun handleUsbAttached(intent: android.content.Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return

        val device: UsbDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

        if (device != null) {
            val hasPerm = usbManager.hasPermission(device)
            Log.i(
                TAG,
                "USB_DEVICE_ATTACHED device=${device.deviceName} VID=0x${device.vendorId.toString(16)} " +
                    "PID=0x${device.productId.toString(16)} hasPermission=$hasPerm",
            )
            if (!hasPerm) {
                Log.w(TAG, "USB_DEVICE_ATTACHED without permission — Control Glasses likely preferred handler")
                logRequirementsState("usb:noPerm")
                return
            }
        }

        val service = GestureAccessibilityService.instance
        if (service == null) {
            if (device != null) GestureAccessibilityService.pendingUsbDevice = device
            logRequirementsState("usb:serviceNotRunning")
            return
        }
        if (device != null) {
            GestureAccessibilityService.pendingUsbDevice = null
            service.openCamera(device)
        } else {
            service.tryConnectCamera()
        }
        logRequirementsState("usb:attached")
    }

    override fun onResume() {
        super.onResume()
        logRequirementsState("onResume")
        val pending = GestureAccessibilityService.pendingUsbDevice
        val service = GestureAccessibilityService.instance
        if (pending != null && service != null) {
            GestureAccessibilityService.pendingUsbDevice = null
            service.openCamera(pending)
        }
    }

    private fun logRequirementsState(trigger: String) {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val a11yEnabled =
            am
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == packageName }
        val a11yRunning = GestureAccessibilityService.instance != null

        val device =
            usbManager.deviceList.values.firstOrNull {
                it.vendorId == XRealGlassesCamera.VENDOR_ID && it.productId == XRealGlassesCamera.PRODUCT_ID
            }
        val usbFound = device != null
        val usbPerm = device != null && usbManager.hasPermission(device)
        val uvcActive =
            device != null &&
                (0 until device.interfaceCount).any {
                    device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO
                }
        val pendingDevice = GestureAccessibilityService.pendingUsbDevice != null

        val msg =
            "[$trigger] a11y=${yn(a11yEnabled)}/${yn(a11yRunning)} " +
                "usb=${yn(usbFound)}/${yn(usbPerm)} uvc=${yn(uvcActive)}" +
                if (pendingDevice) " pending" else ""

        Log.i(REQ_TAG, msg)
        appendLog(msg)
    }

    fun appendLog(msg: String) {
        val line = "${timeFmt.format(Date())} $msg"
        logLines.addLast(line)
        if (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        tvLog.text = logLines.joinToString("\n")
        scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun yn(b: Boolean) = if (b) "Y" else "N"

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_TAG = "Requirements"
        private const val REQ_CAMERA = 1001
        private const val MAX_LOG_LINES = 100

        private val activeInstances = mutableListOf<MainActivity>()

        // Most-recently-started instance, for appendLog.
        var instance: MainActivity? = null
            private set

        fun finishAll() {
            activeInstances.toList().forEach { it.finish() }
        }

        fun appendToAll(msg: String) {
            activeInstances.toList().forEach { it.appendLog(msg) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeInstances.remove(this)
        if (instance == this) instance = null
    }

    override fun onStart() {
        super.onStart()
        instance = this
    }

    override fun onStop() {
        super.onStop()
        if (instance == this) instance = null
    }
}
