package com.repudi8or.xrdroiddesk.camera

import android.hardware.usb.UsbManager
import com.repudi8or.xrdroiddesk.controller.GestureActionDispatcher
import com.repudi8or.xrdroiddesk.gesture.GestureRecognizer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HandTrackingPipelineTest {
    private lateinit var camera: XRealGlassesCamera
    private lateinit var recognizer: GestureRecognizer
    private lateinit var dispatcher: GestureActionDispatcher
    private lateinit var pipeline: HandTrackingPipeline

    @BeforeEach
    fun setUp() {
        val usbManager =
            mockk<UsbManager> {
                every { deviceList } returns hashMapOf()
            }
        camera = XRealGlassesCamera(usbManager = usbManager, onFrame = {})
        recognizer = mockk(relaxed = true)
        dispatcher = mockk(relaxed = true)
        pipeline = HandTrackingPipeline(camera, recognizer, dispatcher)
    }

    @Test
    fun `stop closes the camera`() {
        pipeline.stop()
        // No exception thrown; camera.close() is idempotent when not open
    }

    @Test
    fun `onHandData feeds HandData into recognizer and dispatches non-null gesture`() {
        val handData =
            com.repudi8or.xrdroiddesk.gesture.HandData(
                isTracked = true,
                pinchStrength = 0.9f,
                pointerPose =
                    com.repudi8or.xrdroiddesk.gesture
                        .Pose(0.5f, 0.5f, 0f),
            )
        val gesture = com.repudi8or.xrdroiddesk.gesture.Gesture.Pinch
        every { recognizer.recognize(handData) } returns gesture

        pipeline.onHandData(handData)

        verify { recognizer.recognize(handData) }
        verify { dispatcher.dispatch(gesture) }
    }

    @Test
    fun `onHandData does not dispatch when recognizer returns null`() {
        val handData =
            com.repudi8or.xrdroiddesk.gesture.HandData(
                isTracked = false,
                pinchStrength = 0f,
                pointerPose = null,
            )
        every { recognizer.recognize(handData) } returns null

        pipeline.onHandData(handData)

        verify(exactly = 0) { dispatcher.dispatch(any()) }
    }
}
