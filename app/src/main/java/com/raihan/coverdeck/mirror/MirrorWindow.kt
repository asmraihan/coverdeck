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
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
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
        fun onHideNavBarSelected(hide: Boolean)
        fun onInnerOffSelected(off: Boolean)
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
                    withContext(MirrorSession.worker) {
                        Privileged.with { it.setInnerDisplayAwake(true) }
                        MirrorSession.enforceInnerPanel()
                    }
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

    /** [cropBottom] source pixels at the bottom (the navigation bar) are kept out of view. */
    fun update(sourceWidth: Int, sourceHeight: Int, cropBottom: Int) {
        view?.setSource(sourceWidth, sourceHeight, cropBottom)
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
        private var cropBottom = 0

        /** The picture's full height on screen; [content] is its visible, uncropped part. */
        private var surfaceHeight = 0

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

        /** What a finger went down on inside the menu: a quick toggle 0-2, 3 stop, -1 nothing. */
        private var menuPressed = -1

        /** How "on" each quick toggle is drawn, 0..1, so a change fades rather than flips. */
        private val toggleProgress = FloatArray(TOGGLE_COUNT)
        private val toggleAnimators = arrayOfNulls<ValueAnimator>(TOGGLE_COUNT)

        private val buttons = arrayOf(RectF(), RectF(), RectF(), RectF())
        private val menuRect = RectF()
        private val toggleCells = Array(TOGGLE_COUNT) { RectF() }
        private val stopRect = RectF()
        private val scratch = RectF()
        private val path = Path()

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
        private val blackPaint = Paint().apply { color = Color.BLACK }
        private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PANEL
        }
        private val panelEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PANEL_EDGE
            style = Paint.Style.STROKE
            strokeWidth = density
        }
        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val statusDot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glyphStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val glyphFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stopFill = Paint(Paint.ANTI_ALIAS_FLAG)

        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT
            textSize = 16f * density
            typeface = Typeface.create(Typeface.DEFAULT, 700, false)
        }
        private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SUBTLE
            textSize = 11.5f * density
            typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT
            textSize = 10.5f * density
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        }
        private val stopTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DANGER
            textSize = 13f * density
            typeface = Typeface.create(Typeface.DEFAULT, 600, false)
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
                    if (w == content.width() && h == surfaceHeight) startStreamIfReady()
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    surfaceReady = false
                    stopStream()
                }
            })
        }

        fun setSource(width: Int, height: Int, crop: Int) {
            if (width == sourceWidth && height == sourceHeight && crop == cropBottom) return
            sourceWidth = width
            sourceHeight = height
            cropBottom = crop.coerceIn(0, height / 3)
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
            // Fit what is meant to be seen; a cropped navigation bar hangs below it, past the
            // screen edge or under the black drawn over it in dispatchDraw.
            val visibleHeight = sourceHeight - cropBottom
            val scale = min(area.width().toFloat() / sourceWidth, area.height().toFloat() / visibleHeight)
            val w = even(sourceWidth * scale)
            val h = even(visibleHeight * scale)
            val fullHeight = if (cropBottom > 0) even(sourceHeight * scale) else h
            val left = area.left + (area.width() - w) / 2
            val top = area.top + (area.height() - h) / 2
            val sizeChanged = w != content.width() || fullHeight != surfaceHeight
            content.set(left, top, left + w, top + h)
            surfaceHeight = fullHeight

            val lp = surfaceView.layoutParams as LayoutParams
            if (lp.width != w || lp.height != fullHeight || lp.leftMargin != left || lp.topMargin != top) {
                surfaceView.layoutParams = LayoutParams(w, fullHeight).apply {
                    leftMargin = left
                    topMargin = top
                }
                surfaceView.holder.setFixedSize(w, fullHeight)
                surfaceView.clipBounds = Rect(0, 0, w, h)
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
            val h = surfaceHeight
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
                    postScale(sourceWidth.toFloat() / content.width(), (sourceHeight - cropBottom).toFloat() / content.height())
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
        //
        // One UI's quick panel, in CoverDeck's colours: a floating rounded card with round
        // quick toggles that fill with the accent when on, and a stop button. Toggles apply
        // at once and leave the menu open, like the quick panel; a tap outside closes it.

        private fun openMenu() {
            menuOpen = true
            menuPressed = -1
            syncToggles(animate = false)
            animateMenu(1f, OPEN_MS, OPEN_EASE)
        }

        private fun closeMenu() {
            menuPressed = -1
            animateMenu(0f, CLOSE_MS, CLOSE_EASE) { menuOpen = false }
        }

        private fun animateMenu(target: Float, duration: Long, ease: PathInterpolator, onEnd: (() -> Unit)? = null) {
            menuAnimator?.cancel()
            menuAnimator = ValueAnimator.ofFloat(menuProgress, target).apply {
                this.duration = duration
                interpolator = ease
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
            toggleAnimators.forEach { it?.cancel() }
            super.onDetachedFromWindow()
        }

        private fun toggleOn(index: Int): Boolean {
            val state = MirrorSession.state.value
            return when (index) {
                0 -> state.shape == MirrorSession.Shape.COVER
                1 -> state.hideNavBar
                else -> state.innerOff
            }
        }

        /** Brings the drawn toggles in line with the session, e.g. after a change on the page. */
        private fun syncToggles(animate: Boolean) {
            for (i in 0 until TOGGLE_COUNT) {
                val target = if (toggleOn(i)) 1f else 0f
                if (toggleProgress[i] == target) continue
                if (!animate) {
                    toggleAnimators[i]?.cancel()
                    toggleProgress[i] = target
                    continue
                }
                if (toggleAnimators[i]?.isRunning == true) continue
                toggleAnimators[i] = ValueAnimator.ofFloat(toggleProgress[i], target).apply {
                    duration = TOGGLE_MS
                    interpolator = OPEN_EASE
                    addUpdateListener {
                        toggleProgress[i] = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
        }

        private fun menuTarget(x: Float, y: Float): Int {
            toggleCells.forEachIndexed { i, cell -> if (cell.contains(x, y)) return i }
            return if (stopRect.contains(x, y)) STOP else -1
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
                        !menuRect.contains(x, y) -> closeMenu()
                        pressed < 0 || menuTarget(x, y) != pressed -> invalidate()
                        pressed == STOP -> {
                            haptic(HapticFeedbackConstants.CONFIRM)
                            menuAnimator?.cancel()
                            menuOpen = false
                            menuProgress = 0f
                            invalidate()
                            events.onStopRequested()
                        }

                        else -> flipToggle(pressed)
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    menuPressed = -1
                    invalidate()
                }
            }
        }

        private fun flipToggle(index: Int) {
            haptic(HapticFeedbackConstants.KEYBOARD_TAP)
            val on = toggleOn(index)
            when (index) {
                0 -> events.onShapeSelected(if (on) MirrorSession.Shape.ORIGINAL else MirrorSession.Shape.COVER)
                1 -> events.onHideNavBarSelected(!on)
                else -> events.onInnerOffSelected(!on)
            }
            syncToggles(animate = true)
            invalidate()
        }

        // ---- drawing ----------------------------------------------------------------

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if (cropBottom > 0 && surfaceHeight > content.height()) {
                canvas.drawRect(
                    content.left.toFloat(), content.bottom.toFloat(),
                    content.right.toFloat(), (content.top + surfaceHeight).toFloat(), blackPaint,
                )
            }
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
            val togglesTop = t + pad + (MENU_HEADER + MENU_HEADER_GAP) * density
            val cellWidth = (w - 2 * pad) / TOGGLE_COUNT
            toggleCells.forEachIndexed { i, cell ->
                cell.set(l + pad + i * cellWidth, togglesTop, l + pad + (i + 1) * cellWidth, togglesTop + MENU_TOGGLES * density)
            }
            val stopTop = togglesTop + (MENU_TOGGLES + MENU_GAP) * density
            stopRect.set(l + pad, stopTop, l + w - pad, stopTop + MENU_STOP * density)
        }

        private fun drawMenu(canvas: Canvas) {
            layoutMenu()
            syncToggles(animate = true)
            val progress = menuProgress
            scrimPaint.alpha = (70 * progress).toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

            // Grow out of the ⋯ button, as One UI's popups do.
            val anchor = buttons[3]
            val pivotX = anchor.centerX().coerceIn(menuRect.left, menuRect.right)
            val pivotY = anchor.centerY().coerceIn(menuRect.top, menuRect.bottom)
            val scale = 0.86f + 0.14f * progress
            scratch.set(menuRect)
            scratch.inset(-40 * density, -40 * density)
            val saved = canvas.saveLayerAlpha(scratch, (255 * progress).toInt())
            canvas.scale(scale, scale, pivotX, pivotY)

            val radius = MENU_RADIUS * density
            canvas.drawRoundRect(menuRect, radius, radius, panelPaint)
            canvas.drawRoundRect(menuRect, radius, radius, panelEdge)

            val pad = MENU_PAD * density
            val textLeft = menuRect.left + pad + 4 * density

            // Header: a large title and a quiet status line.
            val titleBaseline = menuRect.top + pad - titlePaint.ascent()
            canvas.drawText("Mirroring", textLeft, titleBaseline, titlePaint)
            val statusCenter = titleBaseline + titlePaint.descent() + 10 * density
            statusDot.color = if (streaming) LIVE else PAUSED
            val dotRadius = 3f * density
            canvas.drawCircle(textLeft + dotRadius, statusCenter, dotRadius, statusDot)
            canvas.drawText(
                if (streaming) "Live" else "Paused",
                textLeft + dotRadius * 2 + 6 * density, baseline(statusCenter, statusPaint), statusPaint,
            )

            // Quick toggles.
            val circleRadius = TOGGLE_CIRCLE * density / 2
            toggleCells.forEachIndexed { i, cell ->
                val on = toggleProgress[i]
                val pressed = menuPressed == i
                val cx = cell.centerX()
                val cy = cell.top + circleRadius
                val tileColor = ColorUtils.blendARGB(TOGGLE_OFF, TOGGLE_ON, on)
                circlePaint.color = tileColor
                canvas.drawCircle(cx, cy, circleRadius * (if (pressed) 0.92f else 1f), circlePaint)
                if (pressed) canvas.drawCircle(cx, cy, circleRadius * 0.92f, pressPaint)
                drawToggleIcon(canvas, i, cx, cy, ColorUtils.blendARGB(ICON_OFF, Color.WHITE, on))
                drawLabel(canvas, TOGGLE_LABELS[i], cx, cy + circleRadius + 6 * density, cell.width() - 8 * density)
            }

            // Stop: One UI's destructive button, a neutral pill with red text.
            val stopRadius = stopRect.height() / 2
            stopFill.color = if (menuPressed == STOP) STOP_PRESSED else TOGGLE_OFF
            canvas.drawRoundRect(stopRect, stopRadius, stopRadius, stopFill)
            val label = "Stop mirroring"
            val icon = 9 * density
            val gap = 8 * density
            val startX = stopRect.centerX() - (icon + gap + stopTextPaint.measureText(label)) / 2
            glyphFill.color = DANGER
            scratch.set(startX, stopRect.centerY() - icon / 2, startX + icon, stopRect.centerY() + icon / 2)
            canvas.drawRoundRect(scratch, 2f * density, 2f * density, glyphFill)
            canvas.drawText(label, startX + icon + gap, baseline(stopRect.centerY(), stopTextPaint), stopTextPaint)

            canvas.restoreToCount(saved)
        }

        /** Centred, wrapped to two lines at most, as quick panel labels are. */
        private fun drawLabel(canvas: Canvas, text: String, cx: Float, top: Float, maxWidth: Float) {
            val lines = mutableListOf<String>()
            var line = ""
            for (word in text.split(" ")) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (labelPaint.measureText(candidate) <= maxWidth || line.isEmpty()) {
                    line = candidate
                } else {
                    lines += line
                    line = word
                }
            }
            if (line.isNotEmpty()) lines += line
            val lineHeight = labelPaint.fontSpacing
            lines.take(2).forEachIndexed { i, l ->
                canvas.drawText(l, cx, top - labelPaint.ascent() + i * lineHeight, labelPaint)
            }
        }

        private fun drawToggleIcon(canvas: Canvas, index: Int, cx: Float, cy: Float, color: Int) {
            val stroke = 1.7f * density
            glyphStroke.color = color
            glyphStroke.strokeWidth = stroke
            when (index) {
                0 -> {
                    // Fit to cover: the four corners of a frame.
                    val hx = 8.5f * density
                    val hy = 7f * density
                    val len = 4f * density
                    path.reset()
                    for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
                        val x = cx + sx * hx
                        val y = cy + sy * hy
                        path.moveTo(x, y - sy * len)
                        path.lineTo(x, y)
                        path.lineTo(x - sx * len, y)
                    }
                    canvas.drawPath(path, glyphStroke)
                }

                1 -> {
                    // Hide nav bar: a phone whose bottom bar is dotted away.
                    val hw = 6f * density
                    val hh = 8.5f * density
                    scratch.set(cx - hw, cy - hh, cx + hw, cy + hh)
                    canvas.drawRoundRect(scratch, 2.5f * density, 2.5f * density, glyphStroke)
                    val barY = cy + hh - 3.6f * density
                    glyphFill.color = color
                    for (k in -1..1) canvas.drawCircle(cx + k * 2.6f * density, barY, 0.9f * density, glyphFill)
                }

                else -> {
                    // Inner screen off: a phone with a line through it.
                    val hw = 6f * density
                    val hh = 8.5f * density
                    val x0 = cx - hw - 2.4f * density
                    val y0 = cy - hh - 0.8f * density
                    val x1 = cx + hw + 2.4f * density
                    val y1 = cy + hh + 0.8f * density
                    val layer = canvas.saveLayer(x0 - 4 * density, y0 - 4 * density, x1 + 4 * density, y1 + 4 * density, null)
                    scratch.set(cx - hw, cy - hh, cx + hw, cy + hh)
                    canvas.drawRoundRect(scratch, 2.5f * density, 2.5f * density, glyphStroke)
                    glyphStroke.xfermode = CLEAR
                    glyphStroke.strokeWidth = 4.2f * density
                    canvas.drawLine(x0, y0, x1, y1, glyphStroke)
                    glyphStroke.xfermode = null
                    glyphStroke.strokeWidth = stroke
                    canvas.drawLine(x0, y0, x1, y1, glyphStroke)
                    canvas.restoreToCount(layer)
                }
            }
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
        val DANGER = Color.rgb(0xFF, 0x6B, 0x6B)
        val LIVE = Color.rgb(0x4A, 0xDE, 0x80)
        val PAUSED = Color.rgb(0xF5, 0xB7, 0x4A)

        // Menu surfaces, after One UI's quick panel in dark mode: a see-through dark sheet over
        // whatever is behind it, frosted white tiles, and CoverDeck's accent for what is on.
        // (Real blur isn't available: the picture is a separate SurfaceView layer, and this
        // phone has cross-window blur switched off.)
        val PANEL = Color.argb(150, 0x06, 0x09, 0x10)
        val PANEL_EDGE = Color.argb(38, 255, 255, 255)
        val TOGGLE_OFF = Color.argb(44, 255, 255, 255)
        val TOGGLE_ON = Color.argb(215, 0x5B, 0x9D, 0xFF)
        val STOP_PRESSED = Color.argb(80, 255, 255, 255)
        val ICON_OFF = Color.rgb(0xEE, 0xF2, 0xF8)

        // Menu geometry, dp.
        const val MENU_WIDTH = 232f
        const val MENU_RADIUS = 24f
        const val MENU_PAD = 14f
        const val MENU_HEADER = 36f
        const val MENU_HEADER_GAP = 10f
        const val MENU_TOGGLES = 76f
        const val MENU_GAP = 10f
        const val MENU_STOP = 40f
        const val TOGGLE_CIRCLE = 44f
        val MENU_HEIGHT = MENU_PAD + MENU_HEADER + MENU_HEADER_GAP + MENU_TOGGLES + MENU_GAP + MENU_STOP + MENU_PAD

        const val TOGGLE_COUNT = 3
        const val STOP = 3
        val TOGGLE_LABELS = arrayOf("Fit to cover", "Hide nav bar", "Inner screen off")
        val CLEAR = PorterDuffXfermode(PorterDuff.Mode.CLEAR)

        // Motion, close to One UI's easing: quick to start, long gentle settle.
        const val OPEN_MS = 280L
        const val CLOSE_MS = 170L
        const val TOGGLE_MS = 180L
        val OPEN_EASE = PathInterpolator(0.22f, 0.25f, 0f, 1f)
        val CLOSE_EASE = PathInterpolator(0.33f, 0f, 0.67f, 1f)
    }
}
