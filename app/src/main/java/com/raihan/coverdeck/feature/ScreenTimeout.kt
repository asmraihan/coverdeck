package com.raihan.coverdeck.feature

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.raihan.coverdeck.privileged.Privileged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How long the cover stays lit without a touch.
 *
 * One UI keeps two timeouts, and the cover uses both:
 *  - `cover_screen_timeout` (System, seconds) is what SystemUI puts on the cover home
 *    screen and quick panel windows. It is read live and accepts any value.
 *  - Once an app is in front on the cover, that window override is gone and the
 *    phone's normal `screen_off_timeout` (System, milliseconds) applies instead.
 * A choice here sets both, so the cover behaves the same everywhere. The values from
 * before CoverDeck first changed them are kept for Reset settings.
 */
object ScreenTimeout {

    data class Option(val seconds: Int, val label: String)

    val options = listOf(
        Option(30, "30 s"),
        Option(60, "1 min"),
        Option(300, "5 min"),
        Option(600, "10 min"),
        Option(1800, "30 min"),
    )

    data class State(
        /** Cover home screen and quick panel, seconds. */
        val coverSeconds: Int = 0,
        /** Apps on the cover (the phone's screen timeout), seconds. */
        val appSeconds: Int = 0,
        /** Developer options' "Stay awake": the screen never times out while charging. */
        val stayAwakeWhileCharging: Boolean = false,
    )

    private const val PREFS = "coverdeck_timeout"
    private const val KEY_HELD = "originals_held"
    private const val KEY_COVER = "orig_cover_seconds"
    private const val KEY_SCREEN = "orig_screen_off_ms"

    private const val COVER_KEY = "cover_screen_timeout"
    private const val COVER_DEFAULT_SECONDS = 10

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Either timeout can also change from One UI's own Settings.
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        val resolver = context.applicationContext.contentResolver
        resolver.registerContentObserver(Settings.System.getUriFor(COVER_KEY), false, observer)
        resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_OFF_TIMEOUT), false, observer)
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.STAY_ON_WHILE_PLUGGED_IN), false, observer)
        refresh()
    }

    fun refresh() {
        val resolver = appContext?.contentResolver ?: return
        _state.value = State(
            coverSeconds = Settings.System.getInt(resolver, COVER_KEY, COVER_DEFAULT_SECONDS),
            appSeconds = Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, 30_000) / 1000,
            stayAwakeWhileCharging = Settings.Global.getInt(resolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0) != 0,
        )
    }

    /** Sets both timeouts. Returns false without Shizuku. */
    fun apply(seconds: Int): Boolean {
        val resolver = appContext?.contentResolver ?: return false
        val p = prefs ?: return false
        if (!p.getBoolean(KEY_HELD, false)) {
            p.edit()
                .putInt(KEY_COVER, Settings.System.getInt(resolver, COVER_KEY, COVER_DEFAULT_SECONDS))
                .putInt(KEY_SCREEN, Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, 30_000))
                .putBoolean(KEY_HELD, true)
                .commit()
        }
        val done = write(seconds, seconds * 1000)
        refresh()
        return done
    }

    /** Puts back the timeouts from before CoverDeck changed them. False when never changed. */
    fun restoreOriginal(): Boolean {
        val p = prefs ?: return false
        if (!p.getBoolean(KEY_HELD, false)) return false
        val done = write(p.getInt(KEY_COVER, COVER_DEFAULT_SECONDS), p.getInt(KEY_SCREEN, 30_000))
        if (done) p.edit().putBoolean(KEY_HELD, false).commit()
        refresh()
        return done
    }

    private fun write(coverSeconds: Int, screenOffMillis: Int): Boolean = Privileged.with {
        it.exec("settings put system $COVER_KEY $coverSeconds")
        it.exec("settings put system ${Settings.System.SCREEN_OFF_TIMEOUT} $screenOffMillis")
        true
    } ?: false

    fun label(seconds: Int): String = options.firstOrNull { it.seconds == seconds }?.label
        ?: when {
            seconds <= 0 -> "Off"
            seconds % 60 == 0 -> "${seconds / 60} min"
            else -> "$seconds s"
        }
}
