package com.raihan.coverdeck.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.LifecycleService
import com.raihan.coverdeck.MainActivity
import com.raihan.coverdeck.R
import com.raihan.coverdeck.core.Displays
import com.raihan.coverdeck.feature.AutoRotate
import com.raihan.coverdeck.feature.CoverAutoRotator
import com.raihan.coverdeck.feature.MirrorController
import com.raihan.coverdeck.feature.RecentsController
import com.raihan.coverdeck.feature.RotationController
import com.raihan.coverdeck.privileged.Privileged
import com.raihan.coverdeck.ui.CloseAllButton
import com.raihan.coverdeck.ui.RecentsCarousel
import com.raihan.coverdeck.ui.theme.CoverDeckTheme
import com.raihan.coverdeck.ui.theme.DeckColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Owns every window CoverDeck puts on the cover screen.
 *
 * A foreground service rather than an activity because all three surfaces - the
 * gesture strip, the recents panel and the mirror - must stay pinned to display 1
 * even while the device state is being overridden, and must outlive the app UI.
 */
class CoverDeckService : LifecycleService() {

    private lateinit var coverWindowContext: Context
    private lateinit var recents: RecentsController
    private lateinit var mirror: MirrorController

    private var strip: GestureStripView? = null
    private var recentsPanel: ComposeOverlay? = null
    private var mirrorOverlay: MirrorOverlay? = null
    private var autoRotator: CoverAutoRotator? = null

    private val coverPanel get() = Displays.cover(this)
    private val mainPanel get() = Displays.main(this)

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        recents = RecentsController(this)
        mirror = MirrorController(this)
        loadServicePrefs(this)

