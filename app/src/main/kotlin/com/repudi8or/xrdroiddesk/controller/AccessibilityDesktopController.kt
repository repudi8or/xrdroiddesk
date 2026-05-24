package com.repudi8or.xrdroiddesk.controller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path

class AccessibilityDesktopController(
    private val service: AccessibilityService,
) : DesktopController {
    override fun perform(action: DesktopAction) {
        when (action) {
            is DesktopAction.Click -> dispatchClick(action.x, action.y)
        }
    }

    private fun dispatchClick(
        x: Float,
        y: Float,
    ) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val TAP_DURATION_MS = 50L
    }
}
