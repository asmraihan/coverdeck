package com.raihan.coverdeck.nav

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Display
import com.raihan.coverdeck.INavGestureListener
import com.raihan.coverdeck.core.Displays
import com.raihan.coverdeck.feature.RotationController
import com.raihan.coverdeck.overlay.CoverHud
import com.raihan.coverdeck.privileged.Privileged
import com.raihan.coverdeck.recents.RecentsPanel
import java.util.concurrent.Executors

/**
 * Hosted by CoverDeckService while either cover navigation gesture is switched on. Keeps
 * the helper's log watcher running only while the phone is folded with the cover lit, and
 * turns its signals into actions:
 *
 *  - Hold Home: recents opens; hold again while open and it closes.
 *  - Tap Home while recents is open: recents closes and the cover goes home.
 *  - Hold Back: cover rotation switches between Auto and locked at 0°, with a
 *    confirmation on the cover.
 *
 * SystemUI logs a Home injection for the press, for the long-press repeat, and for the
 * release. The release after a long press must not close the panel that just opened,
 * so it is skipped.
 */
class NavGestureWatcher(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)

    /** Rotation changes are blocking binder calls; one at a time, in order. */
    private val rotationWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "coverdeck-backrotate").apply { isDaemon = true }
    }

    private var running = false
    private var watching = false

    private var ignoreNextHomeKey = false
    private var closedByHomeAt = 0L
    private var lastBackHoldAt = 0L

    private val listener = object : INavGestureListener.Stub() {
        override fun onHomeLongPress() {
            handler.post { handleHomeLongPress() }
        }

        override fun onHomeKey() {
            handler.post { handleHomeKey() }
        }

        override fun onBackLongPress() {
            handler.post { handleBackLongPress() }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = sync()
        override fun onDisplayRemoved(displayId: Int) = sync()
        override fun onDisplayChanged(displayId: Int) = sync()
    }

    fun start() {
        if (running) return
        running = true
        displayManager?.registerDisplayListener(displayListener, handler)
        sync()
    }

    fun stop() {
        if (!running) return
        running = false
        displayManager?.unregisterDisplayListener(displayListener)
        stopWatching()
        CoverHud.hide()
    }

    /**
     * Called when the Shizuku link (re)connects: the helper may be a fresh process with
     * no reader. Re-registering is safe because the helper ignores a repeat start from a
     * listener it is already serving.
     */
    fun reconnect() {
        watching = false
        sync()
    }

    private fun sync() {
        if (running && folded()) {
            if (!watching) startWatching()
        } else if (watching) {
            stopWatching()
        }
    }

    /** The only situation the gestures exist in: cover on, inner screen off. */
    private fun folded(): Boolean {
        val dm = displayManager ?: return false
        val coverOn = dm.getDisplay(Displays.cover(appContext).displayId)?.state == Display.STATE_ON
        val innerOn = dm.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON
        return coverOn && !innerOn
    }

    private fun startWatching() {
        watching = Privileged.with { it.startNavWatcher(listener); true } ?: false
    }

    private fun stopWatching() {
        Privileged.with { it.stopNavWatcher() }
        watching = false
    }

    // ---- Home: recents ------------------------------------------------------------

    private fun handleHomeKey() {
        if (ignoreNextHomeKey) {
            ignoreNextHomeKey = false
            return
        }
        if (RecentsPanel.isShowing) {
            RecentsPanel.hide()
            closedByHomeAt = SystemClock.uptimeMillis()
        }
    }

    private fun handleHomeLongPress() {
        if (!HomeLongPress.enabled.value) return
        // The release of this same hold is still to come; it must not act as a tap.
        ignoreNextHomeKey = true
        // If the press that started this hold just closed recents, the user was holding
        // Home to close it: leave it closed rather than reopening it.
        if (SystemClock.uptimeMillis() - closedByHomeAt < REOPEN_GUARD_MS) return
        buzz()
        RecentsPanel.show(appContext)
    }

    // ---- Back: rotation -------------------------------------------------------------

    private fun handleBackLongPress() {
        if (!BackLongPress.enabled.value || Privileged.status.value !is Privileged.Status.Ready) return
        val now = SystemClock.uptimeMillis()
        if (now - lastBackHoldAt < BACK_REPEAT_GUARD_MS) return
        lastBackHoldAt = now

        val next = BackLongPress.nextCoverMode()
        buzz()
        // Confirm straight away; the rotation itself follows a moment later.
        if (next == RotationController.Mode.AUTO) {
            CoverHud.show(appContext, CoverHud.Icon.AUTO_ROTATE, "Auto-rotate on", "Hold Back to lock")
        } else {
            CoverHud.show(appContext, CoverHud.Icon.LOCKED, "Rotation locked at 0°", "Hold Back for auto")
        }
        rotationWorker.execute { BackLongPress.applyCoverMode(appContext, next) }
    }

    private fun buzz() {
        runCatching {
            appContext.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        }
    }

    private companion object {
        const val REOPEN_GUARD_MS = 800L
        const val BACK_REPEAT_GUARD_MS = 1_000L
    }
}