        // Windows are bound to a display through their context, so build one for the
        // cover panel up front and add everything through it.
        val display = getSystemService(DisplayManager::class.java)
            ?.getDisplay(coverPanel.displayId)
        coverWindowContext = if (display != null) {
            createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            Log.w(TAG, "cover display not reported; falling back to the default display")
            createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        // Each feature is independent now: restore whichever were on when the process
        // last died, rather than always forcing the strip onto the screen.
        if (_stripEnabled.value) showStrip()
        if (AutoRotate.enabled.value) startAutoRotate()

        // Shizuku binds asynchronously and may come up after the rotator's first
        // reading; push the rotation again once the link is actually ready.
        lifecycleScope.launch {
            Privileged.status.collect { status ->
                if (status is Privileged.Status.Ready) autoRotator?.reapply()
            }
        }
        lifecycleScope.launch {
            AutoRotate.reapplyRequests.drop(1).collect { autoRotator?.reapply() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SHOW_RECENTS -> toggleRecents()
            ACTION_START_MIRROR -> startMirror()
            ACTION_STOP_MIRROR -> stopMirror()
            ACTION_STRIP_ON -> setStrip(true)
            ACTION_STRIP_OFF -> setStrip(false)
            ACTION_AUTO_ROTATE_ON -> startAutoRotate()
            ACTION_AUTO_ROTATE_OFF -> stopAutoRotate()
            ACTION_STOP -> stopEverything()
        }
        stopIfIdle()
        return START_STICKY
    }

    // ---- feature switches ---------------------------------------------------

    private fun setStrip(on: Boolean) {
        setStripPref(this, on)
        if (on) showStrip() else hideStrip()
    }

    private fun startAutoRotate() {
        if (autoRotator == null) {
            autoRotator = CoverAutoRotator(this, coverPanel.displayId).also { it.start() }
        }
    }

    private fun stopAutoRotate() {
        autoRotator?.stop()
        autoRotator = null
    }

    /**
     * The notification's Stop. Turning auto-rotate off must also hand the cover back to
     * the system, or it would stay frozen at whatever angle it was last pointing.
     */
    private fun stopEverything() {
        setStripPref(this, false)
        hideStrip()
        stopMirror()
        hideRecents()
        if (AutoRotate.enabled.value) {
            RotationController.apply(coverPanel.displayId, RotationController.Mode.SYSTEM)
        }
        stopAutoRotate()
        stopSelf()
    }

    /** Nothing left to host means no reason to hold a foreground notification. */
    private fun stopIfIdle() {
        val busy = strip != null ||
            autoRotator != null ||
            mirrorOverlay?.isShowing == true ||
            recentsPanel?.isShowing == true
        if (!busy) stopSelf()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    // ---- gesture strip ---------------------------------------------------

    private fun showStrip() {
        if (strip != null) return
        val view = GestureStripView(
            context = coverWindowContext,
            onLongPress = { toggleRecents() },
            onSwipeUp = { sendKeyToCover(KeyEvent.KEYCODE_HOME) },
            onSwipeRight = { sendKeyToCover(KeyEvent.KEYCODE_BACK) },
            onDoubleTap = { openApp() },
        )
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(STRIP_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // The Flip 5 cover panel has a 66 px cutout band along the bottom edge
            // (camera housing, bottom-right). Sitting the strip flush with the bottom
            // buries it under that band and the corner radius, so lift it clear.
            y = bottomCutoutPx() + dp(4)
        }
        runCatching {
            coverWindowContext.getSystemService(WindowManager::class.java)?.addView(view, params)
            strip = view
        }.onFailure { Log.e(TAG, "could not add the gesture strip", it) }
    }

    private fun hideStrip() {
        strip?.let { view ->
            runCatching {
                coverWindowContext.getSystemService(WindowManager::class.java)
                    ?.removeViewImmediate(view)
            }
        }
        strip = null
    }

    private fun sendKeyToCover(keyCode: Int) {
        Privileged.with { it.injectKey(keyCode, coverPanel.displayId) }
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    // ---- recents panel ---------------------------------------------------

    private fun toggleRecents() {
        if (recentsPanel?.isShowing == true) {
            hideRecents()
        } else {
            showRecents()
        }
    }

    private fun showRecents() {
        lifecycleScope.launch { recents.refresh() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }

        val panel = ComposeOverlay(coverWindowContext, params) {
            CoverDeckTheme {
                val entries by recents.entries.collectAsState()
                val loading by recents.loading.collectAsState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DeckColors.Background.copy(alpha = 0.96f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { hideRecents() }
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Recents",
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                            color = DeckColors.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "Close",
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = DeckColors.TextTertiary,
                            modifier = Modifier.clickable { hideRecents() },
                        )
                    }
                    RecentsCarousel(
                        entries = entries,
                        loading = loading,
                        modifier = Modifier.weight(1f),
                        onOpen = {
                            recents.resume(it, coverPanel.displayId)
                            hideRecents()
                        },
                        onClose = { recents.close(it) },
                        onSendToOtherScreen = {
                            recents.resume(it, mainPanel.displayId)
                            hideRecents()
                        },
                    )
                    Box(Modifier.padding(horizontal = 14.dp)) {
                        CloseAllButton { recents.closeAll() }
                    }
                }
            }
        }
        recentsPanel = panel
        panel.show()
    }

    private fun hideRecents() {
        val wasShowing = recentsPanel != null
        recentsPanel?.hide()
        recentsPanel = null
        // Recents opened from the notification may be the only thing that started us.
        if (wasShowing) stopIfIdle()
    }

    // ---- mirror ----------------------------------------------------------

    private fun startMirror() {
        if (mirrorOverlay?.isShowing == true) return
        mirror.refresh()
        if (!mirror.keepInnerDisplayAwake()) {
            Log.e(TAG, "could not force an open device state; mirror would capture a dark panel")
            return
        }
        val overlay = MirrorOverlay(
            windowContext = coverWindowContext,
            controller = mirror,
            coverPanel = coverPanel,
            sourcePanel = mainPanel,
            onClosed = {
                mirrorOverlay = null
                stopIfIdle()
            },
        )
        mirrorOverlay = overlay
        overlay.show(mirror.state.value.fitMode)
    }

    private fun stopMirror() {
        mirrorOverlay?.hide()
        mirrorOverlay = null
        mirror.stop(alsoReleaseDeviceState = true)
    }

    // ---- lifecycle -------------------------------------------------------

    override fun onDestroy() {
        // Leaving a device-state override or a mirror running after the service dies
        // would strand the phone, so tear everything down unconditionally.
        stopMirror()
        hideRecents()
        hideStrip()
        // The auto-rotate *setting* survives (START_STICKY brings the loop back); only
        // the sensor registration is released here.
        autoRotator?.stop()
        autoRotator = null
        isRunning = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Cover screen controls", NotificationManager.IMPORTANCE_MIN)
                .apply { description = "Keeps cover auto-rotate, the gesture strip and the mirror alive." },
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
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .addAction(action("Recents", ACTION_SHOW_RECENTS))
            .addAction(action("Mirror", ACTION_START_MIRROR))
            .addAction(action("Stop", ACTION_STOP))
            .build()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Height of the cover panel's bottom cutout band, in pixels. 0 when there is none. */
    private fun bottomCutoutPx(): Int = runCatching {
        coverWindowContext.display?.cutout?.safeInsetBottom ?: 0
    }.getOrDefault(0)

    companion object {
        private const val TAG = "CoverDeck/Service"
        private const val CHANNEL_ID = "coverdeck_controls"
        private const val NOTIFICATION_ID = 4201
        private const val STRIP_HEIGHT_DP = 22

        const val ACTION_SHOW_RECENTS = "com.raihan.coverdeck.SHOW_RECENTS"
        const val ACTION_START_MIRROR = "com.raihan.coverdeck.START_MIRROR"
        const val ACTION_STOP_MIRROR = "com.raihan.coverdeck.STOP_MIRROR"
        const val ACTION_STRIP_ON = "com.raihan.coverdeck.STRIP_ON"
        const val ACTION_STRIP_OFF = "com.raihan.coverdeck.STRIP_OFF"
        const val ACTION_AUTO_ROTATE_ON = "com.raihan.coverdeck.AUTO_ROTATE_ON"
        const val ACTION_AUTO_ROTATE_OFF = "com.raihan.coverdeck.AUTO_ROTATE_OFF"
        const val ACTION_STOP = "com.raihan.coverdeck.STOP"

        private const val PREFS = "coverdeck_service"
        private const val KEY_STRIP = "strip_enabled"

        @Volatile
        var isRunning: Boolean = false
            private set

        private val _stripEnabled = MutableStateFlow(false)
        val stripEnabled: StateFlow<Boolean> = _stripEnabled.asStateFlow()

        fun loadServicePrefs(context: Context) {
            _stripEnabled.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_STRIP, false)
        }

        private fun setStripPref(context: Context, on: Boolean) {
            _stripEnabled.value = on
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_STRIP, on).apply()
        }

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
