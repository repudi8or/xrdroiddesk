package com.repudi8or.xrdroiddesk

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

// Transparent, non-interactive activity launched on the glasses display.
// Its sole purpose is to force Android's WindowManager to set up DisplayContent token
// infrastructure for that display, which allows AccessibilityService.createWindowContext()
// to return a valid window token for TYPE_ACCESSIBILITY_OVERLAY overlays.
// Automatically destroyed by the system when the display is removed.
class GlassesWakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        )
    }
}
