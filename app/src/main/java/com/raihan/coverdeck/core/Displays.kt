package com.raihan.coverdeck.core

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Which physical panel is which.
 *
 * Two traps, both hit on the SM-F731B:
 *
 *  - While folded the inner screen is a *disabled* logical display, so
 *    DisplayManager.getDisplays() leaves it out. The first version picked "the largest
 *    display" as the inner one, which silently turned the cover (or a virtual display)
 *    into the "main" screen whenever the phone was shut.
 *  - Per-app DisplayMetrics for another display can come back scaled (the mirror page
 *    once reported the inner screen as 1282x1234). Hardware mode sizes do not.
 *
 * So the inner screen is always the default display (id 0), and sizes come from the
 * panel's active mode.
 */
data class Panel(
    val displayId: Int,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val name: String,
) {
    val area: Long get() = widthPx.toLong() * heightPx.toLong()
    val aspect: Float get() = if (heightPx == 0) 1f else widthPx.toFloat() / heightPx.toFloat()
}

object Displays {

    const val MAIN_ID = Display.DEFAULT_DISPLAY
    const val FALLBACK_COVER_ID = 1

    private const val TYPE_INTERNAL = 1

    // Known panels of this device, used only when the system will not describe a panel.
    private val MAIN_FALLBACK = Panel(MAIN_ID, 1080, 2640, 480, "Main screen")
    private val COVER_FALLBACK = Panel(FALLBACK_COVER_ID, 748, 720, 340, "Cover screen")

    private fun panelOf(display: Display, name: String): Panel {
        val mode = display.mode
        return Panel(
            displayId = display.displayId,
            widthPx = mode.physicalWidth,
            heightPx = mode.physicalHeight,
            densityDpi = runCatching {
                android.util.DisplayMetrics().also { @Suppress("DEPRECATION") display.getRealMetrics(it) }.densityDpi
            }.getOrDefault(0),
            name = name,
        )
    }

    /** Built-in panels only: never the scrcpy/virtual displays that may also exist. */
    private fun isInternal(display: Display): Boolean = runCatching {
        HiddenApiBypass.invoke(Display::class.java, display, "getType") as Int == TYPE_INTERNAL
    }.getOrDefault(true)

    fun all(context: Context): List<Panel> {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return emptyList()
        return dm.displays.orEmpty()
            .filter { isInternal(it) }
            .map { panelOf(it, it.name ?: "display ${it.displayId}") }
    }

    /** The inner screen: always display 0, described even while it is off and disabled. */
    fun main(context: Context): Panel {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(MAIN_ID)
        return display?.let { runCatching { panelOf(it, "Main screen") }.getOrNull() } ?: MAIN_FALLBACK
    }

    /** The cover screen: the built-in panel that is not the default display. */
    fun cover(context: Context): Panel {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return COVER_FALLBACK
        val cover = dm.displays.orEmpty()
            .filter { it.displayId != MAIN_ID && isInternal(it) }
            .minByOrNull { it.mode.physicalWidth.toLong() * it.mode.physicalHeight }
        return cover?.let { runCatching { panelOf(it, "Cover screen") }.getOrNull() } ?: COVER_FALLBACK
    }

    fun display(context: Context, displayId: Int): Display? =
        context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
}
