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
    // One Euro Filter params for cursor smoothing.
    // minCutoff: lower = smoother at rest (try 0.5–2.0 Hz).
    // beta: higher = less lag during fast movement (try 0.001–0.05 for normalised coords).
    // dCutoff: smoothing applied to the speed estimate; 1 Hz is a good default.
    val oneEuroMinCutoff: Float = 1.0f,
    val oneEuroBeta: Float = 0.007f,
    val oneEuroDCutoff: Float = 1.0f,
)
