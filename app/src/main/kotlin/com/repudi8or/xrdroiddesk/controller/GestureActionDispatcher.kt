package com.repudi8or.xrdroiddesk.controller

import com.repudi8or.xrdroiddesk.gesture.Gesture

class GestureActionDispatcher(
    private val controller: DesktopController,
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
                is Gesture.Pinch -> DesktopAction.Click(x = 0.5f, y = 0.5f)
                is Gesture.SwipeLeft -> DesktopAction.Swipe(SwipeDirection.Left)
                is Gesture.SwipeRight -> DesktopAction.Swipe(SwipeDirection.Right)
            }
        controller.perform(action)
    }
}
