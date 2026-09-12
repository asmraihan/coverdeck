package com.raihan.coverdeck.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.raihan.coverdeck.feature.RecentEntry
import com.raihan.coverdeck.ui.theme.DeckColors
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

/**
 * Recents laid out the way One UI does it: big snapping cards with the newest app in
 * the middle and older apps to the left, the app's icon and name above its card, flick
 * a card up to close it, tap the icon for app actions, Close all at the bottom.
 *
 * Used both by the long-press-Home overlay and by the in-app Recents page, so there is
 * a single recents experience to learn.
 *
 * @param entries newest first, as RecentsController reports them.
 * @param onDismiss tapping empty space closes the panel; null disables that (in-app page).
 */
@Composable
fun CoverRecents(
    entries: List<RecentEntry>,
    loading: Boolean,
    keptOpen: Set<String>,
    onOpen: (RecentEntry) -> Unit,
    onClose: (RecentEntry) -> Unit,
    onCloseAll: () -> Unit,
    onToggleKeepOpen: (RecentEntry) -> Unit,
    onAppInfo: (RecentEntry) -> Unit,
    onOpenOnOtherScreen: (RecentEntry) -> Unit,
    modifier: Modifier = Modifier,
    otherScreenLabel: String = "Open on main screen",
    onDismiss: (() -> Unit)? = null,
) {
    // Oldest on the left, newest on the right, newest centred on open. A real copy, not
    // asReversed(): the pager's key lambda can run against the previous page count for a
    // frame after a card is closed, and a reversed *view* of the new list then indexes
    // past its end (the crash seen on-device when closing cards).
    val ordered = remember(entries) { entries.reversed() }
    val pager = rememberPagerState(initialPage = ordered.lastIndex.coerceAtLeast(0)) { ordered.size }
    val scope = rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<RecentEntry?>(null) }

    // The list usually arrives a moment after the panel opens; jump to the newest app
    // the first time it does, without fighting the user's scrolling afterwards.
    var centredOnNewest by remember { mutableStateOf(ordered.isNotEmpty()) }
    LaunchedEffect(ordered.size) {
        if (!centredOnNewest && ordered.isNotEmpty()) {
            pager.scrollToPage(ordered.lastIndex)
            centredOnNewest = true
        }
    }

    val appear = remember { Animatable(if (onDismiss != null) 0f else 1f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(180)) }

    // Full-screen (activity) mode draws edge to edge, so keep interactive content clear
    // of the cover's navigation bar and cutout. The in-app page is already padded.
    val insets = if (onDismiss != null) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = appear.value
                scaleX = 0.96f + 0.04f * appear.value
                scaleY = scaleX
            }
            .then(
                if (onDismiss != null) {
                    Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { if (menuFor != null) menuFor = null else onDismiss() }
                } else {
                    Modifier
                },
            ),
    ) {
        if (onDismiss != null) Backdrop(entries.firstOrNull()?.thumbnail)

        when {
            ordered.isEmpty() && loading -> CircularProgressIndicator(
                color = DeckColors.Accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(26.dp).align(Alignment.Center),
            )

            ordered.isEmpty() -> Text(
                "No recent apps",
                style = MaterialTheme.typography.bodyMedium,
                color = DeckColors.TextSecondary,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> Column(Modifier.fillMaxSize().then(insets).padding(top = 10.dp, bottom = 6.dp)) {
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val cardWidth = (maxWidth * 0.5f).coerceIn(150.dp, 260.dp)
                    val sidePadding = (maxWidth - cardWidth) / 2

                    HorizontalPager(
                        state = pager,
                        contentPadding = PaddingValues(horizontal = sidePadding),
                        pageSpacing = 12.dp,
                        // Both lookups tolerate a stale index for the frame in which the list
                        // shrinks; negative fallback keys cannot collide with task ids.
                        key = { index -> ordered.getOrNull(index)?.task?.taskId ?: (-1 - index) },
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        val entry = ordered.getOrNull(page) ?: return@HorizontalPager
                        // 0 for the centred card, 1 for a neighbour: drives the depth effect.
                        val distance = ((pager.currentPage - page) + pager.currentPageOffsetFraction)
                            .absoluteValue.coerceIn(0f, 1f)

                        RecentCard(
                            entry = entry,
                            kept = entry.task.packageName in keptOpen,
                            depth = distance,
                            onOpen = {
                                if (page == pager.currentPage) onOpen(entry)
                                else scope.launch { pager.animateScrollToPage(page) }
                            },
                            onHeader = {
                                if (page == pager.currentPage) menuFor = entry
                                else scope.launch { pager.animateScrollToPage(page) }
                            },
                            onSwipedAway = {
                                if (menuFor?.task?.taskId == entry.task.taskId) menuFor = null
                                onClose(entry)
                            },
                        )
                    }
                }

                Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Close all",
                        style = MaterialTheme.typography.labelLarge,
                        color = DeckColors.TextPrimary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable {
                                menuFor = null
                                onCloseAll()
                            }
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                }
            }
        }

        menuFor?.let { entry ->
            AppMenu(
                entry = entry,
                kept = entry.task.packageName in keptOpen,
                otherScreenLabel = otherScreenLabel,
                modifier = Modifier.align(Alignment.TopCenter).then(insets).padding(top = 40.dp),
                onAppInfo = { menuFor = null; onAppInfo(entry) },
                onToggleKeepOpen = { menuFor = null; onToggleKeepOpen(entry) },
                onOtherScreen = { menuFor = null; onOpenOnOtherScreen(entry) },
            )
        }
    }
}

