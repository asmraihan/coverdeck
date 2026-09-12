package com.raihan.coverdeck.recents

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.util.Log
import com.raihan.coverdeck.core.Displays
import com.raihan.coverdeck.feature.RecentsController
import com.raihan.coverdeck.privileged.Privileged
import java.lang.ref.WeakReference

/**
 * Opens and closes cover recents from anywhere: the Home long-press, the notification
 * action, or "Try recents now".
 *
 * Launching goes through the Shizuku helper because it runs as shell, which may start
 * activities from the background. CoverDeck itself usually is in the background when
 * the Home button is held.
 */
object RecentsPanel {

    private const val TAG = "CoverDeck/Recents"

    @Volatile
    private var current: WeakReference<CoverRecentsActivity>? = null

    private var controller: RecentsController? = null

    val isShowing: Boolean
        get() = current?.get()?.let { !it.isFinishing && !it.isDestroyed } == true

    /** One controller for the process, so snapshots from the last open are reused. */
    fun controller(context: Context): RecentsController =
        controller ?: RecentsController(context.applicationContext).also { controller = it }

    fun toggle(context: Context) {
        if (isShowing) hide() else show(context)
    }

    fun show(context: Context) {
        if (isShowing) return
        val app = context.applicationContext
        val cover = Displays.cover(app).displayId
        val intent = Intent(app, CoverRecentsActivity::class.java)

        val launched = Privileged.with { service ->
            // The pulled-down cover quick panel is a system layer above any activity.
            service.collapseStatusBar()
            service.startActivityOnDisplay(intent, cover)
        } ?: false

        if (!launched) {
            // Without the helper this only succeeds while CoverDeck is in the foreground,
            // which is still enough for "Try recents now".
            Log.w(TAG, "helper could not launch recents; trying from the app")
            runCatching {
                app.startActivity(
                    Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    ActivityOptions.makeBasic().setLaunchDisplayId(cover).toBundle(),
                )
            }.onFailure { Log.e(TAG, "recents could not be launched", it) }
        }
    }

    fun hide() {
        current?.get()?.finish()
    }

    internal fun attach(activity: CoverRecentsActivity) {
        current = WeakReference(activity)
    }

    internal fun detach(activity: CoverRecentsActivity) {
        if (current?.get() === activity) current = null
    }
}
