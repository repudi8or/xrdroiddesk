package com.repudi8or.xrdroiddesk.gesture

data class GestureConfig(
    val pinchThreshold: Float = 0.6f,
    /** Pointer X delta (normalised) required to fire a swipe. */
    val swipeThreshold: Float = 0.20f,
    // One Euro Filter params — applied to raw wrist position before relative mapping.
    // minCutoff: lower = smoother at rest (try 0.5–2.0 Hz).
    // beta: higher = less lag during fast movement (try 0.001–0.05 for normalised coords).
    // dCutoff: smoothing applied to the speed estimate; 1 Hz is a good default.
    val oneEuroMinCutoff: Float = 1.0f,
    val oneEuroBeta: Float = 0.007f,
    val oneEuroDCutoff: Float = 1.0f,
    // Relative (trackpad-style) cursor mapping.
    // sensitivity: cursor-units moved per wrist-unit delta.
    // deadZone: minimum wrist delta (normalised) before cursor moves — filters micro-jitter.
    val relativeSensitivity: Float = 2.5f,
    val relativeDeadZone: Float = 0.004f,
)
