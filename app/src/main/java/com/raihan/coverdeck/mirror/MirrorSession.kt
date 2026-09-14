package com.raihan.coverdeck.mirror

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.Surface
import com.raihan.coverdeck.core.Displays
import com.raihan.coverdeck.feature.RotationController
import com.raihan.coverdeck.privileged.Privileged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mirror mode: the inner screen, live and touchable, filling the cover.
 *
 * Built for a phone whose inner panel is broken, so the cover is the only usable screen.
 * [MirrorWindow] shows the picture and handles touch; the Shizuku helper keeps the inner
 * panel awake, streams it and injects input. This object owns the decisions and the undo:
 *
 *  - **Reshape the inner display to the cover.** Mirrored as-is, a 1080x2640 screen is a
 *    narrow strip on the 748x720 cover. While mirroring, the inner display gets a logical
 *    size matching the space the window reports, at 450 dpi, so it fills the cover at a
 *    readable size. Its own navigation bar comes along with it.
 *  - **Hold it upright, in every shape.** Folded, the inner half hangs upside down, so
 *    with auto-rotate on its orientation sensor turns the inner display to 180 degrees or
 *    sideways, and the mirror faithfully showed that (the "upside-down mirror"). The inner
 *    display is locked to portrait for the whole session and apps letterbox instead.
 *  - **Put everything back.** Every original is recorded before it is changed and
 *    restored on stop, or on the next launch if the app died mid-session.
 */
object MirrorSession {

    private const val TAG = "CoverDeck/Mirror"

    enum class Shape(val label: String, val description: String) {
        COVER("Fit to cover", "The inner screen takes the cover's shape while mirroring. Fills the screen, readable."),
        ORIGINAL("Original", "The inner screen stays tall and narrow. Everything fits, but small."),
    }

