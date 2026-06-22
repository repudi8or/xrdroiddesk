package com.repudi8or.xrdroiddesk.gesture

import android.util.Log

class GestureRecognizer(
    private val config: GestureConfig = GestureConfig(),
) {
    private var lastPointerX: Float? = null
    private var recognizeCount = 0L

    // true while pinch is held above threshold — prevents repeat-firing on the same pinch
    private var pinchActive = false

    fun recognize(hand: HandData): Gesture? {
        if (!hand.isTracked) {
            lastPointerX = null
            pinchActive = false
            return null
        }

        recognizeCount++
        if (recognizeCount == 1L || recognizeCount % 30L == 0L) {
            Log.d(
                TAG,
                "recognize #$recognizeCount — pinch=%.2f threshold=%.2f active=$pinchActive".format(
                    hand.pinchStrength,
                    config.pinchThreshold,
                ),
            )
        }

        if (hand.pinchStrength >= config.pinchThreshold) {
            if (!pinchActive) {
                // Rising edge — first frame above threshold, fire once
                pinchActive = true
                Log.i(
                    TAG,
                    "PINCH fired — strength=%.2f pos=(%.2f,%.2f)".format(
                        hand.pinchStrength,
                        hand.pointerPose?.x ?: 0.5f,
                        hand.pointerPose?.y ?: 0.5f,
                    ),
                )
                return Gesture.Pinch(
                    x = hand.pointerPose?.x ?: 0.5f,
                    y = hand.pointerPose?.y ?: 0.5f,
                )
            }
            // Already active — hand still pinched, don't re-fire
            return null
        }

        // Below threshold — reset so next pinch can fire
        pinchActive = false

        return detectSwipe(hand.pointerPose?.x).also { gesture ->
            if (gesture != null) Log.i(TAG, "SWIPE detected: $gesture")
            lastPointerX = hand.pointerPose?.x
        }
    }

    companion object {
        private const val TAG = "GestureRecognizer"
    }

    private fun detectSwipe(currentX: Float?): Gesture? {
        val prev = lastPointerX ?: return null
        val curr = currentX ?: return null
        val delta = curr - prev
        return when {
            delta >= config.swipeThreshold -> Gesture.SwipeRight
            delta <= -config.swipeThreshold -> Gesture.SwipeLeft
            else -> null
        }
    }
}
