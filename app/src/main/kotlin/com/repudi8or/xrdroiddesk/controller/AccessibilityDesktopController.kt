package com.repudi8or.xrdroiddesk.controller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display

class AccessibilityDesktopController(
    private val service: AccessibilityService,
) : DesktopController {
    override fun perform(action: DesktopAction) {
        when (action) {
            is DesktopAction.Click -> dispatchClick(action.x, action.y)
            is DesktopAction.Swipe -> Log.d(TAG, "Swipe ${action.direction} — window switching not yet implemented")
        }
    }

    fun normalizedToPixels(
        nx: Float,
        ny: Float,
    ): Pair<Float, Float> {
        val display = targetDisplay()
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getMetrics(metrics)
        return Pair(nx * metrics.widthPixels, ny * metrics.heightPixels)
    }

    private fun dispatchClick(
        x: Float,
        y: Float,
    ) {
        val display = targetDisplay()
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        val builder = GestureDescription.Builder().addStroke(stroke)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.setDisplayId(display.displayId)
        }
        val gesture = builder.build()
        val dispatched = service.dispatchGesture(gesture, null, null)
        Log.d(TAG, "dispatchGesture(${x.toInt()},${y.toInt()} display=${display.displayId}) → $dispatched")
    }

    private fun targetDisplay(): Display {
        val dm = service.getSystemService(DisplayManager::class.java)
        return dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
            ?: dm.getDisplay(Display.DEFAULT_DISPLAY)
    }

    companion object {
        private const val TAP_DURATION_MS = 50L
        private const val TAG = "DesktopController"
    }
}
