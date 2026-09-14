package com.raihan.coverdeck.feature

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import com.raihan.coverdeck.privileged.Privileged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings and live readings for CoverDeck's own cover-screen auto-rotate, shared
 * between the service that runs the sensor loop and the UI that shows it.
 *
 * Why this exists: on One UI the cover launcher (SubHomeActivity) and most cover apps
 * request SCREEN_ORIENTATION_NOSENSOR, which pins the natural orientation no matter
 * what the sensor says. Unlocking rotation ("thaw") therefore just hands control back
 * to Samsung, and the cover never turns. Locks do hold, because they pair
 * freezeRotation with ignore-orientation-request. So auto-rotate here is a lock that
 * follows the sensor.
 */
object AutoRotate {

    private var prefs: SharedPreferences? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _allowUpsideDown = MutableStateFlow(false)
    val allowUpsideDown: StateFlow<Boolean> = _allowUpsideDown.asStateFlow()

    /** Latest sensor quadrant (0..3) before calibration is applied. */
    private val _rawReading = MutableStateFlow<Int?>(null)
    val rawReading: StateFlow<Int?> = _rawReading.asStateFlow()

    private val _appliedRotation = MutableStateFlow<Int?>(null)
    val appliedRotation: StateFlow<Int?> = _appliedRotation.asStateFlow()

    private val _source = MutableStateFlow("stopped")
    val source: StateFlow<String> = _source.asStateFlow()

    private val _calibrated = MutableStateFlow(false)
    val calibrated: StateFlow<Boolean> = _calibrated.asStateFlow()

    /** While true the loop keeps reading but stops rotating, so calibration holds still. */
    @Volatile
    var calibrating: Boolean = false

    /**
     * Bumped when the mapping changes. The sensor only reports on movement, so without
     * this a new calibration would not show until the phone was turned again.
     */
    private val _reapplyRequests = MutableStateFlow(0)
    val reapplyRequests: StateFlow<Int> = _reapplyRequests.asStateFlow()

    fun requestReapply() {
        _reapplyRequests.value = _reapplyRequests.value + 1
    }

    private var offset = 0
    private var direction = 1

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _enabled.value = p.getBoolean(KEY_ENABLED, false)
        _allowUpsideDown.value = p.getBoolean(KEY_UPSIDE_DOWN, false)
        offset = p.getInt(KEY_OFFSET, 0)
        direction = p.getInt(KEY_DIRECTION, 1)
        _calibrated.value = p.contains(KEY_OFFSET)
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs?.edit()?.putBoolean(KEY_ENABLED, on)?.commit()
        if (!on) _appliedRotation.value = null
    }

    fun setAllowUpsideDown(allow: Boolean) {
        _allowUpsideDown.value = allow
        prefs?.edit()?.putBoolean(KEY_UPSIDE_DOWN, allow)?.apply()
        requestReapply()
    }

    internal fun publishReading(raw: Int) {
        _rawReading.value = raw
    }

    internal fun publishApplied(rotation: Int) {
        _appliedRotation.value = rotation
    }

    internal fun publishSource(name: String) {
        _source.value = name
    }

    /** Sensor quadrant to display rotation, through the calibration. */
    fun toRotation(raw: Int): Int = Math.floorMod((raw - offset) * direction, 4)

    /**
     * Two readings teach the mapping: the phone held the normal way, then turned a
     * quarter turn clockwise. Turning a device clockwise must give ROTATION_270, which
     * fixes both the offset and the direction. The direction matters here because the
     * cover faces the opposite way from the inner screen, so "clockwise" can arrive
     * mirrored depending on which half of the body carries the sensor.
     *
     * Returns false when the two readings are not a quarter turn apart.
     */
    fun calibrate(normal: Int, clockwise: Int): Boolean {
        val dir = when (Math.floorMod(clockwise - normal, 4)) {
            3 -> 1
            1 -> -1
            else -> return false
        }
        offset = normal
        direction = dir
        prefs?.edit()?.putInt(KEY_OFFSET, offset)?.putInt(KEY_DIRECTION, direction)?.commit()
        _calibrated.value = true
        return true
    }

    fun resetCalibration() {
        offset = 0
        direction = 1
        prefs?.edit()?.remove(KEY_OFFSET)?.remove(KEY_DIRECTION)?.commit()
        _calibrated.value = false
    }

    private const val PREFS = "coverdeck_autorotate"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_UPSIDE_DOWN = "allow_upside_down"
    private const val KEY_OFFSET = "calibration_offset"
    private const val KEY_DIRECTION = "calibration_direction"
}

