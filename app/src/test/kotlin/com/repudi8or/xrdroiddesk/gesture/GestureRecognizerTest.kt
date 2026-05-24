package com.repudi8or.xrdroiddesk.gesture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GestureRecognizerTest {
    private lateinit var recognizer: GestureRecognizer

    @BeforeEach
    fun setUp() {
        recognizer = GestureRecognizer()
    }

    // ── Tracking ──────────────────────────────────────────────────────────

    @Test
    fun `returns null when hand is not tracked`() {
        assertNull(recognizer.recognize(untracked()))
    }

    // ── Pinch ─────────────────────────────────────────────────────────────

    @Test
    fun `returns Pinch when pinchStrength meets threshold`() {
        assertEquals(Gesture.Pinch, recognizer.recognize(tracked(pinchStrength = 0.8f)))
    }

    @Test
    fun `returns null when pinchStrength is below threshold`() {
        assertNull(recognizer.recognize(tracked(pinchStrength = 0.5f)))
    }

    @Test
    fun `custom pinch threshold is respected`() {
        val strict = GestureRecognizer(GestureConfig(pinchThreshold = 0.95f))
        assertNull(strict.recognize(tracked(pinchStrength = 0.9f)))
        assertEquals(Gesture.Pinch, strict.recognize(tracked(pinchStrength = 0.95f)))
    }

    // ── Swipe ─────────────────────────────────────────────────────────────

    @Test
    fun `returns null on first frame — no previous position to diff`() {
        assertNull(recognizer.recognize(tracked(pointerX = 0.5f)))
    }

    @Test
    fun `returns SwipeRight when pointer moves right beyond threshold`() {
        recognizer.recognize(tracked(pointerX = 0.0f))
        assertEquals(Gesture.SwipeRight, recognizer.recognize(tracked(pointerX = 0.1f)))
    }

    @Test
    fun `returns SwipeLeft when pointer moves left beyond threshold`() {
        recognizer.recognize(tracked(pointerX = 0.1f))
        assertEquals(Gesture.SwipeLeft, recognizer.recognize(tracked(pointerX = 0.0f)))
    }

    @Test
    fun `returns null when pointer movement is below swipe threshold`() {
        recognizer.recognize(tracked(pointerX = 0.0f))
        assertNull(recognizer.recognize(tracked(pointerX = 0.01f)))
    }

    @Test
    fun `custom swipe threshold is respected`() {
        val sensitive = GestureRecognizer(GestureConfig(swipeThreshold = 0.02f))
        sensitive.recognize(tracked(pointerX = 0.0f))
        assertEquals(Gesture.SwipeRight, sensitive.recognize(tracked(pointerX = 0.03f)))
    }

    // ── State management ──────────────────────────────────────────────────

    @Test
    fun `resets swipe state when hand becomes untracked`() {
        recognizer.recognize(tracked(pointerX = 0.0f))
        recognizer.recognize(untracked())
        // Large jump after re-track must not fire — no prior reference
        assertNull(recognizer.recognize(tracked(pointerX = 0.9f)))
    }

    @Test
    fun `resumes swipe detection after re-track`() {
        recognizer.recognize(tracked(pointerX = 0.0f))
        recognizer.recognize(untracked())
        recognizer.recognize(tracked(pointerX = 0.0f)) // first frame after re-track
        assertEquals(Gesture.SwipeRight, recognizer.recognize(tracked(pointerX = 0.1f)))
    }

    // ── Priority ──────────────────────────────────────────────────────────

    @Test
    fun `Pinch takes priority over simultaneous swipe`() {
        recognizer.recognize(tracked(pointerX = 0.0f))
        val both = tracked(pinchStrength = 0.9f, pointerX = 0.5f)
        assertEquals(Gesture.Pinch, recognizer.recognize(both))
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun tracked(
        pinchStrength: Float = 0f,
        pointerX: Float = 0f,
    ) = HandData(
        isTracked = true,
        pinchStrength = pinchStrength,
        pointerPose = Pose(x = pointerX, y = 0f, z = 0f),
    )

    private fun untracked() =
        HandData(
            isTracked = false,
            pinchStrength = 0f,
            pointerPose = null,
        )
}
