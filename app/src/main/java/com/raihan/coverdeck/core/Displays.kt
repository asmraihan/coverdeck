package com.raihan.coverdeck.core

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

/**
 * Which physical panel is which.
 *
 * On this SM-F731B the ids are stable (0 = inner 1080x2640, 1 = cover 748x720), but
 * they are resolved by size rather than hardcoded so a firmware reshuffle or a second
 * foldable does not silently point the DPI tile at the wrong screen.
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

    const val FALLBACK_MAIN_ID = 0
    const val FALLBACK_COVER_ID = 1

    fun all(context: Context): List<Panel> {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return emptyList()
        return dm.displays.orEmpty().mapNotNull { display ->
            runCatching {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                Panel(
                    displayId = display.displayId,
                    widthPx = metrics.widthPixels,
                    heightPx = metrics.heightPixels,
                    densityDpi = metrics.densityDpi,
                    name = display.name ?: "display ${display.displayId}",
                )
            }.getOrNull()
        }
    }

    /**
     * DisplayManager only reports a panel that is currently powered, so when the phone
     * is shut the inner display is simply absent from the list. Falling back to the
     * known ids keeps the tiles addressable while folded, which is exactly when
     * CoverDeck is being used.
     */
    fun main(context: Context): Panel =
        all(context).maxByOrNull { it.area }
            ?: Panel(FALLBACK_MAIN_ID, 1080, 2640, 480, "Main screen")

    fun cover(context: Context): Panel {
        val panels = all(context)
        if (panels.size >= 2) return panels.minByOrNull { it.area }!!
        // Only one panel is awake. If it is small it *is* the cover screen.
        val only = panels.firstOrNull()
        if (only != null && only.area < 1_500_000L) return only
        return Panel(FALLBACK_COVER_ID, 748, 720, 340, "Cover screen")
    }

    fun display(context: Context, displayId: Int): Display? =
        context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
}
