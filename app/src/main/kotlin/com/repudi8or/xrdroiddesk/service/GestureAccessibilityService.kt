package com.repudi8or.xrdroiddesk.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.repudi8or.xrdroiddesk.camera.HandLandmarkerHelper
import com.repudi8or.xrdroiddesk.camera.HandTrackingPipeline
import com.repudi8or.xrdroiddesk.camera.XRealGlassesCamera
import com.repudi8or.xrdroiddesk.controller.AccessibilityDesktopController
import com.repudi8or.xrdroiddesk.controller.GestureActionDispatcher
import com.repudi8or.xrdroiddesk.gesture.GestureRecognizer

class GestureAccessibilityService : AccessibilityService() {
    private lateinit var dispatcher: GestureActionDispatcher
    private var pipeline: HandTrackingPipeline? = null
    private var landmarker: HandLandmarkerHelper? = null

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

    private fun startHandTracking() {
        val helper =
            HandLandmarkerHelper(this) { handData ->
                pipeline?.onHandData(handData)
            }
        landmarker = helper

        val camera =
            XRealGlassesCamera(this) { jpegBytes ->
                helper.processJpegFrame(jpegBytes, System.currentTimeMillis())
            }
        val pl = HandTrackingPipeline(camera, GestureRecognizer(), dispatcher)
        pipeline = pl

        val device = camera.findDevice()
        if (device == null) {
            Log.w(TAG, "XReal glasses not found — is UVC mode enabled in Control Glasses?")
            return
        }
        camera.requestPermission(
            context = this,
            device = device,
            onGranted = {
                if (!camera.open(device)) {
                    Log.e(TAG, "Failed to open UVC stream")
                }
            },
            onDenied = { Log.w(TAG, "USB permission denied for XReal glasses") },
        )
    }

    private fun stopHandTracking() {
        pipeline?.stop()
        landmarker?.close()
        pipeline = null
        landmarker = null
    }

    companion object {
        private const val TAG = "GestureA11yService"

        var instance: GestureAccessibilityService? = null
            private set
    }
}
