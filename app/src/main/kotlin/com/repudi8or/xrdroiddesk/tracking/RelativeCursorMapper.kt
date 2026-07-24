package com.repudi8or.xrdroiddesk.tracking

import kotlin.math.abs

/**
 * Trackpad-style cursor mapper. Hand position deltas move the cursor rather than
 * absolute hand position mapping to absolute screen position, so no calibration is needed.
 *
 * Call [update] each frame with the One Euro filtered wrist position. The mapper
 * accumulates cursor position in [x]/[y] (both clamped to [0, 1]).
 *
 * Call [onHandLost] when tracking is lost — preserves cursor position but clears the
 * previous-position reference so the first frame after re-detection doesn't jump.
 *
 * Call [reset] on full stop to re-centre the cursor.
 */
class RelativeCursorMapper(
    private val sensitivity: Float = 2.5f,
    private val deadZone: Float = 0.004f,
) {
    var x = 0.5f
        private set
    var y = 0.5f
        private set

    private var prevFilteredX: Float? = null
    private var prevFilteredY: Float? = null

    fun update(
        filteredX: Float,
        filteredY: Float,
    ) {
        val px = prevFilteredX
        val py = prevFilteredY
        prevFilteredX = filteredX
        prevFilteredY = filteredY

        if (px == null || py == null) return

        val dx = filteredX - px
        val dy = filteredY - py
        if (abs(dx) < deadZone && abs(dy) < deadZone) return

        x = (x + dx * sensitivity).coerceIn(0f, 1f)
        y = (y + dy * sensitivity).coerceIn(0f, 1f)
    }

    fun onHandLost() {
        prevFilteredX = null
        prevFilteredY = null
    }

    fun reset() {
        x = 0.5f
        y = 0.5f
        prevFilteredX = null
        prevFilteredY = null
    }
}
