package com.raihan.coverdeck.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.raihan.coverdeck.core.Panel
import com.raihan.coverdeck.feature.MirrorController

/**
 * The window that shows the inner display on the cover screen.
 *
 * It is a plain WindowManager overlay rather than a Presentation or an Activity for one
 * specific reason: forcing the device state to OPENED (which is what keeps the inner
 * panel rendering) makes the system re-evaluate where activities belong, and an
 * activity would be yanked over to the inner screen. An overlay window pinned to a
 * display context stays where it is put.
 */
@SuppressLint("ClickableViewAccessibility")
class MirrorOverlay(
    private val windowContext: Context,
    private val controller: MirrorController,
    private val coverPanel: Panel,
    private val sourcePanel: Panel,
    private val onClosed: () -> Unit,
) {

    /** Whether touches drive the inner screen or pan the mirror. */
    private enum class TouchMode { CONTROL, PAN }

    private val windowManager = windowContext.getSystemService(WindowManager::class.java)

    private var root: FrameLayout? = null
    private var surfaceView: SurfaceView? = null
    private var statusLabel: TextView? = null

    private var touchMode = TouchMode.CONTROL
    private var fitMode = MirrorController.FitMode.FIT

    private var downTime = 0L
    private var panStartY = 0f
    private var panOriginY = 0f

    val isShowing: Boolean get() = root != null

    fun show(mode: MirrorController.FitMode) {
        if (root != null) return
        fitMode = mode

        val (vw, vh) = controller.virtualSize(sourcePanel, coverPanel, fitMode)

        val container = FrameLayout(windowContext).apply {
            setBackgroundColor(Color.BLACK)
        }

        val surface = SurfaceView(windowContext).apply {
            layoutParams = FrameLayout.LayoutParams(vw, vh, Gravity.CENTER_HORIZONTAL or Gravity.TOP)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    val started = controller.start(holder.surface, sourcePanel, coverPanel, fitMode)
                    statusLabel?.text = if (started) {
                        "Mirroring via ${controller.state.value.engine}"
                    } else {
                        "Mirror failed to start"
                    }
                }

                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) = Unit

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    controller.stop(alsoReleaseDeviceState = false)
                }
            })
            setOnTouchListener { view, event -> handleTouch(view, event) }
        }
        surfaceView = surface

        // Vertically centre in FIT; pin to the top in FILL so the status bar is the
        // first thing visible and the rest is reachable by panning.
        if (fitMode == MirrorController.FitMode.FIT) {
            (surface.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.CENTER
        }

        container.addView(surface)
        container.addView(buildChrome())

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        root = container
        runCatching { windowManager?.addView(container, params) }
            .onFailure { root = null; onClosed() }
    }

    /** A minimal bar: close, fit mode, and control-vs-pan. Deliberately small. */
    private fun buildChrome(): View {
        val bar = FrameLayout(windowContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(30),
                Gravity.TOP,
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(190, 9, 12, 18), Color.TRANSPARENT),
            )
        }

        statusLabel = TextView(windowContext).apply {
            text = "Starting mirror…"
            setTextColor(Color.argb(220, 149, 162, 181))
            textSize = 10f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL or Gravity.START,
            ).apply { leftMargin = dp(10) }
        }
        bar.addView(statusLabel)

        bar.addView(chromeButton("✕", Gravity.CENTER_VERTICAL or Gravity.END, dp(8)) { hide(); onClosed() })
        bar.addView(chromeButton("⇕", Gravity.CENTER_VERTICAL or Gravity.END, dp(40)) { toggleTouchMode() })
        return bar
    }

    private fun chromeButton(
        label: String,
        gravity: Int,
        marginEnd: Int,
        onClick: () -> Unit,
    ): TextView = TextView(windowContext).apply {
        text = label
        setTextColor(Color.argb(230, 232, 237, 245))
        textSize = 13f
        setPadding(dp(8), dp(3), dp(8), dp(3))
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            gravity,
        ).apply { rightMargin = marginEnd }
        setOnClickListener { onClick() }
    }

    private fun toggleTouchMode() {
        touchMode = if (touchMode == TouchMode.CONTROL) TouchMode.PAN else TouchMode.CONTROL
        statusLabel?.text = if (touchMode == TouchMode.CONTROL) {
            "Touch controls the inner screen"
        } else {
            "Drag to pan the mirror"
        }
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        if (touchMode == TouchMode.PAN) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    panStartY = event.rawY
                    panOriginY = view.translationY
                }

                MotionEvent.ACTION_MOVE -> {
                    val proposed = panOriginY + (event.rawY - panStartY)
                    // Never let the content be dragged past its own edges.
                    val slack = (view.height - coverPanel.heightPx).coerceAtLeast(0)
                    view.translationY = proposed.coerceIn(-slack.toFloat(), 0f)
                }
            }
            return true
        }

        if (!controller.state.value.touchEnabled) return false

        if (event.actionMasked == MotionEvent.ACTION_DOWN) downTime = event.eventTime
        val (sx, sy) = controller.mapToSource(
            event.x, event.y, view.width, view.height, sourcePanel,
        )
        controller.injectTouch(event.actionMasked, sx, sy, sourcePanel.displayId, downTime)
        return true
    }

    fun hide() {
        val current = root ?: return
        controller.stop(alsoReleaseDeviceState = true)
        runCatching { windowManager?.removeViewImmediate(current) }
        root = null
        surfaceView = null
        statusLabel = null
    }

    private fun dp(value: Int): Int =
        (value * windowContext.resources.displayMetrics.density).toInt()
}
