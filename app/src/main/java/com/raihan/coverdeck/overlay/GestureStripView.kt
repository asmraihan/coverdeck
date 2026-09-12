package com.raihan.coverdeck.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * The always-on handle at the bottom of the cover screen.
 *
 * One UI's own navigation gestures are not available to a third-party app, so this is
 * a thin overlay strip that owns its own slice of the screen edge and reproduces the
 * three gestures that matter: long-press for recents, swipe up for home, swipe right
 * for back. It also claims that slice via the system gesture exclusion list so the
 * platform's own edge handling does not eat the touch first.
 */
@SuppressLint("ViewConstructor")
class GestureStripView(
    context: Context,
    private val onLongPress: () -> Unit,
    private val onSwipeUp: () -> Unit,
    private val onSwipeRight: () -> Unit,
    private val onDoubleTap: () -> Unit,
) : View(context) {

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 145, 178, 225)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 91, 157, 255)
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = 320L
    private val doubleTapWindow = 260L

    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var consumed = false
    private var lastTapAt = 0L
    private var pressed = false

    private val longPressRunnable = Runnable {
        if (!consumed) {
            consumed = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onLongPress()
        }
    }

    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val handleWidth = width * 0.34f
        val handleHeight = height * 0.26f
        val left = (width - handleWidth) / 2f
        val top = (height - handleHeight) / 2f
        rect.set(left, top, left + handleWidth, top + handleHeight)
        val radius = handleHeight / 2f
        if (pressed) {
            canvas.drawRoundRect(
                rect.left - 8f, rect.top - 6f, rect.right + 8f, rect.bottom + 6f,
                radius + 6f, radius + 6f, glowPaint,
            )
        }
        canvas.drawRoundRect(rect, radius, radius, handlePaint)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Tell the platform this band belongs to us, so an edge swipe here is not
        // stolen by system back before the view ever sees it.
        systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, width, height))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                consumed = false
                pressed = true
                invalidate()
                handler.postDelayed(longPressRunnable, longPressTimeout)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!consumed && (abs(dx) > touchSlop * 2 || abs(dy) > touchSlop * 2)) {
                    handler.removeCallbacks(longPressRunnable)
                    // A decisive upward drag is home; a decisive rightward one is back.
                    if (abs(dy) > abs(dx) && dy < -touchSlop * 3) {
                        consumed = true
                        onSwipeUp()
                    } else if (dx > touchSlop * 3) {
                        consumed = true
                        onSwipeRight()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                pressed = false
                invalidate()
                if (!consumed && event.actionMasked == MotionEvent.ACTION_UP) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapAt < doubleTapWindow) {
                        lastTapAt = 0L
                        onDoubleTap()
                    } else {
                        lastTapAt = now
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
