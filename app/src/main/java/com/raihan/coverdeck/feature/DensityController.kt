package com.raihan.coverdeck.feature

import android.content.Context
import android.os.Process
import com.raihan.coverdeck.core.Panel
import com.raihan.coverdeck.privileged.Privileged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Forced display density, with a dead-man's switch and a memory of what was there first.
 *
 * Two safety properties:
 *
 *  - A change is provisional. It reverts after [CONFIRM_SECONDS] unless confirmed,
 *    because a bad value on a 3.4" panel can make the "undo" button unreachable.
 *
 *  - The density a display had *before CoverDeck first touched it* is recorded, and
 *    [restoreOriginal] puts that back. The first build reset to factory instead, which
 *    wiped a 450 dpi override the owner had set on the inner screen themselves.
 */
class DensityController(private val context: Context) {

    data class State(
        val baseDpi: Int = 0,
        val effectiveDpi: Int = 0,
        val pendingDpi: Int? = null,
        val secondsLeft: Int = 0,
        val revertTargetDpi: Int? = null,
        val originalDpi: Int? = null,
    ) {
        val isModified: Boolean get() = baseDpi > 0 && effectiveDpi != baseDpi
        val awaitingConfirmation: Boolean get() = pendingDpi != null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var countdown: Job? = null

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun refresh(displayId: Int) {
        Privileged.with { service ->
            _state.value = _state.value.copy(
                baseDpi = service.getBaseDensity(displayId),
                effectiveDpi = service.getEffectiveDensity(displayId),
                originalDpi = prefs.getInt(keyOriginal(displayId), NONE).takeIf { it > 0 },
            )
        }
    }

    /**
     * Reverts a change whose confirmation window was interrupted by the app dying.
     * Only acts when the recorded owner process is gone: a pending change owned by
     * this process still has a live countdown and must be left alone.
     */
    fun recoverUnconfirmed(displayId: Int): Int? {
        val stranded = prefs.getInt(keyPending(displayId), NONE)
        val owner = prefs.getInt(keyPendingPid(displayId), NONE)
        if (stranded <= 0 || owner == Process.myPid()) return null

        val restoreTo = prefs.getInt(keyRevert(displayId), NONE)
        Privileged.with { service -> setOrClear(service, displayId, restoreTo) }
        prefs.edit()
            .remove(keyPending(displayId))
            .remove(keyPendingPid(displayId))
            .remove(keyRevert(displayId))
            .commit()
        refresh(displayId)
        return stranded
    }

    /** Bounds that keep the screen usable. Below ~200 dpi One UI's own panels break. */
    fun range(panel: Panel): IntRange {
        val base = if (_state.value.baseDpi > 0) _state.value.baseDpi else panel.densityDpi
        return (base / 2).coerceAtLeast(MIN_DPI)..(base * 3 / 2).coerceAtMost(MAX_DPI)
    }

    /** The smallest-width dp an app will see, which is what drives layout selection. */
    fun smallestWidthDp(panel: Panel, dpi: Int): Int {
        if (dpi <= 0) return 0
        return (min(panel.widthPx, panel.heightPx) * 160f / dpi).roundToInt()
    }

    fun apply(displayId: Int, dpi: Int) {
        countdown?.cancel()

        val previous = Privileged.with { it.getEffectiveDensity(displayId) }
            ?: _state.value.effectiveDpi.takeIf { it > 0 }
            ?: return

        val edit = prefs.edit()
            .putInt(keyPending(displayId), dpi)
            .putInt(keyPendingPid(displayId), Process.myPid())
            .putInt(keyRevert(displayId), previous)
        if (!prefs.contains(keyOriginal(displayId))) {
            edit.putInt(keyOriginal(displayId), previous)
        }
        // commit(): if the new density crashes something, the record must already exist.
        edit.commit()

        Privileged.with { it.setDensity(displayId, dpi) }
        _state.value = _state.value.copy(
            pendingDpi = dpi,
            revertTargetDpi = previous,
            secondsLeft = CONFIRM_SECONDS,
            effectiveDpi = dpi,
            originalDpi = prefs.getInt(keyOriginal(displayId), NONE).takeIf { it > 0 },
        )

        countdown = scope.launch {
            for (remaining in CONFIRM_SECONDS downTo 1) {
                _state.value = _state.value.copy(secondsLeft = remaining)
                delay(1000)
            }
            revert(displayId)
        }
    }

    fun confirm(displayId: Int) {
        countdown?.cancel()
        countdown = null
        clearPending(displayId)
        _state.value = _state.value.copy(pendingDpi = null, secondsLeft = 0, revertTargetDpi = null)
        refresh(displayId)
    }

    fun revert(displayId: Int) {
        countdown?.cancel()
        countdown = null
        val target = _state.value.revertTargetDpi ?: prefs.getInt(keyRevert(displayId), NONE)
        Privileged.with { service -> setOrClear(service, displayId, target) }
        clearPending(displayId)
        _state.value = _state.value.copy(pendingDpi = null, secondsLeft = 0, revertTargetDpi = null)
        refresh(displayId)
    }

    /**
     * Puts back whatever density this display had before CoverDeck first changed it.
     * Returns false, and changes nothing, when CoverDeck never touched the display.
     */
    fun restoreOriginal(displayId: Int): Boolean {
        val original = prefs.getInt(keyOriginal(displayId), NONE)
        if (original <= 0) return false
        countdown?.cancel()
        countdown = null
        Privileged.with { service -> setOrClear(service, displayId, original) }
        clearPending(displayId)
        prefs.edit().remove(keyOriginal(displayId)).commit()
        _state.value = _state.value.copy(
            pendingDpi = null, secondsLeft = 0, revertTargetDpi = null, originalDpi = null,
        )
        refresh(displayId)
        return true
    }

    /** Factory density with no override at all. Explicit, never part of "Reset all". */
    fun resetToFactory(displayId: Int) {
        countdown?.cancel()
        countdown = null
        Privileged.with { it.resetDensity(displayId) }
        clearPending(displayId)
        prefs.edit().remove(keyOriginal(displayId)).commit()
        _state.value = _state.value.copy(
            pendingDpi = null, secondsLeft = 0, revertTargetDpi = null, originalDpi = null,
        )
        refresh(displayId)
    }

    /**
     * Setting a density equal to the panel's own creates a pointless override that
     * shows up in "wm density" forever; clear the override instead in that case.
     */
    private fun setOrClear(
        service: com.raihan.coverdeck.IPrivilegedService,
        displayId: Int,
        dpi: Int,
    ) {
        val base = service.getBaseDensity(displayId)
        if (dpi <= 0 || dpi == base) service.resetDensity(displayId)
        else service.setDensity(displayId, dpi)
    }

    private fun clearPending(displayId: Int) {
        prefs.edit()
            .remove(keyPending(displayId))
            .remove(keyPendingPid(displayId))
            .remove(keyRevert(displayId))
            .commit()
    }

    private fun keyPending(displayId: Int) = "pending_dpi_$displayId"
    private fun keyPendingPid(displayId: Int) = "pending_pid_$displayId"
    private fun keyRevert(displayId: Int) = "revert_dpi_$displayId"
    private fun keyOriginal(displayId: Int) = "original_dpi_$displayId"

    companion object {
        const val CONFIRM_SECONDS = 15
        const val MIN_DPI = 120
        const val MAX_DPI = 640
        private const val NONE = -1
        private const val PREFS = "coverdeck_density"
    }
}
