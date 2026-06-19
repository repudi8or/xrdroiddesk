package com.repudi8or.xrdroiddesk

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.repudi8or.xrdroiddesk.camera.GlassesUvcEnabler
import com.repudi8or.xrdroiddesk.camera.XRealGlassesCamera
import com.repudi8or.xrdroiddesk.service.GestureAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var usbManager: UsbManager
    private var permReceiver: BroadcastReceiver? = null
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(USB_SERVICE) as UsbManager
        tvStatus = findViewById(R.id.tvServiceStatus)

        findViewById<Button>(R.id.btnDebugClick).setOnClickListener {
            logRequirementsState("btnDebugClick")
            val service = GestureAccessibilityService.instance
            if (service != null) {
                service.triggerDebugClick(x = 500f, y = 500f)
                tvStatus.setText(R.string.service_status_click_sent)
            } else {
                tvStatus.setText(R.string.service_status_disconnected)
            }
        }

        findViewById<Button>(R.id.btnRequestUsbPermission).setOnClickListener {
            logRequirementsState("btnRequestUsbPerm")
            requestUsbPermission()
        }

        findViewById<Button>(R.id.btnEnableUvc).setOnClickListener {
            logRequirementsState("btnEnableUvc")
            tvStatus.text = "Sending UVC enable via HID…"
            activityScope.launch {
                val enabler = GlassesUvcEnabler(this@MainActivity)
                val ok = enabler.enableUvc()
                tvStatus.text =
                    if (ok) {
                        "UVC enable sent — glasses should re-enumerate. Check logcat."
                    } else {
                        "UVC enable failed — see logcat."
                    }
                enabler.release()
                logRequirementsState("btnEnableUvc:done")
            }
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
            logRequirementsState("cameraPermResult:granted=$granted")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbAttached(intent)
    }

    // When the OS routes USB_DEVICE_ATTACHED here (user picked xrdroiddesk from the chooser),
    // permission should be granted by the system. We log hasPermission() immediately to confirm
    // whether the chooser actually granted permission or the intent was broadcast without it
    // (the latter happens when Control Glasses is still the preferred USB handler).
    // If the service isn't running yet, cache the device so startHandTracking() can use it.
    private fun handleUsbAttached(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return

        // TCP UVC enable fallback: only needed when the accessibility service isn't running.
        // When the service is active, its usbAttachReceiver fires TCP immediately on plug-in
        // (before this Activity even starts), so firing it again here would be redundant and
        // could interfere with the re-enumeration the service already triggered.
        if (GestureAccessibilityService.instance == null) {
            activityScope.launch(Dispatchers.IO) {
                val enabler = GlassesUvcEnabler(this@MainActivity)
                val ok = enabler.enableUvcViaTcp()
                enabler.release()
                Log.i(TAG, "TCP UVC enable result: $ok (fallback — service not running)")
            }
        }

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
                    "PID=0x${device.productId.toString(16)} deviceClass=${device.deviceClass} " +
                    "hasPermission=$hasPerm",
            )
            if (!hasPerm) {
                Log.w(
                    TAG,
                    "USB_DEVICE_ATTACHED delivered WITHOUT permission — Control Glasses is likely the " +
                        "preferred USB handler. Fix: Settings → Apps → Control Glasses → Open by default → " +
                        "Clear defaults, then unplug and replug the glasses and pick xrdroiddesk.",
                )
                Toast
                    .makeText(
                        this,
                        "No USB permission. Clear Control Glasses defaults, then replug glasses and pick xrdroiddesk.",
                        Toast.LENGTH_LONG,
                    ).show()
                logRequirementsState("usbAttached:noPerm")
                return
            }
        }

        val service = GestureAccessibilityService.instance
        if (service == null) {
            Log.w(
                TAG,
                "USB_DEVICE_ATTACHED received (permission OK) but service not running — " +
                    "caching device for when service connects",
            )
            if (device != null) GestureAccessibilityService.pendingUsbDevice = device
            logRequirementsState("usbAttached:serviceNotRunning")
            return
        }
        if (device != null) {
            GestureAccessibilityService.pendingUsbDevice = null
            Log.i(TAG, "USB_DEVICE_ATTACHED: opening camera directly")
            service.openCamera(device)
        } else {
            service.tryConnectCamera()
        }
        logRequirementsState("usbAttached:done")
    }

    override fun onResume() {
        super.onResume()
        logRequirementsState("onResume")
        // Handle the race where onServiceConnected fired after handleUsbAttached but before onResume
        val pending = GestureAccessibilityService.pendingUsbDevice
        val service = GestureAccessibilityService.instance
        if (pending != null && service != null) {
            GestureAccessibilityService.pendingUsbDevice = null
            Log.i(TAG, "onResume: service running with pending device — opening camera directly")
            service.openCamera(pending)
        }
    }

    // Requests USB permission for the XReal glasses from this Activity's foreground context.
    // Unlike GrantUsbPermissionActivity (which was launched from the background service),
    // calling from here ensures the dialog appears on the phone screen where the user can see it.
    private fun requestUsbPermission() {
        val device =
            usbManager.deviceList.values.firstOrNull {
                it.vendorId == XRealGlassesCamera.VENDOR_ID && it.productId == XRealGlassesCamera.PRODUCT_ID
            }
        if (device == null) {
            Log.w(TAG, "requestUsbPermission: XReal device not found — plug in glasses first")
            Toast.makeText(this, "XReal glasses not found — plug in first", Toast.LENGTH_SHORT).show()
            return
        }
        if (usbManager.hasPermission(device)) {
            Log.i(TAG, "requestUsbPermission: already have permission — opening camera")
            GestureAccessibilityService.instance?.openCamera(device)
            return
        }
        Log.i(TAG, "requestUsbPermission: calling requestPermission for ${device.deviceName} from MainActivity")
        val permIntent = Intent(ACTION_USB_PERMISSION).apply { `package` = packageName }
        val pi = PendingIntent.getBroadcast(this, 1, permIntent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    permReceiver = null
                    try {
                        unregisterReceiver(this)
                    } catch (_: Exception) {
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.i(TAG, "USB permission result from MainActivity: granted=$granted")
                    if (granted) {
                        tvStatus.setText(R.string.usb_permission_granted)
                        val d = GrantUsbPermissionActivity.intentDevice(intent)
                        d?.let { GestureAccessibilityService.instance?.openCamera(it) }
                    } else {
                        tvStatus.setText(R.string.usb_permission_denied)
                        Log.w(
                            TAG,
                            "USB permission denied from MainActivity — if no dialog appeared, " +
                                "unplug glasses, clear Control Glasses defaults, replug and pick xrdroiddesk",
                        )
                    }
                    logRequirementsState("usbPermResult:granted=$granted")
                }
            }
        permReceiver = receiver
        registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION), RECEIVER_NOT_EXPORTED)
        usbManager.requestPermission(device, pi)
    }

    /**
     * Logs and displays the state of all prerequisites for hand tracking to work.
     * Called at startup (onResume) and after every user action to show what changed.
     */
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

        Log.i(
            REQ_TAG,
            "[$trigger] a11y_enabled=$a11yEnabled a11y_running=$a11yRunning " +
                "usb_found=$usbFound usb_perm=$usbPerm uvc_active=$uvcActive pending_device=$pendingDevice",
        )
        tvStatus.text =
            buildString {
                append("a11y: enabled=${yn(a11yEnabled)} running=${yn(a11yRunning)}\n")
                append("usb:  found=${yn(usbFound)} perm=${yn(usbPerm)} uvc=${yn(uvcActive)}")
                if (pendingDevice) append("\npending usb device queued")
                append("\n[$trigger]")
            }
    }

    private fun yn(b: Boolean) = if (b) "Y" else "N"

    override fun onDestroy() {
        super.onDestroy()
        permReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
            permReceiver = null
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQ_TAG = "Requirements"
        private const val ACTION_USB_PERMISSION = "com.repudi8or.xrdroiddesk.USB_PERMISSION_MAIN"
        private const val REQ_CAMERA = 1001
    }
}
