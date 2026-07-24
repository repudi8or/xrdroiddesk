package com.repudi8or.xrdroiddesk.tracking

import kotlin.math.PI
import kotlin.math.abs

/**
 * One Euro Filter — adaptive low-pass filter for pointer smoothing.
 *
 * At low speeds the cutoff drops toward minCutoff, giving heavy smoothing.
 * At high speeds it rises with beta × speed, reducing lag.
 *
 * Casiez et al., "1€ Filter: A Simple Speed-based Low-pass Filter for Noisy
 * Input in Interactive Systems", CHI 2012.
 *
 * @param minCutoff  Minimum cutoff frequency in Hz. Lower = smoother at rest.
 * @param beta       Speed coefficient. Higher = less lag during fast movement.
 * @param dCutoff    Cutoff for the derivative (speed) estimate, in Hz.
 */
class OneEuroFilter(
    private val minCutoff: Float = 1.0f,
    private val beta: Float = 0.007f,
    private val dCutoff: Float = 1.0f,
) {
    private var prevValue: Float? = null
    private var prevDerivative = 0f
    private var prevTimestampMs: Long = 0L

    /**
     * Feed a new sample. Returns the filtered value.
     * On the first call the raw value is returned unchanged.
     */
    fun filter(
        value: Float,
        timestampMs: Long,
    ): Float {
        val prev = prevValue
        if (prev == null) {
            prevValue = value
            prevTimestampMs = timestampMs
            return value
        }

        val dt = ((timestampMs - prevTimestampMs) / 1000f).coerceAtLeast(1e-6f)
        prevTimestampMs = timestampMs

        val raw = (value - prev) / dt
        val alphaD = alpha(dCutoff, dt)
        val dFiltered = alphaD * raw + (1f - alphaD) * prevDerivative
        prevDerivative = dFiltered

        val cutoff = minCutoff + beta * abs(dFiltered)
        val filtered = alpha(cutoff, dt) * value + (1f - alpha(cutoff, dt)) * prev
        prevValue = filtered
        return filtered
    }

    fun reset() {
        prevValue = null
        prevDerivative = 0f
        prevTimestampMs = 0L
    }

    private fun alpha(
        cutoff: Float,
        dt: Float,
    ): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }
}
