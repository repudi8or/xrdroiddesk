package com.repudi8or.xrdroiddesk.gesture

data class GestureConfig(
    /** pinchStrength value (0–1) at which a Pinch gesture fires. */
    val pinchThreshold: Float = 0.8f,
    /** Pointer X delta (normalised, 0–1 range) required to fire a swipe. */
    val swipeThreshold: Float = 0.05f,
)
