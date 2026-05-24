package com.repudi8or.xrdroiddesk.gesture

sealed class Gesture {
    object Pinch : Gesture()

    object SwipeLeft : Gesture()

    object SwipeRight : Gesture()
}
