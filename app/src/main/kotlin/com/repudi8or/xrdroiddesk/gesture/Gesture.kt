package com.repudi8or.xrdroiddesk.gesture

sealed class Gesture {
    data class Pinch(
        val x: Float,
        val y: Float,
    ) : Gesture()

    object SwipeLeft : Gesture()

    object SwipeRight : Gesture()
}
