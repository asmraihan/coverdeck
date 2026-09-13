package com.raihan.coverdeck.nav

import android.content.Context
import android.content.SharedPreferences
import com.raihan.coverdeck.core.Displays
import com.raihan.coverdeck.feature.AutoRotate
import com.raihan.coverdeck.feature.RotationController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The "hold the cover's Back button to switch rotation" switch: Auto becomes a lock at 0°,
 * and anything else becomes Auto. Detected by the same helper log reader as the Home
 * long-press ([com.raihan.coverdeck.privileged.NavLogWatcher]); [NavGestureWatcher] acts on it.
 */
object BackLongPress {

    private const val PREFS = "coverdeck_backlongpress"
    private const val KEY_ENABLED = "enabled"

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

    /** Where a hold would take the cover from here. */
    fun nextCoverMode(): RotationController.Mode =
        if (AutoRotate.enabled.value) RotationController.Mode.DEG_0 else RotationController.Mode.AUTO

    /** Applies [mode] to the cover. Blocking binder calls: keep off the main thread. */
    fun applyCoverMode(context: Context, mode: RotationController.Mode) {
        val cover = Displays.cover(context).displayId
        val obey = RotationController.state(cover).value.forceAppsToObey
        RotationController.apply(cover, mode, obey)
    }
}
