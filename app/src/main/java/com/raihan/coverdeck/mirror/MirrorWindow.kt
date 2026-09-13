package com.raihan.coverdeck.mirror

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import com.raihan.coverdeck.core.Displays
import com.raihan.coverdeck.privileged.Privileged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * The full-screen mirror on the cover: the inner display's picture, the control strip
 * beside the camera, and the menu. Owned by [MirrorHostService]; everything here runs on
 * the main thread except binder calls, which go through [MirrorSession.worker].
 */
internal class MirrorWindow(
    private val host: MirrorHostService,
    private val events: Events,
) {

    interface Events {
        fun onAreaMeasured(width: Int, height: Int)
        fun onStopRequested()
        fun onShapeToggleRequested()
        fun onStreamChanged(streaming: Boolean, engine: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val displayManager = host.getSystemService(DisplayManager::class.java)

    private var windowManager: WindowManager? = null
    private var view: MirrorView? = null
    private var coverDisplayId = Displays.FALLBACK_COVER_ID

    /** The cover's last seen power state. */
    private var coverOn = true

    /** The inner panel is held awake for this screen-on period, so the picture can run. */
    private var innerReady = true

    val isShowing: Boolean get() = view != null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != coverDisplayId) return
            val on = displayManager?.getDisplay(coverDisplayId)?.state == Display.STATE_ON
            if (on == coverOn) return
            coverOn = on
            if (on) {
                // Screen back on. The system drops the awake override while asleep, so
                // take it again, and only then restart the picture.
                scope.launch {
                    withContext(MirrorSession.worker) { Privileged.with { it.setInnerDisplayAwake(true) } }
                    if (coverOn) {
                        innerReady = true
                        view?.restartStream()
                    }
                }
            } else {
                // Let go while the cover is off. One UI cancels the override itself on the
                // next wake, and a cancel at that moment counts as folding the phone: it
                // goes straight back to sleep.
                innerReady = false
                view?.stopStream()
                scope.launch(MirrorSession.worker) { Privileged.with { it.setInnerDisplayAwake(false) } }
            }
        }
    }

    fun show(coverId: Int): Boolean {
        if (view != null) return true
        coverDisplayId = coverId
        return try {
            val display = displayManager?.getDisplay(coverId) ?: error("cover display $coverId not found")
            val windowContext = host.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            val wm = windowContext.getSystemService(WindowManager::class.java)
            val mirrorView = MirrorView(windowContext)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    // The cover times out after 30 s, which is unusable for a primary
                    // screen. The power button still turns it off.
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE,
            ).apply {
                title = "CoverDeck-Mirror"
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
            }
            wm.addView(mirrorView, params)
            windowManager = wm
            view = mirrorView
            coverOn = display.state == Display.STATE_ON
            innerReady = coverOn
            if (!coverOn) {
                // Started with the cover off (from a script, say): same rule as above.
                scope.launch(MirrorSession.worker) { Privileged.with { it.setInnerDisplayAwake(false) } }
            }
            displayManager.registerDisplayListener(displayListener, null)
            Log.i(TAG, "mirror window added on display $coverId")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "could not add the mirror window", t)
            false
        }
    }

    fun update(sourceWidth: Int, sourceHeight: Int) {
        view?.setSource(sourceWidth, sourceHeight)
    }

    fun hide() {
        runCatching { displayManager?.unregisterDisplayListener(displayListener) }
        view?.let { v ->
            v.stopStream()
            runCatching { windowManager?.removeViewImmediate(v) }
        }
        view = null
        windowManager = null
        scope.cancel()
    }

    /**
     * Drawn in software, which is plenty for a few shapes; the picture itself is
     * composited by SurfaceFlinger straight from the virtual display into the SurfaceView.
     */
    @SuppressLint("ViewConstructor")
    private inner class MirrorView(context: Context) : FrameLayout(context) {

        private val density = resources.displayMetrics.density
        private val surfaceView = SurfaceView(context)

        private var sourceWidth = 0
        private var sourceHeight = 0

        private val area = Rect()
        private val content = Rect()
        private val band = Rect()
        private var streaming = false
        private var surfaceReady = false
        private var menuOpen = false
        private var trackingButtons = false
        private var forwarding = false
        private var lastAreaReported = 0L

        private val buttons = arrayOf(RectF(), RectF(), RectF(), RectF())
        private val menuRect = RectF()
        private val menuRows = arrayOf(RectF(), RectF(), RectF())

        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0xE8, 0xED, 0xF5)
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xE8, 0xED, 0xF5) }
        private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x1B, 0x23, 0x31) }
        private val scrimPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0xE8, 0xED, 0xF5)
            textSize = 15f * density
        }
        private val subtlePaint = Paint(textPaint).apply { color = Color.rgb(0x95, 0xA2, 0xB5) }
        private val dangerPaint = Paint(textPaint).apply { color = Color.rgb(0xFF, 0x6B, 0x6B) }

        init {
            setBackgroundColor(Color.BLACK)
            setWillNotDraw(false)
            addView(surfaceView, LayoutParams(1, 1))
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    surfaceReady = true
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                    if (w == content.width() && h == content.height()) startStreamIfReady()
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    surfaceReady = false
                    stopStream()
                }
            })
        }

        fun setSource(width: Int, height: Int) {
            if (width == sourceWidth && height == sourceHeight) return
            sourceWidth = width
            sourceHeight = height
            stopStream()
            layoutContent()
            invalidate()
        }

        // ---- layout -----------------------------------------------------------------

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            if (changed) layoutContent()
        }

        /** Insets can arrive after the first layout; lay out again once they do. */
        override fun onApplyWindowInsets(insets: android.view.WindowInsets): android.view.WindowInsets {
            post { layoutContent() }
            return super.onApplyWindowInsets(insets)
        }

        private fun layoutContent() {
            if (width == 0 || height == 0) return
            layoutBand()
            val vertical = band.height() > band.width()
            val cell = (if (vertical) band.height() else band.width()) / 4f
            buttons.forEachIndexed { i, rect ->
                if (vertical) {
                    rect.set(band.left.toFloat(), band.top + i * cell, band.right.toFloat(), band.top + (i + 1) * cell)
                } else {
                    rect.set(band.left + i * cell, band.top.toFloat(), band.left + (i + 1) * cell, band.bottom.toFloat())
                }
            }

            val key = (area.width().toLong() shl 32) or area.height().toLong()
            if (key != lastAreaReported) {
                lastAreaReported = key
                events.onAreaMeasured(area.width(), area.height())
            }

            if (sourceWidth <= 0 || sourceHeight <= 0) return
            val scale = min(area.width().toFloat() / sourceWidth, area.height().toFloat() / sourceHeight)
            val w = even(sourceWidth * scale)
            val h = even(sourceHeight * scale)
            val left = area.left + (area.width() - w) / 2
            val top = area.top + (area.height() - h) / 2
            val sizeChanged = w != content.width() || h != content.height()
            content.set(left, top, left + w, top + h)

            val lp = surfaceView.layoutParams as LayoutParams
            if (lp.width != w || lp.height != h || lp.leftMargin != left || lp.topMargin != top) {
                surfaceView.layoutParams = LayoutParams(w, h).apply {
                    leftMargin = left
                    topMargin = top
                }
                surfaceView.holder.setFixedSize(w, h)
            }
            if (!sizeChanged && surfaceReady) startStreamIfReady()
            invalidate()
        }

        /**
         * Splits the window into the picture area and the control band.
         *
         * The cover's cameras cut a strip off one edge that no app can use. Held the usual
         * way that strip runs along the top, cameras on the left, but the cover can be
         * rotated, so the edge is read from the cutout each time rather than assumed. The
         * picture gets everything except that strip; the controls sit in the strip beside
         * the cameras. Without a usable cutout, a strip along the bottom is used instead.
         */
        private fun layoutBand() {
            val w = width
            val h = height
            val cutout = rootWindowInsets?.displayCutout ?: display?.cutout
            val gap = (8 * density).toInt()
            val minLength = (150 * density).toInt()

            if (cutout != null) {
                val top = cutout.safeInsetTop
                val bottom = cutout.safeInsetBottom
                val left = cutout.safeInsetLeft
                val right = cutout.safeInsetRight
                when {
                    top > 0 || bottom > 0 -> {
                        val atTop = top > 0
                        val inset = if (atTop) top else bottom
                        val camera = (if (atTop) cutout.boundingRectTop else cutout.boundingRectBottom)
                        val y0 = if (atTop) 0 else h - inset
                        // The longer free run beside the camera.
                        val (from, to) = freeRun(camera.takeIf { !it.isEmpty }?.let { it.left to it.right }, w, gap)
                        if (to - from >= minLength) {
                            area.set(0, if (atTop) inset else 0, w, if (atTop) h else h - inset)
                            band.set(from, y0, to, y0 + inset)
                            return
                        }
                    }

                    left > 0 || right > 0 -> {
                        val atLeft = left > 0
                        val inset = if (atLeft) left else right
                        val camera = (if (atLeft) cutout.boundingRectLeft else cutout.boundingRectRight)
                        val x0 = if (atLeft) 0 else w - inset
                        val (from, to) = freeRun(camera.takeIf { !it.isEmpty }?.let { it.top to it.bottom }, h, gap)
                        if (to - from >= minLength) {
                            area.set(if (atLeft) inset else 0, 0, if (atLeft) w else w - inset, h)
                            band.set(x0, from, x0 + inset, to)
                            return
                        }
                    }
                }
            }
            val strip = (44 * density).toInt()
            area.set(0, 0, w, h - strip)
            band.set(0, h - strip, w, h)
        }

        /** The longer stretch of [0, length) not covered by [camera], kept [gap] clear of it. */
        private fun freeRun(camera: Pair<Int, Int>?, length: Int, gap: Int): Pair<Int, Int> {
            if (camera == null) return 0 to length
            val before = camera.first - gap
            val after = length - (camera.second + gap)
            return if (after >= before) (camera.second + gap) to length else 0 to before
        }

        // ---- stream -----------------------------------------------------------------

        fun restartStream() {
            stopStream()
            startStreamIfReady()
        }

        private fun startStreamIfReady() {
            if (streaming || !surfaceReady || !innerReady || content.isEmpty || sourceWidth <= 0) return
            val surface = surfaceView.holder.surface ?: return
            val w = content.width()
            val h = content.height()
            streaming = true
            scope.launch {
                val engine = withContext(MirrorSession.worker) {
                    Privileged.with { service ->
                        val dpi = service.getEffectiveDensity(Displays.MAIN_ID).takeIf { it > 0 } ?: 480
                        if (service.startMirror(surface, Displays.MAIN_ID, w, h, dpi)) service.mirrorEngine else null
                    }
                }
                streaming = engine != null
                events.onStreamChanged(engine != null, engine ?: "none")
                Log.i(TAG, "stream ${engine ?: "FAILED"} at ${w}x$h for source ${sourceWidth}x$sourceHeight")
            }
        }

        fun stopStream() {
            if (!streaming) return
            streaming = false
            scope.launch(MirrorSession.worker) { Privileged.with { it.stopMirror() } }
            events.onStreamChanged(false, "none")
        }

        // ---- input ------------------------------------------------------------------

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (menuOpen) {
                if (event.actionMasked == MotionEvent.ACTION_UP) onMenuTap(event.x, event.y)
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN && band.contains(event.x.toInt(), event.y.toInt())) {
                trackingButtons = true
            }
            if (trackingButtons) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_UP -> {
                        trackingButtons = false
                        onButtonTap(event.x, event.y)
                    }

                    MotionEvent.ACTION_CANCEL -> trackingButtons = false
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                forwarding = streaming && content.contains(event.x.toInt(), event.y.toInt())
            }
            if (forwarding) forward(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                forwarding = false
            }
            return true
        }

        /** Window coordinates to inner-display pixels, as a whole event (all pointers). */
        private fun forward(event: MotionEvent) {
            val copy = MotionEvent.obtain(event)
            copy.transform(
                Matrix().apply {
                    setTranslate(-content.left.toFloat(), -content.top.toFloat())
                    postScale(sourceWidth.toFloat() / content.width(), sourceHeight.toFloat() / content.height())
                },
            )
            Privileged.with { it.injectMotionEvent(copy, Displays.MAIN_ID) }
            copy.recycle()
        }

        private fun sendKey(keyCode: Int) {
            scope.launch(MirrorSession.worker) { Privileged.with { it.injectKey(keyCode, Displays.MAIN_ID) } }
        }

        private fun onButtonTap(x: Float, y: Float) {
            when (buttons.indexOfFirst { it.contains(x, y) }) {
                0 -> sendKey(KeyEvent.KEYCODE_APP_SWITCH)
                1 -> sendKey(KeyEvent.KEYCODE_HOME)
                2 -> sendKey(KeyEvent.KEYCODE_BACK)
                3 -> {
                    menuOpen = true
                    invalidate()
                }
            }
        }

        private fun onMenuTap(x: Float, y: Float) {
            val row = if (menuRect.contains(x, y)) menuRows.indexOfFirst { it.contains(x, y) } else -1
            menuOpen = false
            invalidate()
            when (row) {
                0 -> events.onShapeToggleRequested()
                1 -> events.onStopRequested()
                // Close, or a tap outside the panel, just closes the menu.
            }
        }

        // ---- drawing ----------------------------------------------------------------

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            drawButtons(canvas)
            if (menuOpen) drawMenu(canvas)
        }

        private fun drawButtons(canvas: Canvas) {
            val s = min(buttons[0].height(), buttons[0].width()) * 0.27f
            buttons[0].let { r ->
                // Recents: three bars, as on One UI's navigation bar.
                for (i in -1..1) {
                    canvas.drawLine(r.centerX() + i * s * 0.7f, r.centerY() - s, r.centerX() + i * s * 0.7f, r.centerY() + s, iconPaint)
                }
            }
            buttons[1].let { r ->
                canvas.drawRoundRect(r.centerX() - s, r.centerY() - s, r.centerX() + s, r.centerY() + s, s * 0.6f, s * 0.6f, iconPaint)
            }
            buttons[2].let { r ->
                canvas.drawLine(r.centerX() + s * 0.4f, r.centerY() - s, r.centerX() - s * 0.5f, r.centerY(), iconPaint)
                canvas.drawLine(r.centerX() - s * 0.5f, r.centerY(), r.centerX() + s * 0.4f, r.centerY() + s, iconPaint)
            }
            buttons[3].let { r ->
                for (i in -1..1) canvas.drawCircle(r.centerX() + i * s * 0.9f, r.centerY(), 2.2f * density, dotPaint)
            }
        }

        private fun drawMenu(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
            val rowH = 46 * density
            val w = min(width * 0.8f, 280 * density)
            val h = rowH * menuRows.size + 16 * density
            menuRect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            canvas.drawRoundRect(menuRect, 22 * density, 22 * density, panelPaint)

            val shape = MirrorSession.state.value.shape
            val labels = arrayOf(
                "Fit: ${shape.label}",
                "Stop mirroring",
                "Close",
            )
            labels.forEachIndexed { i, label ->
                val top = menuRect.top + 8 * density + i * rowH
                menuRows[i].set(menuRect.left, top, menuRect.right, top + rowH)
                val paint = when (i) {
                    1 -> dangerPaint
                    2 -> subtlePaint
                    else -> textPaint
                }
                canvas.drawText(label, menuRect.left + 20 * density, top + rowH / 2 + paint.textSize / 3, paint)
            }
        }

        private fun even(value: Float) = (value.toInt().coerceAtLeast(2) / 2) * 2
    }

    private companion object {
        const val TAG = "CoverDeck/Mirror"
    }
}
