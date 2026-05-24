package com.repudi8or.xrdroiddesk.camera

import com.repudi8or.xrdroiddesk.controller.GestureActionDispatcher
import com.repudi8or.xrdroiddesk.gesture.GestureRecognizer
import com.repudi8or.xrdroiddesk.gesture.HandData

class HandTrackingPipeline(
    private val camera: XRealGlassesCamera,
    private val recognizer: GestureRecognizer,
    private val dispatcher: GestureActionDispatcher,
) {
    fun stop() {
        camera.close()
    }

    fun onHandData(handData: HandData) {
        val gesture = recognizer.recognize(handData) ?: return
        dispatcher.dispatch(gesture)
    }
}
