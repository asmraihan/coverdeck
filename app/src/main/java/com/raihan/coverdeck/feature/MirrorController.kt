package com.raihan.coverdeck.feature

import android.content.Context
import android.os.Process
import android.util.Log
import android.view.Surface
import com.raihan.coverdeck.core.Panel
import com.raihan.coverdeck.privileged.Privileged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Projects the inner display onto the cover screen while the hinge is shut.
 *
 * The blocker this works around: closing the Flip powers the inner panel down, so
 * there is no content to capture. DeviceStateManager is therefore overridden to report
 * an open-ish state (CONCURRENT_INNER_DEFAULT if the firmware offers it, otherwise
 * OPENED) which keeps display 0 composited, and only then is a mirror attached.
 *
 * That override is a real change to how the phone sees itself, so it is tracked on
 * disk and force-released on the next launch if this process ever dies holding it.
 */
class MirrorController(context: Context) {

    enum class FitMode(val label: String, val description: String) {
        FIT("Fit", "Whole inner screen, letterboxed"),
        FILL("Fill width", "Sharper, pan up and down"),
    }

    data class State(
        val running: Boolean = false,
        val engine: String = "none",
        val fitMode: FitMode = FitMode.FIT,
        val touchEnabled: Boolean = true,
        val overriddenState: Int? = null,
        val originalState: Int? = null,
        val availableStates: List<Pair<Int, String>> = emptyList(),
        val lastError: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun refresh() {
        Privileged.with { service ->
            val states = service.deviceStates.orEmpty().mapNotNull { raw ->
                val (id, name) = raw.split(":", limit = 2).let {
                    it.getOrNull(0)?.toIntOrNull() to it.getOrNull(1)
                }
                if (id != null && name != null) id to name else null
            }
            _state.value = _state.value.copy(
                availableStates = states,
                engine = service.mirrorEngine,
            )
        }
    }

    /**
     * A stranded override survives process death, so clear one at startup before the
     * user is left wondering why the phone thinks it is unfolded.
     */
    fun recoverStrandedOverride(): Boolean {
        // The override is only stranded if the process that took it is gone. The first
        // build checked a plain boolean, so reopening the app mid-mirror "recovered" a
        // live override and killed the mirror (seen on-device at 20:16:28).
        val owner = prefs.getInt(KEY_OVERRIDE_OWNER_PID, NO_OWNER)
        if (owner == NO_OWNER || owner == Process.myPid()) return false
        Privileged.with { it.resetDeviceState() }
        prefs.edit().remove(KEY_OVERRIDE_OWNER_PID).commit()
        Log.w(TAG, "released a device-state override left behind by dead process $owner")
        return true
    }

    /** Prefers Samsung's concurrent dual-display state; falls back to plain OPENED. */
    fun preferredAwakeState(): Int? {
        val states = _state.value.availableStates
        return states.firstOrNull { it.second.contains("CONCURRENT", ignoreCase = true) }?.first
            ?: states.firstOrNull { it.second.equals("OPENED", ignoreCase = true) }?.first
    }

    fun keepInnerDisplayAwake(stateId: Int? = null): Boolean {
        val target = stateId ?: preferredAwakeState() ?: run {
            _state.value = _state.value.copy(lastError = "No open device state reported")
            return false
        }
        return Privileged.with { service ->
            val original = service.currentDeviceState
            // commit(), not apply(): this record is the only way a crash right after the
            // override gets cleaned up, so it has to be on disk before the override lands.
            prefs.edit().putInt(KEY_OVERRIDE_OWNER_PID, Process.myPid()).commit()
            service.requestDeviceState(target)
            _state.value = _state.value.copy(
                overriddenState = target,
                originalState = original.takeIf { it >= 0 },
                lastError = null,
            )
            true
        } ?: false
    }

    fun releaseDeviceState() {
        Privileged.with { it.resetDeviceState() }
        prefs.edit().remove(KEY_OVERRIDE_OWNER_PID).commit()
        _state.value = _state.value.copy(overriddenState = null)
    }

    /**
     * Virtual display geometry, always at the *source* aspect ratio so SurfaceFlinger
     * never letterboxes inside the buffer. Fitting and panning are done by the view
     * transform on the cover side, which keeps them free and gesture-driven.
     */
    fun virtualSize(source: Panel, cover: Panel, mode: FitMode): Pair<Int, Int> {
        val srcW = source.widthPx.coerceAtLeast(1)
        val srcH = source.heightPx.coerceAtLeast(1)
        val scale = when (mode) {
            FitMode.FIT -> minOf(cover.widthPx.toFloat() / srcW, cover.heightPx.toFloat() / srcH)
            FitMode.FILL -> cover.widthPx.toFloat() / srcW
        }
        fun even(value: Float) = (value.toInt().coerceAtLeast(2) / 2) * 2
        return even(srcW * scale) to even(srcH * scale)
    }

    fun start(surface: Surface, source: Panel, cover: Panel, mode: FitMode): Boolean {
        val (width, height) = virtualSize(source, cover, mode)
        val ok = Privileged.with { service ->
            service.startMirror(surface, source.displayId, width, height, source.densityDpi)
        } ?: false
        _state.value = _state.value.copy(
            running = ok,
            fitMode = mode,
            engine = Privileged.with { it.mirrorEngine } ?: "none",
            lastError = if (ok) null else "No mirror engine accepted the surface",
        )
        return ok
    }

    fun stop(alsoReleaseDeviceState: Boolean = true) {
        Privileged.with { it.stopMirror() }
        if (alsoReleaseDeviceState) releaseDeviceState()
        _state.value = _state.value.copy(running = false, engine = "none")
    }

    fun setFitMode(mode: FitMode) {
        _state.value = _state.value.copy(fitMode = mode)
    }

    fun setTouchEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(touchEnabled = enabled)
    }

    /** Cover-screen coordinates inside the mirror view, mapped back to inner-display pixels. */
    fun mapToSource(
        viewX: Float,
        viewY: Float,
        viewWidth: Int,
        viewHeight: Int,
        source: Panel,
    ): Pair<Float, Float> {
        if (viewWidth <= 0 || viewHeight <= 0) return 0f to 0f
        val nx = (viewX / viewWidth).coerceIn(0f, 1f)
        val ny = (viewY / viewHeight).coerceIn(0f, 1f)
        return nx * source.widthPx to ny * source.heightPx
    }

    fun injectTouch(action: Int, x: Float, y: Float, sourceDisplayId: Int, downTime: Long) {
        Privileged.with { it.injectTouch(action, x, y, sourceDisplayId, downTime, 0) }
    }

    private companion object {
        const val TAG = "CoverDeck/Mirror"
        const val PREFS = "coverdeck_mirror"
        const val KEY_OVERRIDE_OWNER_PID = "device_state_override_owner_pid"
        const val NO_OWNER = -1
    }
}
