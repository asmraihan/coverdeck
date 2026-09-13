package com.raihan.coverdeck.privileged

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.raihan.coverdeck.INavGestureListener
import com.raihan.coverdeck.IPrivilegedService
import com.raihan.coverdeck.model.TaskItem
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.system.exitProcess

/**
 * The privileged half of CoverDeck.
 *
 * Shizuku loads this class into a process running as shell (uid 2000), so every call
 * below executes with shell's permission set. Nothing here touches the UI; the app
 * process talks to it only over the [IPrivilegedService] binder.
 *
 * House rule for this file: a hidden-API call is always paired with a shell-command
 * fallback where one exists, because Samsung moves these signatures between One UI
 * releases and a dead feature is worse than a slow one.
 */
class PrivilegedService(private val context: Context?) : IPrivilegedService.Stub() {

    @Suppress("unused") // Shizuku picks whichever constructor the remote build offers.
    constructor() : this(Hidden.systemContext())

    private val ctx: Context? = context ?: Hidden.systemContext()

    private val mirrorEngines: List<MirrorEngine> by lazy {
        listOf(VirtualDisplayEngine(ctx), ScreenrecordEngine())
    }

    private var activeMirror: MirrorEngine? = null

    private val navWatcher = NavLogWatcher()

    init {
        Hidden.init()
        Log.i(Hidden.TAG, "PrivilegedService up as uid=${Process.myUid()} sdk=${Build.VERSION.SDK_INT}")
    }

    override fun destroy() {
        runCatching { stopMirror() }
        runCatching { navWatcher.stop() }
        Log.i(Hidden.TAG, "PrivilegedService destroyed")
        // Shizuku only *asks* the service to stop; the process lives on unless it exits.
        // Without this every reinstall and app restart left another ~200 MB shell-uid
        // process behind (ten were found running on-device).
        exitProcess(0)
    }

    override fun ping(): String =
        "uid=${Process.myUid()} sdk=${Build.VERSION.SDK_INT} ctx=${ctx != null}"

    override fun getRemoteUid(): Int = Process.myUid()

    override fun exec(cmd: String): String = Hidden.sh(cmd)

    // =====================================================================
    // Rotation
    // =====================================================================

