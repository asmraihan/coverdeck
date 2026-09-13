package com.raihan.coverdeck.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.raihan.coverdeck.core.Displays
import com.raihan.coverdeck.mirror.MirrorHostService
import kotlin.math.max

/**
 * A short confirmation pill on the cover screen, over whatever is showing.
 *
 * Drawn as an accessibility overlay through [MirrorHostService] when that service is on,
 * because that is the one window type that stays visible over Settings screens and the
 * cover's quick panel (app overlays are hidden there). Without the service it falls back
 * to a plain toast on the cover.
 */
object CoverHud {

    enum class Icon { UNLOCKED, LOCKED }

    private const val TAG = "CoverDeck/Hud"
    private const val SHOW_MS = 1_600L

    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideNow() }

    /** Main thread only. */
    private var windowManager: WindowManager? = null
    private var view: PillView? = null

    fun show(context: Context, icon: Icon, title: String, detail: String) {
        val app = context.applicationContext
        handler.post { showNow(app, icon, title, detail) }
    }

    fun hide() {
        handler.post { hideNow() }
    }

    private fun showNow(context: Context, icon: Icon, title: String, detail: String) {
        handler.removeCallbacks(hideRunnable)
        val current = view
        if (current != null) {
            current.bind(icon, title, detail)
        } else if (!addPill(context, icon, title, detail)) {
            toast(context, "$title. $detail")
            return
        }
        handler.postDelayed(hideRunnable, SHOW_MS)
    }

    private fun addPill(context: Context, icon: Icon, title: String, detail: String): Boolean {
        val host = MirrorHostService.current ?: return false
        return try {
            val coverId = Displays.cover(context).displayId
            val display = host.getSystemService(DisplayManager::class.java)?.getDisplay(coverId)
                ?: error("cover display $coverId not found")
            val windowContext = host.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            val wm = windowContext.getSystemService(WindowManager::class.java)
            val pill = PillView(windowContext).apply { bind(icon, title, detail) }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                this.title = "CoverDeck-Hud"
                gravity = Gravity.CENTER
                windowAnimations = android.R.style.Animation_Toast
            }
            wm.addView(pill, params)
            windowManager = wm
            view = pill
            true
        } catch (t: Throwable) {
            Log.w(TAG, "could not show the confirmation pill", t)
            false
        }
    }

    private fun hideNow() {
        handler.removeCallbacks(hideRunnable)
        view?.let { v -> runCatching { windowManager?.removeView(v) } }
        view = null
        windowManager = null
    }

    private fun toast(context: Context, text: String) {
        runCatching {
            val display = context.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Displays.cover(context).displayId)
            val target = display?.let { context.createDisplayContext(it) } ?: context
            Toast.makeText(target, text, Toast.LENGTH_SHORT).show()
        }
    }

    /** Icon, title and hint in a rounded pill, in CoverDeck's colours. */
    @SuppressLint("ViewConstructor")
    private class PillView(context: Context) : View(context) {

        private val density = resources.displayMetrics.density
        private var icon = Icon.UNLOCKED
        private var title = ""
        private var detail = ""

        private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(245, 0x1B, 0x23, 0x31) }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 0x5B, 0x9D, 0xFF)
            style = Paint.Style.STROKE
            strokeWidth = density
        }
        private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(48, 0x5B, 0x9D, 0xFF) }
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x8F, 0xBC, 0xFF)
            style = Paint.Style.STROKE
            strokeWidth = 1.7f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val glyphFill = Paint(glyph).apply { style = Paint.Style.FILL }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0xE8, 0xED, 0xF5)
            textSize = 13f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x95, 0xA2, 0xB5)
            textSize = 11f * density
        }

        private val rect = RectF()

        fun bind(icon: Icon, title: String, detail: String) {
            this.icon = icon
            this.title = title
            this.detail = detail
            requestLayout()
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // Text is measured in pixels already; only the fixed parts are in dp.
            val textWidth = max(titlePaint.measureText(title), detailPaint.measureText(detail))
            val width = (PAD_START + BADGE + GAP + PAD_END) * density + textWidth
            setMeasuredDimension(width.toInt(), (HEIGHT * density).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            val radius = h / 2
            rect.set(density / 2, density / 2, width - density / 2, h - density / 2)
            canvas.drawRoundRect(rect, radius, radius, background)
            canvas.drawRoundRect(rect, radius, radius, outline)

            val cx = (PAD_START + BADGE / 2) * density
            val cy = h / 2
            canvas.drawCircle(cx, cy, BADGE / 2 * density, badge)
            drawPadlock(canvas, cx, cy, open = icon == Icon.UNLOCKED)

            val textX = (PAD_START + BADGE + GAP) * density
            canvas.drawText(title, textX, cy - 2.5f * density, titlePaint)
            canvas.drawText(detail, textX, cy + 10f * density, detailPaint)
        }

        /**
         * A padlock, closed or open. The open one lifts the left side of the shackle clear
         * of the body, so the two read as a pair.
         */
        private fun drawPadlock(canvas: Canvas, cx: Float, cy: Float, open: Boolean) {
            val bodyWidth = 11f * density
            val bodyHeight = 8f * density
            val bodyTop = cy - 0.5f * density
            rect.set(cx - bodyWidth / 2, bodyTop, cx + bodyWidth / 2, bodyTop + bodyHeight)
            canvas.drawRoundRect(rect, 2f * density, 2f * density, glyphFill)

            val r = 3.4f * density
            val lift = if (open) 2.2f * density else 0f
            val legBottom = bodyTop + 0.5f * density
            val arcCenterY = bodyTop - 3f * density - lift
            rect.set(cx - r, arcCenterY - r, cx + r, arcCenterY + r)
            canvas.drawArc(rect, 180f, 180f, false, glyph)
            // Right leg always reaches the body; the left one stops short when open.
            canvas.drawLine(cx + r, arcCenterY, cx + r, legBottom, glyph)
            canvas.drawLine(cx - r, arcCenterY, cx - r, if (open) arcCenterY + 1.6f * density else legBottom, glyph)
        }

        private companion object {
            // dp
            const val HEIGHT = 44f
            const val PAD_START = 8f
            const val BADGE = 28f
            const val GAP = 9f
            const val PAD_END = 16f
        }
    }
}
