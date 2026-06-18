package com.repudi8or.xrdroiddesk.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.repudi8or.xrdroiddesk.GrantUsbPermissionActivity
import com.repudi8or.xrdroiddesk.camera.GlassesUvcEnabler
import com.repudi8or.xrdroiddesk.camera.HandLandmarkerHelper
import com.repudi8or.xrdroiddesk.camera.HandTrackingPipeline
import com.repudi8or.xrdroiddesk.camera.XRealGlassesCamera
import com.repudi8or.xrdroiddesk.controller.AccessibilityDesktopController
import com.repudi8or.xrdroiddesk.controller.GestureActionDispatcher
import com.repudi8or.xrdroiddesk.gesture.GestureRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GestureAccessibilityService : AccessibilityService() {
    private lateinit var dispatcher: GestureActionDispatcher
    private var pipeline: HandTrackingPipeline? = null
    private var landmarker: HandLandmarkerHelper? = null
    private var camera: XRealGlassesCamera? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var retryJob: Job? = null
    private var tcpEnablePending = false

    // Set on non-UVC and UVC re-enum attach to arm auto-tap for the requestPermission dialog.
    // Both attach paths call requestPermission() immediately. CG's concurrent USB permission
    // dialogs are auto-dismissed (Cancel) so CG is never set as the preferred USB handler for
    // VID/PID 0x3318/0x0436 — which would permanently block our requestPermission() calls.
    private var requestPermExpected = false

    private var usbChooserExpected = false
    private var usbChooserTimeoutJob: Job? = null
    private var usbAttachTimestampMs = 0L
    private var permissionPollActive = false

    // Armed on non-UVC attach, cleared on camera open success or detach.
    // Controls dismissCgUsbDialog() so it stays live through the full USB lifecycle,
    // not just while requestPermExpected/usbChooserExpected are set.
    private var usbSessionActive = false

    // Guards against launching a second HID enable coroutine if openCamera() is somehow
    // called twice on the same non-UVC device before the re-enum fires.
    private var hidEnablePending = false

    // Fires immediately on USB_DEVICE_ATTACHED — before the permission dialog appears.
    // The accessibility service is always running, so this receiver is live at plug-in time,
    // hitting the MCU's ~2s HOST_TYPE init window without waiting for user interaction.
    private val usbAttachReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                val device: UsbDevice? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                if (device?.vendorId != XRealGlassesCamera.VENDOR_ID ||
                    device.productId != XRealGlassesCamera.PRODUCT_ID
                ) {
                    return
                }
                if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                    Log.i(TAG, "USB_DEVICE_DETACHED: XReal glasses unplugged — resetting pending state")
                    retryJob?.cancel()
                    retryJob = null
                    tcpEnablePending = false
                    hidEnablePending = false
                    requestPermExpected = false
                    usbChooserExpected = false
                    usbChooserTimeoutJob?.cancel()
                    usbChooserTimeoutJob = null
                    permissionPollActive = false
                    usbSessionActive = false
                    return
                }
                if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
                val uvcAlreadyActive =
                    (0 until device.interfaceCount).any { i ->
                        device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO
                    }
                if (uvcAlreadyActive) {
                    // UVC re-enumeration: TCP enable triggered UVC and glasses re-enumerated.
                    val usbMan = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
                    if (usbMan.hasPermission(device)) {
                        Log.i(TAG, "USB_DEVICE_ATTACHED (receiver): UVC re-enum — permission already granted, opening camera")
                        openCamera(device)
                    } else {
                        usbAttachTimestampMs = System.currentTimeMillis()
                        requestPermExpected = true
                        // CG's concurrent USB dialog will be auto-dismissed (Cancel) by
                        // onAccessibilityEvent → dismissCgUsbDialog(), preventing CG from being
                        // set as the preferred handler which would block our requestPermission().
                        Log.i(TAG, "USB_DEVICE_ATTACHED (receiver): UVC re-enum — requesting permission immediately")
                        startActivity(
                            Intent(this@GestureAccessibilityService, GrantUsbPermissionActivity::class.java).apply {
                                putExtra(UsbManager.EXTRA_DEVICE, device)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    }
                    return
                }
                if (tcpEnablePending || hidEnablePending) {
                    Log.d(TAG, "USB_DEVICE_ATTACHED (receiver): enable already in flight — skipping")
                    return
                }
                retryJob?.cancel()
                retryJob = null
                usbSessionActive = true
                usbAttachTimestampMs = System.currentTimeMillis()

                val usbMan2 = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
                if (usbMan2.hasPermission(device)) {
                    // Already have VID/PID permission from a previous session — skip the permission
                    // dialog entirely and send HID HOST_TYPE=2 within ~50ms of attach, well inside
                    // the MCU's config window (~300-500ms). No TCP fallback needed.
                    hidEnablePending = true
                    Log.i(TAG, "USB_DEVICE_ATTACHED (receiver): already have permission — launching HID enableUvc() immediately")
                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            val enabler = GlassesUvcEnabler(this@GestureAccessibilityService)
                            val ok = enabler.enableUvc()
                            enabler.release()
                            Log.i(TAG, "Immediate HID UVC enable (receiver): $ok")
                        } finally {
                            withContext(Dispatchers.Main) { hidEnablePending = false }
                        }
                    }
                    return
                }

                // First time (no permission yet) — go through dialog + TCP flow.
                // HID HOST_TYPE will arrive ~535ms after attach (after dialog completes), which may
                // miss the MCU's config window. Permission is stored after this first attempt, so the
                // NEXT plug-in will use the fast path above and succeed.
                tcpEnablePending = true
                requestPermExpected = true
                // Request USB permission immediately — before the UVC re-enum and before the multi-app
                // chooser can interfere. requestPermission() shows "Allow xrdroiddesk?" (OK/Cancel)
                // which DOES grant permission, unlike the multi-app chooser "Just once" which doesn't
                // on Android 16. Same VID/PID permission persists through UVC re-enum (~2s later).
                Log.i(TAG, "USB_DEVICE_ATTACHED (receiver): XReal attached without UVC — requesting permission + TCP enable")
                startActivity(
                    Intent(this@GestureAccessibilityService, GrantUsbPermissionActivity::class.java).apply {
                        putExtra(UsbManager.EXTRA_DEVICE, device)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val enabler = GlassesUvcEnabler(this@GestureAccessibilityService)
                        val ok = enabler.enableUvcViaTcp()
                        enabler.release()
                        Log.i(TAG, "TCP UVC enable (receiver): $ok")
                    } finally {
                        withContext(Dispatchers.Main) { tcpEnablePending = false }
                    }
                }
            }
        }

    override fun onServiceConnected() {
        dispatcher = GestureActionDispatcher(AccessibilityDesktopController(this))
        instance = this
        registerReceiver(
            usbAttachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply {
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
        )
        startHandTracking()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Auto-dismiss CG's USB permission dialogs before the user can tap them.
        // If CG is granted USB access, Android sets CG as the preferred handler for
        // VID/PID 0x3318/0x0436, permanently blocking our requestPermission() calls.
        // CG enables UVC via TCP (no USB permission needed); display streams via USB NCM.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            if (usbSessionActive) dismissCgUsbDialog()
        }

        if (!usbChooserExpected && !requestPermExpected) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED
        ) {
            return
        }
        val elapsed = System.currentTimeMillis() - usbAttachTimestampMs

        // Multi-app chooser path (fallback): only when explicitly armed for UVC re-enum.
        // Note: on Android 16, tapping "Just once" in the multi-app chooser does NOT grant USB
        // permission — kept here as a no-harm fallback in case this changes in a future OS version.
        if (usbChooserExpected) {
            val appItem = findInAllWindows("xrdroiddesk")
            if (appItem != null) {
                Log.i(TAG, "USB chooser: multiple apps — selecting 'xrdroiddesk' (+${elapsed}ms from attach)")
                appItem.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                usbChooserExpected = false
                usbChooserTimeoutJob?.cancel()
                usbChooserTimeoutJob = null
                startPermissionPoll()
                serviceScope.launch { pollForJustOnce() }
                return
            }
            val justOnce = findJustOnceInXrdroideskContext()
            if (justOnce != null) {
                Log.i(TAG, "USB chooser auto-tap: 'Just once' (+${elapsed}ms from attach) — tapping now")
                justOnce.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                usbChooserExpected = false
                usbChooserTimeoutJob?.cancel()
                usbChooserTimeoutJob = null
                startPermissionPoll()
                return
            }
        }

        // requestPermission dialog: "Allow xrdroiddesk to access USB device?" with OK/Allow button.
        // This dialog DOES grant USB permission (unlike the multi-app chooser). It appears when
        // GrantUsbPermissionActivity calls requestPermission() on the non-UVC device immediately
        // on attach — before the 2s UVC re-enum — so permission for VID/PID is stored early.
        // Guard: skip windows that are the multi-app chooser (contain "Just once") to avoid
        // accidentally tapping the OK in a different dialog context.
        val root = rootInActiveWindow ?: return
        val isChooser = root.findAccessibilityNodeInfosByText("Just once").isNotEmpty()
        if (!isChooser && root.findAccessibilityNodeInfosByText("xrdroiddesk").isNotEmpty()) {
            val ok =
                root.findAccessibilityNodeInfosByText("OK").let { nodes ->
                    nodes.firstOrNull { it.isClickable }
                        ?: nodes.firstOrNull()?.parent?.takeIf { it.isClickable }
                } ?: root.findAccessibilityNodeInfosByText("Allow").let { nodes ->
                    nodes.firstOrNull { it.isClickable }
                        ?: nodes.firstOrNull()?.parent?.takeIf { it.isClickable }
                }
            if (ok != null) {
                Log.i(TAG, "USB permission dialog auto-tap: 'OK/Allow' (+${elapsed}ms from attach) — tapping now")
                ok.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                requestPermExpected = false
                usbChooserExpected = false
                usbChooserTimeoutJob?.cancel()
                usbChooserTimeoutJob = null
                startPermissionPoll()
            }
        }
    }

    // Find the first clickable node with the given text across all accessibility windows.
    // Logs the window package when found for diagnostics.
    private fun findInAllWindows(text: String): AccessibilityNodeInfo? {
        for (window in windows ?: return null) {
            val root = window.root ?: continue
            val nodes = root.findAccessibilityNodeInfosByText(text)
            val node =
                nodes.firstOrNull { it.isClickable }
                    ?: nodes.firstOrNull()?.parent?.takeIf { it.isClickable }
            if (node != null) {
                Log.d(TAG, "findInAllWindows('$text'): found in pkg=${root.packageName}")
                return node
            }
        }
        return null
    }

    // Find "Just once" only in windows that also contain "xrdroiddesk" text.
    // Prevents tapping CG's "Allow Control Glasses → Just once" dialog that appears
    // concurrently with xrdroiddesk's USB chooser on UVC re-enum.
    private fun findJustOnceInXrdroideskContext(): AccessibilityNodeInfo? {
        for (window in windows ?: return null) {
            val root = window.root ?: continue
            if (root.findAccessibilityNodeInfosByText("xrdroiddesk").isEmpty()) continue
            val nodes = root.findAccessibilityNodeInfosByText("Just once")
            val node =
                nodes.firstOrNull { it.isClickable }
                    ?: nodes.firstOrNull()?.parent?.takeIf { it.isClickable }
            if (node != null) {
                Log.d(TAG, "findJustOnceInXrdroideskContext: found in pkg=${root.packageName}")
                return node
            }
        }
        return null
    }

    // Poll for the xrdroiddesk app row in the USB chooser at 50ms intervals.
    // Used instead of waiting for accessibility events, which can be delayed 3+ seconds.
    // Initial 400ms delay allows the non-UVC chooser dismissal animation to complete and
    // the UVC chooser to fully render before we start searching.
    private suspend fun pollForAppRow() {
        delay(400L)
        repeat(600) { attempt ->
            val elapsed = System.currentTimeMillis() - usbAttachTimestampMs
            if (!usbChooserExpected) {
                Log.d(TAG, "pollForAppRow: usbChooserExpected cleared — stopping (+${elapsed}ms)")
                return
            }
            val appItem = findInAllWindows("xrdroiddesk")
            if (appItem != null) {
                Log.i(TAG, "pollForAppRow: 'xrdroiddesk' row (+${elapsed}ms, attempt ${attempt + 1}) — tapping")
                appItem.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                usbChooserExpected = false
                usbChooserTimeoutJob?.cancel()
                usbChooserTimeoutJob = null
                startPermissionPoll()
                serviceScope.launch { pollForJustOnce() }
                return
            }
            delay(50L)
        }
        val elapsed = System.currentTimeMillis() - usbAttachTimestampMs
        Log.d(TAG, "pollForAppRow: 'xrdroiddesk' not found after 30s (+${elapsed}ms) — onAccessibilityEvent will handle it")
        // Do NOT clear usbChooserExpected here — the 5-minute timeout handles cleanup.
        // onAccessibilityEvent will fire when the chooser eventually appears.
    }

    // Poll all windows for "Just once" (in xrdroiddesk context) after tapping the app row.
    // The 300ms initial delay lets Android process the row selection before we tap "Just once" —
    // tapping too quickly (32ms) confirms before the row is selected, granting permission to nobody.
    private suspend fun pollForJustOnce() {
        delay(300L)
        repeat(40) {
            val elapsed = System.currentTimeMillis() - usbAttachTimestampMs
            val node = findJustOnceInXrdroideskContext()
            if (node != null) {
                Log.i(TAG, "USB chooser auto-tap: 'Just once' after row select (+${elapsed}ms from attach) — tapping")
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                startPermissionPoll()
                return
            }
            delay(50L)
        }
        val elapsed = System.currentTimeMillis() - usbAttachTimestampMs
        Log.d(TAG, "USB chooser: 'Just once' not found after 2s (+${elapsed}ms) — startPermissionPoll as fallback")
        startPermissionPoll()
    }

    // Poll for USB permission after auto-tap; granted ~50-100ms post-tap.
    // Guard prevents duplicate polls from concurrent tap paths.
    private fun startPermissionPoll() {
        if (permissionPollActive) return
        permissionPollActive = true
        val usbMan = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        val device =
            usbMan.deviceList.values.firstOrNull {
                it.vendorId == XRealGlassesCamera.VENDOR_ID && it.productId == XRealGlassesCamera.PRODUCT_ID
            } ?: run {
                permissionPollActive = false
                return
            }
        serviceScope.launch {
            repeat(40) { attempt ->
                if (usbMan.hasPermission(device)) {
                    val totalMs = System.currentTimeMillis() - usbAttachTimestampMs
                    Log.i(TAG, "USB permission confirmed (attempt ${attempt + 1}, +${totalMs}ms from attach) — opening camera")
                    permissionPollActive = false
                    openCamera(device)
                    return@launch
                }
                delay(50L)
            }
            permissionPollActive = false
            Log.w(
                TAG,
                "USB permission not detected after 2s of polling — trying cam.open() as fallback (hasPermission may be stale on Android 16)",
            )
            openCamera(device)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        try {
            unregisterReceiver(usbAttachReceiver)
        } catch (_: Exception) {
        }
        stopHandTracking()
        instance = null
        return super.onUnbind(intent)
    }

    fun triggerDebugClick(
        x: Float,
        y: Float,
    ) {
        dispatcher.triggerClick(x, y)
    }

    fun tryConnectCamera() {
        val cam = camera ?: return
        val device = cam.findDevice()
        if (device == null) {
            Log.w(TAG, "XReal glasses not found — retrying in ${RETRY_DELAY_MS}ms. Is UVC mode enabled in Control Glasses?")
            retryJob?.cancel()
            retryJob =
                serviceScope.launch {
                    delay(RETRY_DELAY_MS)
                    tryConnectCamera()
                }
            return
        }
        val uvcActive =
            (0 until device.interfaceCount).any { i ->
                device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO
            }
        if (!uvcActive) {
            retryJob?.cancel()
            retryJob = null
            if (hidEnablePending) {
                Log.d(TAG, "tryConnectCamera: non-UVC + HID enable in flight — waiting for re-enum")
                return
            }
            // Request USB permission for the non-UVC device — needed so the attach receiver's
            // fast path (hasPermission=true) fires immediately on the next plug-in.
            if (!requestPermExpected) {
                requestPermExpected = true
                usbAttachTimestampMs = System.currentTimeMillis()
                Log.i(TAG, "Device found without UVC — requesting permission immediately (before UVC re-enum)")
                startActivity(
                    Intent(this, GrantUsbPermissionActivity::class.java).apply {
                        putExtra(UsbManager.EXTRA_DEVICE, device)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
            Log.i(TAG, "Device found without UVC interfaces — waiting for Control Glasses to enable UVC (USB_DEVICE_ATTACHED will resume)")
            return
        }
        retryJob?.cancel()
        retryJob = null
        if (cam.hasPermission(device)) {
            Log.i(TAG, "USB permission already granted — opening UVC stream")
            openCamera(device)
        } else if (usbChooserExpected) {
            Log.i(TAG, "USB permission not yet granted — USB chooser pending, skipping explicit requestPermission()")
        } else {
            Log.w(TAG, "Device found but no USB permission — launching permission request")
            val intent =
                Intent(this, GrantUsbPermissionActivity::class.java).apply {
                    putExtra(UsbManager.EXTRA_DEVICE, device)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            startActivity(intent)
        }
    }

    fun openCamera(device: UsbDevice) {
        val cam = camera ?: return
        val hasPerm = (getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager).hasPermission(device)
        Log.i(TAG, "openCamera: calling cam.open() — device=${device.deviceName} hasPerm=$hasPerm")
        val startMs = System.currentTimeMillis()
        val opened = cam.open(device)
        val elapsedMs = System.currentTimeMillis() - startMs
        if (opened) {
            Log.i(TAG, "openCamera: SUCCESS — cam.open() returned true in ${elapsedMs}ms")
            usbSessionActive = false
            return
        }
        val uvcActive =
            (0 until device.interfaceCount).any { i ->
                device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO
            }
        if (!uvcActive) {
            // Non-UVC device — do NOT schedule a retry loop. USB_DEVICE_ATTACHED fires again
            // for the UVC device after HID enable triggers re-enum.
            retryJob?.cancel()
            retryJob = null
            if (!hidEnablePending) {
                // cam.open() failed because this is the non-UVC device and HID enable hasn't
                // launched yet (e.g., first plug where hasPermission was false at attach time
                // but is now true). Send HOST_TYPE=2 + 0x26 + GET + SET via USB HID bulk.
                hidEnablePending = true
                Log.i(TAG, "openCamera: no UVC interfaces — launching HID enableUvc() (TCP alone cannot trigger re-enum)")
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        val enabler = GlassesUvcEnabler(this@GestureAccessibilityService)
                        val ok = enabler.enableUvc()
                        enabler.release()
                        Log.i(
                            TAG,
                            "openCamera: HID UVC enable result=$ok${if (ok) " — waiting for UVC re-enum in ~2s" else " — MCU did not enter config mode (Control Glasses may be required)"}",
                        )
                    } finally {
                        withContext(Dispatchers.Main) { hidEnablePending = false }
                    }
                }
            } else {
                Log.d(TAG, "openCamera: no UVC + HID enable already in flight — not retrying")
            }
            return
        }
        Log.w(TAG, "openCamera: FAILED — cam.open() returned false in ${elapsedMs}ms (hasPerm=$hasPerm) — retrying in ${RETRY_DELAY_MS}ms")
        retryJob?.cancel()
        retryJob =
            serviceScope.launch {
                delay(RETRY_DELAY_MS)
                tryConnectCamera()
            }
    }

    private fun startHandTracking() {
        val helper =
            HandLandmarkerHelper(this) { handData ->
                pipeline?.onHandData(handData)
            }
        landmarker = helper

        val cam =
            XRealGlassesCamera(this) { jpegBytes ->
                helper.processJpegFrame(jpegBytes, System.currentTimeMillis())
            }
        camera = cam

        val pl = HandTrackingPipeline(cam, GestureRecognizer(), dispatcher)
        pipeline = pl

        val pending = pendingUsbDevice
        if (pending != null) {
            pendingUsbDevice = null
            Log.i(TAG, "Service connected with pending USB device — opening camera directly")
            openCamera(pending)
        } else {
            tryConnectCamera()
        }
    }

    private fun stopHandTracking() {
        retryJob?.cancel()
        retryJob = null
        tcpEnablePending = false
        hidEnablePending = false
        requestPermExpected = false
        usbSessionActive = false
        usbChooserTimeoutJob?.cancel()
        usbChooserTimeoutJob = null
        usbChooserExpected = false
        permissionPollActive = false
        pipeline?.stop()
        landmarker?.close()
        pipeline = null
        landmarker = null
        camera = null
    }

    // Tap the negative button on CG's USB permission dialog (system-issued, not a chooser).
    // This prevents CG from being granted USB access and set as the preferred handler for
    // VID/PID 0x3318/0x0436, which would permanently block our requestPermission() calls.
    private fun dismissCgUsbDialog() {
        val cgDialogRoot =
            windows
                ?.firstOrNull { w ->
                    val r = w.root ?: return@firstOrNull false
                    val pkg = r.packageName?.toString() ?: ""
                    // System-issued dialog (not our app, not CG itself) that mentions "Control Glasses"
                    // but is NOT the multi-app chooser (chooser has "Just once").
                    pkg != packageName &&
                        pkg != "com.xreal.glassescontrol.store" &&
                        r.findAccessibilityNodeInfosByText("Control Glasses").isNotEmpty() &&
                        r.findAccessibilityNodeInfosByText("Just once").isEmpty()
                }?.root ?: return
        val cancel = findNegativeButton(cgDialogRoot) ?: return
        val elapsed = System.currentTimeMillis() - usbAttachTimestampMs
        Log.i(TAG, "CG USB dialog: auto-dismissing Cancel (+${elapsed}ms) — prevents CG preferred-handler")
        cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findNegativeButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (text in listOf("Don't allow", "Cancel", "Deny", "No")) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            val node =
                nodes.firstOrNull { it.isClickable }
                    ?: nodes.firstOrNull()?.parent?.takeIf { it.isClickable }
            if (node != null) return node
        }
        return null
    }

    companion object {
        private const val TAG = "GestureA11yService"
        private const val RETRY_DELAY_MS = 3000L

        var instance: GestureAccessibilityService? = null
            private set

        // Set by MainActivity when USB_DEVICE_ATTACHED fires before the service is running.
        // Consumed in startHandTracking() to open the camera directly with the OS-granted permission.
        var pendingUsbDevice: UsbDevice? = null
    }
}
