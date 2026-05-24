package com.repudi8or.xrdroiddesk.camera

import com.repudi8or.xrdroiddesk.gesture.HandData
import com.repudi8or.xrdroiddesk.gesture.Pose
import kotlin.math.sqrt

internal object LandmarkIndex {
    const val WRIST = 0
    const val THUMB_TIP = 4
    const val INDEX_MCP = 5 // metacarpophalangeal joint — stable pointer reference
    const val INDEX_TIP = 8
}

private const val MAX_PINCH_DIST_M = 0.08f // metres at which pinchStrength reaches 0

internal fun landmarkToHandData(
    isTracked: Boolean,
    worldLandmarks: List<Triple<Float, Float, Float>>,
    imageLandmarks: List<Triple<Float, Float, Float>>,
): HandData {
    if (!isTracked || worldLandmarks.size < 21 || imageLandmarks.size < 21) {
        return HandData(isTracked = false, pinchStrength = 0f, pointerPose = null)
    }
    val thumbTip = worldLandmarks[LandmarkIndex.THUMB_TIP]
    val indexTip = worldLandmarks[LandmarkIndex.INDEX_TIP]
    val dist =
        sqrt(
            (thumbTip.first - indexTip.first).let { it * it } +
                (thumbTip.second - indexTip.second).let { it * it } +
                (thumbTip.third - indexTip.third).let { it * it },
        )
    val pinch = (1f - dist / MAX_PINCH_DIST_M).coerceIn(0f, 1f)

    val pointer = imageLandmarks[LandmarkIndex.INDEX_MCP]
    return HandData(
        isTracked = true,
        pinchStrength = pinch,
        pointerPose = Pose(x = pointer.first, y = pointer.second, z = pointer.third),
    )
}
