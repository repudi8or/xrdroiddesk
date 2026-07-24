package com.repudi8or.xrdroiddesk.service

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.repudi8or.xrdroiddesk.camera.HandLandmarkerHelper
import com.repudi8or.xrdroiddesk.camera.HandTrackingPipeline
import com.repudi8or.xrdroiddesk.camera.XRealGlassesCamera
import com.repudi8or.xrdroiddesk.controller.AccessibilityDesktopController
import com.repudi8or.xrdroiddesk.controller.GestureActionDispatcher
import com.repudi8or.xrdroiddesk.gesture.GestureConfig
import com.repudi8or.xrdroiddesk.gesture.GestureRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GestureAccessibilityService : AccessibilityService() {
    private lateinit var dispatcher: GestureActionDispatcher
    private lateinit var usbSetup: UsbSetupAutomator
    private var pipeline: HandTrackingPipeline? = null
    private var landmarker: HandLandmarkerHelper? = null
    private var camera: XRealGlassesCamera? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var retryJob: Job? = null
    private var stateLogJob: Job? = null
    private var stateLogSeq = 0

    // Tracks which display we last launched on; prevents duplicate launches per plug cycle.
    private var lastLaunchedDisplayId = Display.INVALID_DISPLAY

    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                Log.d(TAG, "onDisplayAdded: id=$displayId lastLaunched=$lastLaunchedDisplayId")
                if (displayId != Display.DEFAULT_DISPLAY && lastLaunchedDisplayId != displayId) {
                    uiLog("external display $displayId added — launching xrdroiddesk")
                    launchOnGlassesDisplay()
                } else if (lastLaunchedDisplayId == displayId) {
                    Log.d(TAG, "onDisplayAdded: display $displayId already launched — skipping")
                }
                if (displayId != Display.DEFAULT_DISPLAY) {
                    val cam = camera
                    if (cam != null && !cam.isOpen && !usbSetup.hidEnablePending) {
                        Log.d(TAG, "onDisplayAdded: external display appeared — calling tryConnectCamera()")
                        tryConnectCamera()
                    }
                }
            }

            override fun onDisplayRemoved(displayId: Int) {
                Log.d(TAG, "onDisplayRemoved: id=$displayId lastLaunched=$lastLaunchedDisplayId")
                if (displayId == lastLaunchedDisplayId) lastLaunchedDisplayId = Display.INVALID_DISPLAY
            }

            override fun onDisplayChanged(displayId: Int) {}
        }

    private val debugResetReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                Log.i(TAG, "debug reset received")
                camera?.close()
                usbSetup.reset()
                tryConnectCamera()
            }
        }

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
                Log.d(
                    TAG,
                    "usbReceiver: action=${intent.action} device=${device?.deviceName} VID=${device?.vendorId?.toString(
                        16,
                    )} PID=${device?.productId?.toString(16)}",
                )
                if (device?.vendorId != XRealGlassesCamera.VENDOR_ID ||
                    device.productId != XRealGlassesCamera.PRODUCT_ID
                ) {
                    return
                }
                if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                    uiLog("USB detached — camera stopped, scheduling reconnect in 2s")
                    camera?.close()
                    retryJob?.cancel()
                    usbSetup.onDeviceDetached()
                    // lastLaunchedDisplayId intentionally NOT reset here — the glasses display
                    // persists through the non-UVC → UVC re-enum (it's an NCM/ECM display, not
                    // USB video). onDisplayRemoved resets it if the display actually disappears.
                    retryJob =
                        serviceScope.launch {
                            delay(CAMERA_RECONNECT_DELAY_MS)
                            tryConnectCamera()
                        }
                    return
                }
                if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
                onUsbDeviceAttached(device)
            }
        }

    override fun onServiceConnected() {
        val controller = AccessibilityDesktopController(this)
        dispatcher = GestureActionDispatcher(controller, controller::normalizedToPixels)
        usbSetup =
            UsbSetupAutomator(
                context = this,
                scope = serviceScope,
                windowsProvider = { windows },
                onCameraReady = ::openCamera,
                uiLog = ::uiLog,
            )
        instance = this
        val dm = getSystemService(DisplayManager::class.java)
        dm.registerDisplayListener(displayListener, null)
        val existingExternal = dm.displays.filter { it.displayId != Display.DEFAULT_DISPLAY }
        if (existingExternal.isNotEmpty()) {
            Log.d(TAG, "onServiceConnected: ${existingExternal.size} external display(s) already present — launching")
            launchOnGlassesDisplay()
        }
        val usbFilter =
            IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply {
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbAttachReceiver, usbFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbAttachReceiver, usbFilter)
        }
        registerReceiver(
            debugResetReceiver,
            IntentFilter(ACTION_DEBUG_RESET),
            RECEIVER_NOT_EXPORTED,
        )
        startHandTracking()
        startStateLogger()
        scanPreAttachedCamera()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED
        ) {
            return
        }
        usbSetup.onAccessibilityEvent(isCameraOpen = camera?.isOpen == true)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        try {
            unregisterReceiver(usbAttachReceiver)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(debugResetReceiver)
        } catch (_: Exception) {
        }
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        stopHandTracking()
        stopStateLogger()
        instance = null
        return super.onUnbind(intent)
    }

    fun triggerDebugClick(
        x: Float,
        y: Float,
    ) {
        dispatcher.triggerClick(x, y)
    }

    fun onUsbPermissionDenied() {
        usbSetup.onUsbPermissionDenied()
    }

    // Called by the dynamic usbAttachReceiver (~130ms after plug-in).
    // UsbSetupAutomator.onDeviceAttached() is idempotent via hidEnablePending.
    fun onUsbDeviceAttached(device: UsbDevice) {
        retryJob?.cancel()
        retryJob = null
        usbSetup.onDeviceAttached(device)
        retryJob =
            serviceScope.launch {
                delay(RETRY_DELAY_MS)
                tryConnectCamera()
            }
    }

    // Called from GrantUsbPermissionActivity.onCreate() — Android launches it via
    // USB_DEVICE_ATTACHED manifest filter at ~50ms, earlier than the dynamic receiver.
    // skipActivityLaunch=true prevents the 50ms-delayed coroutine from launching a
    // second GrantUsbPermissionActivity while the first is still on screen.
    fun onManifestUsbAttached(device: UsbDevice) {
        retryJob?.cancel()
        retryJob = null
        usbSetup.onDeviceAttached(device, skipActivityLaunch = true)
        // Schedule a retry so UVC re-enum (or next plug) is picked up even if the
        // USB_DEVICE_ATTACHED re-delivery is missed.
        retryJob =
            serviceScope.launch {
                delay(RETRY_DELAY_MS)
                tryConnectCamera()
            }
    }

    fun tryConnectCamera() {
        val cam = camera ?: return
        val device = cam.findDevice() ?: usbSetup.lastKnownDevice
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
            retryJob?.cancel()
            retryJob = null
            val usbMan = getSystemService(Context.USB_SERVICE) as UsbManager
            if (usbMan.hasPermission(device)) {
                usbSetup.armNonUvcWithPerm(device)
                retryJob =
                    serviceScope.launch {
                        delay(RETRY_DELAY_MS)
                        tryConnectCamera()
                    }
            } else {
                usbSetup.armNonUvcNoPerm(device)
                retryJob =
                    serviceScope.launch {
                        delay(RETRY_DELAY_MS)
                        tryConnectCamera()
                    }
            }
            return
        }
        retryJob?.cancel()
        retryJob = null
        usbSetup.setUvcPhaseActive()
        if (cam.hasPermission(device)) {
            Log.i(TAG, "tryConnectCamera: UVC device, permission already granted — opening camera")
            openCamera(device)
        } else {
            Log.i(TAG, "tryConnectCamera: UVC device found — requesting permission")
            usbSetup.requestUvcPerm(device)
        }
    }

    fun openCamera(device: UsbDevice) {
        val cam = camera ?: return
        if (cam.isOpen) {
            Log.i(TAG, "openCamera: camera already running — ignoring duplicate call (device=${device.deviceName})")
            return
        }
        val hasPerm = (getSystemService(Context.USB_SERVICE) as UsbManager).hasPermission(device)
        Log.i(TAG, "openCamera: calling cam.open() — device=${device.deviceName} hasPerm=$hasPerm")
        val startMs = System.currentTimeMillis()
        val opened = cam.open(device)
        val elapsedMs = System.currentTimeMillis() - startMs
        if (opened) {
            uiLog("camera open OK (${elapsedMs}ms)")
            usbSetup.onCameraOpened()
            // Reset so launchOnGlassesDisplay() runs even if it already ran from onDisplayAdded.
            lastLaunchedDisplayId = Display.INVALID_DISPLAY
            launchOnGlassesDisplay()
            return
        }
        val uvcActive =
            (0 until device.interfaceCount).any { i ->
                device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO
            }
        if (!uvcActive) {
            retryJob?.cancel()
            retryJob = null
            usbSetup.onCameraOpenFailedNonUvc()
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

    private fun scanPreAttachedCamera() {
        val usbMan = getSystemService(Context.USB_SERVICE) as UsbManager
        val device =
            usbMan.deviceList.values.firstOrNull {
                it.vendorId == XRealGlassesCamera.VENDOR_ID &&
                    it.productId == XRealGlassesCamera.PRODUCT_ID
            } ?: return
        val hasUvc =
            (0 until device.interfaceCount).any { i ->
                device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO
            }
        if (hasUvc && usbMan.hasPermission(device)) {
            Log.i(TAG, "scanPreAttachedCamera: UVC device found with permission — opening camera")
            openCamera(device)
        }
    }

    private fun launchOnGlassesDisplay() {
        val dm = getSystemService(DisplayManager::class.java)
        val allDisplays = dm.displays
        val glassesDisplay = allDisplays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        if (glassesDisplay == null) {
            Log.d(TAG, "launchOnGlassesDisplay: no external display — skipping")
            return
        }
        if (lastLaunchedDisplayId == glassesDisplay.displayId) {
            Log.d(TAG, "launchOnGlassesDisplay: already launched on display ${glassesDisplay.displayId} — skipping")
            return
        }
        lastLaunchedDisplayId = glassesDisplay.displayId
        val intent =
            Intent(this, com.repudi8or.xrdroiddesk.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(glassesDisplay.displayId)
        try {
            startActivity(intent, options.toBundle())
            uiLog("xrdroiddesk launched on display ${glassesDisplay.displayId} (${glassesDisplay.name})")
        } catch (e: Exception) {
            uiLog("could not auto-launch on glasses: ${e.message}")
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

        val pl = HandTrackingPipeline(cam, GestureRecognizer(GestureConfig()), dispatcher)
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
        lastLaunchedDisplayId = Display.INVALID_DISPLAY
        usbSetup.reset()
        pipeline?.stop()
        landmarker?.close()
        pipeline = null
        landmarker = null
        camera = null
    }

    private fun startStateLogger() {
        stateLogJob?.cancel()
        stateLogJob =
            serviceScope.launch {
                while (isActive) {
                    delay(STATE_LOG_INTERVAL_MS)
                    logFlowState()
                }
            }
    }

    private fun stopStateLogger() {
        stateLogJob?.cancel()
        stateLogJob = null
    }

    @Suppress("DEPRECATION")
    private fun logFlowState() {
        val n = ++stateLogSeq
        val usbMan = getSystemService(Context.USB_SERVICE) as UsbManager
        val dm = getSystemService(DisplayManager::class.java)

        val camPerm =
            checkSelfPermission(android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        val xrDevices =
            usbMan.deviceList.values.filter {
                it.vendorId == XRealGlassesCamera.VENDOR_ID && it.productId == XRealGlassesCamera.PRODUCT_ID
            }
        if (xrDevices.isEmpty()) {
            Log.i(STATE_TAG, "#$n USB: no XREAL device")
        } else {
            for (dev in xrDevices) {
                val hasUvc =
                    (0 until dev.interfaceCount).any { i ->
                        dev.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO
                    }
                val hasPerm = usbMan.hasPermission(dev)
                val classes =
                    (0 until dev.interfaceCount)
                        .map { dev.getInterface(it).interfaceClass }
                        .distinct()
                        .sorted()
                        .joinToString(",")
                Log.i(STATE_TAG, "#$n USB: ${dev.deviceName} ifaces=${dev.interfaceCount} classes=[$classes] uvc=$hasUvc xrdPerm=$hasPerm")
            }
        }

        val extDisplays = dm.displays.filter { it.displayId != Display.DEFAULT_DISPLAY }
        if (extDisplays.isEmpty()) {
            Log.i(STATE_TAG, "#$n DISP: none | xrdLaunchedOn=$lastLaunchedDisplayId")
        } else {
            for (d in extDisplays) {
                val m = android.util.DisplayMetrics()
                d.getMetrics(m)
                Log.i(STATE_TAG, "#$n DISP: id=${d.displayId} ${m.widthPixels}x${m.heightPixels} '${d.name}' state=${d.state}")
            }
            Log.i(STATE_TAG, "#$n DISP: xrdLaunchedOn=$lastLaunchedDisplayId")
        }

        val camOpen = camera?.isOpen ?: false
        Log.i(STATE_TAG, "#$n CAM: open=$camOpen camPerm=$camPerm")

        Log.i(
            STATE_TAG,
            "#$n FLAGS: sess=${usbSetup.usbSessionActive} cgAllow=${usbSetup.cgAllowPhase} uvcPhase=${usbSetup.uvcPhaseActive} selfPerm=${usbSetup.selfPermPending} hidPend=${usbSetup.hidEnablePending}",
        )

        val wins =
            windows ?: run {
                Log.i(STATE_TAG, "#$n DIALOGS: (windows unavailable)")
                return
            }
        var cgPermDlg = false
        var xrdPermDlg = false
        var chooserOpen = false
        val interestingWindows = mutableListOf<String>()

        for (w in wins) {
            val r = w.root ?: continue
            val pkg = r.packageName?.toString() ?: "?"
            if (pkg == packageName) continue

            if (pkg == "com.xreal.glassescontrol.store") {
                val cgText = collectAllText(r)
                if (cgText.isNotEmpty()) {
                    Log.i(STATE_TAG, "#$n CG_WIN: ${cgText.take(20).joinToString(" | ")}")
                }
            }

            val hasCg = r.findAccessibilityNodeInfosByText("Control Glasses").isNotEmpty()
            val hasXrd = r.findAccessibilityNodeInfosByText("xrdroiddesk").isNotEmpty()
            val hasJustOnce = r.findAccessibilityNodeInfosByText("Just once").isNotEmpty()
            val hasMirror = r.findAccessibilityNodeInfosByText("Mirror").isNotEmpty()
            val hasDesktop = r.findAccessibilityNodeInfosByText("Desktop").isNotEmpty()
            val hasAllow = r.findAccessibilityNodeInfosByText("Allow").isNotEmpty()
            val hasDeny = r.findAccessibilityNodeInfosByText("Don't allow").isNotEmpty()
            val hasXReal =
                r.findAccessibilityNodeInfosByText("XREAL").isNotEmpty() ||
                    r.findAccessibilityNodeInfosByText("XReal").isNotEmpty()
            val hasCamAccess =
                r.findAccessibilityNodeInfosByText("Camera access").isNotEmpty() ||
                    r.findAccessibilityNodeInfosByText("camera access").isNotEmpty()

            val isNotCgApp = pkg != "com.xreal.glassescontrol.store"
            val isNotClaude = pkg != "com.anthropic.claude"
            if (isNotCgApp && hasCg) cgPermDlg = true
            if (isNotCgApp && isNotClaude && hasXrd) xrdPermDlg = true
            if (hasJustOnce) chooserOpen = true

            val kw =
                buildString {
                    if (hasCg) append("CG ")
                    if (hasXrd) append("xrd ")
                    if (hasJustOnce) append("JustOnce ")
                    if (hasMirror) append("Mirror ")
                    if (hasDesktop) append("Desktop ")
                    if (hasAllow) append("Allow ")
                    if (hasDeny) append("Deny ")
                    if (hasXReal) append("XReal ")
                    if (hasCamAccess) append("CamAccess ")
                }.trim()
            if (kw.isNotEmpty()) {
                interestingWindows.add("$pkg[$kw]")
            }
        }

        Log.i(STATE_TAG, "#$n DIALOGS: cgPerm=$cgPermDlg xrdPerm=$xrdPermDlg chooser=$chooserOpen")
        if (interestingWindows.isNotEmpty()) {
            Log.i(STATE_TAG, "#$n WIN_DETAIL: ${interestingWindows.joinToString(" | ")}")
        }
    }

    // logFlowState still needs to scan accessibility windows directly — kept here
    private fun collectAllText(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
    ): List<String> {
        if (depth > 12) return emptyList()
        val result = mutableListOf<String>()
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) result.add(text)
        val cd = node.contentDescription?.toString()?.trim()
        if (!cd.isNullOrEmpty() && cd != text) result.add(cd)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { result.addAll(collectAllText(it, depth + 1)) }
        }
        return result
    }

    private fun uiLog(msg: String) {
        Log.i(TAG, msg)
        com.repudi8or.xrdroiddesk.MainActivity.instance?.runOnUiThread {
            com.repudi8or.xrdroiddesk.MainActivity.instance
                ?.appendLog(msg)
        }
    }

    companion object {
        private const val TAG = "GestureA11yService"
        private const val STATE_TAG = "FlowState"
        private const val RETRY_DELAY_MS = 3000L
        private const val CAMERA_RECONNECT_DELAY_MS = 2000L
        private const val STATE_LOG_INTERVAL_MS = 3000L
        const val ACTION_DEBUG_RESET = "com.repudi8or.xrdroiddesk.ACTION_DEBUG_RESET"

        var instance: GestureAccessibilityService? = null
            private set

        // Set by MainActivity when USB_DEVICE_ATTACHED fires before the service is running.
        var pendingUsbDevice: UsbDevice? = null
    }
}
