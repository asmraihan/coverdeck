package com.raihan.coverdeck.privileged

import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Physical display tokens, for switching a panel off without putting its display to sleep.
 *
 * Since Android 14 the token lookup lives in `com.android.server.display.DisplayControl`,
 * which ships inside services.jar with its native half in libandroid_servers. The shell
 * process can load both, the same way scrcpy does for its "turn screen off" feature.
 */
internal object PanelPower {

    /** SurfaceControl.POWER_MODE_OFF / POWER_MODE_NORMAL. */
    const val POWER_MODE_OFF = 0
    const val POWER_MODE_NORMAL = 2

    private val displayControl: Class<*>? by lazy {
        runCatching {
            val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
            val create = factory.getDeclaredMethod(
                "createClassLoader",
                String::class.java, String::class.java, String::class.java,
                ClassLoader::class.java, Int::class.java, Boolean::class.java, String::class.java,
            )
            val loader = create.invoke(
                null, "/system/framework/services.jar", null, null,
                ClassLoader.getSystemClassLoader(), 0, true, null,
            ) as ClassLoader
            val cls = loader.loadClass("com.android.server.display.DisplayControl")
            val loadLibrary = Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
            loadLibrary.isAccessible = true
            loadLibrary.invoke(Runtime.getRuntime(), cls, "android_servers")
            cls
        }.onFailure { Log.w(Hidden.TAG, "DisplayControl unavailable: ${it.message}") }.getOrNull()
    }

    fun displayToken(physicalId: Long): IBinder? {
        if (Build.VERSION.SDK_INT >= 34) {
            displayControl?.let { cls ->
                return runCatching {
                    cls.getMethod("getPhysicalDisplayToken", Long::class.java).invoke(null, physicalId) as IBinder?
                }.onFailure { Log.w(Hidden.TAG, "getPhysicalDisplayToken failed: ${it.message}") }.getOrNull()
            }
            return null
        }
        return runCatching {
            Class.forName("android.view.SurfaceControl")
                .getMethod("getPhysicalDisplayToken", Long::class.java)
                .invoke(null, physicalId) as IBinder?
        }.getOrNull()
    }
}
