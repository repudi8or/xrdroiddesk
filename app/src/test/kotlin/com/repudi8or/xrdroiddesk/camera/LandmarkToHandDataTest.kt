package com.repudi8or.xrdroiddesk.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LandmarkToHandDataTest {
    // 21 landmarks all at origin — pinched
    private fun landmarks(vararg overrides: Pair<Int, Triple<Float, Float, Float>>): List<Triple<Float, Float, Float>> {
        val pts = MutableList(21) { Triple(0f, 0f, 0f) }
        overrides.forEach { (idx, pos) -> pts[idx] = pos }
        return pts
    }

    @Test
    fun `untracked input yields untracked HandData`() {
        val result = landmarkToHandData(isTracked = false, worldLandmarks = emptyList(), imageLandmarks = emptyList())
        assertFalse(result.isTracked)
        assertEquals(0f, result.pinchStrength)
        assertNull(result.pointerPose)
    }

    @Test
    fun `thumb and index at same world position gives pinchStrength 1`() {
        val world = landmarks() // all at origin → distance 0
        val image = landmarks()
        val result = landmarkToHandData(isTracked = true, worldLandmarks = world, imageLandmarks = image)
        assertTrue(result.isTracked)
        assertEquals(1.0f, result.pinchStrength)
    }

    @Test
    fun `thumb and index far apart gives pinchStrength 0`() {
        val world =
            landmarks(
                LandmarkIndex.THUMB_TIP to Triple(0f, 0f, 0f),
                LandmarkIndex.INDEX_TIP to Triple(1f, 0f, 0f), // 1 metre apart
            )
        val image = landmarks()
        val result = landmarkToHandData(isTracked = true, worldLandmarks = world, imageLandmarks = image)
        assertEquals(0.0f, result.pinchStrength)
    }

    @Test
    fun `pinchStrength decreases as fingers separate`() {
        fun strengthAt(dist: Float): Float {
            val world =
                landmarks(
                    LandmarkIndex.INDEX_TIP to Triple(dist, 0f, 0f),
                )
            return landmarkToHandData(true, world, landmarks()).pinchStrength
        }
        assertTrue(strengthAt(0.02f) > strengthAt(0.05f))
        assertTrue(strengthAt(0.05f) > strengthAt(0.08f))
    }

    @Test
    fun `pointer pose uses image X and Y of wrist`() {
        val image =
            landmarks(
                LandmarkIndex.WRIST to Triple(0.6f, 0.4f, 0f),
            )
        val result = landmarkToHandData(isTracked = true, worldLandmarks = landmarks(), imageLandmarks = image)
        assertEquals(0.6f, result.pointerPose!!.x, 0.001f)
        assertEquals(0.4f, result.pointerPose!!.y, 0.001f)
    }
}
