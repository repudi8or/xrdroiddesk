package com.repudi8or.xrdroiddesk.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
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

    override fun onServiceConnected() {
        dispatcher = GestureActionDispatcher(AccessibilityDesktopController(this))
        instance = this
        startHandTracking()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
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
        retryJob?.cancel()
        retryJob = null
        if (cam.hasPermission(device)) {
            Log.i(TAG, "USB permission already granted — opening UVC stream")
            openCamera(device)
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
        Log.i(TAG, "USB permission granted — opening UVC stream")
        if (cam.open(device)) return

        // UVC interfaces missing — automatically enable UVC mode then wait for re-enumeration
        Log.i(TAG, "No UVC interfaces — auto-enabling UVC via HID")
        serviceScope.launch(Dispatchers.IO) {
            val enabler = GlassesUvcEnabler(this@GestureAccessibilityService)
            val ok = enabler.enableUvc()
            enabler.release()
            Log.i(TAG, "Auto UVC enable result: $ok")
            if (ok) {
                // Glasses re-enumerate after UVC enable (~3-5s). Retry on Main once settled.
                delay(UVC_REENUM_DELAY_MS)
                withContext(Dispatchers.Main) {
                    Log.i(TAG, "Re-enumeration delay done — retrying camera connect")
                    tryConnectCamera()
                }
            }
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
        pipeline?.stop()
        landmarker?.close()
        pipeline = null
        landmarker = null
        camera = null
    }

    companion object {
        private const val TAG = "GestureA11yService"
        private const val RETRY_DELAY_MS = 3000L
        private const val UVC_REENUM_DELAY_MS = 5000L

        var instance: GestureAccessibilityService? = null
            private set

        // Set by MainActivity when USB_DEVICE_ATTACHED fires before the service is running.
        // Consumed in startHandTracking() to open the camera directly with the OS-granted permission.
        var pendingUsbDevice: UsbDevice? = null
    }
}
