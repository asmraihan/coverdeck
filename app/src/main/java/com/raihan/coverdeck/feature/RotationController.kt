package com.raihan.coverdeck.feature

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.view.Display
import android.view.Surface
import com.raihan.coverdeck.IPrivilegedService
import com.raihan.coverdeck.core.Displays
import com.raihan.coverdeck.overlay.CoverDeckService
import com.raihan.coverdeck.privileged.Privileged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-display rotation control.
 *
 * The inner screen already auto-rotates properly, so there "Auto" simply unlocks it.
 * The cover screen does not: One UI's cover launcher and most cover apps request
 * NOSENSOR, so unlocking just returns to a cover that never turns. On the cover, "Auto"
 * therefore runs [CoverAutoRotator], and the old unlock-only behaviour is kept as
 * "System" for anyone who wants Samsung's default back.
 *
 * The lock state a display had before CoverDeck first changed it is remembered, and
 * [restoreOriginal] puts exactly that back.
 */
object RotationController {

    enum class Mode(val label: String, val rotation: Int?) {
        SYSTEM("System", null),
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
        val autoRotate: Boolean = false,
    )

    private val states = mutableMapOf<Int, MutableStateFlow<State>>()

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun state(displayId: Int): StateFlow<State> = flowFor(displayId).asStateFlow()

    private fun flowFor(displayId: Int): MutableStateFlow<State> =
        states.getOrPut(displayId) { MutableStateFlow(State()) }

    fun isCover(displayId: Int): Boolean {
        val ctx = appContext ?: return displayId != Display.DEFAULT_DISPLAY
        return displayId == Displays.cover(ctx).displayId && displayId != Display.DEFAULT_DISPLAY
    }

    /** The modes that make sense for a display. System only exists on the cover. */
    fun modesFor(displayId: Int): List<Mode> =
        if (isCover(displayId)) Mode.entries else Mode.entries - Mode.SYSTEM

    fun refresh(displayId: Int) {
        Privileged.with { service ->
            val rotation = service.getRotation(displayId)
            val frozen = service.isRotationFrozen(displayId)
            val auto = isCover(displayId) && AutoRotate.enabled.value
            flowFor(displayId).value = flowFor(displayId).value.copy(
                currentRotation = rotation,
                frozen = frozen,
                autoRotate = auto,
                mode = when {
                    auto -> Mode.AUTO
                    frozen -> Mode.forRotation(rotation)
                    isCover(displayId) -> Mode.SYSTEM
                    else -> Mode.AUTO
                },
            )
        }
    }

    /**
     * Every mode that pins the cover pairs the rotation with ignore-orientation-request.
     * Without it, an app or launcher that asks for a fixed orientation wins the moment
     * it is focused, and the lock (or the auto-rotate) looks broken.
     */
    fun apply(displayId: Int, mode: Mode, forceAppsToObey: Boolean = true) {
        val cover = isCover(displayId)
        val autoOnCover = cover && mode == Mode.AUTO

        Privileged.with { service ->
            rememberOriginal(service, displayId)
            val rotation = mode.rotation
            when {
                autoOnCover -> {
                    service.setIgnoreOrientationRequest(displayId, forceAppsToObey)
                    service.setFixedToUserRotation(
                        displayId,
                        if (forceAppsToObey) FIXED_TO_USER_ENABLED else FIXED_TO_USER_DEFAULT,
                    )
                    // The rotator does the freezing from here on.
                }

                rotation == null -> {
                    service.setIgnoreOrientationRequest(displayId, false)
                    service.setFixedToUserRotation(displayId, FIXED_TO_USER_DEFAULT)
                    service.thawRotation(displayId)
                }

                else -> {
                    service.setIgnoreOrientationRequest(displayId, forceAppsToObey)
                    service.setFixedToUserRotation(
                        displayId,
                        if (forceAppsToObey) FIXED_TO_USER_ENABLED else FIXED_TO_USER_DEFAULT,
                    )
                    service.freezeRotation(displayId, rotation)
                }
            }
        }

        if (cover) setCoverAutoRotate(autoOnCover)

        flowFor(displayId).value = flowFor(displayId).value.copy(
            mode = mode,
            forceAppsToObey = forceAppsToObey,
            autoRotate = autoOnCover,
        )
        refresh(displayId)
    }

    private fun setCoverAutoRotate(on: Boolean) {
        val ctx = appContext ?: return
        if (AutoRotate.enabled.value == on) {
            // Already in that state, but make sure the service agrees (e.g. after a
            // process restart the service may not be up yet).
            if (on && !CoverDeckService.isRunning) CoverDeckService.send(ctx, CoverDeckService.ACTION_AUTO_ROTATE_ON)
            return
        }
        AutoRotate.setEnabled(on)
        if (on) {
            CoverDeckService.send(ctx, CoverDeckService.ACTION_AUTO_ROTATE_ON)
        } else if (CoverDeckService.isRunning) {
            CoverDeckService.send(ctx, CoverDeckService.ACTION_AUTO_ROTATE_OFF)
        }
    }

    private fun rememberOriginal(service: IPrivilegedService, displayId: Int) {
        val p = prefs ?: return
        if (p.contains(keyTouched(displayId))) return

        // For the inner screen the truth is the user's own Auto rotate toggle, which
        // lives in Settings.System. The window-manager query was unreliable for it while
        // folded: the first build recorded "frozen" for a phone that had auto-rotate on.
        val (frozen, rotation) = if (displayId == Display.DEFAULT_DISPLAY && appContext != null) {
            val resolver = appContext!!.contentResolver
            val auto = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1
            !auto to Settings.System.getInt(resolver, Settings.System.USER_ROTATION, Surface.ROTATION_0)
        } else {
            service.isRotationFrozen(displayId) to service.getRotation(displayId)
        }

        p.edit()
            .putBoolean(keyTouched(displayId), true)
            .putBoolean(keyFrozen(displayId), frozen)
            .putInt(keyRotation(displayId), rotation)
            .commit()
    }

    /**
     * Undoes CoverDeck's changes on [displayId] only. The two window-manager flags go
     * back to their defaults (One UI exposes neither to users); the lock itself goes
     * back to exactly what it was. Returns false when the display was never touched.
     */
    fun restoreOriginal(displayId: Int): Boolean {
        if (isCover(displayId)) setCoverAutoRotate(false)

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

    // "v2_" deliberately orphans records written by the first build, whose captured
    // inner-screen state could be wrong (see rememberOriginal).
    private fun keyTouched(displayId: Int) = "v2_touched_$displayId"
    private fun keyFrozen(displayId: Int) = "v2_original_frozen_$displayId"
    private fun keyRotation(displayId: Int) = "v2_original_rotation_$displayId"

    private const val PREFS = "coverdeck_rotation"
    private const val FIXED_TO_USER_DEFAULT = 0
    private const val FIXED_TO_USER_ENABLED = 2
}
