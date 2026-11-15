package com.mdos.auto.car

import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View

/**
 * Android Auto reports taps, drags and flings on the car screen as callbacks. This turns them back
 * into real finger touch events on our UI, so web pages scroll and buttons click as on a phone.
 */
class TouchInjector(
    private val target: () -> View?,
    private val anchor: () -> PointF,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var dragging = false
    private var downTime = 0L
    private var x = 0f
    private var y = 0f
    private val endDrag = Runnable { finishDrag() }

    fun tap(px: Float, py: Float) {
        finishDrag()
        val v = target() ?: return
        val t = SystemClock.uptimeMillis()
        dispatch(v, MotionEvent.ACTION_DOWN, t, t, px, py)
        handler.postDelayed({
            target()?.let { dispatch(it, MotionEvent.ACTION_UP, t, SystemClock.uptimeMillis(), px, py) }
        }, 60)
    }

    /** distanceX/Y follow GestureDetector: previous position minus current position. */
    fun scroll(distanceX: Float, distanceY: Float) {
        val v = target() ?: return
        if (!dragging) startDrag(v)
        x -= distanceX
        y -= distanceY
        val out = x < 0 || y < 0 || x > v.width || y > v.height
        clamp(v)
        dispatch(v, MotionEvent.ACTION_MOVE, downTime, SystemClock.uptimeMillis(), x, y)
        handler.removeCallbacks(endDrag)
        if (out) finishDrag() else handler.postDelayed(endDrag, 150)
    }

    fun fling(velocityX: Float, velocityY: Float) {
        val v = target() ?: return
        if (!dragging) startDrag(v)
        handler.removeCallbacks(endDrag)
        var t = SystemClock.uptimeMillis()
        repeat(3) {
            x += velocityX * 0.016f
            y += velocityY * 0.016f
            clamp(v)
            t += 16
            dispatch(v, MotionEvent.ACTION_MOVE, downTime, t, x, y)
        }
        dispatch(v, MotionEvent.ACTION_UP, downTime, t, x, y)
        dragging = false
    }

    private fun startDrag(v: View) {
        val a = anchor()
        x = a.x
        y = a.y
        downTime = SystemClock.uptimeMillis()
        dragging = true
        dispatch(v, MotionEvent.ACTION_DOWN, downTime, downTime, x, y)
    }

    private fun finishDrag() {
        handler.removeCallbacks(endDrag)
        if (!dragging) return
        dragging = false
        target()?.let { dispatch(it, MotionEvent.ACTION_UP, downTime, SystemClock.uptimeMillis(), x, y) }
    }

    private fun clamp(v: View) {
        x = x.coerceIn(0f, (v.width - 1).coerceAtLeast(0).toFloat())
        y = y.coerceIn(0f, (v.height - 1).coerceAtLeast(0).toFloat())
    }

    private fun dispatch(v: View, action: Int, down: Long, time: Long, px: Float, py: Float) {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            x = px
            y = py
            pressure = 1f
            size = 1f
        }
        val ev = MotionEvent.obtain(
            down, time, action, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            v.dispatchTouchEvent(ev)
        } finally {
            ev.recycle()
        }
    }
}
