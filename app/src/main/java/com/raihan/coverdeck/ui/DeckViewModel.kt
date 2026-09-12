package com.raihan.coverdeck.ui

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.raihan.coverdeck.core.Displays
import com.raihan.coverdeck.core.Panel
import com.raihan.coverdeck.feature.AutoRotate
import com.raihan.coverdeck.feature.DensityController
import com.raihan.coverdeck.feature.MirrorController
import com.raihan.coverdeck.feature.RecentsController
import com.raihan.coverdeck.feature.RotationController
import com.raihan.coverdeck.overlay.CoverDeckService
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
    val recents = RecentsController(context)
    val mirror = MirrorController(context)

    val privilegedStatus: StateFlow<Privileged.Status> = Privileged.status

    private val _route = MutableStateFlow(DeckRoute.Home)
    val route: StateFlow<DeckRoute> = _route.asStateFlow()

    private val _target = MutableStateFlow(Target.Cover)
    val target: StateFlow<Target> = _target.asStateFlow()

    private val _coverPanel = MutableStateFlow(Displays.cover(context))
    val coverPanel: StateFlow<Panel> = _coverPanel.asStateFlow()

    private val _mainPanel = MutableStateFlow(Displays.main(context))
    val mainPanel: StateFlow<Panel> = _mainPanel.asStateFlow()

    /** The strip is a persisted switch now, independent of whether the service is up. */
    val stripEnabled: StateFlow<Boolean> = CoverDeckService.stripEnabled

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    val targetDisplayId: Int
        get() = if (_target.value == Target.Cover) _coverPanel.value.displayId else _mainPanel.value.displayId

    val targetPanel: Panel
        get() = if (_target.value == Target.Cover) _coverPanel.value else _mainPanel.value

    fun rotationState(displayId: Int) = RotationController.state(displayId)

    fun navigate(route: DeckRoute) {
        _route.value = route
        if (route == DeckRoute.Recents) refreshRecents()
    }

    fun back() {
        _route.value = DeckRoute.Home
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
        mirror.refresh()
        if (mirror.recoverStrandedOverride()) {
            _notice.value = "Released a device-state override left over from a previous run."
        }
        val strandedDpi = density.recoverUnconfirmed(_coverPanel.value.displayId)
        if (strandedDpi != null) {
            _notice.value = "Reverted an unconfirmed ${strandedDpi} dpi change on the cover screen."
        }
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

    fun refreshRecents() {
        viewModelScope.launch { recents.refresh() }
    }

    fun toggleStrip() {
        if (stripEnabled.value) {
            if (CoverDeckService.isRunning) {
                CoverDeckService.send(context, CoverDeckService.ACTION_STRIP_OFF)
            }
        } else {
            if (!Settings.canDrawOverlays(context)) {
                _notice.value = "Grant \"Display over other apps\" first."
                return
            }
            CoverDeckService.send(context, CoverDeckService.ACTION_STRIP_ON)
        }
    }

    /**
     * Brings the service back when a persisted feature (auto-rotate, strip) is on but
     * the process was killed. Called from the foreground activity, where starting a
     * foreground service is always allowed.
     */
    fun resumePersistentFeatures() {
        if (CoverDeckService.isRunning) return
        val action = when {
            AutoRotate.enabled.value -> CoverDeckService.ACTION_AUTO_ROTATE_ON
            stripEnabled.value && Settings.canDrawOverlays(context) -> CoverDeckService.ACTION_STRIP_ON
            else -> return
        }
        runCatching { CoverDeckService.send(context, action) }
    }

    fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(context)

    /**
     * The panic button: undo everything CoverDeck changed, and only that.
     *
     * "Undo" means back to how each display was before CoverDeck first touched it, not
     * factory. The first build reset to factory and wiped a 450 dpi inner-screen
     * override the owner had set themselves; displays CoverDeck never changed are now
     * left strictly alone.
     */
    fun resetEverything() {
        mirror.stop(alsoReleaseDeviceState = true)
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
            "Mirroring and the gesture strip are off. Rotation and density were never changed."
        } else {
            "Restored ${undone.joinToString()} to how they were before CoverDeck."
        }
    }
}
