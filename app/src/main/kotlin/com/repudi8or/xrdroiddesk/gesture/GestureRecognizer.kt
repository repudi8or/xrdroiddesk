package com.repudi8or.xrdroiddesk.gesture

class GestureRecognizer(
    private val config: GestureConfig = GestureConfig(),
) {
    private var lastPointerX: Float? = null

    fun recognize(hand: HandData): Gesture? {
        if (!hand.isTracked) {
            lastPointerX = null
            return null
        }

        if (hand.pinchStrength >= config.pinchThreshold) {
            return Gesture.Pinch
        }

        return detectSwipe(hand.pointerPose?.x).also {
            lastPointerX = hand.pointerPose?.x
        }
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
