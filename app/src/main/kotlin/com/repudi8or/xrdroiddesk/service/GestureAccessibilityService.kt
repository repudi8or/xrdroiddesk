package com.repudi8or.xrdroiddesk.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.repudi8or.xrdroiddesk.controller.AccessibilityDesktopController
import com.repudi8or.xrdroiddesk.controller.GestureActionDispatcher

class GestureAccessibilityService : AccessibilityService() {
    private lateinit var dispatcher: GestureActionDispatcher

    override fun onServiceConnected() {
        dispatcher = GestureActionDispatcher(AccessibilityDesktopController(this))
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    fun triggerDebugClick(
        x: Float,
        y: Float,
    ) {
        dispatcher.triggerClick(x, y)
    }

    companion object {
        var instance: GestureAccessibilityService? = null
            private set
    }
}
