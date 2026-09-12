package com.raihan.coverdeck.overlay

import android.content.Context
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Hosts Compose content in a raw WindowManager window.
 *
 * A ComposeView refuses to compose unless the view tree exposes a lifecycle, a
 * ViewModelStore and a SavedStateRegistry. Outside an Activity nobody provides those,
 * so this class is that owner. It is what lets the recents panel and the mirror chrome
 * live on display 1 as overlays rather than as activities, which matters because an
 * activity would be relocated to the inner screen the moment the device state changes.
 */
class ComposeOverlay(
    private val windowContext: Context,
    private val params: WindowManager.LayoutParams,
    private val content: @Composable () -> Unit,
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val windowManager = windowContext.getSystemService(WindowManager::class.java)

    private var view: ComposeView? = null

    val isShowing: Boolean get() = view != null

    fun show() {
        if (view != null) return
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        val composeView = ComposeView(windowContext).apply {
            setViewTreeLifecycleOwner(this@ComposeOverlay)
            setViewTreeViewModelStoreOwner(this@ComposeOverlay)
            setViewTreeSavedStateRegistryOwner(this@ComposeOverlay)
            setContent(content)
        }
        view = composeView
        runCatching { windowManager?.addView(composeView, params) }
            .onFailure { view = null; return }
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun hide() {
        val current = view ?: return
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        runCatching { windowManager?.removeViewImmediate(current) }
        store.clear()
        view = null
    }

    fun updateParams(block: WindowManager.LayoutParams.() -> Unit) {
        params.block()
        view?.let { runCatching { windowManager?.updateViewLayout(it, params) } }
    }

    fun asView(): View? = view
}
