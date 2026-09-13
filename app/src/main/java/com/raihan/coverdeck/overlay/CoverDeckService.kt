package com.raihan.coverdeck.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.raihan.coverdeck.MainActivity
import com.raihan.coverdeck.R
import com.raihan.coverdeck.core.Displays
import com.raihan.coverdeck.feature.AutoRotate
import com.raihan.coverdeck.feature.CoverAutoRotator
import com.raihan.coverdeck.feature.RotationController
import com.raihan.coverdeck.mirror.MirrorSession
import com.raihan.coverdeck.nav.HomeLongPress
import com.raihan.coverdeck.nav.HomeLongPressWatcher
import com.raihan.coverdeck.privileged.Privileged
import com.raihan.coverdeck.recents.RecentsPanel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Hosts the long-running cover features that need no screen of their own: auto-rotate and
 * the Home long-press watcher.
 *
 * Recents (an activity) and the mirror (a helper-owned window) are only launched from its
 * notification. It stops itself as soon as neither hosted feature
 * is running.
 */
class CoverDeckService : LifecycleService() {

    private var autoRotator: CoverAutoRotator? = null
    private var homeWatcher: HomeLongPressWatcher? = null

    private val coverPanel get() = Displays.cover(this)

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification())

        // Restore whatever was on when the process last died.
        if (AutoRotate.enabled.value) startAutoRotate()
        if (HomeLongPress.enabled.value) startHomeWatcher()

        // Shizuku binds asynchronously and may come up after these features start; the
        // rotator needs to push its rotation again and the Home watcher needs to register
        // with what may be a brand-new helper process.
        lifecycleScope.launch {
            Privileged.status.collect { status ->
                if (status is Privileged.Status.Ready) {
                    autoRotator?.reapply()
                    homeWatcher?.reconnect()
                }
            }
        }
        lifecycleScope.launch {
            AutoRotate.reapplyRequests.drop(1).collect { autoRotator?.reapply() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SHOW_RECENTS -> RecentsPanel.toggle(this)
            ACTION_START_MIRROR -> MirrorSession.start()
            ACTION_AUTO_ROTATE_ON -> startAutoRotate()
            ACTION_AUTO_ROTATE_OFF -> stopAutoRotate()
            ACTION_HOME_LONGPRESS_ON -> startHomeWatcher()
            ACTION_HOME_LONGPRESS_OFF -> stopHomeWatcher()
            ACTION_STOP -> stopEverything()
        }
        stopIfIdle()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    // ---- auto-rotate ------------------------------------------------------------

    private fun startAutoRotate() {
        if (autoRotator == null) {
            autoRotator = CoverAutoRotator(this, coverPanel.displayId).also { it.start() }
        }
    }

    private fun stopAutoRotate() {
        autoRotator?.stop()
        autoRotator = null
    }

    // ---- Home long-press --------------------------------------------------------

    private fun startHomeWatcher() {
        if (homeWatcher == null) {
            homeWatcher = HomeLongPressWatcher(this).also { it.start() }
        }
    }

    private fun stopHomeWatcher() {
        homeWatcher?.stop()
        homeWatcher = null
    }

    // ---- lifecycle ----------------------------------------------------------------

    /**
     * The notification's Stop. Turning auto-rotate off must also hand the cover back to
     * the system, or it would stay frozen at whatever angle it was last pointing.
     */
    private fun stopEverything() {
        RecentsPanel.hide()
        lifecycleScope.launch(MirrorSession.worker) { MirrorSession.end() }
        if (AutoRotate.enabled.value) {
            RotationController.apply(coverPanel.displayId, RotationController.Mode.SYSTEM)
        }
        stopAutoRotate()
        HomeLongPress.setEnabled(false)
        stopHomeWatcher()
        stopSelf()
    }

    /** Nothing left to host means no reason to hold a foreground notification. */
    private fun stopIfIdle() {
        if (autoRotator == null && homeWatcher == null) stopSelf()
    }

    override fun onDestroy() {
        // The feature *settings* survive; only the sensor and log-reader registrations
        // are released here.
        autoRotator?.stop()
        autoRotator = null
        homeWatcher?.stop()
        homeWatcher = null
        isRunning = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Cover screen controls", NotificationManager.IMPORTANCE_MIN)
                .apply { description = "Keeps cover auto-rotate and Home long-press running." },
        )

        fun action(label: String, intentAction: String) = Notification.Action.Builder(
            null,
            label,
            PendingIntent.getService(
                this,
                intentAction.hashCode(),
                Intent(this, CoverDeckService::class.java).setAction(intentAction),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("CoverDeck")
            .setContentText("Cover screen controls active")
            .setSmallIcon(R.drawable.ic_coverdeck_notification)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            .addAction(action("Recents", ACTION_SHOW_RECENTS))
            .addAction(action("Mirror", ACTION_START_MIRROR))
            .addAction(action("Stop", ACTION_STOP))
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "coverdeck_controls"
        private const val NOTIFICATION_ID = 4201

        const val ACTION_SHOW_RECENTS = "com.raihan.coverdeck.SHOW_RECENTS"
        const val ACTION_START_MIRROR = "com.raihan.coverdeck.START_MIRROR"
        const val ACTION_AUTO_ROTATE_ON = "com.raihan.coverdeck.AUTO_ROTATE_ON"
        const val ACTION_AUTO_ROTATE_OFF = "com.raihan.coverdeck.AUTO_ROTATE_OFF"
        const val ACTION_HOME_LONGPRESS_ON = "com.raihan.coverdeck.HOME_LONGPRESS_ON"
        const val ACTION_HOME_LONGPRESS_OFF = "com.raihan.coverdeck.HOME_LONGPRESS_OFF"
        const val ACTION_STOP = "com.raihan.coverdeck.STOP"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun stop(context: Context) {
            if (isRunning) send(context, ACTION_STOP)
        }

        /**
         * Always through startForegroundService: a plain startService is refused when it
         * would have to create the service, and the service promotes itself in onCreate.
         */
        fun send(context: Context, action: String) {
            context.startForegroundService(
                Intent(context, CoverDeckService::class.java).setAction(action),
            )
        }
    }
}
