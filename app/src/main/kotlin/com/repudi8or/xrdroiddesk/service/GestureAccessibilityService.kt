package com.repudi8or.xrdroiddesk.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class GestureAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {}
}
