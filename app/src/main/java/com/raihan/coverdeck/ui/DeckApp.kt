package com.raihan.coverdeck.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.raihan.coverdeck.feature.RotationController
import com.raihan.coverdeck.privileged.Privileged
import com.raihan.coverdeck.ui.theme.DeckColors

@Composable
fun DeckApp(model: DeckViewModel) {
    val route by model.route.collectAsState()

    BackHandler(enabled = route != DeckRoute.Home) { model.back() }

    Box(
        Modifier
            .fillMaxSize()
            .background(DeckColors.Background)
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.systemBars),
    ) {
        when (route) {
            DeckRoute.Home -> HomeScreen(model)
            DeckRoute.Rotation -> RotationPage(model)
            DeckRoute.Density -> DensityPage(model)
            DeckRoute.Recents -> RecentsPage(model)
            DeckRoute.Mirror -> MirrorPage(model)
            DeckRoute.Setup -> SetupPage(model)
        }
    }
}

@Composable
private fun HomeScreen(model: DeckViewModel) {
    val status by model.privilegedStatus.collectAsState()
    val target by model.target.collectAsState()
    val cover by model.coverPanel.collectAsState()
    val main by model.mainPanel.collectAsState()
    val notice by model.notice.collectAsState()
    val stripOn by model.stripEnabled.collectAsState()

    val panel = if (target == Target.Cover) cover else main
    val rotation by model.rotationState(panel.displayId).collectAsState()
    val densityState by model.density.state.collectAsState()
    val mirrorState by model.mirror.state.collectAsState()

    val ready = status is Privileged.Status.Ready

    // Until Shizuku answers, the controller reports 0; show what the panel itself says
    // rather than a nonsense "0 dpi" (visible in the first on-device screenshot).
    val shownDpi = densityState.effectiveDpi.takeIf { it > 0 } ?: panel.densityDpi
    val factoryDpi = densityState.baseDpi.takeIf { it > 0 } ?: panel.densityDpi

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
        DeckHeader(
            title = "CoverDeck",
            subtitle = "${panel.widthPx}×${panel.heightPx} · $shownDpi dpi",
            trailing = {
                StatusChip(
                    text = status.shortLabel(),
                    tone = status.tone(),
                    modifier = Modifier.clickable { model.navigate(DeckRoute.Setup) },
                )
            },
        )

        if (notice != null) {
            NoticeBanner(notice!!) { model.dismissNotice() }
            Spacer(Modifier.height(8.dp))
        }

        SegmentedSelector(
            options = Target.entries.map { it.label },
            selectedIndex = Target.entries.indexOf(target),
            modifier = Modifier.fillMaxWidth(),
            onSelect = { model.setTarget(Target.entries[it]) },
        )
        Spacer(Modifier.height(10.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 148.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                DeckTile(
                    icon = Icons.Rounded.ScreenRotation,
                    title = "Rotation",
                    // Auto on the cover freezes rotation continuously, so it must be
                    // checked before "frozen", or it would read as a fixed lock.
                    status = when {
                        rotation.autoRotate -> "Auto"
                        rotation.frozen ->
                            "Locked ${RotationController.Mode.forRotation(rotation.currentRotation).label}"
                        RotationController.isCover(panel.displayId) -> "System"
                        else -> "Auto"
                    },
                    active = rotation.frozen || rotation.autoRotate,
                    enabled = ready,
                    onClick = { model.navigate(DeckRoute.Rotation) },
                )
            }
            item {
                DeckTile(
                    icon = Icons.Rounded.Layers,
                    title = "Recents",
                    status = "Task switcher",
                    enabled = ready,
                    onClick = { model.navigate(DeckRoute.Recents) },
                )
            }
            item {
                DeckTile(
                    icon = Icons.Rounded.FormatSize,
                    title = "Density",
                    status = when {
                        densityState.awaitingConfirmation -> "Confirm ${densityState.secondsLeft}s"
                        densityState.isModified -> "$shownDpi dpi"
                        else -> "Stock $factoryDpi dpi"
                    },
                    active = densityState.isModified,
                    tone = when {
                        densityState.awaitingConfirmation -> Tone.Warning
                        densityState.isModified -> Tone.Active
                        else -> Tone.Neutral
                    },
                    enabled = ready,
                    onClick = { model.navigate(DeckRoute.Density) },
                )
            }
            item {
                DeckTile(
                    icon = Icons.Rounded.Cast,
                    title = "Mirror",
                    status = if (mirrorState.running) "On · ${mirrorState.engine}" else "Inner screen",
                    active = mirrorState.running,
                    enabled = ready,
                    onClick = { model.navigate(DeckRoute.Mirror) },
                )
            }
            item {
                DeckTile(
                    icon = Icons.Rounded.SwipeUp,
                    title = "Gesture strip",
                    status = if (stripOn) "On" else "Off",
                    active = stripOn,
                    enabled = ready,
                    onClick = { model.toggleStrip() },
                )
            }
            item {
                DeckTile(
                    icon = Icons.Rounded.RestartAlt,
                    title = "Reset all",
                    status = "Undo CoverDeck changes",
                    tone = Tone.Danger,
                    enabled = ready,
                    onClick = { model.resetEverything() },
                )
            }
        }
    }
}

@Composable
private fun NoticeBanner(text: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DeckColors.Accent.copy(alpha = 0.12f))
            .border(1.dp, DeckColors.Accent.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = DeckColors.AccentBright,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Rounded.Close,
            contentDescription = "Dismiss",
            tint = DeckColors.TextTertiary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onDismiss)
                .padding(2.dp)
                .width(14.dp)
                .height(14.dp),
        )
    }
}

internal fun Privileged.Status.shortLabel(): String = when (this) {
    is Privileged.Status.Ready -> "Shizuku"
    Privileged.Status.Connecting -> "Linking"
    Privileged.Status.NotInstalled -> "No Shizuku"
    Privileged.Status.NotRunning -> "Not running"
    Privileged.Status.PermissionRequired -> "Authorise"
    is Privileged.Status.Failed -> "Error"
}

internal fun Privileged.Status.tone(): Tone = when (this) {
    is Privileged.Status.Ready -> Tone.Success
    Privileged.Status.Connecting -> Tone.Neutral
    is Privileged.Status.Failed -> Tone.Danger
    else -> Tone.Warning
}