/**
 * The sensor loop behind [AutoRotate]. Owned by CoverDeckService and pinned to one
 * display. It sleeps whenever that display is not on, which covers both the phone being
 * unfolded and the screen being off, so it costs nothing when the cover is not in use.
 */
class CoverAutoRotator(
    context: Context,
    private val displayId: Int,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val sensorManager = appContext.getSystemService(SensorManager::class.java)
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)

    private var running = false
    private var listening = false
    private var lastApplied: Int? = null
    private var pendingTarget: Int? = null

    /** The orientation sensor's last value as it arrived, before [coverFrame], and when. */
    private var lastSensorValue = -1
    private var lastSensorAt = 0L
    private var innerOn = false

    // ---- sensor sources ---------------------------------------------------

    /**
     * Samsung's fused "auto_rotation Screen Orientation Sensor" (hidden type 27,
     * android.sensor.device_orientation). It already reports a debounced quadrant, and
     * it is exactly what the framework's own rotation judge consumes on this phone.
     */
    private val orientationSensor: Sensor? =
        runCatching { sensorManager?.getDefaultSensor(TYPE_DEVICE_ORIENTATION) }.getOrNull()

    private val orientationListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val raw = event.values.firstOrNull()?.toInt() ?: return
            if (raw !in 0..3) return
            lastSensorValue = raw
            lastSensorAt = SystemClock.uptimeMillis()
            onReading(coverFrame(raw))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Public-API fallback for builds that hide type 27 from apps. */
    private val accelerometerListener = object : OrientationEventListener(appContext) {
        private var current = -1

        override fun onOrientationChanged(degrees: Int) {
            if (degrees == ORIENTATION_UNKNOWN) return // lying flat: keep what we have
            val nearest = Math.floorMod(((degrees + 45) / 90) * 90, 360)
            val distance = minOf(Math.floorMod(degrees - nearest, 360), Math.floorMod(nearest - degrees, 360))
            // Hysteresis: only accept a new quadrant well inside its 90 degree sector,
            // so a phone held near 45 degrees does not flap between two rotations.
            if (distance > HYSTERESIS_DEGREES && current != -1) return
            // Device turned clockwise by 90 degrees needs ROTATION_270, and so on.
            val quadrant = when (nearest) {
                0 -> Surface.ROTATION_0
                90 -> Surface.ROTATION_270
                180 -> Surface.ROTATION_180
                else -> Surface.ROTATION_90
            }
            if (quadrant != current) {
                current = quadrant
                onReading(quadrant)
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(id: Int) {
            if (id == displayId) syncListening()
            if (id == Display.DEFAULT_DISPLAY) onInnerScreenChanged()
        }

        // Folded, the inner display is disabled rather than just off, so it can come and go
        // as a whole display too.
        override fun onDisplayAdded(id: Int) {
            if (id == displayId) syncListening()
            if (id == Display.DEFAULT_DISPLAY) onInnerScreenChanged()
        }

        override fun onDisplayRemoved(id: Int) {
            if (id == displayId) syncListening()
            if (id == Display.DEFAULT_DISPLAY) onInnerScreenChanged()
        }
    }

    // ---- lifecycle ----------------------------------------------------------

    fun start() {
        if (running) return
        running = true
        innerOn = innerScreenOn()
        displayManager?.registerDisplayListener(displayListener, handler)
        syncListening()
    }

    fun stop() {
        if (!running) return
        running = false
        displayManager?.unregisterDisplayListener(displayListener)
        stopListening()
        handler.removeCallbacksAndMessages(null)
        lastApplied = null
        AutoRotate.publishSource("stopped")
    }

    /**
     * Forgets what was last applied and re-sends the current target. Used when the
     * privileged link comes up late, and after a fold, since One UI can reset the cover
     * rotation behind our back.
     */
    fun reapply() {
        lastApplied = null
        AutoRotate.rawReading.value?.let { onReading(it) }
    }

    private fun coverIsOn(): Boolean =
        displayManager?.getDisplay(displayId)?.state == Display.STATE_ON

    private fun syncListening() {
        if (running && coverIsOn()) {
            if (!listening) startListening()
        } else if (listening) {
            stopListening()
        }
    }

    private fun startListening() {
        listening = true
        lastApplied = null
        val sensor = orientationSensor
        if (sensor != null &&
            sensorManager?.registerListener(orientationListener, sensor, SensorManager.SENSOR_DELAY_UI, handler) == true
        ) {
            AutoRotate.publishSource("device orientation sensor")
        } else if (accelerometerListener.canDetectOrientation()) {
            accelerometerListener.enable()
            AutoRotate.publishSource("accelerometer")
        } else {
            AutoRotate.publishSource("no orientation sensor")
            Log.w(TAG, "no usable orientation sensor; cover auto-rotate cannot run")
        }
        // On-change sensors do not always repeat their last value on registration,
        // so push the last known reading through straight away.
        AutoRotate.rawReading.value?.let { onReading(it) }
    }

    private fun stopListening() {
        listening = false
        runCatching { sensorManager?.unregisterListener(orientationListener) }
        runCatching { accelerometerListener.disable() }
        handler.removeCallbacks(applyRunnable)
        AutoRotate.publishSource(if (running) "paused (cover off)" else "stopped")
    }

    // ---- sensor frame -----------------------------------------------------------

    /**
     * Samsung's orientation sensor answers for whichever screen is the phone's main one.
     * Folded with only the cover on, that is the cover. While CoverDeck mirrors, both
     * screens are on and it answers for the inner screen instead. Folded, the inner screen
     * sits flipped over the hinge behind the cover, so upright and upside down trade places
     * while sideways stays sideways: mirroring used to turn a 0° cover to 180° and back.
     * Readings are put back into the cover's frame as they arrive.
     */
    private fun coverFrame(raw: Int): Int =
        if (innerOn && (raw == Surface.ROTATION_0 || raw == Surface.ROTATION_180)) (raw + 2) % 4 else raw

    private fun innerScreenOn(): Boolean =
        displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON

    /**
     * The sensor switches screens together with the device state, and its first reading in
     * the new frame can arrive just before the display reports the change. A reading that
     * recent is taken again in the new frame; later readings arrive in it anyway.
     */
    private fun onInnerScreenChanged() {
        val on = innerScreenOn()
        if (on == innerOn) return
        innerOn = on
        if (!listening || lastSensorValue < 0) return
        if (SystemClock.uptimeMillis() - lastSensorAt <= FRAME_SWITCH_WINDOW_MS) {
            onReading(coverFrame(lastSensorValue))
        }
    }

    // ---- decision -------------------------------------------------------------

    private fun onReading(raw: Int) {
        AutoRotate.publishReading(raw)
        // The switch flips synchronously while the service's OFF command arrives later;
        // checking it here stops a late reading re-freezing a cover that was just
        // handed back to the system or restored.
        if (AutoRotate.calibrating || !AutoRotate.enabled.value) return

        val target = AutoRotate.toRotation(raw)
        if (target == Surface.ROTATION_180 && !AutoRotate.allowUpsideDown.value) return
        if (target == lastApplied) return

        // A short settle time absorbs the jitter of a phone being picked up.
        pendingTarget = target
        handler.removeCallbacks(applyRunnable)
        handler.postDelayed(applyRunnable, SETTLE_MS)
    }

    private val applyRunnable = Runnable {
        val target = pendingTarget ?: return@Runnable
        if (!AutoRotate.enabled.value || AutoRotate.calibrating) return@Runnable
        // Only record success when the privileged link actually took the call; a null
        // here means Shizuku is not connected yet and reapply() will retry.
        val done = Privileged.with { it.freezeRotation(displayId, target) }
        if (done != null) {
            lastApplied = target
            AutoRotate.publishApplied(target)
            Log.d(TAG, "cover rotation -> ${target * 90} deg (raw ${AutoRotate.rawReading.value})")
        }
    }

    private companion object {
        const val TAG = "CoverDeck/AutoRotate"
        const val TYPE_DEVICE_ORIENTATION = 27
        const val SETTLE_MS = 250L
        const val HYSTERESIS_DEGREES = 30
        const val FRAME_SWITCH_WINDOW_MS = 1_000L
    }
}
