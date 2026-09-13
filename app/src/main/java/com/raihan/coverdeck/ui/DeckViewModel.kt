package com.raihan.coverdeck.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.raihan.coverdeck.core.Displays
import com.raihan.coverdeck.core.Panel
import com.raihan.coverdeck.feature.AutoRotate
import com.raihan.coverdeck.feature.DensityController
import com.raihan.coverdeck.feature.RotationController
import com.raihan.coverdeck.mirror.MirrorSession
import com.raihan.coverdeck.nav.HomeLongPress
import com.raihan.coverdeck.overlay.CoverDeckService
import com.raihan.coverdeck.recents.RecentsPanel
import com.raihan.coverdeck.privileged.Privileged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class DeckRoute { Home, Rotation, Density, Recents, Mirror, Setup }

/** Which physical panel the rotation and DPI tiles are pointed at. */
enum class Target(val label: String) { Cover("Cover"), Main("Main") }

class DeckViewModel(app: Application) : AndroidViewModel(app) {

    private val context: Context get() = getApplication()

    val density = DensityController(context)

    val privilegedStatus: StateFlow<Privileged.Status> = Privileged.status

    private val _route = MutableStateFlow(DeckRoute.Home)
    val route: StateFlow<DeckRoute> = _route.asStateFlow()

    private val _target = MutableStateFlow(Target.Cover)
    val target: StateFlow<Target> = _target.asStateFlow()

    private val _coverPanel = MutableStateFlow(Displays.cover(context))
    val coverPanel: StateFlow<Panel> = _coverPanel.asStateFlow()

    private val _mainPanel = MutableStateFlow(Displays.main(context))
    val mainPanel: StateFlow<Panel> = _mainPanel.asStateFlow()

    /** The "hold the cover's Home button for recents" switch. */
    val homeLongPressEnabled: StateFlow<Boolean> = HomeLongPress.enabled

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    val targetDisplayId: Int
        get() = if (_target.value == Target.Cover) _coverPanel.value.displayId else _mainPanel.value.displayId

    val targetPanel: Panel
        get() = if (_target.value == Target.Cover) _coverPanel.value else _mainPanel.value

    fun rotationState(displayId: Int) = RotationController.state(displayId)

    fun navigate(route: DeckRoute) {
        _route.value = route
    }

    fun back() {
        _route.value = DeckRoute.Home
        // Home shows the cover's state, so leave an inner-screen selection behind on its page.
        if (_target.value != Target.Cover) setTarget(Target.Cover)
    }

    fun setTarget(target: Target) {
        _target.value = target
        refreshDisplayState()
    }

    fun dismissNotice() {
        _notice.value = null
    }

    /**
     * Called once the privileged link comes up. Cleans up anything a previous run may
     * have left applied before the user sees stale values in the tiles.
     */
    fun onPrivilegedReady() {
        viewModelScope.launch(MirrorSession.worker) {
            if (MirrorSession.recoverStranded()) {
                _notice.value = "Restored the inner screen after a mirror session that didn't close cleanly."
            }
        }
        val strandedDpi = density.recoverUnconfirmed(_coverPanel.value.displayId)
        if (strandedDpi != null) {
            _notice.value = "Reverted an unconfirmed ${strandedDpi} dpi change on the cover screen."
        }
        HomeLongPress.cleanUpLegacyAccessibility(context)
        resumePersistentFeatures()
        refreshDisplayState()
    }

    fun refreshDisplayState() {
        _coverPanel.value = Displays.cover(context)
        _mainPanel.value = Displays.main(context)
        RotationController.refresh(targetDisplayId)
        density.refresh(targetDisplayId)
    }

    fun applyRotation(mode: RotationController.Mode, forceAppsToObey: Boolean) {
        RotationController.apply(targetDisplayId, mode, forceAppsToObey)
    }

    fun setHomeLongPress(on: Boolean) {
        if (on) {
            // No overlay permission needed any more: recents is an activity, launched by
            // the Shizuku helper.
            HomeLongPress.setEnabled(true)
            CoverDeckService.send(context, CoverDeckService.ACTION_HOME_LONGPRESS_ON)
        } else {
            HomeLongPress.setEnabled(false)
            if (CoverDeckService.isRunning) {
                CoverDeckService.send(context, CoverDeckService.ACTION_HOME_LONGPRESS_OFF)
            }
        }
    }

    /** Opens the same recents panel the Home long-press does, so it can be tried out. */
    fun previewCoverRecents() = RecentsPanel.show(context)

    /**
     * Brings the service back when a persisted feature (auto-rotate, Home long-press) is
     * on but the process was killed. Called from the foreground activity, where starting a
     * foreground service is always allowed.
     */
    fun resumePersistentFeatures() {
        if (CoverDeckService.isRunning) return
        val action = when {
            AutoRotate.enabled.value -> CoverDeckService.ACTION_AUTO_ROTATE_ON
            HomeLongPress.enabled.value -> CoverDeckService.ACTION_HOME_LONGPRESS_ON
            else -> return
        }
        runCatching { CoverDeckService.send(context, action) }
    }

    fun stopMirror() {
        viewModelScope.launch(MirrorSession.worker) { MirrorSession.end() }
    }

    /**
     * The panic button: undo everything CoverDeck changed, and only that.
     *
     * "Undo" means back to how each display was before CoverDeck first touched it, not
     * factory. The first build reset to factory and wiped a 450 dpi inner-screen
     * override the owner had set themselves; displays CoverDeck never changed are now
     * left strictly alone.
     */
    fun resetEverything() {
        viewModelScope.launch(MirrorSession.worker) { MirrorSession.end() }
        CoverDeckService.stop(context)

        val undone = mutableListOf<String>()
        val panels = listOf(_coverPanel.value, _mainPanel.value).distinctBy { it.displayId }
        for (panel in panels) {
            val label = if (panel.displayId == _coverPanel.value.displayId) "cover" else "inner"
            if (RotationController.restoreOriginal(panel.displayId)) undone += "$label rotation"
            if (density.restoreOriginal(panel.displayId)) undone += "$label density"
        }
        refreshDisplayState()
        _notice.value = if (undone.isEmpty()) {
            "Mirroring and auto-rotate are off. Rotation and density were never changed."
        } else {
            "Restored ${undone.joinToString()} to how they were before CoverDeck."
        }
    }
}
