package com.repudi8or.xrdroiddesk.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Semi-transparent crosshair cursor drawn as a TYPE_ACCESSIBILITY_OVERLAY on a target display.
 * Position is updated from normalised (0-1) hand pointer coordinates each MediaPipe frame.
 * Visible only when the hand is tracked; hidden otherwise.
 */
class CursorOverlay(
    service: AccessibilityService,
    displayId: Int,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val wm: WindowManager
    private val view: CursorView
    private val params: WindowManager.LayoutParams
    private val displayW: Int
    private val displayH: Int
    private val sizePx: Int

    @Volatile private var added = false

    @Volatile private var pendingNx = 0.5f

    @Volatile private var pendingNy = 0.5f

    @Volatile private var pendingTracked = false

    private val applyUpdate =
        Runnable {
            params.x = (pendingNx * displayW).toInt() - sizePx / 2
            params.y = (pendingNy * displayH).toInt() - sizePx / 2
            if (!added) {
                try {
                    wm.addView(view, params)
                    added = true
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "addView failed: ${e.message}")
                }
            } else {
                try {
                    wm.updateViewLayout(view, params)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "updateViewLayout failed: ${e.message}")
                }
                view.setTracked(pendingTracked)
            }
        }

    init {
        val dm = service.getSystemService(android.hardware.display.DisplayManager::class.java)
        val display = dm.getDisplay(displayId) ?: dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)!!
        val metrics =
            DisplayMetrics().also {
                @Suppress("DEPRECATION")
                display.getMetrics(it)
            }
        displayW = metrics.widthPixels
        displayH = metrics.heightPixels
        sizePx = (CURSOR_DP * metrics.density).toInt().coerceAtLeast(32)

        // createWindowContext gives TYPE_ACCESSIBILITY_OVERLAY the right token on the target
        // display. createDisplayContext() has no window token → addView() fails with token null.
        val windowCtx =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                service.createWindowContext(
                    display,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    null,
                )
            } else {
                service.createDisplayContext(display)
            }
        wm = windowCtx.getSystemService(WindowManager::class.java)
        view = CursorView(windowCtx)

        params =
            WindowManager
                .LayoutParams(
                    sizePx,
                    sizePx,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = displayW / 2 - sizePx / 2
                    y = displayH / 2 - sizePx / 2
                }
    }

    /** Update cursor position from normalised coords (0-1). Call from any thread. */
    fun update(
        nx: Float,
        ny: Float,
        isTracked: Boolean,
    ) {
        pendingNx = nx
        pendingNy = ny
        pendingTracked = isTracked
        handler.removeCallbacks(applyUpdate)
        handler.post(applyUpdate)
    }

    fun close() {
        handler.post {
            if (added) {
                try {
                    wm.removeView(view)
                } catch (_: Exception) {
                }
                added = false
            }
        }
    }

    companion object {
        private const val CURSOR_DP = 28f
        private const val TAG = "CursorOverlay"
    }
}

private class CursorView(
    context: Context,
) : View(context) {
    private val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            alpha = 210
        }
    private val outlinePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
    private val crossPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            strokeCap = Paint.Cap.ROUND
        }
    private val dimPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            alpha = 80
        }

    private var tracked = true

    fun setTracked(t: Boolean) {
        if (tracked != t) {
            tracked = t
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - outlinePaint.strokeWidth - 1f
        val fill = if (tracked) fillPaint else dimPaint
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, outlinePaint)
        if (tracked) {
            val arm = r * 0.55f
            canvas.drawLine(cx - arm, cy, cx + arm, cy, crossPaint)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, crossPaint)
            canvas.drawPoint(cx, cy, outlinePaint)
        }
    }
}
