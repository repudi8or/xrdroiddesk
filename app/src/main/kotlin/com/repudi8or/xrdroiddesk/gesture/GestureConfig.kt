package com.repudi8or.xrdroiddesk.gesture

data class GestureConfig(
    val pinchThreshold: Float = 0.6f,
    /** Pointer X delta (normalised) required to fire a swipe. 0.20 avoids false triggers
     *  from cursor movement. */
    val swipeThreshold: Float = 0.20f,
    // Pointer remapping — stretch the camera's usable hand zone to fill the full screen.
    // Raw landmark coords outside [min,max] are clamped; the range maps to [0,1].
    val pointerXMin: Float = 0.10f,
    val pointerXMax: Float = 0.90f,
    val pointerYMin: Float = 0.05f,
    val pointerYMax: Float = 0.75f,
    // EMA smoothing factor: 0 = frozen, 1 = no smoothing. ~0.35 is responsive but stable.
    val pointerSmoothing: Float = 0.35f,
)
