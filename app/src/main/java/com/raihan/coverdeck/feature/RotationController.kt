package com.raihan.coverdeck.feature

import android.content.Context
import android.content.SharedPreferences
import android.view.Surface
import com.raihan.coverdeck.privileged.Privileged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-display rotation lock.
 *
 * The system's own auto-rotate setting only ever addresses the default display, which
 * is why locking the cover screen normally is not possible. This drives
 * IWindowManager directly with an explicit display id instead.
 *
 * Like density, the lock state a display had before CoverDeck first changed it is
 * remembered. Thawing display 0 is the same thing as turning on system auto-rotate,
 * so a blind reset would silently override the owner's quick-settings choice.
 */
object RotationController {

    enum class Mode(val label: String, val rotation: Int?) {
        AUTO("Auto", null),
        DEG_0("0°", Surface.ROTATION_0),
        DEG_90("90°", Surface.ROTATION_90),
        DEG_180("180°", Surface.ROTATION_180),
        DEG_270("270°", Surface.ROTATION_270),
        ;

        companion object {
            fun forRotation(rotation: Int): Mode =
                entries.firstOrNull { it.rotation == rotation } ?: AUTO
        }
    }

    data class State(
        val mode: Mode = Mode.AUTO,
        val currentRotation: Int = Surface.ROTATION_0,
        val frozen: Boolean = false,
        val forceAppsToObey: Boolean = true,
    )

    private val states = mutableMapOf<Int, MutableStateFlow<State>>()

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun state(displayId: Int): StateFlow<State> = flowFor(displayId).asStateFlow()

    private fun flowFor(displayId: Int): MutableStateFlow<State> =
        states.getOrPut(displayId) { MutableStateFlow(State()) }

    fun refresh(displayId: Int) {
        Privileged.with { service ->
            val rotation = service.getRotation(displayId)
            val frozen = service.isRotationFrozen(displayId)
            flowFor(displayId).value = flowFor(displayId).value.copy(
                currentRotation = rotation,
                frozen = frozen,
                mode = if (frozen) Mode.forRotation(rotation) else Mode.AUTO,
            )
        }
    }

    /**
     * Applying a lock also tells the display to ignore app-requested orientation.
     * Without that second step an app with a fixed orientation snaps the screen back
     * the instant it is focused, and the lock looks broken.
     */
    fun apply(displayId: Int, mode: Mode, forceAppsToObey: Boolean = true) {
        Privileged.with { service ->
            rememberOriginal(service, displayId)
            val rotation = mode.rotation
            if (rotation == null) {
                service.setIgnoreOrientationRequest(displayId, false)
                service.setFixedToUserRotation(displayId, FIXED_TO_USER_DEFAULT)
                service.thawRotation(displayId)
            } else {
                service.setIgnoreOrientationRequest(displayId, forceAppsToObey)
                service.setFixedToUserRotation(
                    displayId,
                    if (forceAppsToObey) FIXED_TO_USER_ENABLED else FIXED_TO_USER_DEFAULT,
                )
                service.freezeRotation(displayId, rotation)
            }
        }
        flowFor(displayId).value = flowFor(displayId).value.copy(
            mode = mode,
            forceAppsToObey = forceAppsToObey,
        )
        refresh(displayId)
    }

    private fun rememberOriginal(service: com.raihan.coverdeck.IPrivilegedService, displayId: Int) {
        val p = prefs ?: return
        if (p.contains(keyTouched(displayId))) return
        p.edit()
            .putBoolean(keyTouched(displayId), true)
            .putBoolean(keyFrozen(displayId), service.isRotationFrozen(displayId))
            .putInt(keyRotation(displayId), service.getRotation(displayId))
            .commit()
    }

    /**
     * Undoes CoverDeck's changes on [displayId] only. The two window-manager flags go
     * back to their defaults (One UI exposes neither to users); the lock itself goes
     * back to exactly what it was. Returns false when the display was never touched.
     */
    fun restoreOriginal(displayId: Int): Boolean {
        val p = prefs ?: return false
        if (!p.getBoolean(keyTouched(displayId), false)) return false
        val wasFrozen = p.getBoolean(keyFrozen(displayId), false)
        val rotation = p.getInt(keyRotation(displayId), Surface.ROTATION_0)

        Privileged.with { service ->
            service.setIgnoreOrientationRequest(displayId, false)
            service.setFixedToUserRotation(displayId, FIXED_TO_USER_DEFAULT)
            if (wasFrozen) service.freezeRotation(displayId, rotation)
            else service.thawRotation(displayId)
        }
        p.edit()
            .remove(keyTouched(displayId))
            .remove(keyFrozen(displayId))
            .remove(keyRotation(displayId))
            .commit()
        flowFor(displayId).value = State()
        refresh(displayId)
        return true
    }

    private fun keyTouched(displayId: Int) = "touched_$displayId"
    private fun keyFrozen(displayId: Int) = "original_frozen_$displayId"
    private fun keyRotation(displayId: Int) = "original_rotation_$displayId"

    private const val PREFS = "coverdeck_rotation"
    private const val FIXED_TO_USER_DEFAULT = 0
    private const val FIXED_TO_USER_ENABLED = 2
}
