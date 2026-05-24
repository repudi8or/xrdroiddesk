package com.repudi8or.xrdroiddesk.controller

sealed class DesktopAction {
    data class Click(
        val x: Float,
        val y: Float,
    ) : DesktopAction()

    data class Swipe(
        val direction: SwipeDirection,
    ) : DesktopAction()
}

enum class SwipeDirection { Left, Right }
