package com.raihan.coverdeck.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import android.graphics.drawable.Drawable
import com.raihan.coverdeck.feature.RecentEntry
import com.raihan.coverdeck.ui.theme.DeckColors
import kotlin.math.roundToInt

/**
 * A One UI shaped recents strip: cards side by side, app identity above the snapshot,
 * tap to resume, flick a card upward to close it.
 *
 * Shared by the in-app Recents page and the gesture-strip overlay so both behave the
 * same way and there is only one set of gestures to learn.
 */
@Composable
fun RecentsCarousel(
    entries: List<RecentEntry>,
    loading: Boolean,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 132.dp,
    cardHeight: Dp = 186.dp,
    onOpen: (RecentEntry) -> Unit,
    onClose: (RecentEntry) -> Unit,
    onSendToOtherScreen: ((RecentEntry) -> Unit)? = null,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            loading && entries.isEmpty() -> CircularProgressIndicator(
                color = DeckColors.Accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(26.dp),
            )

            entries.isEmpty() -> Text(
                "No recent apps",
                style = MaterialTheme.typography.bodyMedium,
                color = DeckColors.TextTertiary,
            )

            else -> LazyRow(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(entries, key = { it.task.taskId }) { entry ->
                    RecentCard(
                        entry = entry,
                        width = cardWidth,
                        height = cardHeight,
                        onOpen = { onOpen(entry) },
                        onClose = { onClose(entry) },
                        onSendToOtherScreen = onSendToOtherScreen?.let { action -> { action(entry) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentCard(
    entry: RecentEntry,
    width: Dp,
    height: Dp,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onSendToOtherScreen: (() -> Unit)?,
) {
    var dragOffset by remember(entry.task.taskId) { mutableFloatStateOf(0f) }
    val dismissThreshold = -140f

    Column(
        modifier = Modifier
            .width(width)
            .alpha((1f + dragOffset / 260f).coerceIn(0.25f, 1f))
            .pointerInput(entry.task.taskId) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (dragOffset < dismissThreshold) onClose() else dragOffset = 0f
                    },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        dragOffset = (dragOffset + amount).coerceAtMost(0f)
                    },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp, start = 2.dp, end = 2.dp),
        ) {
            entry.icon?.let { drawable ->
                Image(
                    painter = remember(entry.task.packageName) { DrawablePainter(drawable) },
                    contentDescription = null,
                    modifier = Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = entry.label,
                style = MaterialTheme.typography.labelSmall,
                color = DeckColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        Box(
            modifier = Modifier
                .width(width)
                .height(height)
                .offset { IntOffset(0, dragOffset.roundToInt()) }
                .clip(RoundedCornerShape(16.dp))
                .background(DeckColors.SurfaceRaised)
                .border(1.dp, DeckColors.Outline, RoundedCornerShape(16.dp))
                .clickable(onClick = onOpen),
        ) {
            val thumb = entry.thumbnail
            if (thumb != null && !thumb.isRecycled) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = entry.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    entry.icon?.let { drawable ->
                        Image(
                            painter = remember(entry.task.packageName + "_big") { DrawablePainter(drawable) },
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
            }

            if (onSendToOtherScreen != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(26.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(DeckColors.Background.copy(alpha = 0.78f))
                        .clickable(onClick = onSendToOtherScreen),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.OpenInFull,
                        contentDescription = "Send to the other screen",
                        tint = DeckColors.Accent,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun CloseAllButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    DeckButton(
        text = "Close all",
        icon = Icons.Rounded.DeleteSweep,
        tone = Tone.Danger,
        modifier = modifier,
        onClick = onClick,
    )
}

/**
 * Bridges a PackageManager Drawable into Compose. Coil would be overkill for icons we
 * already hold in memory.
 */
private class DrawablePainter(private val drawable: Drawable) : Painter() {

    override val intrinsicSize: Size
        get() = Size(
            drawable.intrinsicWidth.coerceAtLeast(1).toFloat(),
            drawable.intrinsicHeight.coerceAtLeast(1).toFloat(),
        )

    override fun DrawScope.onDraw() {
        drawIntoCanvas { canvas ->
            drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }
}
