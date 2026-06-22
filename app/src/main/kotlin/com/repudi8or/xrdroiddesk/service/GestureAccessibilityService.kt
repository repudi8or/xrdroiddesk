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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GestureAccessibilityService : AccessibilityService() {
    private lateinit var dispatcher: GestureActionDispatcher
    private var pipeline: HandTrackingPipeline? = null
    private var landmarker: HandLandmarkerHelper? = null
    private var camera: XRealGlassesCamera? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var retryJob: Job? = null
    private var stateLogJob: Job? = null
    private var stateLogSeq = 0

    // Armed on attach, cleared on camera open success or detach.
    // Gates handleCgUsbDialog() through the full USB lifecycle.
    private var usbSessionActive = false

    // true while HID enableUvc() coroutine is running (fast path).
    private var hidEnablePending = false

    // true = non-UVC phase: Allow CG's permission dialog so CG can enable UVC via HID.
    // false = post-Allow guard (prevents re-tapping Allow) and UVC phase (permission dialog ignored).
    private var cgAllowPhase = false

    // true after UVC re-enum, false until then. Distinguishes UVC chooser (select xrdroiddesk)
    // from non-UVC chooser (Cancel — CG already has permission via its dialog).
    private var uvcPhaseActive = false

    private var usbAttachTimestampMs = 0L

    // true while waiting for our own USB permission dialog (non-UVC device, CG-less path).
    private var selfPermPending = false

    // true after clicking the CG row in the chooser, while waiting for "Just once" to appear.
    // On Android 16 the "Just once" confirmation button only appears after an app row is tapped.
    private var cgChooserClickPending = false

    // true after we've tapped Allow on CG's USB permission dialog.
    // Keeps cgAllowPhase true so the subsequent chooser dialog is still handled.
    private var cgPermTapped = false

    // true after we've tapped the Camera Access toggle in CG's settings screen this session.
    // Prevents hammering the toggle on every accessibility event.
    private var cgCameraToggleTapped = false

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
                // UVC re-enum may have just completed — try to connect camera if not already open.
                // This rescues the stuck state where Android 16 didn't deliver USB_DEVICE_ATTACHED
                // to our dynamic receiver (CG had session memory as preferred handler).
                if (displayId != Display.DEFAULT_DISPLAY) {
                    val cam = camera
                    if (cam != null && !cam.isOpen && !hidEnablePending) {
                        Log.d(TAG, "onDisplayAdded: external display appeared — calling tryConnectCamera()")
                        tryConnectCamera()
                    }
                }
            }

            override fun onDisplayRemoved(displayId: Int) {
                Log.d(TAG, "onDisplayRemoved: id=$displayId lastLaunched=$lastLaunchedDisplayId")
                if (displayId == lastLaunchedDisplayId) lastLaunchedDisplayId = Display.INVALID_DISPLAY
            }

            override fun onDisplayChanged(displayId: Int) {
                // Don't re-launch here — it fires constantly and causes launch spam.
                // launchOnGlassesDisplay() is driven by onDisplayAdded and openCamera success.
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
                    usbSessionActive = false
                    cgAllowPhase = false
                    uvcPhaseActive = false
                    hidEnablePending = false
                    selfPermPending = false
                    cgChooserClickPending = false
                    cgPermTapped = false
                    cgCameraToggleTapped = false
                    // lastLaunchedDisplayId intentionally NOT reset here — the glasses display
                    // persists through the non-UVC → UVC re-enum (it's an NCM/ECM display, not
                    // USB video). onDisplayRemoved resets it if the display actually disappears.
                    // Resetting here causes a duplicate xrdroiddesk launch after camera opens.
                    // Don't finishAll() — keep xrdroiddesk visible on glasses while camera
                    // reconnects. CG's Acceptor causes a ~2s re-enum after first open; we
                    // auto-recover via tryConnectCamera() rather than requiring a replug.
                    // If the display is also replaced (e.g. CG re-creates it), onDisplayAdded
                    // will relaunch xrdroiddesk on the new display automatically.
                    retryJob =
                        serviceScope.launch {
                            delay(CAMERA_RECONNECT_DELAY_MS)
                            tryConnectCamera()
                        }
                    return
                }
                if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
                val uvcAlreadyActive =
                    (0 until device.interfaceCount).any { i ->
                        device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO
                    }
                if (uvcAlreadyActive) {
                    // UVC re-enum: cancel any pending reconnect retry — the broadcast path takes over.
                    retryJob?.cancel()
                    retryJob = null
                    // CG has done its job enabling UVC. Now DENY CG's USB permission
                    // dialog so it cannot claim HID interface 0 and send HOST_TYPE=2 (which darkens
                    // the display). cgAllowPhase=false triggers the deny path in handleCgUsbDialog.
                    usbSessionActive = true
                    cgAllowPhase = false
                    uvcPhaseActive = true
                    usbAttachTimestampMs = System.currentTimeMillis()
                    val usbMan = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
                    if (usbMan.hasPermission(device)) {
                        uiLog("USB attached: UVC — already have perm, opening camera")
                        openCamera(device)
                    } else {
                        // CG may already have USB permission from the non-UVC phase (same VID/PID),
                        // meaning it auto-starts its Acceptor without showing any dialog.
                        // Schedule our own permission request unconditionally — don't wait for a
                        // chooser that may never appear.
                        uiLog("USB attached: UVC re-enum — scheduling own perm (CG may have auto-permission)")
                        if (!selfPermPending) {
                            selfPermPending = true
                            scheduleCameraPermissionRequest()
                        }
                    }
                    return
                }
                // Non-UVC device. Fast path: if we already have USB permission (stored from a
                // prior grant in this session) enable UVC ourselves via HID. This avoids CG
                // entirely — no competing window on the glasses display.
                // Slow path: arm CG-Allow so CG can enable UVC for us (first plug after install).
                retryJob?.cancel()
                retryJob = null
                usbSessionActive = true
                usbAttachTimestampMs = System.currentTimeMillis()
                val usbManCheck = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
                if (usbManCheck.hasPermission(device)) {
                    hidEnablePending = true
                    // Arm CG-Allow immediately so any visible "open CG?" dialog is tapped
                    // right away — don't wait for HID to complete (or fail) first.
                    cgAllowPhase = true
                    handleCgUsbDialog()
                    uiLog("USB attached: non-UVC — fast path (have perm) + arming CG-Allow")
                    serviceScope.launch {
                        try {
                            val enabler =
                                com.repudi8or.xrdroiddesk.camera
                                    .GlassesUvcEnabler(this@GestureAccessibilityService)
                            val ok = enabler.enableUvc()
                            enabler.release()
                            uiLog("HID enableUvc: ${if (ok) "✓ awaiting UVC re-enum" else "✗ CG path active"}")
                            // Tap dialog proactively in case it appeared while HID was running
                            // and no accessibility event fired since cgAllowPhase became true.
                            if (!ok) handleCgUsbDialog()
                        } finally {
                            hidEnablePending = false
                        }
                    }
                } else {
                    // Slow path: no stored permission. ARM CG-Allow (in case CG's real USB
                    // permission dialog appears — button "Allow", not the "open CG?" button "OK").
                    // ALSO request our own permission so the fast-path (HID at ~50ms) activates
                    // on the next plug cycle. First plug: HID timing window is missed (permission
                    // grant takes ~300ms). Second plug: stored permission → HID at ~50ms → works.
                    cgAllowPhase = true
                    handleCgUsbDialog()
                    if (!selfPermPending) {
                        selfPermPending = true
                        serviceScope.launch {
                            delay(50L)
                            val usbMan2 = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
                            val dev2 =
                                usbMan2.deviceList.values.firstOrNull {
                                    it.vendorId == XRealGlassesCamera.VENDOR_ID &&
                                        it.productId == XRealGlassesCamera.PRODUCT_ID
                                } ?: run {
                                    selfPermPending = false
                                    return@launch
                                }
                            if (usbMan2.hasPermission(dev2)) {
                                selfPermPending = false
                                openCamera(dev2)
                                return@launch
                            }
                            startActivity(
                                Intent(this@GestureAccessibilityService, GrantUsbPermissionActivity::class.java).apply {
                                    putExtra(UsbManager.EXTRA_DEVICE, dev2)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                                ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
                            )
                        }
                    }
                    uiLog("USB attached: non-UVC — no perm, arming CG-Allow + requesting own perm (fast path next plug)")
                }
            }
        }

    override fun onServiceConnected() {
        val controller = AccessibilityDesktopController(this)
        dispatcher = GestureActionDispatcher(controller, controller::normalizedToPixels)
        instance = this
        val dm = getSystemService(DisplayManager::class.java)
        dm.registerDisplayListener(displayListener, null)
        // Launch on any external display that was already present before we registered.
        // onDisplayAdded won't fire for pre-existing displays.
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
        startHandTracking()
        startStateLogger()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED
        ) {
            return
        }

        handleDisplayModeChooser()
        if (usbSessionActive) {
            handleCgUsbDialog()
            handleCgSettingsScreen()
        }
        if (uvcPhaseActive || selfPermPending) handleXrdroideskPermDialog()
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        try {
            unregisterReceiver(usbAttachReceiver)
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

    // Called by GrantUsbPermissionActivity when requestPermission() is silently denied
    // (another app is the default handler). Clears selfPermPending so the retry loop can
    // re-attempt the permission request on the next tryConnectCamera() cycle.
    fun onUsbPermissionDenied() {
        selfPermPending = false
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
            retryJob?.cancel()
            retryJob = null
            usbSessionActive = true
            val usbMan2 = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
            if (usbMan2.hasPermission(device)) {
                hidEnablePending = true
                cgAllowPhase = true
                handleCgUsbDialog()
                Log.i(TAG, "tryConnectCamera: non-UVC — have perm, arming CG-Allow + enabling UVC via HID")
                serviceScope.launch {
                    try {
                        val enabler =
                            com.repudi8or.xrdroiddesk.camera
                                .GlassesUvcEnabler(this@GestureAccessibilityService)
                        val ok = enabler.enableUvc()
                        enabler.release()
                        uiLog("HID enableUvc: ${if (ok) "✓ awaiting UVC re-enum" else "✗ CG path active"}")
                        if (!ok) {
                            handleCgUsbDialog()
                            retryJob?.cancel()
                            retryJob =
                                serviceScope.launch {
                                    delay(RETRY_DELAY_MS)
                                    tryConnectCamera()
                                }
                        }
                    } finally {
                        hidEnablePending = false
                    }
                }
            } else {
                cgAllowPhase = true
                handleCgUsbDialog()
                if (!selfPermPending) {
                    selfPermPending = true
                    serviceScope.launch {
                        delay(50L)
                        val usbMan3 = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
                        val dev3 =
                            usbMan3.deviceList.values.firstOrNull {
                                it.vendorId == XRealGlassesCamera.VENDOR_ID &&
                                    it.productId == XRealGlassesCamera.PRODUCT_ID
                            } ?: run {
                                selfPermPending = false
                                return@launch
                            }
                        if (usbMan3.hasPermission(dev3)) {
                            selfPermPending = false
                            openCamera(dev3)
                            return@launch
                        }
                        startActivity(
                            Intent(this@GestureAccessibilityService, GrantUsbPermissionActivity::class.java).apply {
                                putExtra(UsbManager.EXTRA_DEVICE, dev3)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                            ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
                        )
                    }
                }
                Log.i(TAG, "tryConnectCamera: non-UVC — no perm, arming CG-Allow + requesting own perm (fast path next plug)")
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
        usbSessionActive = true
        cgAllowPhase = false
        uvcPhaseActive = true
        if (cam.hasPermission(device)) {
            Log.i(TAG, "tryConnectCamera: UVC device, permission already granted — opening camera")
            openCamera(device)
        } else {
            Log.i(TAG, "tryConnectCamera: UVC device found — requesting permission")
            usbAttachTimestampMs = System.currentTimeMillis()
            selfPermPending = true
            startActivity(
                Intent(this, GrantUsbPermissionActivity::class.java).apply {
                    putExtra(UsbManager.EXTRA_DEVICE, device)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
            )
        }
    }

    fun openCamera(device: UsbDevice) {
        val cam = camera ?: return
        if (cam.isOpen) {
            Log.i(TAG, "openCamera: camera already running — ignoring duplicate call (device=${device.deviceName})")
            return
        }
        val hasPerm = (getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager).hasPermission(device)
        Log.i(TAG, "openCamera: calling cam.open() — device=${device.deviceName} hasPerm=$hasPerm")
        val startMs = System.currentTimeMillis()
        val opened = cam.open(device)
        val elapsedMs = System.currentTimeMillis() - startMs
        if (opened) {
            uiLog("camera open OK (${elapsedMs}ms)")
            usbSessionActive = false
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
            selfPermPending = false
            cgAllowPhase = true
            handleCgUsbDialog()
            if (!hidEnablePending) {
                hidEnablePending = true
                Log.i(TAG, "openCamera: non-UVC with perm — arming CG-Allow + enabling UVC via HID")
                serviceScope.launch {
                    try {
                        val enabler =
                            com.repudi8or.xrdroiddesk.camera
                                .GlassesUvcEnabler(this@GestureAccessibilityService)
                        val ok = enabler.enableUvc()
                        enabler.release()
                        uiLog(
                            if (ok) "HID enableUvc: ✓ awaiting UVC re-enum" else "HID enableUvc: ✗ CG path active (permission now stored)",
                        )
                        if (!ok) handleCgUsbDialog()
                    } finally {
                        hidEnablePending = false
                    }
                }
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

        // Without FLAG_ACTIVITY_MULTIPLE_TASK, Android reuses an existing task on the target
        // display rather than creating a new one. Combined with setLaunchDisplayId this avoids
        // accumulating stale xrdroiddesk instances across plug cycles.
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
        hidEnablePending = false
        selfPermPending = false
        cgChooserClickPending = false
        cgPermTapped = false
        cgCameraToggleTapped = false
        lastLaunchedDisplayId = Display.INVALID_DISPLAY
        pipeline?.stop()
        landmarker?.close()
        pipeline = null
        landmarker = null
        camera = null
    }

    // Auto-tap "Desktop" on the Mirror/Desktop display-mode chooser shown when glasses plug in.
    // Logs partial matches (windows with only "Mirror" or "Desktop") to diagnose missed taps.
    private fun handleDisplayModeChooser() {
        val allWindows = windows ?: return
        val dialogRoot =
            allWindows
                .firstOrNull { w ->
                    val r = w.root ?: return@firstOrNull false
                    r.findAccessibilityNodeInfosByText("Mirror").isNotEmpty() &&
                        r.findAccessibilityNodeInfosByText("Desktop").isNotEmpty()
                }?.root
        if (dialogRoot == null) {
            // Verbose-only: log windows with Mirror or Desktop text (but not both).
            // Nexus Launcher on the external display always has "Desktop" — this is normal.
            if (android.util.Log.isLoggable(TAG, android.util.Log.VERBOSE)) {
                allWindows.forEach { w ->
                    val r = w.root ?: return@forEach
                    val pkg = r.packageName?.toString() ?: "?"
                    val hasMirror = r.findAccessibilityNodeInfosByText("Mirror").isNotEmpty()
                    val hasDesktop = r.findAccessibilityNodeInfosByText("Desktop").isNotEmpty()
                    if (hasMirror || hasDesktop) {
                        Log.v(TAG, "handleDisplayModeChooser: pkg=$pkg mirror=$hasMirror desktop=$hasDesktop — partial")
                    }
                }
            }
            return
        }
        val elapsed = System.currentTimeMillis() - usbAttachTimestampMs
        val desktopNode =
            findClickableByText(dialogRoot, "Desktop") ?: run {
                Log.w(TAG, "handleDisplayModeChooser: found Mirror+Desktop window but no clickable Desktop node")
                return
            }
        uiLog("display mode chooser: tapping Desktop (+${elapsed}ms)")
        desktopNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // Handle CG's USB dialog — either a permission dialog or an app chooser.
    //
    // Permission dialog (no "Just once" text):
    //   uvcPhaseActive  → Allow CG (so CG can create the external display) + schedule our own perm
    //   cgAllowPhase=true → Allow (non-UVC phase: CG needs access to enable UVC via HID)
    //   else → DO NOTHING (guard against re-tap)
    //
    // Chooser dialog ("Just once" text present — both CG and xrdroiddesk in manifest):
    //   !uvcPhaseActive → select Control Glasses + "Just once" (non-UVC phase: CG enables UVC)
    //   uvcPhaseActive  → select Control Glasses + "Just once" (CG creates external display)
    //                     then schedule our own camera permission via GrantUsbPermissionActivity
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
        // Detect chooser three ways:
        //  1. "Just once" already visible (upfront on some Android versions)
        //  2. cgChooserClickPending — we already clicked CG, still waiting for "Just once"
        //  3. Our own app name "xrdroiddesk" appears in the dialog (multi-app chooser listing)
        val justOnceNode = findNodeByText(dialogRoot, "Just once")
        val isChooser =
            justOnceNode != null ||
                cgChooserClickPending ||
                dialogRoot.findAccessibilityNodeInfosByText("xrdroiddesk").isNotEmpty()

        if (isChooser) {
            if (uvcPhaseActive) {
                // UVC phase chooser: select xrdroiddesk (not CG) to get USB permission directly.
                // Selecting CG would start its Acceptor, which sends HOST_TYPE=2 repeatedly.
                // The external display is already up via NCM/ECM regardless of who has USB Host.
                val xrdRow = findClickableByText(dialogRoot, "xrdroiddesk")
                if (xrdRow != null) {
                    Log.i(TAG, "USB chooser (UVC phase): selecting xrdroiddesk (+${elapsed}ms) — direct permission, no Acceptor")
                    xrdRow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    justOnceNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else {
                    Log.i(
                        TAG,
                        "USB chooser (UVC phase): xrdroiddesk not in list — ignoring chooser, relying on scheduleCameraPermissionRequest",
                    )
                }
                if (!selfPermPending) {
                    selfPermPending = true
                    scheduleCameraPermissionRequest()
                }
            } else {
                // Non-UVC phase: select Control Glasses so CG can enable UVC.
                // Two-phase: click CG row first; if "Just once" not yet visible, set
                // cgChooserClickPending and confirm on the next accessibility event.
                if (cgChooserClickPending) {
                    // Phase 2: CG row was already clicked — confirm with "Just once" now that it's visible
                    if (justOnceNode != null) {
                        Log.i(TAG, "USB chooser (non-UVC): confirming Just once (+${elapsed}ms)")
                        cgChooserClickPending = false
                        cgAllowPhase = false // chooser confirmed — prevent re-tap on future events
                        justOnceNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    // else: still waiting; will retry on next accessibility event
                } else if (cgAllowPhase) {
                    // Only enter this branch if we haven't already confirmed the chooser
                    val cgRow = findClickableByText(dialogRoot, "Control Glasses") ?: return
                    Log.i(TAG, "USB chooser (non-UVC phase): selecting Control Glasses (+${elapsed}ms) — CG will enable UVC")
                    cgRow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (justOnceNode != null) {
                        Log.i(TAG, "USB chooser (non-UVC): Just once visible immediately — confirming")
                        cgAllowPhase = false // chooser confirmed — prevent re-tap on future events
                        justOnceNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } else {
                        cgChooserClickPending = true
                        Log.i(TAG, "USB chooser (non-UVC): Just once not visible yet — waiting for next event")
                    }
                }
            }
        } else {
            // Permission dialog (no chooser)
            if (uvcPhaseActive) {
                // DENY CG USB Host access to the UVC device. The external display is already
                // created via NCM/ECM (it appears before USB_DEVICE_ATTACHED fires to us), so
                // CG does not need USB Host permission to maintain the display. Allowing it
                // would start CG's Acceptor, which claims HID iface 0 and sends HOST_TYPE=2
                // repeatedly, fighting our keepalive and eventually darkening the display.
                val deny = findNegativeButton(dialogRoot) ?: return
                uiLog("CG permission (UVC phase): DENY tapped (+${elapsed}ms) — blocking Acceptor")
                deny.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!selfPermPending) {
                    selfPermPending = true
                    scheduleCameraPermissionRequest()
                }
            } else if (cgAllowPhase && !cgPermTapped) {
                // Non-UVC phase: tap "Allow" (USB permission dialog) or "OK" (Android 16
                // confirmation dialog). Both grant CG USB permission and trigger its Acceptor,
                // which sends HID HOST_TYPE=2 + GET + SET → UVC re-enum in ~2s. Confirmed
                // working: tapping OK here causes UVC to enable correctly.
                val allow =
                    findClickableByText(dialogRoot, "Allow")
                        ?: findClickableByText(dialogRoot, "OK")
                        ?: return
                val allowText = (allow.text?.toString() ?: allow.contentDescription?.toString() ?: "").trim()
                if (!allowText.equals("Allow", ignoreCase = true) &&
                    !allowText.equals("OK", ignoreCase = true)
                ) {
                    return
                }
                uiLog("CG permission: Allow tapped (+${elapsed}ms)")
                cgPermTapped = true
                allow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            // else: guard against re-tap
        }
    }

    // Request xrdroiddesk's own USB camera permission after giving CG time to initialise.
    // Guarded by selfPermPending — caller must set it true before calling to prevent duplicates.
    private fun scheduleCameraPermissionRequest() {
        serviceScope.launch {
            delay(200)
            if (!usbSessionActive) return@launch
            val usbMan = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
            // Prefer the UVC-capable device — both the old non-UVC device and the newly
            // enumerated UVC device can be present with the same VID/PID at this moment.
            val dev =
                usbMan.deviceList.values
                    .filter {
                        it.vendorId == XRealGlassesCamera.VENDOR_ID &&
                            it.productId == XRealGlassesCamera.PRODUCT_ID
                    }.maxByOrNull { d ->
                        (0 until d.interfaceCount).count {
                            d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO
                        }
                    } ?: return@launch
            if (usbMan.hasPermission(dev)) {
                Log.i(TAG, "scheduleCameraPermissionRequest: already have perm — opening camera")
                openCamera(dev)
            } else {
                Log.i(TAG, "scheduleCameraPermissionRequest: requesting perm via GrantUsbPermissionActivity")
                startActivity(
                    Intent(this@GestureAccessibilityService, GrantUsbPermissionActivity::class.java).apply {
                        putExtra(UsbManager.EXTRA_DEVICE, dev)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                    ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
                )
            }
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
                        pkg != "com.anthropic.claude" &&
                        r.findAccessibilityNodeInfosByText("xrdroiddesk").isNotEmpty()
                }?.root ?: run {
                Log.v(TAG, "handleXrdroideskPermDialog: no matching window (uvcPhase=$uvcPhaseActive selfPerm=$selfPermPending)")
                return
            }
        val elapsed = System.currentTimeMillis() - usbAttachTimestampMs
        val pkg = dialogRoot.packageName?.toString() ?: "?"
        val allow =
            findPositiveButton(dialogRoot) ?: run {
                Log.w(TAG, "handleXrdroideskPermDialog: window found (pkg=$pkg) but no positive button — buttons may use unexpected labels")
                return
            }
        uiLog("xrdroiddesk permission: tapped '+${elapsed}ms' (pkg=$pkg)")
        uvcPhaseActive = false
        selfPermPending = false
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
        // Try exact-text candidates first to avoid "Don't allow" matching "Allow".
        for (text in listOf("OK", "Allow", "Just once")) {
            val exactNodes =
                root.findAccessibilityNodeInfosByText(text).filter { n ->
                    val t = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
                    t.equals(text.trim(), ignoreCase = true)
                }
            val node =
                exactNodes.firstOrNull { it.isClickable }
                    ?: exactNodes
                        .mapNotNull { n ->
                            var cur: AccessibilityNodeInfo? = n.parent
                            while (cur != null && !cur.isClickable) cur = cur.parent
                            cur
                        }.firstOrNull()
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
        val usbMan = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        val dm = getSystemService(DisplayManager::class.java)

        // Camera permission
        val camPerm =
            checkSelfPermission(android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        // USB devices (all XREAL VID/PID)
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

        // External displays
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

        // Camera open state + permissions
        val camOpen = camera?.isOpen ?: false
        Log.i(STATE_TAG, "#$n CAM: open=$camOpen camPerm=$camPerm")

        // Internal service flags
        Log.i(
            STATE_TAG,
            "#$n FLAGS: sess=$usbSessionActive cgAllow=$cgAllowPhase uvcPhase=$uvcPhaseActive selfPerm=$selfPermPending hidPend=$hidEnablePending",
        )

        // Accessibility window scan — dialogs relevant to the USB/camera flow
        val wins =
            windows ?: run {
                Log.i(STATE_TAG, "#$n DIALOGS: (windows unavailable)")
                return
            }
        var cgPermDlg = false // "Allow Control Glasses to access..." permission dialog
        var xrdPermDlg = false // "Allow xrdroiddesk to access..." permission dialog
        var chooserOpen = false // USB app chooser with "Just once" button
        val interestingWindows = mutableListOf<String>()

        for (w in wins) {
            val r = w.root ?: continue
            val pkg = r.packageName?.toString() ?: "?"
            if (pkg == packageName) continue // skip our own windows

            // Dump all text from CG's own settings screen (resolution error, toggle states, etc.)
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

            // Summarise any window that has keywords we care about
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

    // CG's main UI appears when CG is launched via chooser instead of as silent Acceptor.
    // It shows "errCode 10: System output resolution conflicts" (no dismiss button) plus
    // toggles for Screen Mirroring and Camera Access (the UVC enable switch).
    // Tapping "Camera access" enables UVC — same as the one-time manual setup step.
    private fun handleCgSettingsScreen() {
        if (cgCameraToggleTapped) return
        val cgWin =
            windows
                ?.firstOrNull { w ->
                    val r = w.root ?: return@firstOrNull false
                    r.packageName?.toString() == "com.xreal.glassescontrol.store"
                }?.root ?: return

        val elapsed = System.currentTimeMillis() - usbAttachTimestampMs
        // Find the Camera access toggle and tap it to enable UVC
        for (label in listOf("Camera access", "camera access", "Camera Access")) {
            val toggle = findClickableByText(cgWin, label) ?: continue
            uiLog("CG settings: tapping Camera access toggle (+${elapsed}ms) — enabling UVC")
            cgCameraToggleTapped = true
            toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
        // Toggle not found yet — log all text so we can diagnose on next run
        val allText = collectAllText(cgWin)
        if (allText.isNotEmpty()) {
            Log.i(TAG, "CG settings (no Camera access node): ${allText.take(15).joinToString(" | ")}")
        }
    }

    // Recursively collect all non-empty text strings from an accessibility node tree.
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

        var instance: GestureAccessibilityService? = null
            private set

        // Set by MainActivity when USB_DEVICE_ATTACHED fires before the service is running.
        // Consumed in startHandTracking() to open the camera directly with the OS-granted permission.
        var pendingUsbDevice: UsbDevice? = null
    }
}