    data class State(
        val active: Boolean = false,
        val streaming: Boolean = false,
        val engine: String = "none",
        val shape: Shape = Shape.COVER,
        val sourceWidth: Int = 0,
        val sourceHeight: Int = 0,
        val lastError: String? = null,
        /** Keep the inner panel dark while mirroring; it still renders and takes input. */
        val innerOff: Boolean = false,
        /** Leave the inner screen's navigation bar out of the picture; the strip navigates. */
        val hideNavBar: Boolean = false,
        /** Pixels cut from the bottom of the inner screen in the picture (its navigation bar). */
        val cropBottom: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Session steps are blocking binder calls; run them one at a time, in order. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val worker = Dispatchers.IO.limitedParallelism(1)

    private val scope = CoroutineScope(SupervisorJob() + worker)

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    /** Main thread only. */
    private var window: MirrorWindow? = null

    /** The last area the window reported, so a shape change can be applied without it. */
    @Volatile
    private var area: Pair<Int, Int>? = null

    /** Re-applies "inner screen off" while a session runs; see [enforceInnerPanel]. */
    private var panelKeeper: Job? = null

    private val events = object : MirrorWindow.Events {
        override fun onAreaMeasured(width: Int, height: Int) {
            area = width to height
            scope.launch { applyShape() }
        }

        override fun onStopRequested() {
            scope.launch { end() }
        }

        override fun onShapeSelected(shape: Shape) {
            setShape(shape)
        }

        override fun onHideNavBarSelected(hide: Boolean) {
            setHideNavBar(hide)
        }

        override fun onInnerOffSelected(off: Boolean) {
            setInnerOff(off)
        }

        override fun onRotationCycleRequested() {
            cycleCoverRotation()
        }

        override fun onStreamChanged(streaming: Boolean, engine: String) {
            _state.update { it.copy(streaming = streaming, engine = engine) }
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _state.update {
            it.copy(
                shape = runCatching { Shape.valueOf(p.getString(KEY_SHAPE, Shape.COVER.name)!!) }.getOrDefault(Shape.COVER),
                innerOff = p.getBoolean(KEY_INNER_OFF, false),
                hideNavBar = p.getBoolean(KEY_HIDE_NAV, false),
            )
        }
    }

    // ---- user-facing ----------------------------------------------------------------

    /** Returns false straight away when the window host (accessibility service) is off. */
    fun start(): Boolean {
        val host = MirrorHostService.current
        if (host == null) {
            _state.update { it.copy(lastError = "Turn on CoverDeck's mirror host in Accessibility first") }
            return false
        }
        scope.launch {
            prefs?.edit()?.putInt(KEY_OWNER_PID, Process.myPid())?.commit()
            Privileged.with { it.setInnerDisplayAwake(true) }
            val cover = Displays.cover(host).displayId
            val shown = withContext(Dispatchers.Main) {
                val w = window ?: MirrorWindow(host, events).also { window = it }
                w.show(cover)
            }
            _state.update { it.copy(active = shown, lastError = if (shown) null else "Could not open the mirror window") }
            if (shown) startPanelKeeper()
            Log.i(TAG, "mirror ${if (shown) "shown" else "FAILED"} on display $cover")
            // On success the window reports its area next (onAreaMeasured), which sets the
            // inner display's shape and starts the picture.
            if (!shown) end()
        }
        return true
    }

    /** Stops mirroring and restores every setting the session changed. */
    suspend fun end() {
        panelKeeper?.cancel()
        panelKeeper = null
        withContext(Dispatchers.Main) {
            window?.hide()
            window = null
        }
        withContext(worker) {
            Privileged.with {
                it.stopMirror()
                it.setInnerDisplayAwake(false)
            }
            restoreGeometry()
            prefs?.edit()?.remove(KEY_OWNER_PID)?.commit()
        }
        area = null
        _state.update { it.copy(active = false, streaming = false, engine = "none", sourceWidth = 0, sourceHeight = 0, cropBottom = 0) }
    }

    /**
     * Inner screen off while mirroring, applied live. Only the panel goes dark: the
     * display keeps rendering, so the mirror, touch from the cover and scrcpy all keep
     * working. Ending the session needs no undo, because releasing the inner display
     * turns it off anyway and One UI powers the panel up normally next time.
     */
    fun setInnerOff(off: Boolean) {
        prefs?.edit()?.putBoolean(KEY_INNER_OFF, off)?.apply()
        _state.update { it.copy(innerOff = off) }
        if (_state.value.active) {
            scope.launch { Privileged.with { it.setDisplayPanelOn(Displays.MAIN_ID, !off) } }
        }
    }

    /**
     * Puts the inner panel back to dark if the setting is on. One UI powers the panel up
     * whenever it wakes the inner display (each session start, and every time the cover
     * comes back on), so this runs right after those and every few seconds as a backstop.
     * Call on [worker].
     */
    internal fun enforceInnerPanel() {
        val s = _state.value
        if (s.active && s.innerOff) Privileged.with { it.setDisplayPanelOn(Displays.MAIN_ID, false) }
    }

    private fun startPanelKeeper() {
        panelKeeper?.cancel()
        panelKeeper = scope.launch {
            while (isActive) {
                enforceInnerPanel()
                delay(PANEL_KEEPER_MS)
            }
        }
    }

    /**
     * Hides the inner screen's navigation bar from the mirror, applied live. The bar itself
     * stays on the inner screen (Android offers no way to remove it from one display only,
     * and gesture navigation would change the cover's navigation too): the inner screen is
     * made taller by exactly the bar's height and the picture is cut above it.
     */
    fun setHideNavBar(hide: Boolean) {
        prefs?.edit()?.putBoolean(KEY_HIDE_NAV, hide)?.apply()
        _state.update { it.copy(hideNavBar = hide) }
        if (_state.value.active) scope.launch { applyShape() }
    }

    /** The cover rotation the menu offers, in the order a tap steps through them. */
    private val coverRotationCycle = listOf(
        RotationController.Mode.AUTO,
        RotationController.Mode.DEG_0,
        RotationController.Mode.DEG_90,
    )

    /** Auto, then locked at 0°, then locked at 90°, then Auto again. Anything else goes to Auto. */
    fun cycleCoverRotation() {
        val ctx = appContext ?: return
        val cover = Displays.cover(ctx).displayId
        scope.launch {
            // No refresh here: the menu refreshed when it opened, and a refresh straight after
            // a quick second tap could still read the angle the display is turning away from.
            val state = RotationController.state(cover).value
            val index = coverRotationCycle.indexOf(state.mode)
            val next = if (index < 0) RotationController.Mode.AUTO else coverRotationCycle[(index + 1) % coverRotationCycle.size]
            RotationController.apply(cover, next, state.forceAppsToObey)
            withContext(Dispatchers.Main) { window?.refreshMenu() }
            Log.i(TAG, "cover rotation from the menu: ${state.mode} -> $next")
        }
    }

    /** Brings the menu's rotation toggle up to date, e.g. when the menu opens. */
    internal fun refreshCoverRotation() {
        val ctx = appContext ?: return
        scope.launch {
            RotationController.refresh(Displays.cover(ctx).displayId)
            withContext(Dispatchers.Main) { window?.refreshMenu() }
        }
    }

    fun setShape(shape: Shape) {
        prefs?.edit()?.putString(KEY_SHAPE, shape.name)?.apply()
        _state.update { it.copy(shape = shape) }
        if (_state.value.active) scope.launch { applyShape() }
    }

    /** The accessibility host went away (switched off, or the app died): tidy up. */
    internal fun onHostLost() {
        if (_state.value.active) scope.launch { end() }
    }

    /**
     * Undoes a session left behind by a dead app process: the inner display would otherwise
     * stay reshaped, rotation-locked and awake. Call on [worker].
     */
    fun recoverStranded(): Boolean {
        val p = prefs ?: return false
        val owner = p.getInt(KEY_OWNER_PID, NO_OWNER)
        if (owner == NO_OWNER || owner == Process.myPid()) return false
        Privileged.with {
            it.stopMirror()
            it.setInnerDisplayAwake(false)
        }
        restoreGeometry()
        p.edit().remove(KEY_OWNER_PID).commit()
        Log.w(TAG, "restored a mirror session left behind by dead process $owner")
        return true
    }

    // ---- shape ------------------------------------------------------------------------

    private suspend fun applyShape() {
        val (areaW, areaH) = area ?: return
        val sizes = Privileged.with { it.getDisplaySizes(Displays.MAIN_ID) } ?: return
        if (sizes[0] <= 0 || areaW <= 0 || areaH <= 0) return
        holdOriginals()
        val hideNav = _state.value.hideNavBar

        // Every shape: upright, whatever the sensor or an app asks for. Done first, so the
        // navigation bar is measured where it will stay: along the bottom.
        Privileged.with {
            it.setIgnoreOrientationRequest(Displays.MAIN_ID, true)
            it.freezeRotation(Displays.MAIN_ID, Surface.ROTATION_0)
        }

        val width: Int
        val height: Int
        var crop = 0
        when (_state.value.shape) {
            Shape.COVER -> {
                width = sizes[0]
                val visible = even(width.toFloat() * areaH / areaW)
                // With the bar hidden, the inner screen grows by the bar's height so what is
                // left above it still has the cover's shape. The height from last time is a
                // good first guess; the real one is measured and corrected below.
                crop = if (hideNav) expectedNavBar(FIT_DENSITY) else 0
                Privileged.with {
                    it.setDensity(Displays.MAIN_ID, FIT_DENSITY)
                    it.setDisplaySize(Displays.MAIN_ID, width, visible + crop)
                }
                if (hideNav) {
                    // SystemUI resizes its bar for the new density a moment after the display
                    // changes, so a first reading can still be the old height (144 px at 480
                    // dpi instead of 135 at 450). Measure again after each correction until
                    // the bar and the display agree.
                    for (attempt in 0 until NAV_FIT_ATTEMPTS) {
                        val measured = measureNavBar(visible + crop)
                        if (measured < 0 || measured == crop) break
                        crop = measured
                        Privileged.with { it.setDisplaySize(Displays.MAIN_ID, width, visible + crop) }
                    }
                    rememberNavBar(crop, FIT_DENSITY)
                }
                height = visible + crop
            }

            Shape.ORIGINAL -> {
                restoreSizeAndDensity()
                val now = Privileged.with { it.getDisplaySizes(Displays.MAIN_ID) } ?: sizes
                width = now[2]
                height = now[3]
                if (hideNav) crop = measureNavBar(height).coerceAtLeast(0)
            }
        }

        _state.update { it.copy(sourceWidth = width, sourceHeight = height, cropBottom = crop) }
        withContext(Dispatchers.Main) { window?.update(width, height, crop) }
        Log.i(TAG, "mirroring inner screen at ${width}x$height, bottom $crop px hidden, into ${areaW}x$areaH")
    }

    /**
     * The inner navigation bar's height in pixels, once the window manager has laid the
     * display out at [displayHeight]. Falls back to the last reading if that takes too long;
     * -1 when there is no bar to measure.
     */
    private suspend fun measureNavBar(displayHeight: Int): Int {
        var last = -1
        var steady = 0
        repeat(NAV_MEASURE_TRIES) {
            val frame = Privileged.with { it.getNavigationBarFrame(Displays.MAIN_ID) }
            if (frame != null && frame.size == 4) {
                val barHeight = frame[3] - frame[1]
                // Only a bar sitting at the new bottom edge, reading the same height a few
                // times in a row, has finished resizing.
                steady = if (frame[3] == displayHeight && barHeight == last) steady + 1 else 0
                last = barHeight
                if (steady >= NAV_STEADY_READINGS) return barHeight
            }
            delay(NAV_MEASURE_INTERVAL_MS)
        }
        Log.w(TAG, "navigation bar never settled at height $displayHeight; using $last px")
        return last
    }

    private fun expectedNavBar(density: Int): Int {
        val dp = prefs?.getFloat(KEY_NAV_DP, 0f) ?: 0f
        return if (dp > 0f) Math.round(dp * density / 160f) else 0
    }

    private fun rememberNavBar(px: Int, density: Int) {
        if (px > 0) prefs?.edit()?.putFloat(KEY_NAV_DP, px * 160f / density)?.apply()
    }

    /** Records the inner display's size, density and rotation, once per session. */
    private fun holdOriginals() {
        val p = prefs ?: return
        if (p.getBoolean(KEY_HELD, false)) return
        val resolver = appContext?.contentResolver
        Privileged.with { service ->
            val sizes = service.getDisplaySizes(Displays.MAIN_ID)
            p.edit()
                .putBoolean(KEY_SIZE_FORCED, sizes[2] != sizes[0] || sizes[3] != sizes[1])
                .putInt(KEY_SIZE_W, sizes[2])
                .putInt(KEY_SIZE_H, sizes[3])
                .putInt(KEY_DENSITY, service.getEffectiveDensity(Displays.MAIN_ID))
                .putInt(KEY_DENSITY_BASE, service.getBaseDensity(Displays.MAIN_ID))
                .putBoolean(
                    KEY_ROT_AUTO,
                    resolver?.let { Settings.System.getInt(it, Settings.System.ACCELEROMETER_ROTATION, 1) == 1 } ?: true,
                )
                .putInt(KEY_ROT, resolver?.let { Settings.System.getInt(it, Settings.System.USER_ROTATION, 0) } ?: 0)
                .putBoolean(KEY_HELD, true)
                .commit()
        }
    }

    private fun restoreSizeAndDensity() {
        val p = prefs ?: return
        if (!p.getBoolean(KEY_HELD, false)) return
        Privileged.with { service ->
            if (p.getBoolean(KEY_SIZE_FORCED, false)) {
                service.setDisplaySize(Displays.MAIN_ID, p.getInt(KEY_SIZE_W, 0), p.getInt(KEY_SIZE_H, 0))
            } else {
                service.resetDisplaySize(Displays.MAIN_ID)
            }
            val density = p.getInt(KEY_DENSITY, 0)
            if (density <= 0 || density == p.getInt(KEY_DENSITY_BASE, -1)) {
                service.resetDensity(Displays.MAIN_ID)
            } else {
                service.setDensity(Displays.MAIN_ID, density)
            }
        }
    }

    /** Puts size, density and rotation back exactly as they were before the session. */
    private fun restoreGeometry() {
        val p = prefs ?: return
        if (!p.getBoolean(KEY_HELD, false)) return
        restoreSizeAndDensity()
        val done = Privileged.with { service ->
            service.setIgnoreOrientationRequest(Displays.MAIN_ID, false)
            if (p.getBoolean(KEY_ROT_AUTO, true)) {
                service.thawRotation(Displays.MAIN_ID)
            } else {
                service.freezeRotation(Displays.MAIN_ID, p.getInt(KEY_ROT, 0))
            }
            true
        } ?: false
        if (done) p.edit().putBoolean(KEY_HELD, false).commit()
    }

    private fun even(value: Float) = (value.toInt().coerceAtLeast(2) / 2) * 2

    private const val PREFS = "coverdeck_mirror_session"
    // "v2": the first sessions' saved choices were toggled by a mirror that had been moved
    // onto the (broken) inner display, not by the owner, so they are not carried over.
    private const val KEY_SHAPE = "shape_v2"
    private const val KEY_OWNER_PID = "owner_pid"
    private const val KEY_INNER_OFF = "inner_off"
    private const val KEY_HIDE_NAV = "hide_nav_bar"
    private const val KEY_NAV_DP = "nav_bar_dp"
    private const val NAV_MEASURE_TRIES = 30
    private const val NAV_MEASURE_INTERVAL_MS = 100L
    private const val NAV_STEADY_READINGS = 3
    private const val NAV_FIT_ATTEMPTS = 3
    private const val PANEL_KEEPER_MS = 3_000L
    private const val KEY_HELD = "originals_held_v2"
    private const val KEY_SIZE_FORCED = "orig_size_forced"
    private const val KEY_SIZE_W = "orig_size_w"
    private const val KEY_SIZE_H = "orig_size_h"
    private const val KEY_DENSITY = "orig_density"
    private const val KEY_DENSITY_BASE = "orig_density_base"
    private const val KEY_ROT_AUTO = "orig_rotation_auto"
    private const val KEY_ROT = "orig_rotation"
    private const val NO_OWNER = -1

    private const val FIT_DENSITY = 450
}
