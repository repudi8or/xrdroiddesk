package com.repudi8or.xrdroiddesk.controller

import com.repudi8or.xrdroiddesk.gesture.Gesture
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GestureActionDispatcherTest {
    private lateinit var controller: DesktopController
    private lateinit var dispatcher: GestureActionDispatcher

    @BeforeEach
    fun setUp() {
        controller = mockk(relaxed = true)
        dispatcher = GestureActionDispatcher(controller)
    }

    @Test
    fun `triggerClick dispatches Click action with given coordinates`() {
        dispatcher.triggerClick(x = 100f, y = 200f)
        verify { controller.perform(DesktopAction.Click(x = 100f, y = 200f)) }
    }

    @Test
    fun `triggerClick passes coordinates through unchanged`() {
        dispatcher.triggerClick(x = 0f, y = 0f)
        verify { controller.perform(DesktopAction.Click(x = 0f, y = 0f)) }

        dispatcher.triggerClick(x = 1920f, y = 1080f)
        verify { controller.perform(DesktopAction.Click(x = 1920f, y = 1080f)) }
    }

    @Test
    fun `dispatch Pinch applies coord mapping and performs Click`() {
        val mapped = GestureActionDispatcher(controller) { x, y -> Pair(x * 1920f, y * 1080f) }
        mapped.dispatch(Gesture.Pinch(x = 0.5f, y = 0.5f))
        verify { controller.perform(DesktopAction.Click(x = 960f, y = 540f)) }
    }

    @Test
    fun `dispatch SwipeLeft performs Swipe Left action`() {
        dispatcher.dispatch(Gesture.SwipeLeft)
        verify { controller.perform(DesktopAction.Swipe(SwipeDirection.Left)) }
    }

    @Test
    fun `dispatch SwipeRight performs Swipe Right action`() {
        dispatcher.dispatch(Gesture.SwipeRight)
        verify { controller.perform(DesktopAction.Swipe(SwipeDirection.Right)) }
    }
}