    override fun getRotation(displayId: Int): Int {
        runCatching {
            val global = Class.forName("android.hardware.display.DisplayManagerGlobal")
                .getMethod("getInstance").invoke(null)
            val info = Hidden.callAny(
                global, "getDisplayInfo",
                Hidden.sig(Int::class.java) to Hidden.args(displayId),
            ) ?: return@runCatching
            val field = info.javaClass.getField("rotation")
            return field.getInt(info)
        }
        // Verified on One UI 8.5: "wm user-rotation -d N" prints bare "free", or
        // "lock <rotation>" when a rotation is pinned. No key=value pairs.
        val out = Hidden.sh("wm user-rotation -d $displayId").trim()
        return Regex("lock\\s+(\\d)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    override fun freezeRotation(displayId: Int, rotation: Int) {
        val wm = Hidden.windowManager
        val done = Hidden.callVoid(
            wm, "freezeDisplayRotation",
            // Android 14+ added a caller tag for rotation attribution.
            Hidden.sig(Int::class.java, Int::class.java, String::class.java) to
                Hidden.args(displayId, rotation, CALLER),
            Hidden.sig(Int::class.java, Int::class.java) to Hidden.args(displayId, rotation),
        )
        if (!done) {
            Hidden.sh("wm user-rotation -d $displayId lock $rotation")
        }
    }

    override fun thawRotation(displayId: Int) {
        val wm = Hidden.windowManager
        val done = Hidden.callVoid(
            wm, "thawDisplayRotation",
            Hidden.sig(Int::class.java, String::class.java) to Hidden.args(displayId, CALLER),
            Hidden.sig(Int::class.java) to Hidden.args(displayId),
        )
        if (!done) {
            Hidden.sh("wm user-rotation -d $displayId free")
        }
    }

    override fun isRotationFrozen(displayId: Int): Boolean {
        val wm = Hidden.windowManager
        val frozen = Hidden.callAny(
            wm, "isDisplayRotationFrozen",
            Hidden.sig(Int::class.java) to Hidden.args(displayId),
        ) as? Boolean
        if (frozen != null) return frozen
        return Hidden.sh("wm user-rotation -d $displayId").trim().startsWith("lock")
    }

    /**
     * Without this, an app that declares a fixed orientation overrides the lock the
     * moment it comes to the foreground. Setting it on the cover display is what makes
     * "locked" actually mean locked.
     */
    override fun setIgnoreOrientationRequest(displayId: Int, ignore: Boolean) {
        val done = Hidden.callVoid(
            Hidden.windowManager, "setIgnoreOrientationRequest",
            Hidden.sig(Int::class.java, Boolean::class.java) to Hidden.args(displayId, ignore),
        )
        if (!done) {
            Hidden.sh("wm set-ignore-orientation-request -d $displayId $ignore")
        }
    }

    override fun setFixedToUserRotation(displayId: Int, mode: Int) {
        val done = Hidden.callVoid(
            Hidden.windowManager, "setFixedToUserRotation",
            Hidden.sig(Int::class.java, Int::class.java) to Hidden.args(displayId, mode),
        )
        if (!done) {
            val word = when (mode) {
                1 -> "disabled"
                2 -> "enabled"
                3 -> "enabled_if_no_auto_rotation"
                else -> "default"
            }
            Hidden.sh("wm fixed-to-user-rotation -d $displayId $word")
        }
    }

    // =====================================================================
    // Density
    // =====================================================================

    override fun getBaseDensity(displayId: Int): Int {
        (Hidden.callAny(
            Hidden.windowManager, "getInitialDisplayDensity",
            Hidden.sig(Int::class.java) to Hidden.args(displayId),
        ) as? Int)?.let { return it }
        return parseDensity(Hidden.sh("wm density -d $displayId"), "Physical density")
    }

    override fun getEffectiveDensity(displayId: Int): Int {
        (Hidden.callAny(
            Hidden.windowManager, "getBaseDisplayDensity",
            Hidden.sig(Int::class.java) to Hidden.args(displayId),
        ) as? Int)?.let { return it }
        val out = Hidden.sh("wm density -d $displayId")
        val override = parseDensity(out, "Override density")
        return if (override > 0) override else parseDensity(out, "Physical density")
    }

    override fun setDensity(displayId: Int, density: Int) {
        val done = Hidden.callVoid(
            Hidden.windowManager, "setForcedDisplayDensityForUser",
            Hidden.sig(Int::class.java, Int::class.java, Int::class.java) to
                Hidden.args(displayId, density, USER_SYSTEM),
        )
        if (!done) {
            Hidden.sh("wm density $density -d $displayId")
        }
    }

    override fun resetDensity(displayId: Int) {
        val done = Hidden.callVoid(
            Hidden.windowManager, "clearForcedDisplayDensityForUser",
            Hidden.sig(Int::class.java, Int::class.java) to Hidden.args(displayId, USER_SYSTEM),
        )
        if (!done) {
            Hidden.sh("wm density reset -d $displayId")
        }
    }

    private fun parseDensity(output: String, label: String): Int =
        Regex("$label:\\s*(\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    // =====================================================================
    // Recents
    // =====================================================================

    override fun getRecentTasks(max: Int): List<TaskItem> {
        val atm = Hidden.activityTaskManager ?: Hidden.activityManager ?: return emptyList()
        val slice = Hidden.callAny(
            atm, "getRecentTasks",
            Hidden.sig(Int::class.java, Int::class.java, Int::class.java) to
                Hidden.args(max, RECENT_IGNORE_UNAVAILABLE, USER_SYSTEM),
        ) ?: return emptyList()

        @Suppress("UNCHECKED_CAST")
        val raw = runCatching {
            slice.javaClass.getMethod("getList").invoke(slice) as List<Any>
        }.getOrElse {
            Log.e(Hidden.TAG, "could not unwrap ParceledListSlice", it)
            return emptyList()
        }

        return raw.mapNotNull { info ->
            // Launchers and the recents screen itself are tasks too. Filter them by the
            // type the system gives them, not by package: com.android.settings declares a
            // HOME activity (FallbackHome), and hiding "home packages" hid every Settings
            // task along with it.
            val type = runCatching { info.javaClass.getField("topActivityType").getInt(info) }.getOrNull()
            if (type == ACTIVITY_TYPE_HOME || type == ACTIVITY_TYPE_RECENTS) null else toTaskItem(info)
        }
    }

    private fun toTaskItem(info: Any): TaskItem? = runCatching {
        fun <T> field(name: String): T? = runCatching {
            @Suppress("UNCHECKED_CAST")
            info.javaClass.getField(name).get(info) as T?
        }.getOrNull()

        val taskId = field<Int>("taskId") ?: field<Int>("persistentId") ?: return null
        val top = field<android.content.ComponentName>("topActivity")
        val base = field<android.content.ComponentName>("baseActivity")
        val component = top ?: base
        val pkg = component?.packageName
            ?: field<android.content.Intent>("baseIntent")?.component?.packageName
            ?: return null

        // TaskDescription carries the label the app itself set for recents; it is the
        // closest thing to what One UI shows, so prefer it over the PM label.
        val label = runCatching {
            val desc = info.javaClass.getField("taskDescription").get(info)
            desc?.javaClass?.getMethod("getLabel")?.invoke(desc) as? String
        }.getOrNull()

        TaskItem(
            taskId = taskId,
            packageName = pkg,
            activityName = component?.className,
            label = label,
            userId = field<Int>("userId") ?: USER_SYSTEM,
            displayId = field<Int>("displayId") ?: 0,
            lastActiveTime = field<Long>("lastActiveTime") ?: 0L,
            isRunning = field<Boolean>("isRunning") ?: true,
            isExcluded = false,
        )
    }.getOrNull()

    override fun getTaskSnapshot(taskId: Int, maxDim: Int): Bitmap? {
        val atm = Hidden.activityTaskManager ?: return null
        // A task only has a stored snapshot once it has been backgrounded, so the app
        // currently on screen came back empty. Try the cached low-res copy, then the
        // full-res one, then ask the window manager to take a fresh snapshot.
        val snapshot = Hidden.callAny(
            atm, "getTaskSnapshot",
            Hidden.sig(Int::class.java, Boolean::class.java, Boolean::class.java) to
                Hidden.args(taskId, true, true),
            Hidden.sig(Int::class.java, Boolean::class.java) to Hidden.args(taskId, true),
        ) ?: Hidden.callAny(
            atm, "getTaskSnapshot",
            Hidden.sig(Int::class.java, Boolean::class.java) to Hidden.args(taskId, false),
        ) ?: Hidden.callAny(
            atm, "takeTaskSnapshot",
            Hidden.sig(Int::class.java, Boolean::class.java) to Hidden.args(taskId, false),
            Hidden.sig(Int::class.java) to Hidden.args(taskId),
        ) ?: return null

        return runCatching {
            val buffer = snapshot.javaClass.getMethod("getHardwareBuffer").invoke(snapshot)
                as? android.hardware.HardwareBuffer ?: return null
            val colorSpace = runCatching {
                snapshot.javaClass.getMethod("getColorSpace").invoke(snapshot)
                    as? android.graphics.ColorSpace
            }.getOrNull() ?: android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)

            val hw = Bitmap.wrapHardwareBuffer(buffer, colorSpace) ?: return null
            try {
                downscale(hw, maxDim)
            } finally {
                runCatching { buffer.close() }
            }
        }.onFailure { Log.w(Hidden.TAG, "snapshot for $taskId failed: ${it.message}") }.getOrNull()
    }

    /**
     * Snapshots come back at panel resolution. A 1080x2640 ARGB bitmap is ~11 MB and
     * a Binder transaction dies at 1 MB, so this shrinks to a thumbnail and converts
     * off the hardware buffer before it ever crosses the boundary.
     */
    private fun downscale(source: Bitmap, maxDim: Int): Bitmap {
        val longest = max(source.width, source.height).coerceAtLeast(1)
        val scale = (maxDim.toFloat() / longest).coerceAtMost(1f)
        val w = (source.width * scale).roundToInt().coerceAtLeast(1)
        val h = (source.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        // RGB_565 halves the payload and thumbnails do not miss the alpha channel.
        return scaled.copy(Bitmap.Config.RGB_565, false) ?: scaled
    }

    override fun launchTask(taskId: Int, displayId: Int) {
        val options = launchOptions(displayId)
        val done = Hidden.callAny(
            Hidden.activityTaskManager, "startActivityFromRecents",
            Hidden.sig(Int::class.java, Bundle::class.java) to Hidden.args(taskId, options),
        )
        if (done == null) Log.w(Hidden.TAG, "startActivityFromRecents unavailable for $taskId")
    }

    override fun removeTask(taskId: Int) {
        val done = Hidden.callAny(
            Hidden.activityTaskManager, "removeTask",
            Hidden.sig(Int::class.java) to Hidden.args(taskId),
        ) ?: Hidden.callAny(
            Hidden.activityManager, "removeTask",
            Hidden.sig(Int::class.java) to Hidden.args(taskId),
        )
        if (done == null) Log.w(Hidden.TAG, "removeTask unavailable for $taskId")
    }

    override fun moveTaskToDisplay(taskId: Int, displayId: Int) {
        val moved = Hidden.callVoid(
            Hidden.activityTaskManager, "moveRootTaskToDisplay",
            Hidden.sig(Int::class.java, Int::class.java) to Hidden.args(taskId, displayId),
        )
        if (!moved) Hidden.sh("am display move-stack $taskId $displayId")
    }

    override fun launchPackage(packageName: String, displayId: Int): Boolean {
        val component = ctx?.packageManager
            ?.getLaunchIntentForPackage(packageName)
            ?.component
            ?.flattenToShortString()
        val out = if (component != null) {
            Hidden.sh("am start --display $displayId -n $component")
        } else {
            Hidden.sh(
                "am start --display $displayId -a android.intent.action.MAIN " +
                    "-c android.intent.category.LAUNCHER $packageName",
            )
        }
        return !out.contains("Error", ignoreCase = true)
    }

    private fun launchOptions(displayId: Int): Bundle? = runCatching {
        val optionsClass = Class.forName("android.app.ActivityOptions")
        val options = optionsClass.getMethod("makeBasic").invoke(null)
        optionsClass.getMethod("setLaunchDisplayId", Int::class.java).invoke(options, displayId)
        optionsClass.getMethod("toBundle").invoke(options) as Bundle
    }.getOrNull()

    // =====================================================================
    // Device state and mirroring
    // =====================================================================

    /**
     * The Flip 5 reports CLOSED(0), TENT(1), HALF_OPENED(2), OPENED(3) and
     * CONCURRENT_INNER_DEFAULT(4). Overriding to 3 or 4 while the hinge is physically
     * shut is what keeps the inner display powered and rendering, which is the whole
     * precondition for mirroring it onto the cover.
     */
    override fun getDeviceStates(): List<String> {
        val out = Hidden.sh("cmd device_state print-states")
        return Regex("identifier=(\\d+),\\s*name='([^']+)'")
            .findAll(out)
            .map { "${it.groupValues[1]}:${it.groupValues[2]}" }
            .toList()
    }

    override fun getCurrentDeviceState(): Int {
        val out = Hidden.sh("cmd device_state state")
        return Regex("identifier=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    override fun requestDeviceState(state: Int) {
        Hidden.sh("cmd device_state state $state")
    }

    override fun resetDeviceState() {
        Hidden.sh("cmd device_state state reset")
    }

    override fun startMirror(
        surface: Surface,
        sourceDisplayId: Int,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): Boolean {
        stopMirror()
        for (engine in mirrorEngines) {
            if (runCatching { engine.start(surface, sourceDisplayId, width, height, densityDpi) }
                    .getOrDefault(false)
            ) {
                activeMirror = engine
                Log.i(Hidden.TAG, "mirror started via ${engine.name} at ${width}x$height")
                return true
            }
            Log.w(Hidden.TAG, "mirror engine ${engine.name} declined, trying next")
        }
        return false
    }

    override fun stopMirror() {
        activeMirror?.let { runCatching { it.stop() } }
        activeMirror = null
    }

    override fun getMirrorEngine(): String = activeMirror?.name ?: "none"

    // =====================================================================
    // Input
    // =====================================================================

    override fun injectTouch(
        action: Int,
        x: Float,
        y: Float,
        targetDisplayId: Int,
        downTime: Long,
        pointerId: Int,
    ) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            if (downTime > 0) downTime else now, now, action, x, y, 0,
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        runCatching {
            MotionEvent::class.java.getMethod("setDisplayId", Int::class.java)
                .invoke(event, targetDisplayId)
        }
        try {
            if (!inject(event)) {
                // Only viable for discrete taps; a drag through `input` would be far
                // too slow, but a tap still beats doing nothing.
                if (action == MotionEvent.ACTION_UP) {
                    Hidden.sh("input -d $targetDisplayId tap ${x.toInt()} ${y.toInt()}")
                }
            }
        } finally {
            event.recycle()
        }
    }

    // =====================================================================
    // System surfaces
    // =====================================================================

    /**
     * Closes the shade. Verified on the Flip 5 that this also closes the *cover* quick
     * panel (SubScreenQuickPanel, a NOTIFICATION_SHADE_WIDGET window), which otherwise
     * sits above recents and hides it.
     */
    override fun collapseStatusBar() {
        val statusBar = Hidden.binder("statusbar")?.let {
            Hidden.stub("com.android.internal.statusbar.IStatusBarService", it)
        }
        if (!Hidden.callVoid(statusBar, "collapsePanels", Hidden.sig() to Hidden.args())) {
            Hidden.sh("cmd statusbar collapse")
        }
    }

    /**
     * Starts [intent] on [displayId] as shell. Recents has to be an activity rather than
     * an overlay: Settings and One UI Home's settings screens set
     * HIDE_NON_SYSTEM_OVERLAY_WINDOWS, which force-hides every app overlay while they
     * are showing (seen on-device as setForceHideNonSystemOverlayWindowIfNeeded). And
     * shell, unlike a backgrounded app, is allowed to start activities from the
     * background.
     */
    override fun startActivityOnDisplay(intent: Intent, displayId: Int): Boolean {
        val options = launchOptions(displayId)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // IActivityManager.startActivityAsUserWithFeature(caller, callingPackage,
        // callingFeatureId, intent, resolvedType, resultTo, resultWho, requestCode,
        // flags, profilerInfo, options, userId): present since Android 11 and the same
        // entry point scrcpy uses from this exact shell-uid position.
        val am = Hidden.activityManager
        val method = am?.javaClass?.methods?.firstOrNull {
            it.name == "startActivityAsUserWithFeature" && it.parameterTypes.size == 12
        }
        if (method != null) {
            val result = runCatching {
                method.invoke(
                    am, null, Hidden.SHELL_PACKAGE, null, intent, null, null, null,
                    0, 0, null, options, USER_CURRENT,
                ) as? Int
            }.onFailure { Log.w(Hidden.TAG, "startActivityAsUserWithFeature failed: ${it.cause ?: it}") }
                .getOrNull()
            // START_SUCCESS is 0; the other non-negative codes (e.g. delivered to top) are fine.
            if (result != null && result >= 0) return true
        }

        val component = intent.component?.flattenToShortString() ?: return false
        val out = Hidden.sh("am start --display $displayId --activity-no-animation -n $component")
        return !out.contains("Error", ignoreCase = true)
    }

    // =====================================================================
    // Display geometry
    // =====================================================================

    /**
     * Sizes straight from the window manager. The app cannot measure the inner display
     * itself while folded: it is *disabled* then, so DisplayManager omits it and the
     * metrics the app got instead produced a 1282x1234 "inner screen", which is why the
     * first mirror was mis-scaled and taps landed too high.
     */
    override fun getDisplaySizes(displayId: Int): IntArray {
        val wm = Hidden.windowManager
        val initial = android.graphics.Point()
        val current = android.graphics.Point()
        val gotInitial = Hidden.callVoid(
            wm, "getInitialDisplaySize",
            Hidden.sig(Int::class.java, android.graphics.Point::class.java) to Hidden.args(displayId, initial),
        )
        val gotCurrent = Hidden.callVoid(
            wm, "getBaseDisplaySize",
            Hidden.sig(Int::class.java, android.graphics.Point::class.java) to Hidden.args(displayId, current),
        )
        if (gotInitial && gotCurrent && initial.x > 0 && current.x > 0) {
            return intArrayOf(initial.x, initial.y, current.x, current.y)
        }
        // "Physical size: 1080x2640" and, when forced, "Override size: 1080x1040".
        val out = Hidden.sh("wm size -d $displayId")
        fun parse(label: String) = Regex("$label size:\\s*(\\d+)x(\\d+)").find(out)
            ?.groupValues?.let { it[1].toInt() to it[2].toInt() }
        val physical = parse("Physical") ?: (0 to 0)
        val override = parse("Override") ?: physical
        return intArrayOf(physical.first, physical.second, override.first, override.second)
    }

    override fun setDisplaySize(displayId: Int, width: Int, height: Int) {
        val done = Hidden.callVoid(
            Hidden.windowManager, "setForcedDisplaySize",
            Hidden.sig(Int::class.java, Int::class.java, Int::class.java) to Hidden.args(displayId, width, height),
        )
        if (!done) Hidden.sh("wm size ${width}x$height -d $displayId")
    }

    override fun resetDisplaySize(displayId: Int) {
        val done = Hidden.callVoid(
            Hidden.windowManager, "clearForcedDisplaySize",
            Hidden.sig(Int::class.java) to Hidden.args(displayId),
        )
        if (!done) Hidden.sh("wm size reset -d $displayId")
    }

    // =====================================================================
    // Mirror
    // =====================================================================

    /**
     * Injects a whole MotionEvent (all pointers, pressure, history) rather than a single
     * rebuilt point, so pinch-zoom and two-finger gestures work through the mirror.
     */
    override fun injectMotionEvent(event: MotionEvent, targetDisplayId: Int) {
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        runCatching {
            MotionEvent::class.java.getMethod("setDisplayId", Int::class.java).invoke(event, targetDisplayId)
        }
        try {
            inject(event)
        } finally {
            event.recycle()
        }
    }

    override fun setInnerDisplayAwake(awake: Boolean): Boolean {
        if (awake) return acquireAwakeState()
        if (getCurrentDeviceState() == 0) return true
        val wasInteractive = isInteractive()
        resetDeviceState()
        // Dropping back to CLOSED while the screen is on counts as folding the phone, and
        // One UI puts it to sleep ("device_folded"). Mirroring was stopped on purpose, so
        // wake the cover straight back up rather than leave the user at a dark screen.
        if (wasInteractive) {
            val deadline = SystemClock.uptimeMillis() + 1_500
            while (isInteractive() && SystemClock.uptimeMillis() < deadline) Thread.sleep(40)
            if (!isInteractive()) injectKey(KeyEvent.KEYCODE_WAKEUP, Display.DEFAULT_DISPLAY)
        }
        return true
    }

    override fun setDisplayPanelOn(displayId: Int, on: Boolean): Boolean {
        val physical = Hidden.physicalDisplayId(displayId)?.toLongOrNull() ?: return false
        val token = PanelPower.displayToken(physical) ?: return false
        return runCatching {
            Class.forName("android.view.SurfaceControl")
                .getMethod("setDisplayPowerMode", android.os.IBinder::class.java, Int::class.java)
                .invoke(null, token, if (on) PanelPower.POWER_MODE_NORMAL else PanelPower.POWER_MODE_OFF)
            true
        }.getOrElse {
            Log.w(Hidden.TAG, "setDisplayPowerMode failed: ${it.message}")
            false
        }
    }

    private fun isInteractive(): Boolean {
        val power = Hidden.binder("power")?.let { Hidden.stub("android.os.IPowerManager", it) }
        return power?.let { runCatching { it.javaClass.getMethod("isInteractive").invoke(it) as Boolean }.getOrNull() }
            ?: Hidden.sh("dumpsys power").contains("mWakefulness=Awake")
    }

    /**
     * Keeps the inner display powered while folded: CONCURRENT_INNER_DEFAULT (both panels
     * on) when the firmware offers it, else OPENED. Waits for the state to commit, because
     * mirroring a display that is still disabled yields a black picture.
     */
    private fun acquireAwakeState(): Boolean {
        val states = getDeviceStates().mapNotNull { raw ->
            val parts = raw.split(":", limit = 2)
            parts[0].toIntOrNull()?.let { it to parts.getOrElse(1) { "" } }
        }
        val target = states.firstOrNull { it.second.contains("CONCURRENT", ignoreCase = true) }?.first
            ?: states.firstOrNull { it.second.equals("OPENED", ignoreCase = true) }?.first
            ?: return false
        requestDeviceState(target)
        val deadline = SystemClock.uptimeMillis() + 2_000
        while (getCurrentDeviceState() != target && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(50)
        }
        return getCurrentDeviceState() == target
    }

    override fun startNavWatcher(listener: INavGestureListener) = navWatcher.start(listener)

    override fun stopNavWatcher() = navWatcher.stop()

    override fun injectKey(keyCode: Int, targetDisplayId: Int) {
        val now = SystemClock.uptimeMillis()
        var handled = true
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(
                now, now, action, keyCode, 0, 0,
                KeyEvent.FLAG_FROM_SYSTEM, 0, 0, InputDevice.SOURCE_KEYBOARD,
            )
            runCatching {
                KeyEvent::class.java.getMethod("setDisplayId", Int::class.java)
                    .invoke(event, targetDisplayId)
            }
            if (!inject(event)) handled = false
        }
        if (!handled) Hidden.sh("input -d $targetDisplayId keyevent $keyCode")
    }

    /** InputManagerGlobal on Android 14+, InputManager before that. */
    private fun inject(event: android.view.InputEvent): Boolean {
        val manager = runCatching {
            Class.forName("android.hardware.input.InputManagerGlobal")
                .getMethod("getInstance").invoke(null)
        }.getOrNull() ?: runCatching {
            Class.forName("android.hardware.input.InputManager")
                .getMethod("getInstance").invoke(null)
        }.getOrNull() ?: return false

        return runCatching {
            manager.javaClass.getMethod(
                "injectInputEvent", android.view.InputEvent::class.java, Int::class.java,
            ).invoke(manager, event, INJECT_ASYNC) as? Boolean ?: true
        }.getOrDefault(false)
    }

    private companion object {
        const val CALLER = "CoverDeck"
        const val USER_SYSTEM = 0
        const val RECENT_IGNORE_UNAVAILABLE = 0x0002
        const val USER_CURRENT = -2
        const val ACTIVITY_TYPE_HOME = 2
        const val ACTIVITY_TYPE_RECENTS = 3
        const val INJECT_ASYNC = 0
    }
}
