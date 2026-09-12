package com.raihan.coverdeck.privileged

import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import com.raihan.coverdeck.INavGestureListener
import java.util.Locale

/**
 * Notices the cover navigation bar's Home long press by reading SystemUI's own log.
 *
 * This is the one signal that survived testing on the Flip 5 (One UI 8.5):
 *
 *  - The cover's Home button is a SystemUI KeyButtonView that *injects* KEYCODE_HOME.
 *    Injected keys never pass through the accessibility key filter, so an
 *    accessibility service sees nothing.
 *  - SystemUI does not report the long click to accessibility for the nav bar either.
 *  - But the platform logs the gesture precisely. Each Home injection logs
 *    `KeyButtonView: injectInputEvent - 3`, and when the hold reaches the long-press
 *    timeout while folded, the window manager logs
 *    `PhoneWindowManagerExt: Home long press is blocked due to folded state`.
 *
 * Shell holds READ_LOGS, so this helper can tail exactly those two tags. It starts
 * reading from "now" so history can never trigger anything, and the app only keeps
 * it running while the cover screen is on.
 *
 * Exactly one reader may exist. The first version could leave two running after a
 * quick stop/start, which doubled every signal; each start now gets a generation
 * number, and a reader whose generation is stale shuts itself down.
 */
internal class NavLogWatcher {

    private val lock = Any()

    /** Bumped by every start and stop; a reader only lives while its number is current. */
    private var generation = 0

    private var listener: INavGestureListener? = null
    private var process: Process? = null

    private val deathRecipient = IBinder.DeathRecipient {
        // The app died; nobody is left to show recents, so stop reading logs.
        stop()
    }

    fun start(newListener: INavGestureListener) {
        val gen: Int
        synchronized(lock) {
            // Same app-side listener already being served: nothing to do. This is what
            // stops the app's "Shizuku is ready" callback from restarting a healthy reader.
            if (listener?.asBinder() == newListener.asBinder()) return
            stopLocked()
            listener = newListener
            runCatching { newListener.asBinder().linkToDeath(deathRecipient, 0) }
            gen = ++generation
        }
        Thread({ loop(gen) }, "coverdeck-navlog-$gen").apply {
            isDaemon = true
            start()
        }
        Log.i(Hidden.TAG, "nav log watcher started (generation $gen)")
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        val hadListener = listener != null
        generation++
        runCatching { listener?.asBinder()?.unlinkToDeath(deathRecipient, 0) }
        listener = null
        runCatching { process?.destroy() }
        process = null
        if (hadListener) Log.i(Hidden.TAG, "nav log watcher stopped")
    }

    private fun isCurrent(gen: Int) = synchronized(lock) { generation == gen }

    private fun loop(gen: Int) {
        var failures = 0
        while (isCurrent(gen)) {
            val startedAt = SystemClock.elapsedRealtime()
            var proc: Process? = null
            try {
                proc = ProcessBuilder(
                    "/system/bin/logcat",
                    "-b", "main",
                    "-v", "brief",
                    "-T", sinceNow(),
                    "-s", "$TAG_WM:I", "$TAG_NAVBUTTON:I",
                ).redirectErrorStream(true).start()

                // Register the process only if this reader is still wanted; otherwise a
                // stop() that ran while logcat was spawning would miss it.
                val keep = synchronized(lock) {
                    if (generation == gen) {
                        process = proc
                        true
                    } else {
                        false
                    }
                }
                if (!keep) break

                proc.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!isCurrent(gen)) break
                        dispatch(line)
                    }
                }
            } catch (t: Throwable) {
                if (isCurrent(gen)) Log.w(Hidden.TAG, "nav log reader failed: ${t.message}")
            } finally {
                runCatching { proc?.destroy() }
            }

            if (!isCurrent(gen)) break
            // logcat should run until stopped; if it keeps dying immediately, back off
            // rather than respawning it in a tight loop.
            failures = if (SystemClock.elapsedRealtime() - startedAt < 2_000) failures + 1 else 0
            runCatching { Thread.sleep((500L * failures).coerceAtMost(10_000)) }
        }
    }

    private fun dispatch(line: String) {
        val target = synchronized(lock) { listener } ?: return
        try {
            when {
                line.contains(LONG_PRESS_MARKER) -> target.onHomeLongPress()
                line.trimEnd().endsWith(HOME_INJECT_MARKER) -> target.onHomeKey()
            }
        } catch (e: RemoteException) {
            stop()
        }
    }

    /** logcat's `-T` accepts `seconds.millis`; starting at now means no replayed history. */
    private fun sinceNow(): String {
        val now = System.currentTimeMillis()
        return String.format(Locale.US, "%d.%03d", now / 1000, now % 1000)
    }

    private companion object {
        const val TAG_WM = "PhoneWindowManagerExt"
        const val TAG_NAVBUTTON = "KeyButtonView"
        const val LONG_PRESS_MARKER = "Home long press is blocked"
        // KEYCODE_HOME is 3. Matched at the end of the line, because a plain contains()
        // would also accept key codes 30-39.
        const val HOME_INJECT_MARKER = "injectInputEvent - 3"
    }
}
