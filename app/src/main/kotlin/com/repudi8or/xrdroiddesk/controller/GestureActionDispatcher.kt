package com.repudi8or.xrdroiddesk.controller

import com.repudi8or.xrdroiddesk.gesture.Gesture

class GestureActionDispatcher(
    private val controller: DesktopController,
    private val mapCoords: (Float, Float) -> Pair<Float, Float> = { x, y -> Pair(x, y) },
) {
    fun triggerClick(
        x: Float,
        y: Float,
    ) {
        controller.perform(DesktopAction.Click(x = x, y = y))
    }

    fun dispatch(gesture: Gesture) {
        val action =
            when (gesture) {
                is Gesture.Pinch -> {
                    val (px, py) = mapCoords(gesture.x, gesture.y)
                    DesktopAction.Click(x = px, y = py)
                }
                is Gesture.SwipeLeft -> DesktopAction.Swipe(SwipeDirection.Left)
                is Gesture.SwipeRight -> DesktopAction.Swipe(SwipeDirection.Right)
            }
        controller.perform(action)
    }
}
