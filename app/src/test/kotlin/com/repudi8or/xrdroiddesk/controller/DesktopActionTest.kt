package com.repudi8or.xrdroiddesk.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class DesktopActionTest {
    @Test
    fun `Click holds the given coordinates`() {
        val action = DesktopAction.Click(x = 123f, y = 456f)
        assertEquals(123f, action.x)
        assertEquals(456f, action.y)
    }

    @Test
    fun `Click equality is coordinate-based`() {
        assertEquals(DesktopAction.Click(1f, 2f), DesktopAction.Click(1f, 2f))
        assertNotEquals(DesktopAction.Click(1f, 2f), DesktopAction.Click(1f, 3f))
    }
}
