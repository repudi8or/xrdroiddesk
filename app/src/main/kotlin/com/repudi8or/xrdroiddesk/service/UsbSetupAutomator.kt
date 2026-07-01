package com.repudi8or.xrdroiddesk.service

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.repudi8or.xrdroiddesk.GrantUsbPermissionActivity
import com.repudi8or.xrdroiddesk.camera.GlassesUvcEnabler
import com.repudi8or.xrdroiddesk.camera.XRealGlassesCamera
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns all USB setup state: dialog automation flags, dialog handlers, HID enable flow,
 * and permission request scheduling. GestureAccessibilityService delegates the full USB
 * setup lifecycle here and retains only camera/pipeline/gesture orchestration.
 */
class UsbSetupAutomator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val windowsProvider: () -> List<AccessibilityWindowInfo>?,
    private val onCameraReady: (UsbDevice) -> Unit,
    private val uiLog: (String) -> Unit,
) {
    var usbSessionActive = false
        private set
    var hidEnablePending = false
        private set
    var uvcPhaseActive = false
        private set
    var selfPermPending = false
        private set
    var cgAllowPhase = false
        private set
    var lastKnownDevice: UsbDevice? = null
        private set

    private var cgChooserClickPending = false
    private var cgPermTapped = false
    private var cgCameraToggleTapped = false
    private var usbAttachTimestampMs = 0L
    private var tcpEnablePending = false

    // Set when the manifest-filter GrantUsbPermissionActivity notifies us via
    // onDeviceAttached(skipActivityLaunch=true). Prevents the 200ms coroutine from
    // launching a duplicate Activity on top of the one already started by Android.
    private var manifestActivityStarted = false

    private val windows get() = windowsProvider()

    // -------------------------------------------------------------------------
    // USB lifecycle — called from usbAttachReceiver
    // -------------------------------------------------------------------------

    // skipActivityLaunch=true when called from GrantUsbPermissionActivity itself — prevents
    // a duplicate activity launch from the 50ms-delayed coroutine.
    fun onDeviceAttached(
        device: UsbDevice,
        skipActivityLaunch: Boolean = false,
    ) {
        lastKnownDevice = device
        if (!usbSessionActive) usbAttachTimestampMs = System.currentTimeMillis()
        usbSessionActive = true
        // Record manifest Activity launch BEFORE selfPermPending check so the coroutine
        // guard below sees it even when the dynamic receiver set selfPermPending first.
        if (skipActivityLaunch) manifestActivityStarted = true
        if (device.hasUvc()) {
            cgAllowPhase = false
            uvcPhaseActive = true
            val usbMan = context.getSystemService(Context.USB_SERVICE) as UsbManager
            if (usbMan.hasPermission(device)) {
                uiLog("USB attached: UVC — already have perm, opening camera")
                onCameraReady(device)
            } else {
                uiLog("USB attached: UVC re-enum — scheduling own perm (CG may have auto-permission)")
                if (!selfPermPending) {
                    selfPermPending = true
                    scheduleCameraPermissionRequest()
                }
            }
        } else {
            val usbMan = context.getSystemService(Context.USB_SERVICE) as UsbManager
            if (usbMan.hasPermission(device)) {
                if (!hidEnablePending) {
                    hidEnablePending = true
                    cgAllowPhase = true
                    handleCgUsbDialog()
                    uiLog("USB attached: non-UVC — fast path (have perm), enabling UVC via HID")
                    launchHidEnable()
                }
            } else {
                cgAllowPhase = true
                handleCgUsbDialog()
                launchTcpEnable()
                if (!selfPermPending) {
                    selfPermPending = true
                    if (!skipActivityLaunch) {
                        scope.launch {
                            delay(200L)
                            // Manifest Activity may have fired between now and when we started
                            // this coroutine. If so, it already called requestPermission() —
                            // launching a second Activity would overwrite that PendingIntent and
                            // freeze the first dialog's OK button.
                            if (manifestActivityStarted) return@launch
                            val usbMan2 = context.getSystemService(Context.USB_SERVICE) as UsbManager
                            if (usbMan2.hasPermission(device)) {
                                selfPermPending = false
                                onCameraReady(device)
                                return@launch
                            }
                            context.startActivity(
                                Intent(context, GrantUsbPermissionActivity::class.java).apply {
                                    putExtra(UsbManager.EXTRA_DEVICE, device)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                                ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
                            )
                        }
                    }
                }
                uiLog("USB attached: non-UVC — no perm, arming CG-Allow + requesting own perm")
            }
        }
    }

    fun onDeviceDetached() {
        lastKnownDevice = null
        usbSessionActive = false
        cgAllowPhase = false
        uvcPhaseActive = false
        hidEnablePending = false
        tcpEnablePending = false
        selfPermPending = false
        manifestActivityStarted = false
        cgChooserClickPending = false
        cgPermTapped = false
        cgCameraToggleTapped = false
        usbAttachTimestampMs = 0L
    }

    // -------------------------------------------------------------------------
    // USB lifecycle — called from tryConnectCamera (reconnect path)
    // -------------------------------------------------------------------------

    /**
     * Non-UVC reconnect path when we already have USB permission.
     * [onHidFailed] is invoked on the main thread if HID enable returns false,
     * so the service can schedule a camera retry.
     */
    fun armNonUvcWithPerm(device: UsbDevice) {
        usbSessionActive = true
        hidEnablePending = true
        cgAllowPhase = true
        handleCgUsbDialog()
        Log.i(TAG, "tryConnectCamera: non-UVC — have perm, enabling UVC via HID")
        launchHidEnable()
    }

    /**
     * Non-UVC reconnect path when we lack USB permission.
     * The service schedules a retry after calling this.
     */
    fun armNonUvcNoPerm(device: UsbDevice) {
        usbSessionActive = true
        cgAllowPhase = true
        handleCgUsbDialog()
        launchTcpEnable()
        if (!selfPermPending) {
            selfPermPending = true
            scope.launch {
                delay(200L)
                if (manifestActivityStarted) return@launch
                val usbMan = context.getSystemService(Context.USB_SERVICE) as UsbManager
                val dev =
                    usbMan.deviceList.values.firstOrNull {
                        it.vendorId == XRealGlassesCamera.VENDOR_ID &&
                            it.productId == XRealGlassesCamera.PRODUCT_ID
                    } ?: lastKnownDevice ?: run {
                        selfPermPending = false
                        return@launch
                    }
                if (usbMan.hasPermission(dev)) {
                    selfPermPending = false
                    onCameraReady(dev)
                    return@launch
                }
                context.startActivity(
                    Intent(context, GrantUsbPermissionActivity::class.java).apply {
                        putExtra(UsbManager.EXTRA_DEVICE, dev)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                    ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
                )
            }
        }
        Log.i(TAG, "tryConnectCamera: non-UVC — no perm, arming CG-Allow + requesting own perm (fast path next plug)")
    }

    /** Set UVC phase flags. Call before openCamera() or requestUvcPerm(). */
    fun setUvcPhaseActive() {
        usbSessionActive = true
        cgAllowPhase = false
        uvcPhaseActive = true
    }

    /** Request permission for a UVC device. Used by tryConnectCamera when no permission yet. */
    fun requestUvcPerm(device: UsbDevice) {
        setUvcPhaseActive()
        usbAttachTimestampMs = System.currentTimeMillis()
        selfPermPending = true
        context.startActivity(
            Intent(context, GrantUsbPermissionActivity::class.java).apply {
                putExtra(UsbManager.EXTRA_DEVICE, device)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
        )
    }

    // -------------------------------------------------------------------------
    // Camera result callbacks — called from openCamera()
    // -------------------------------------------------------------------------

    fun onCameraOpened() {
        usbSessionActive = false
    }

    /** Called when cam.open() returns false and the device is non-UVC. */
    fun onCameraOpenFailedNonUvc() {
        selfPermPending = false
        cgAllowPhase = true
        handleCgUsbDialog()
        if (!hidEnablePending) {
            hidEnablePending = true
            Log.i(TAG, "openCamera: non-UVC with perm — enabling UVC via HID")
            launchHidEnable()
        }
    }

    // -------------------------------------------------------------------------
    // Accessibility event dispatch
    // -------------------------------------------------------------------------

    /** Call from onAccessibilityEvent() after filtering for relevant event types. */
    fun onAccessibilityEvent(isCameraOpen: Boolean) {
        handleDisplayModeChooser()
        if (usbSessionActive || isCameraOpen) handleCgUsbDialog(isCameraOpen)
        if (uvcPhaseActive || selfPermPending) handleXrdroideskPermDialog()
        if (usbSessionActive) handleCgSettingsScreen()
    }

    // -------------------------------------------------------------------------
    // External callbacks
    // -------------------------------------------------------------------------

    fun onUsbPermissionDenied() {
        selfPermPending = false
    }

    /** Reset all USB setup state. Call from stopHandTracking(). */
    fun reset() {
        usbSessionActive = false
        cgAllowPhase = false
        uvcPhaseActive = false
        hidEnablePending = false
        tcpEnablePending = false
        selfPermPending = false
        manifestActivityStarted = false
        cgChooserClickPending = false
        cgPermTapped = false
        cgCameraToggleTapped = false
        lastKnownDevice = null
        usbAttachTimestampMs = 0L
    }

    // -------------------------------------------------------------------------
    // Private — HID enable
    // -------------------------------------------------------------------------

    private fun launchTcpEnable() {
        if (tcpEnablePending) return
        tcpEnablePending = true
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val enabler = GlassesUvcEnabler(context)
                val ok = enabler.enableUvcViaTcp()
                uiLog("TCP enableUvc: ${if (ok) "✓ SET sent — awaiting re-enum" else "✗ GET heartbeats (MCU not in config mode via TCP)"}")
            } finally {
                tcpEnablePending = false
            }
        }
    }

    private fun launchHidEnable() {
        // Use IO dispatcher so the coroutine starts on a background thread immediately,
        // without waiting for the main thread to finish Activity lifecycle work.
        // This keeps HOST_TYPE arrival within the ~100ms MCU config window.
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val enabler = GlassesUvcEnabler(context)
                val ok = enabler.enableUvc()
                enabler.release()
                uiLog("HID enableUvc: ${if (ok) "✓ awaiting UVC re-enum" else "✗ MCU window missed"}")
            } finally {
                hidEnablePending = false
            }
        }
    }

    private fun scheduleCameraPermissionRequest() {
        scope.launch {
            delay(200)
            if (!usbSessionActive) return@launch
            val usbMan = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val dev =
                usbMan.deviceList.values
                    .filter {
                        it.vendorId == XRealGlassesCamera.VENDOR_ID &&
                            it.productId == XRealGlassesCamera.PRODUCT_ID
                    }.maxByOrNull { d ->
                        (0 until d.interfaceCount).count {
                            d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO
                        }
                    } ?: lastKnownDevice ?: return@launch
            if (usbMan.hasPermission(dev)) {
                Log.i(TAG, "scheduleCameraPermissionRequest: already have perm — opening camera")
                onCameraReady(dev)
            } else {
                Log.i(TAG, "scheduleCameraPermissionRequest: requesting perm via GrantUsbPermissionActivity")
                context.startActivity(
                    Intent(context, GrantUsbPermissionActivity::class.java).apply {
                        putExtra(UsbManager.EXTRA_DEVICE, dev)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                    ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private — dialog handlers (moved from GestureAccessibilityService)
    // -------------------------------------------------------------------------

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

    private fun handleCgUsbDialog(isCameraOpen: Boolean = false) {
        val dialogRoot =
            windows
                ?.firstOrNull { w ->
                    val r = w.root ?: return@firstOrNull false
                    val pkg = r.packageName?.toString() ?: ""
                    pkg != context.packageName &&
                        pkg != "com.xreal.glassescontrol.store" &&
                        r.findAccessibilityNodeInfosByText("Control Glasses").isNotEmpty()
                }?.root ?: return
        val elapsed = System.currentTimeMillis() - usbAttachTimestampMs
        val justOnceNode = findNodeByText(dialogRoot, "Just once")
        val isChooser =
            justOnceNode != null ||
                cgChooserClickPending ||
                dialogRoot.findAccessibilityNodeInfosByText("xrdroiddesk").isNotEmpty()

        if (isChooser) {
            if (uvcPhaseActive) {
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
                if (cgChooserClickPending) {
                    if (justOnceNode != null) {
                        Log.i(TAG, "USB chooser (non-UVC): confirming Just once (+${elapsed}ms)")
                        cgChooserClickPending = false
                        cgAllowPhase = false
                        justOnceNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                } else if (cgAllowPhase) {
                    val cgRow = findClickableByText(dialogRoot, "Control Glasses") ?: return
                    Log.i(TAG, "USB chooser (non-UVC phase): selecting Control Glasses (+${elapsed}ms) — CG will enable UVC")
                    cgRow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (justOnceNode != null) {
                        Log.i(TAG, "USB chooser (non-UVC): Just once visible immediately — confirming")
                        cgAllowPhase = false
                        justOnceNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } else {
                        cgChooserClickPending = true
                        Log.i(TAG, "USB chooser (non-UVC): Just once not visible yet — waiting for next event")
                    }
                }
            }
        } else {
            if (uvcPhaseActive || isCameraOpen) {
                // DENY CG USB Host access to the UVC device. The external display is already
                // created via NCM/ECM (it appears before USB_DEVICE_ATTACHED fires to us), so
                // CG does not need USB Host permission to maintain the display. Allowing it
                // would start CG's Acceptor, which claims HID iface 0 and sends HOST_TYPE=2
                // repeatedly, fighting our keepalive and eventually darkening the display.
                val deny = findNegativeButton(dialogRoot) ?: return
                uiLog("CG permission (UVC/cam phase): DENY tapped (+${elapsed}ms) — blocking Acceptor")
                deny.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!selfPermPending && uvcPhaseActive) {
                    selfPermPending = true
                    scheduleCameraPermissionRequest()
                }
            } else if (cgAllowPhase && !cgPermTapped) {
                // Non-UVC phase: tap "Allow" or "OK" on CG's USB permission dialog.
                // Uses findPositiveButton (exact-text match) to avoid matching "Don't allow"
                // as a substring of "Allow" — substring match caused silent no-op previously.
                val allow = findPositiveButton(dialogRoot) ?: return
                uiLog("CG permission: Allow tapped (+${elapsed}ms)")
                cgPermTapped = true
                allow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
    }

    private fun handleXrdroideskPermDialog() {
        val dialogRoot =
            windows
                ?.firstOrNull { w ->
                    val r = w.root ?: return@firstOrNull false
                    val pkg = r.packageName?.toString() ?: ""
                    pkg != context.packageName &&
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

    // CG's main UI appears when CG is launched via chooser instead of as silent Acceptor.
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
        for (label in listOf("Camera access", "camera access", "Camera Access")) {
            val toggle = findClickableByText(cgWin, label) ?: continue
            uiLog("CG settings: tapping Camera access toggle (+${elapsed}ms) — enabling UVC")
            cgCameraToggleTapped = true
            toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
        val allText = collectAllText(cgWin)
        if (allText.isNotEmpty()) {
            Log.i(TAG, "CG settings (no Camera access node): ${allText.take(15).joinToString(" | ")}")
        }
    }

    // -------------------------------------------------------------------------
    // Private — node-finding utilities
    // -------------------------------------------------------------------------

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
        // "Always" is tried first — when xrdroiddesk is the sole USB handler Android may offer it.
        // Try exact-text candidates first to avoid "Don't allow" matching "Allow".
        for (text in listOf("OK", "Always", "Allow", "Just once")) {
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

    private fun UsbDevice.hasUvc(): Boolean =
        (0 until interfaceCount).any { getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }

    companion object {
        private const val TAG = "UsbSetupAutomator"
    }
}
