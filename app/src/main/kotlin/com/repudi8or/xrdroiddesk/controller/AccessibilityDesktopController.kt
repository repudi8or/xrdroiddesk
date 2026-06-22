package com.repudi8or.xrdroiddesk.controller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import java.util.concurrent.atomic.AtomicBoolean

class AccessibilityDesktopController(
    private val service: AccessibilityService,
) : DesktopController {
    private val displaysLogged = AtomicBoolean(false)

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
        val metrics = displayMetrics()
        return Pair(nx * metrics.widthPixels, ny * metrics.heightPixels)
    }

    private fun displayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        targetDisplay().getMetrics(metrics)
        return metrics
    }

    private fun dispatchClick(
        x: Float,
        y: Float,
    ) {
        logDisplaysOnce()
        val display = targetDisplay()
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        val builder = GestureDescription.Builder().addStroke(stroke)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.setDisplayId(display.displayId)
        }
        val gesture = builder.build()
        val accepted =
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        Log.i(TAG, "gesture completed (x=${x.toInt()},y=${y.toInt()})")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription) {
                        Log.w(TAG, "gesture CANCELLED (x=${x.toInt()},y=${y.toInt()})")
                        com.repudi8or.xrdroiddesk.MainActivity.instance?.runOnUiThread {
                            com.repudi8or.xrdroiddesk.MainActivity.instance
                                ?.appendLog("click CANCELLED display=${display.displayId}")
                        }
                    }
                },
                null,
            )
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getMetrics(metrics)
        val msg =
            "click (${x.toInt()},${y.toInt()}) disp=${display.displayId} " +
                "${metrics.widthPixels}x${metrics.heightPixels} accepted=$accepted"
        Log.i(TAG, msg)
        com.repudi8or.xrdroiddesk.MainActivity.instance?.runOnUiThread {
            com.repudi8or.xrdroiddesk.MainActivity.instance
                ?.appendLog(msg)
        }
    }

    private fun logDisplaysOnce() {
        if (!displaysLogged.compareAndSet(false, true)) return
        val dm = service.getSystemService(DisplayManager::class.java)
        dm.displays.forEach { d ->
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            d.getMetrics(m)
            Log.i(TAG, "display id=${d.displayId} ${m.widthPixels}x${m.heightPixels} name='${d.name}' state=${d.state}")
        }
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