/**
 * What sits behind the cards. One UI shows recents over a blurred copy of the screen,
 * but this Flip 5 reports cross-window blur off (mBlurEnabled=false), so a window
 * blur-behind flag does nothing. Blurring inside CoverDeck's own window does work, so
 * the snapshot of the app that was just on screen is drawn, blurred, as the backdrop.
 */
@Composable
private fun Backdrop(snapshot: android.graphics.Bitmap?) {
    if (snapshot != null && !snapshot.isRecycled) {
        Image(
            bitmap = remember(snapshot) { snapshot.asImageBitmap() },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(36.dp),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
    } else {
        // Until the snapshot arrives: dark enough that the app underneath reads as
        // backdrop rather than content.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)))
    }
}

@Composable
private fun RecentCard(
    entry: RecentEntry,
    kept: Boolean,
    depth: Float,
    onOpen: () -> Unit,
    onHeader: () -> Unit,
    onSwipedAway: () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val dragY = remember(entry.task.taskId) { Animatable(0f) }
    val dismissPx = with(density) { 90.dp.toPx() }
    val icon = rememberIconBitmap(entry)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val scale = 1f - 0.1f * depth
                scaleX = scale
                scaleY = scale
                alpha = (1f - 0.35f * depth) * (1f + dragY.value / (dismissPx * 3f)).coerceIn(0f, 1f)
            },
    ) {
        // App identity above the card, as One UI shows it.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .widthIn(max = 220.dp)
                .clip(CircleShape)
                .clickable(onClick = onHeader)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            if (icon != null) {
                Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                entry.label,
                style = MaterialTheme.typography.labelLarge,
                color = DeckColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (kept) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = "Kept open",
                    tint = DeckColors.AccentBright,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .offset { IntOffset(0, dragY.value.roundToInt()) }
                .clip(RoundedCornerShape(22.dp))
                .background(DeckColors.SurfaceRaised)
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(22.dp))
                .pointerInput(entry.task.taskId) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (dragY.value < -dismissPx) {
                                    dragY.animateTo(-size.height.toFloat(), tween(160))
                                    onSwipedAway()
                                } else {
                                    dragY.animateTo(0f, tween(180))
                                }
                            }
                        },
                        onDragCancel = { scope.launch { dragY.animateTo(0f) } },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            // Upward only, like One UI; a downward pull just resists.
                            scope.launch { dragY.snapTo((dragY.value + amount).coerceAtMost(0f)) }
                        },
                    )
                }
                .clickable(onClick = onOpen),
        ) {
            val thumb = entry.thumbnail
            if (thumb != null && !thumb.isRecycled) {
                Image(
                    bitmap = remember(thumb) { thumb.asImageBitmap() },
                    contentDescription = entry.label,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = entry.label,
                    modifier = Modifier.size(52.dp).align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun AppMenu(
    entry: RecentEntry,
    kept: Boolean,
    otherScreenLabel: String,
    modifier: Modifier = Modifier,
    onAppInfo: () -> Unit,
    onToggleKeepOpen: () -> Unit,
    onOtherScreen: () -> Unit,
) {
    // Drawn in the same composition rather than as a Popup: a Popup needs its own window,
    // and sub-windows of an overlay window are not something to rely on.
    Column(
        modifier = modifier
            .width(210.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(DeckColors.SurfaceRaised)
            .border(1.dp, DeckColors.Outline, RoundedCornerShape(20.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(vertical = 6.dp),
    ) {
        MenuItem(Icons.Outlined.Info, "App info", onAppInfo)
        MenuItem(
            if (kept) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
            if (kept) "Don't keep open" else "Keep open",
            onToggleKeepOpen,
        )
        MenuItem(Icons.Rounded.OpenInFull, otherScreenLabel, onOtherScreen)
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Icon(icon, contentDescription = null, tint = DeckColors.TextSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = DeckColors.TextPrimary, maxLines = 1)
    }
}

@Composable
private fun rememberIconBitmap(entry: RecentEntry): ImageBitmap? = remember(entry.task.packageName) {
    entry.icon?.let { runCatching { it.toBitmap(96, 96).asImageBitmap() }.getOrNull() }
}
