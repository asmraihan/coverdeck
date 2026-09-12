package com.raihan.coverdeck.privileged

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.os.Process
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.BufferedReader
import java.lang.reflect.Method

/**
 * Reflection plumbing for the shell-UID half of CoverDeck.
 *
 * Everything here runs inside the process Shizuku spawns with `app_process`, so it
 * has shell's permission set (WRITE_SECURE_SETTINGS, REAL_GET_TASKS, MANAGE_DISPLAYS,
 * CAPTURE_VIDEO_OUTPUT, CONTROL_DEVICE_STATE, INJECT_EVENTS) and, like scrcpy, no
 * hidden-API blocklist. The blocklist exemption below is belt-and-braces.
 *
 * Framework signatures churn between releases, so each wrapper tries the known
 * variants oldest-last and falls back to a shell command where one exists. That
 * fallback is what keeps CoverDeck working after a One UI update breaks a signature.
 */
internal object Hidden {

    const val TAG = "CoverDeck/Priv"

    private val cache = HashMap<String, Method>()

    fun init() {
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
            .onFailure { Log.w(TAG, "hidden api exemption not needed/failed: ${it.message}") }
    }

    // ---- service access -------------------------------------------------

    fun binder(name: String): IBinder? = runCatching {
        val sm = Class.forName("android.os.ServiceManager")
        sm.getMethod("getService", String::class.java).invoke(null, name) as IBinder?
    }.getOrNull()

    /** Wraps a raw binder in its `IFoo$Stub.asInterface()` proxy. */
    fun stub(stubClassName: String, binder: IBinder): Any? = runCatching {
        val stub = Class.forName("$stubClassName\$Stub")
        stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    }.getOrNull()

    val windowManager: Any? by lazy {
        binder("window")?.let { stub("android.view.IWindowManager", it) }
    }

    val activityTaskManager: Any? by lazy {
        binder("activity_task")?.let { stub("android.app.IActivityTaskManager", it) }
    }

    val activityManager: Any? by lazy {
        binder("activity")?.let { stub("android.app.IActivityManager", it) }
    }

    /**
     * A Context inside the shell process. Shizuku hands one to the user service
     * constructor; if that ever stops happening we build the system context the
     * way ActivityThread does for system services.
     */
    fun systemContext(): Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val thread = runCatching { at.getMethod("currentActivityThread").invoke(null) }.getOrNull()
            ?: at.getMethod("systemMain").invoke(null)
        at.getMethod("getSystemContext").invoke(thread) as Context
    }.onFailure { Log.w(TAG, "no system context: ${it.message}") }.getOrNull()

    const val SHELL_PACKAGE = "com.android.shell"

    /**
     * A Context that introduces itself as com.android.shell.
     *
     * System services check that the calling uid owns the package name a request
     * claims. This process is uid 2000, but the system context says "android" and
     * CoverDeck's own context says com.raihan.coverdeck, so both get rejected with
     * "packageName must match the owner uid". Seen on-device on One UI 8.5 when
     * creating the mirror VirtualDisplay.
     */
    class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PACKAGE
        override fun getOpPackageName(): String = SHELL_PACKAGE
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.SHELL_UID).setPackageName(SHELL_PACKAGE).build()
        override fun getApplicationContext(): Context = this
    }

    /**
     * A DisplayManager bound to [ShellContext]. It has to be constructed directly:
     * getSystemService() on a wrapper hands back the base context's cached instance,
     * which still carries the wrong package name.
     */
    fun shellDisplayManager(base: Context?): DisplayManager? {
        val root = base ?: systemContext() ?: return null
        return runCatching {
            DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }
                .newInstance(ShellContext(root))
        }.onFailure { Log.w(TAG, "could not build a shell DisplayManager: ${it.message}") }
            .getOrNull()
    }

    /**
     * Maps a logical display id (0, 1) to the physical id SurfaceFlinger tools want.
     * screenrecord and screencap reject logical ids outright ("Display Id '1' is not
     * valid"); on this Flip 5 the inner panel is 4630946592180194435.
     */
    fun physicalDisplayId(logicalId: Int): String? {
        runCatching {
            val global = Class.forName("android.hardware.display.DisplayManagerGlobal")
                .getMethod("getInstance").invoke(null)
            val info = callAny(global, "getDisplayInfo", sig(Int::class.java) to args(logicalId))
            val unique = info?.javaClass?.getField("uniqueId")?.get(info) as? String
            unique?.substringAfter("local:", "")
                ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                ?.let { return it }
        }
        // dumpsys lists every viewport even while its panel is off, e.g.
        // DisplayViewport{... displayId=0, uniqueId='local:4630946592180194435' ...}
        val dump = sh("dumpsys display")
        return Regex("displayId=$logicalId, uniqueId='local:(\\d+)'").find(dump)?.groupValues?.get(1)
    }

    // ---- invocation helpers ---------------------------------------------

    /** Looks up [name] with an exact parameter list; null when this build lacks it. */
    fun method(owner: Any, name: String, vararg params: Class<*>): Method? {
        val key = owner.javaClass.name + "#" + name + "(" + params.joinToString(",") { it.name } + ")"
        cache[key]?.let { return it }
        var c: Class<*>? = owner.javaClass
        while (c != null) {
            runCatching { c!!.getDeclaredMethod(name, *params) }.getOrNull()?.let {
                it.isAccessible = true
                cache[key] = it
                return it
            }
            c = c.superclass
        }
        return null
    }

    /**
     * Tries each candidate signature in turn and returns the first that resolves
     * and invokes. [variants] is a list of (parameterTypes, arguments) pairs.
     */
    fun callAny(
        owner: Any?,
        name: String,
        vararg variants: Pair<Array<Class<*>>, Array<Any?>>,
    ): Any? {
        if (owner == null) return null
        for ((params, args) in variants) {
            val m = method(owner, name, *params) ?: continue
            return try {
                m.invoke(owner, *args)
            } catch (t: Throwable) {
                Log.w(TAG, "$name${params.map { it.simpleName }} threw: ${t.cause ?: t}")
                continue
            }
        }
        Log.w(TAG, "no usable signature for $name on ${owner.javaClass.simpleName}")
        return null
    }

    /**
     * [callAny] for methods that return void. Those invoke successfully yet return null,
     * which callAny cannot tell apart from "no such method", so every void call used to
     * run its shell fallback as well (an extra `wm` process on each cover rotation).
     * Returns whether a signature was found and invoked without throwing.
     */
    fun callVoid(
        owner: Any?,
        name: String,
        vararg variants: Pair<Array<Class<*>>, Array<Any?>>,
    ): Boolean {
        if (owner == null) return false
        for ((params, args) in variants) {
            val m = method(owner, name, *params) ?: continue
            try {
                m.invoke(owner, *args)
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "$name${params.map { it.simpleName }} threw: ${t.cause ?: t}")
            }
        }
        Log.w(TAG, "no usable signature for $name on ${owner.javaClass.simpleName}")
        return false
    }

    fun sig(vararg p: Class<*>): Array<Class<*>> = arrayOf(*p)
    fun args(vararg a: Any?): Array<Any?> = arrayOf(*a)

    // ---- shell fallback --------------------------------------------------

    /**
     * Runs a command as shell. This is the escape hatch every feature falls back to,
     * and is why a broken hidden-API signature degrades rather than kills a feature.
     */
    fun sh(cmd: String): String = try {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use(BufferedReader::readText)
        p.waitFor()
        out.trim()
    } catch (t: Throwable) {
        Log.e(TAG, "sh failed: $cmd", t)
        "error: ${t.message}"
    }
}
