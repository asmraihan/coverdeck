package com.raihan.coverdeck.mirror

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
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
        fun onShapeSelected(shape: MirrorSession.Shape)
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

        /** The strip button under the finger, or -1. Drawn pressed; acts on release. */
        private var pressedButton = -1

        /** 0 closed, 1 fully open; animated both ways. */
        private var menuProgress = 0f
        private var menuAnimator: ValueAnimator? = null

        /** What a finger went down on inside the menu: 0 or 1 a fit option, 2 stop, -1 nothing. */
        private var menuPressed = -1

        private val buttons = arrayOf(RectF(), RectF(), RectF(), RectF())
        private val menuRect = RectF()
        private val segmentTrack = RectF()
        private val segments = arrayOf(RectF(), RectF())
        private val stopRect = RectF()
        private val scratch = RectF()

        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT }
        private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 255, 255, 255) }

        private val scrimPaint = Paint().apply { color = Color.BLACK }
        private val menuPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x17, 0x1E, 0x2A)
            setShadowLayer(20 * density, 0f, 8 * density, Color.argb(140, 0, 0, 0))
        }
        private val menuBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(28, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = density
        }
        private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(20, 255, 255, 255) }
        private val statusDot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(16, 255, 255, 255) }
        private val segmentPressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(22, 255, 255, 255) }
        private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(50, Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT))
        }
        private val selectedBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, Color.red(ACCENT), Color.green(ACCENT), Color.blue(ACCENT))
            style = Paint.Style.STROKE
            strokeWidth = density
        }
        private val stopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(34, Color.red(DANGER), Color.green(DANGER), Color.blue(DANGER))
        }
        private val stopPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(64, Color.red(DANGER), Color.green(DANGER), Color.blue(DANGER))
        }
        private val glyphStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * density
            strokeJoin = Paint.Join.ROUND
        }
        private val glyphFill = Paint(Paint.ANTI_ALIAS_FLAG)

        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT
            textSize = 15f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SUBTLE
            textSize = 12f * density
        }
        private val optionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SUBTLE
            textSize = 12.5f * density
            textAlign = Paint.Align.CENTER
        }
        private val optionSelectedPaint = Paint(optionPaint).apply {
            color = ACCENT_BRIGHT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val stopTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DANGER
            textSize = 14f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

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
                onMenuTouch(event)
                return true
            }
            val x = event.x
            val y = event.y
            if (event.actionMasked == MotionEvent.ACTION_DOWN && band.contains(x.toInt(), y.toInt())) {
                trackingButtons = true
                pressedButton = buttons.indexOfFirst { it.contains(x, y) }
                if (pressedButton >= 0) {
                    // On press, like One UI's own navigation keys.
                    haptic()
                    invalidate()
                }
            }
            if (trackingButtons) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE ->
                        if (pressedButton >= 0 && !buttons[pressedButton].contains(x, y)) {
                            pressedButton = -1
                            invalidate()
                        }

                    MotionEvent.ACTION_UP -> {
                        val index = pressedButton
                        trackingButtons = false
                        pressedButton = -1
                        invalidate()
                        if (index >= 0) onButton(index)
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        trackingButtons = false
                        pressedButton = -1
                        invalidate()
                    }
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                forwarding = streaming && content.contains(x.toInt(), y.toInt())
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

        /** Follows the system's touch-feedback setting, as the real navigation bar does. */
        private fun haptic(type: Int = HapticFeedbackConstants.VIRTUAL_KEY) {
            performHapticFeedback(type)
        }

        private fun onButton(index: Int) {
            when (index) {
                0 -> sendKey(KeyEvent.KEYCODE_APP_SWITCH)
                1 -> sendKey(KeyEvent.KEYCODE_HOME)
                2 -> sendKey(KeyEvent.KEYCODE_BACK)
                3 -> openMenu()
            }
        }

        // ---- menu -------------------------------------------------------------------

        private fun openMenu() {
            menuOpen = true
            menuPressed = -1
            animateMenu(1f)
        }

        private fun closeMenu() {
            menuPressed = -1
            animateMenu(0f) { menuOpen = false }
        }

        private fun animateMenu(target: Float, onEnd: (() -> Unit)? = null) {
            menuAnimator?.cancel()
            menuAnimator = ValueAnimator.ofFloat(menuProgress, target).apply {
                duration = if (target > menuProgress) 210L else 150L
                interpolator = DecelerateInterpolator(2f)
                addUpdateListener {
                    menuProgress = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (!cancelled) onEnd?.invoke()
                        invalidate()
                    }
                })
                start()
            }
        }

        override fun onDetachedFromWindow() {
            menuAnimator?.cancel()
            super.onDetachedFromWindow()
        }

        private fun menuTarget(x: Float, y: Float): Int = when {
            segments[0].contains(x, y) -> 0
            segments[1].contains(x, y) -> 1
            stopRect.contains(x, y) -> 2
            else -> -1
        }

        private fun onMenuTouch(event: MotionEvent) {
            val x = event.x
            val y = event.y
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    menuPressed = menuTarget(x, y)
                    invalidate()
                }

                MotionEvent.ACTION_MOVE ->
                    if (menuPressed >= 0 && menuTarget(x, y) != menuPressed) {
                        menuPressed = -1
                        invalidate()
                    }

                MotionEvent.ACTION_UP -> {
                    val pressed = menuPressed
                    menuPressed = -1
                    when {
                        // A tap outside the panel closes it.
                        !menuRect.contains(x, y) -> closeMenu()
                        pressed < 0 || menuTarget(x, y) != pressed -> invalidate()
                        pressed == 2 -> {
                            haptic(HapticFeedbackConstants.CONFIRM)
                            menuAnimator?.cancel()
                            menuOpen = false
                            menuProgress = 0f
                            invalidate()
                            events.onStopRequested()
                        }

                        else -> chooseShape(if (pressed == 0) MirrorSession.Shape.COVER else MirrorSession.Shape.ORIGINAL)
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    menuPressed = -1
                    invalidate()
                }
            }
        }

        /** Shows the new choice highlighted for a moment, then gets out of the way. */
        private fun chooseShape(shape: MirrorSession.Shape) {
            if (MirrorSession.state.value.shape == shape) {
                closeMenu()
                return
            }
            haptic(HapticFeedbackConstants.KEYBOARD_TAP)
            events.onShapeSelected(shape)
            invalidate()
            postDelayed({ if (menuOpen) closeMenu() }, 260)
        }

        // ---- drawing ----------------------------------------------------------------

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            drawButtons(canvas)
            if (menuOpen) drawMenu(canvas)
        }

        private fun drawButtons(canvas: Canvas) {
            if (pressedButton >= 0) {
                val r = buttons[pressedButton]
                canvas.drawCircle(r.centerX(), r.centerY(), min(r.width(), r.height()) * 0.44f, pressPaint)
            }
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

        /**
         * Places the menu next to the ⋯ button, towards the middle of the screen, so it opens
         * from where it was tapped whichever edge the camera strip is on.
         */
        private fun layoutMenu() {
            val margin = 8 * density
            val w = min(width - 2 * margin, MENU_WIDTH * density)
            val h = MENU_HEIGHT * density
            val anchor = buttons[3]
            val left: Float
            val top: Float
            if (band.height() > band.width()) {
                left = if (anchor.centerX() < width / 2f) anchor.right + margin else anchor.left - margin - w
                top = anchor.centerY() - h / 2
            } else {
                left = anchor.right - w
                top = if (anchor.centerY() < height / 2f) anchor.bottom + margin else anchor.top - margin - h
            }
            val l = left.coerceIn(margin, (width - margin - w).coerceAtLeast(margin))
            val t = top.coerceIn(margin, (height - margin - h).coerceAtLeast(margin))
            menuRect.set(l, t, l + w, t + h)

            val pad = MENU_PAD * density
            val trackTop = t + pad + (MENU_HEADER + MENU_GAP) * density
            segmentTrack.set(l + pad, trackTop, l + w - pad, trackTop + MENU_TRACK * density)
            val inset = 4 * density
            segments[0].set(segmentTrack.left + inset, segmentTrack.top + inset, segmentTrack.centerX() - inset / 2, segmentTrack.bottom - inset)
            segments[1].set(segmentTrack.centerX() + inset / 2, segmentTrack.top + inset, segmentTrack.right - inset, segmentTrack.bottom - inset)
            val stopTop = segmentTrack.bottom + MENU_GAP * density
            stopRect.set(l + pad, stopTop, l + w - pad, stopTop + MENU_STOP * density)
        }

        private fun drawMenu(canvas: Canvas) {
            layoutMenu()
            val progress = menuProgress
            scrimPaint.alpha = (110 * progress).toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

            // Fade and grow out of the ⋯ button.
            val anchor = buttons[3]
            val pivotX = anchor.centerX().coerceIn(menuRect.left, menuRect.right)
            val pivotY = anchor.centerY().coerceIn(menuRect.top, menuRect.bottom)
            val scale = 0.9f + 0.1f * progress
            scratch.set(menuRect)
            scratch.inset(-32 * density, -32 * density)
            val saved = canvas.saveLayerAlpha(scratch, (255 * progress).toInt())
            canvas.scale(scale, scale, pivotX, pivotY)

            val radius = 24 * density
            canvas.drawRoundRect(menuRect, radius, radius, menuPaint)
            canvas.drawRoundRect(menuRect, radius, radius, menuBorder)

            val pad = MENU_PAD * density
            val state = MirrorSession.state.value

            // Header: title and a live/paused chip.
            val headerMid = menuRect.top + pad + MENU_HEADER * density / 2
            canvas.drawText("Mirroring", menuRect.left + pad + 4 * density, baseline(headerMid, titlePaint), titlePaint)
            val status = if (streaming) "Live" else "Paused"
            val chipH = 24 * density
            val chipW = statusPaint.measureText(status) + 30 * density
            scratch.set(menuRect.right - pad - chipW, headerMid - chipH / 2, menuRect.right - pad, headerMid + chipH / 2)
            canvas.drawRoundRect(scratch, chipH / 2, chipH / 2, chipPaint)
            statusDot.color = if (streaming) LIVE else PAUSED
            canvas.drawCircle(scratch.left + 12 * density, headerMid, 3.5f * density, statusDot)
            canvas.drawText(status, scratch.left + 21 * density, baseline(headerMid, statusPaint), statusPaint)

            // Fit: two options, the current one highlighted.
            canvas.drawRoundRect(segmentTrack, 18 * density, 18 * density, trackPaint)
            val options = arrayOf(MirrorSession.Shape.COVER, MirrorSession.Shape.ORIGINAL)
            options.forEachIndexed { i, option ->
                val r = segments[i]
                val selected = option == state.shape
                val corner = 14 * density
                when {
                    selected -> {
                        canvas.drawRoundRect(r, corner, corner, selectedPaint)
                        canvas.drawRoundRect(r, corner, corner, selectedBorder)
                    }

                    menuPressed == i -> canvas.drawRoundRect(r, corner, corner, segmentPressPaint)
                }
                drawShapeGlyph(canvas, option, r.centerX(), r.top + r.height() * 0.36f, selected)
                canvas.drawText(option.label, r.centerX(), r.top + r.height() * 0.8f, if (selected) optionSelectedPaint else optionPaint)
            }

            // Stop.
            val stopCorner = 16 * density
            canvas.drawRoundRect(stopRect, stopCorner, stopCorner, if (menuPressed == 2) stopPressedPaint else stopPaint)
            val label = "Stop mirroring"
            val icon = 11 * density
            val gap = 9 * density
            val startX = stopRect.centerX() - (icon + gap + stopTextPaint.measureText(label)) / 2
            glyphFill.color = DANGER
            scratch.set(startX, stopRect.centerY() - icon / 2, startX + icon, stopRect.centerY() + icon / 2)
            canvas.drawRoundRect(scratch, 2.5f * density, 2.5f * density, glyphFill)
            canvas.drawText(label, startX + icon + gap, baseline(stopRect.centerY(), stopTextPaint), stopTextPaint)

            canvas.restoreToCount(saved)
        }

        /** A cover-shaped frame, either filled (fit to cover) or with a narrow strip (original). */
        private fun drawShapeGlyph(canvas: Canvas, shape: MirrorSession.Shape, cx: Float, cy: Float, selected: Boolean) {
            val color = if (selected) ACCENT_BRIGHT else SUBTLE
            glyphStroke.color = color
            val w = 22 * density
            val h = 18 * density
            scratch.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            canvas.drawRoundRect(scratch, 4 * density, 4 * density, glyphStroke)
            val inset = 3.5f * density
            if (shape == MirrorSession.Shape.COVER) {
                scratch.inset(inset, inset)
            } else {
                scratch.set(cx - 3.5f * density, cy - h / 2 + inset, cx + 3.5f * density, cy + h / 2 - inset)
            }
            glyphFill.color = color
            glyphFill.alpha = if (selected) 220 else 140
            canvas.drawRoundRect(scratch, 2 * density, 2 * density, glyphFill)
        }

        private fun baseline(centerY: Float, paint: Paint) = centerY - (paint.descent() + paint.ascent()) / 2

        private fun even(value: Float) = (value.toInt().coerceAtLeast(2) / 2) * 2
    }

    private companion object {
        const val TAG = "CoverDeck/Mirror"

        // CoverDeck's palette (ui/theme), as plain ints for canvas drawing.
        val TEXT = Color.rgb(0xE8, 0xED, 0xF5)
        val SUBTLE = Color.rgb(0x95, 0xA2, 0xB5)
        val ACCENT = Color.rgb(0x5B, 0x9D, 0xFF)
        val ACCENT_BRIGHT = Color.rgb(0x8F, 0xBC, 0xFF)
        val DANGER = Color.rgb(0xFF, 0x6B, 0x6B)
        val LIVE = Color.rgb(0x4A, 0xDE, 0x80)
        val PAUSED = Color.rgb(0xF5, 0xB7, 0x4A)

        // Menu geometry, dp.
        const val MENU_WIDTH = 264f
        const val MENU_PAD = 14f
        const val MENU_HEADER = 34f
        const val MENU_GAP = 10f
        const val MENU_TRACK = 64f
        const val MENU_STOP = 44f
        val MENU_HEIGHT = MENU_PAD + MENU_HEADER + MENU_GAP + MENU_TRACK + MENU_GAP + MENU_STOP + MENU_PAD
    }
}
