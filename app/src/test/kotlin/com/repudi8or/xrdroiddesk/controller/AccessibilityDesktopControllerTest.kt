package com.repudi8or.xrdroiddesk.controller

import android.accessibilityservice.AccessibilityService
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AccessibilityDesktopControllerTest {
    private lateinit var service: AccessibilityService
    private lateinit var displayManager: DisplayManager
    private lateinit var externalDisplay: Display
    private lateinit var controller: AccessibilityDesktopController

    @BeforeEach
    fun setUp() {
        externalDisplay =
            mockk {
                every { displayId } returns 1
                every { getMetrics(any()) } answers {
                    val m = firstArg<DisplayMetrics>()
                    m.widthPixels = 1920
                    m.heightPixels = 1080
                }
            }
        displayManager =
            mockk {
                every { displays } returns arrayOf(externalDisplay)
            }
        service =
            mockk {
                every { getSystemService(DisplayManager::class.java) } returns displayManager
            }
        controller = AccessibilityDesktopController(service)
    }

    @Test
    fun `normalizedToPixels maps 0,0 to origin`() {
        val (x, y) = controller.normalizedToPixels(0f, 0f)
        assertEquals(0f, x)
        assertEquals(0f, y)
    }

    @Test
    fun `normalizedToPixels maps 1,1 to display dimensions`() {
        val (x, y) = controller.normalizedToPixels(1f, 1f)
        assertEquals(1920f, x)
        assertEquals(1080f, y)
    }

    @Test
    fun `normalizedToPixels maps centre correctly`() {
        val (x, y) = controller.normalizedToPixels(0.5f, 0.5f)
        assertEquals(960f, x)
        assertEquals(540f, y)
    }

    @Test
    fun `normalizedToPixels uses external display over default`() {
        // externalDisplay has displayId=1 (non-default) — controller should prefer it
        val defaultDisplay =
            mockk<Display> {
                every { displayId } returns Display.DEFAULT_DISPLAY
                every { getMetrics(any()) } answers {
                    val m = firstArg<DisplayMetrics>()
                    m.widthPixels = 1080
                    m.heightPixels = 2400
                }
            }
        every { displayManager.displays } returns arrayOf(defaultDisplay, externalDisplay)

        val (x, y) = controller.normalizedToPixels(1f, 1f)
        // Must use externalDisplay (1920×1080), not defaultDisplay (1080×2400)
        assertEquals(1920f, x)
        assertEquals(1080f, y)
    }
}
