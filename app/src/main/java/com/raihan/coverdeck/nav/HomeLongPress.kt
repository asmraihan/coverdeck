package com.raihan.coverdeck.nav

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import com.raihan.coverdeck.privileged.Privileged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The "long-press the cover's Home button for recents" switch.
 *
 * Detection lives in the Shizuku helper ([com.raihan.coverdeck.privileged.NavLogWatcher]);
 * see there for why an accessibility service cannot see this gesture on One UI 8.5.
 * [NavGestureWatcher] acts on it.
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
