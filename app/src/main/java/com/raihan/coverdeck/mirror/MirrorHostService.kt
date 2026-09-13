package com.raihan.coverdeck.mirror

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.raihan.coverdeck.privileged.Privileged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hosts the mirror window, and [com.raihan.coverdeck.overlay.CoverHud]'s confirmations. It
 * observes nothing and filters no keys; it exists only because an accessibility overlay is
 * the one window type that fits the job:
 *
 *  - An app overlay is force-hidden on every display whenever a Settings screen is
 *    visible (so opening Settings through the mirror blanked the mirror) and it sits
 *    under the cover's navigation bar.
 *  - An activity is moved to the inner display by One UI whenever the device state
 *    changes, which happens on every wake.
 *  - A system window from the Shizuku helper is refused outright on Android 16
 *    ("Unknown pid ... uid=2000"): only real app processes may open a window session.
 *
 * A TYPE_ACCESSIBILITY_OVERLAY window is exempt from overlay hiding, draws above the
 * cover's system bars and quick panel, and belongs to no task.
 */
class MirrorHostService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
        _connected.value = true
        Log.i(TAG, "mirror host connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        MirrorSession.onHostLost()
        if (current === this) current = null
        _connected.value = false
    }

    companion object {
        private const val TAG = "CoverDeck/MirrorHost"

        @Volatile
        internal var current: MirrorHostService? = null
            private set

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        private fun component(context: Context) = ComponentName(context, MirrorHostService::class.java)

        private fun enabledList(context: Context): List<String> =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').filter { it.isNotBlank() }

        /**
         * Switches the host on through Shizuku, appending to (never replacing) any other
         * enabled accessibility services. This skips the system confirmation screen, so it
         * is only wired to an explicit button that explains what the service is for.
         */
        fun enableWithShizuku(context: Context): Boolean {
            val mine = component(context)
            val list = enabledList(context)
            val next = if (list.any { ComponentName.unflattenFromString(it) == mine }) list else list + mine.flattenToString()
            return Privileged.with {
                it.exec("settings put secure enabled_accessibility_services '${next.joinToString(":")}'")
                it.exec("settings put secure accessibility_enabled 1")
                true
            } ?: false
        }

        fun openSettings(context: Context) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
