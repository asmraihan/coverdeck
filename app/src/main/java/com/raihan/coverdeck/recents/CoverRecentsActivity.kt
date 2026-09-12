package com.raihan.coverdeck.recents

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.raihan.coverdeck.core.Displays
import com.raihan.coverdeck.ui.CoverRecents
import com.raihan.coverdeck.ui.theme.CoverDeckTheme
import kotlinx.coroutines.launch

/**
 * Recents on the cover screen, as a see-through activity, the same way One UI's own
 * recents is an activity rather than an overlay.
 *
 * It began as a WindowManager overlay and that failed in two places on-device:
 * Settings (and One UI Home's settings) set HIDE_NON_SYSTEM_OVERLAY_WINDOWS, which
 * force-hides every app overlay, and the cover's pulled-down quick panel is a system
 * layer above any overlay. An activity is not subject to the first, and [RecentsPanel]
 * closes the quick panel before launching it.
 */
class CoverRecentsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RecentsPanel.attach(this)
        enableEdgeToEdge()
        // No window blur-behind: this phone reports cross-window blur off, so the flag
        // does nothing. CoverRecents draws its own blurred backdrop instead.

        val recents = RecentsPanel.controller(this)
        val cover = Displays.cover(this).displayId
        val main = Displays.main(this).displayId

        setContent {
            CoverDeckTheme {
                val entries by recents.entries.collectAsState()
                val loading by recents.loading.collectAsState()
                val kept by recents.keptOpen.collectAsState()
                CoverRecents(
                    entries = entries,
                    loading = loading,
                    keptOpen = kept,
                    onOpen = {
                        recents.resume(it, cover)
                        finish()
                    },
                    onClose = { recents.close(it) },
                    onCloseAll = {
                        recents.closeAll()
                        // One UI closes recents after Close all, unless kept apps remain.
                        if (recents.entries.value.isEmpty()) finish()
                    },
                    onToggleKeepOpen = recents::toggleKeepOpen,
                    onAppInfo = {
                        recents.openAppInfo(it, cover)
                        finish()
                    },
                    onOpenOnOtherScreen = {
                        recents.resume(it, main)
                        finish()
                    },
                    onDismiss = ::finish,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { RecentsPanel.controller(this@CoverRecentsActivity).refresh() }
    }

    /** Leaving recents (Home, opening an app, screen off) closes it, as stock recents does. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !isFinishing) finish()
    }

    override fun onDestroy() {
        RecentsPanel.detach(this)
        super.onDestroy()
    }
}
