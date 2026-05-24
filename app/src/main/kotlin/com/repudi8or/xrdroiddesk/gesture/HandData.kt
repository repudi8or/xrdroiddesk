package com.repudi8or.xrdroiddesk.gesture

/**
 * Platform-agnostic snapshot of one hand's tracking state.
 * Populated from NRSDK HandState when glasses are connected.
 */
data class HandData(
    val isTracked: Boolean,
    val pinchStrength: Float, // 0.0 – 1.0
    val pointerPose: Pose?, // null when not tracked or pose invalid
)
