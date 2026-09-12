package com.raihan.coverdeck.nav

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.Display
import com.raihan.coverdeck.INavGestureListener
import com.raihan.coverdeck.core.Displays
import com.raihan.coverdeck.recents.RecentsPanel
import com.raihan.coverdeck.privileged.Privileged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The "long-press the cover's Home button for recents" switch.
 *
 * Detection lives in the Shizuku helper ([com.raihan.coverdeck.privileged.NavLogWatcher]);
 * see there for why an accessibility service cannot see this gesture on One UI 8.5.
 */
object HomeLongPress {

    private const val PREFS = "coverdeck_homelongpress"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LEGACY_A11Y_CLEANED = "legacy_a11y_cleaned"
    private const val LEGACY_A11Y_CLASS = "com.raihan.coverdeck.nav.CoverNavService"

    private var prefs: SharedPreferences? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _enabled.value = prefs?.getBoolean(KEY_ENABLED, false) ?: false
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs?.edit()?.putBoolean(KEY_ENABLED, on)?.apply()
    }

    /**
     * The previous build used an accessibility service that no longer exists. Its entry
     * stays in Settings.Secure and shows up as a broken "not working" service, so remove
     * CoverDeck's own entry, once, and nothing else.
     */
    fun cleanUpLegacyAccessibility(context: Context) {
        val p = prefs ?: return
        if (p.getBoolean(KEY_LEGACY_A11Y_CLEANED, false)) return
        val resolver = context.contentResolver
        val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val entries = current.split(':').filter { it.isNotBlank() }
        val kept = entries.filterNot {
            val cn = ComponentName.unflattenFromString(it)
            cn?.packageName == context.packageName && cn.className == LEGACY_A11Y_CLASS
        }
        if (kept.size == entries.size) {
            p.edit().putBoolean(KEY_LEGACY_A11Y_CLEANED, true).apply()
            return
        }
        val done = Privileged.with {
            it.exec("settings put secure enabled_accessibility_services '${kept.joinToString(":")}'")
            true
        } ?: false
        if (done) {
            p.edit().putBoolean(KEY_LEGACY_A11Y_CLEANED, true).apply()
            Log.i("CoverDeck/HomeLongPress", "removed the old CoverDeck accessibility service entry")
        }
    }
}

/**
 * Hosted by CoverDeckService while the switch is on. Keeps the helper's log watcher
 * running only while the phone is folded with the cover lit, and turns its two signals
 * into stock-like recents behaviour:
 *
 *  - Hold Home: recents opens; hold again while open and it closes.
 *  - Tap Home while recents is open: recents closes and the cover goes home.
 *
 * SystemUI logs a Home injection for the press, for the long-press repeat, and for the
 * release. The release after a long press must not close the panel that just opened,
 * so it is skipped.
 */
class HomeLongPressWatcher(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)

    private var running = false
    private var watching = false

    private var ignoreNextHomeKey = false
    private var closedByHomeAt = 0L

    private val listener = object : INavGestureListener.Stub() {
        override fun onHomeLongPress() {
            handler.post { handleLongPress() }
        }

        override fun onHomeKey() {
            handler.post { handleHomeKey() }
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

    /** The only situation the gesture exists in: cover on, inner screen off. */
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

    private fun handleLongPress() {
        // The release of this same hold is still to come; it must not act as a tap.
        ignoreNextHomeKey = true
        // If the press that started this hold just closed recents, the user was holding
        // Home to close it: leave it closed rather than reopening it.
        if (SystemClock.uptimeMillis() - closedByHomeAt < REOPEN_GUARD_MS) return
        buzz()
        RecentsPanel.show(appContext)
    }

    private fun buzz() {
        runCatching {
            appContext.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        }
    }

    private companion object {
        const val REOPEN_GUARD_MS = 800L
    }
}
