package com.repudi8or.xrdroiddesk.controller

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
}
