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

class GestureAccessibilityService : AccessibilityService() {
    private lateinit var dispatcher: GestureActionDispatcher
    private var pipeline: HandTrackingPipeline? = null
    private var landmarker: HandLandmarkerHelper? = null
    private var camera: XRealGlassesCamera? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var retryJob: Job? = null

    // Armed on attach, cleared on camera open success or detach.
    // Gates handleCgUsbDialog() through the full USB lifecycle.
    private var usbSessionActive = false

    // true = non-UVC phase: Allow CG's permission dialog so CG can enable UVC via HID.
    // false = post-Allow guard (prevents re-tapping Allow) and UVC phase (permission dialog ignored).
    private var cgAllowPhase = false

    // true after UVC re-enum, false until then. Distinguishes UVC chooser (select xrdroiddesk)
    // from non-UVC chooser (Cancel — CG already has permission via its dialog).
    private var uvcPhaseActive = false

    private var usbAttachTimestampMs = 0L

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
                    Log.i(TAG, "USB_DEVICE_DETACHED: XReal glasses unplugged — stopping camera and resetting state")
                    camera?.close()
                    retryJob?.cancel()
                    retryJob = null
                    usbSessionActive = false
                    cgAllowPhase = false
                    uvcPhaseActive = false
                    return
                }
                if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
                val uvcAlreadyActive =
                    (0 until device.interfaceCount).any { i ->
                        device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO
                    }
                if (uvcAlreadyActive) {
                    // UVC re-enum: CG has done its job. Arm uvcPhaseActive so the chooser handler
                    // selects xrdroiddesk. Re-arm cgAllowPhase so we Allow CG's UVC permission dialog
                    // (if Android shows one before the chooser).
                    usbSessionActive = true
                    cgAllowPhase = true
                    uvcPhaseActive = true
                    usbAttachTimestampMs = System.currentTimeMillis()
                    val usbMan = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
                    if (usbMan.hasPermission(device)) {
                        Log.i(TAG, "USB_DEVICE_ATTACHED (receiver): UVC re-enum — already have permission, opening camera")
                        openCamera(device)
                    } else {
                        Log.i(TAG, "USB_DEVICE_ATTACHED (receiver): UVC re-enum — waiting for chooser to grant xrdroiddesk permission")
                    }
                    return
                }
                // Non-UVC device: arm the CG-Allow flow and wait.
                // We do NOT request permission or send HID/TCP — Android 16 "Just once" permissions
                // are session-only so a stored grant never survives to the next plug. CG is the only
                // app that can reach the MCU's <100ms config window (via auto-grant). Our accessibility
                // service will tap Allow on CG's permission dialog, then handle the UVC re-enum.
                retryJob?.cancel()
                retryJob = null
                usbSessionActive = true
                cgAllowPhase = true
                usbAttachTimestampMs = System.currentTimeMillis()
                Log.i(TAG, "USB_DEVICE_ATTACHED (receiver): non-UVC — arming CG-Allow; will tap Allow on CG permission dialog")
            }
        }

    override fun onServiceConnected() {
        val controller = AccessibilityDesktopController(this)
        dispatcher = GestureActionDispatcher(controller, controller::normalizedToPixels)
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
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED
        ) {
            return
        }

        if (usbSessionActive) handleCgUsbDialog()
        if (uvcPhaseActive) handleXrdroideskPermDialog()
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
            Log.w(TAG, "XReal glasses not found — retrying in ${RETRY_DELAY_MS}ms")
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
            // Glasses attached without UVC — arm CG-Allow and wait for USB_DEVICE_ATTACHED
            // to fire again after CG enables UVC and the device re-enumerates.
            retryJob?.cancel()
            retryJob = null
            usbSessionActive = true
            cgAllowPhase = true
            Log.i(TAG, "tryConnectCamera: non-UVC device — arming CG-Allow; waiting for UVC re-enum")
            return
        }
        retryJob?.cancel()
        retryJob = null
        usbSessionActive = true
        cgAllowPhase = false
        if (cam.hasPermission(device)) {
            Log.i(TAG, "tryConnectCamera: UVC device, permission already granted — opening camera")
            openCamera(device)
        } else {
            Log.i(TAG, "tryConnectCamera: UVC device found — requesting permission")
            usbAttachTimestampMs = System.currentTimeMillis()
            startActivity(
                Intent(this, GrantUsbPermissionActivity::class.java).apply {
                    putExtra(UsbManager.EXTRA_DEVICE, device)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
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
            // Non-UVC device — do not retry. USB_DEVICE_ATTACHED will fire for the UVC device
            // after CG enables UVC and the glasses re-enumerate.
            retryJob?.cancel()
            retryJob = null
            Log.i(TAG, "openCamera: no UVC interfaces — waiting for CG to enable UVC via re-enum")
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
                helper.processFrame(jpegBytes, System.currentTimeMillis())
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
        usbSessionActive = false
        cgAllowPhase = false
        uvcPhaseActive = false
        pipeline?.stop()
        landmarker?.close()
        pipeline = null
        landmarker = null
        camera = null
    }

    // Handle CG's USB dialog — either a permission dialog or an app chooser.
    //
    // Permission dialog (no "Just once" text):
    //   cgAllowPhase=true → Allow (armed in both non-UVC attach and UVC re-enum so CG can proceed)
    //   cgAllowPhase=false → DO NOTHING (guard against re-tap after Allow already fired)
    //
    // Chooser dialog ("Just once" text present — both CG and xrdroiddesk in manifest):
    //   !uvcPhaseActive → select Control Glasses + "Just once" (non-UVC phase: lets CG enable UVC
    //                     AND ensures Android shows a new chooser for the UVC re-enum)
    //   uvcPhaseActive  → select xrdroiddesk + "Just once" (grants us camera permission directly)
    private fun handleCgUsbDialog() {
        val dialogRoot =
            windows
                ?.firstOrNull { w ->
                    val r = w.root ?: return@firstOrNull false
                    val pkg = r.packageName?.toString() ?: ""
                    pkg != packageName &&
                        pkg != "com.xreal.glassescontrol.store" &&
                        r.findAccessibilityNodeInfosByText("Control Glasses").isNotEmpty()
                }?.root ?: return
        val elapsed = System.currentTimeMillis() - usbAttachTimestampMs
        val isChooser = dialogRoot.findAccessibilityNodeInfosByText("Just once").isNotEmpty()

        if (isChooser) {
            if (uvcPhaseActive) {
                val xrRow = findClickableByText(dialogRoot, "xrdroiddesk") ?: return
                Log.i(TAG, "USB chooser (UVC phase): selecting xrdroiddesk (+${elapsed}ms) — grants camera permission")
                xrRow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                findNodeByText(dialogRoot, "Just once")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                val cgRow = findClickableByText(dialogRoot, "Control Glasses") ?: return
                Log.i(TAG, "USB chooser (non-UVC phase): selecting Control Glasses (+${elapsed}ms) — CG will enable UVC")
                cgRow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                findNodeByText(dialogRoot, "Just once")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        } else {
            // Permission dialog (no chooser)
            if (cgAllowPhase) {
                val allow = findPositiveButton(dialogRoot) ?: return
                Log.i(TAG, "CG USB permission dialog: Allow (+${elapsed}ms)")
                cgAllowPhase = false
                allow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (uvcPhaseActive) {
                    // CG now has UVC permission. Request it for xrdroiddesk after dialog clears.
                    serviceScope.launch {
                        delay(300)
                        if (!usbSessionActive) return@launch // camera already opened via another path
                        val usbMan = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
                        val dev =
                            usbMan.deviceList.values.firstOrNull {
                                it.vendorId == XRealGlassesCamera.VENDOR_ID &&
                                    it.productId == XRealGlassesCamera.PRODUCT_ID
                            } ?: return@launch
                        if (usbMan.hasPermission(dev)) {
                            Log.i(TAG, "UVC permission: already granted after CG Allow — opening camera")
                            openCamera(dev)
                        } else {
                            Log.i(TAG, "UVC permission: requesting for xrdroiddesk via GrantUsbPermissionActivity")
                            startActivity(
                                Intent(this@GestureAccessibilityService, GrantUsbPermissionActivity::class.java).apply {
                                    putExtra(UsbManager.EXTRA_DEVICE, dev)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                            )
                        }
                    }
                }
            }
            // cgAllowPhase=false: guard against re-tap
        }
    }

    // Auto-tap the "Allow xrdroiddesk to access XREAL One Pro?" dialog that appears after
    // requestPermission() is called from GrantUsbPermissionActivity in the UVC phase.
    private fun handleXrdroideskPermDialog() {
        val dialogRoot =
            windows
                ?.firstOrNull { w ->
                    val r = w.root ?: return@firstOrNull false
                    val pkg = r.packageName?.toString() ?: ""
                    pkg != packageName &&
                        pkg != "com.xreal.glassescontrol.store" &&
                        r.findAccessibilityNodeInfosByText("xrdroiddesk").isNotEmpty()
                }?.root ?: return
        val elapsed = System.currentTimeMillis() - usbAttachTimestampMs
        val allow = findPositiveButton(dialogRoot) ?: return
        Log.i(TAG, "xrdroiddesk USB permission dialog: Allow (+${elapsed}ms from UVC attach)")
        uvcPhaseActive = false
        allow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // Find a clickable node containing text, traversing up to the first clickable ancestor.
    private fun findClickableByText(
        root: AccessibilityNodeInfo,
        text: String,
    ): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        return nodes.firstOrNull { it.isClickable }
            ?: nodes
                .mapNotNull { n ->
                    var cur: AccessibilityNodeInfo? = n.parent
                    while (cur != null && !cur.isClickable) cur = cur.parent
                    cur
                }.firstOrNull()
    }

    private fun findNodeByText(
        root: AccessibilityNodeInfo,
        text: String,
    ): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        return nodes.firstOrNull { it.isClickable }
            ?: nodes.firstOrNull()?.parent?.takeIf { it.isClickable }
    }

    private fun findPositiveButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (text in listOf("OK", "Allow")) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            val node =
                nodes.firstOrNull { it.isClickable }
                    ?: nodes.firstOrNull()?.parent?.takeIf { it.isClickable }
            if (node != null) return node
        }
        return null
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
