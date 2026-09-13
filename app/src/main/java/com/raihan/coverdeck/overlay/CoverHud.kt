package com.raihan.coverdeck.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * A short confirmation pill on the cover screen, over whatever is showing.
 *
 * Drawn as an accessibility overlay through [MirrorHostService] when that service is on,
 * because that is the one window type that stays visible over Settings screens and the
 * cover's quick panel (app overlays are hidden there). Without the service it falls back
 * to a plain toast on the cover.
 */
object CoverHud {

    enum class Icon { AUTO_ROTATE, LOCKED }

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
        private var icon = Icon.AUTO_ROTATE
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
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val glyphFill = Paint(glyph).apply { style = Paint.Style.FILL }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0xE8, 0xED, 0xF5)
            textSize = 15f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x95, 0xA2, 0xB5)
            textSize = 12.5f * density
        }

        private val rect = RectF()
        private val path = Path()

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
            when (icon) {
                Icon.AUTO_ROTATE -> drawAutoRotate(canvas, cx, cy)
                Icon.LOCKED -> drawLock(canvas, cx, cy)
            }

            val textX = (PAD_START + BADGE + GAP) * density
            canvas.drawText(title, textX, cy - 2 * density, titlePaint)
            canvas.drawText(detail, textX, cy + detailPaint.textSize + 1 * density, detailPaint)
        }

        /** A circular arrow. */
        private fun drawAutoRotate(canvas: Canvas, cx: Float, cy: Float) {
            val r = 7.5f * density
            rect.set(cx - r, cy - r, cx + r, cy + r)
            val start = -60f
            val sweep = 290f
            canvas.drawArc(rect, start, sweep, false, glyph)
            val end = Math.toRadians((start + sweep).toDouble())
            val tipX = cx + r * cos(end).toFloat()
            val tipY = cy + r * sin(end).toFloat()
            val size = 4f * density
            path.reset()
            path.moveTo(tipX - size, tipY - size * 0.2f)
            path.lineTo(tipX, tipY)
            path.lineTo(tipX + size * 0.2f, tipY - size)
            canvas.drawPath(path, glyph)
        }

        /** A padlock. */
        private fun drawLock(canvas: Canvas, cx: Float, cy: Float) {
            val w = 13f * density
            val bodyTop = cy - 1f * density
            rect.set(cx - w / 2, bodyTop, cx + w / 2, bodyTop + 9.5f * density)
            canvas.drawRoundRect(rect, 2.5f * density, 2.5f * density, glyphFill)
            val shackle = 4.2f * density
            rect.set(cx - shackle, bodyTop - 2 * shackle + 1f * density, cx + shackle, bodyTop + 1f * density)
            canvas.drawArc(rect, 180f, 180f, false, glyph)
            canvas.drawLine(cx - shackle, bodyTop - shackle + 1f * density, cx - shackle, bodyTop, glyph)
            canvas.drawLine(cx + shackle, bodyTop - shackle + 1f * density, cx + shackle, bodyTop, glyph)
        }

        private companion object {
            // dp
            const val HEIGHT = 56f
            const val PAD_START = 12f
            const val BADGE = 34f
            const val GAP = 11f
            const val PAD_END = 20f
        }
    }
}
