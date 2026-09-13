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
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Timer
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
import com.raihan.coverdeck.feature.ScreenTimeout
import com.raihan.coverdeck.mirror.MirrorSession
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
            DeckRoute.Recents -> RecentsPage(model)
            DeckRoute.Mirror -> MirrorPage(model)
            DeckRoute.Timeout -> TimeoutPage(model)
            DeckRoute.Setup -> SetupPage(model)
        }
    }
}

@Composable
private fun HomeScreen(model: DeckViewModel) {
    val status by model.privilegedStatus.collectAsState()
    val notice by model.notice.collectAsState()
    val navOn by model.homeLongPressEnabled.collectAsState()
    val backOn by model.backLongPressEnabled.collectAsState()

    // Home is about the cover. The inner screen's rotation is one level down, on the
    // Rotation page's own Cover/Main switch; the other tiles never depended on it.
    val panel by model.coverPanel.collectAsState()
    val rotation by model.rotationState(panel.displayId).collectAsState()
    val mirrorState by MirrorSession.state.collectAsState()
    val timeout by ScreenTimeout.state.collectAsState()

    val ready = status is Privileged.Status.Ready

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
        DeckHeader(
            title = "CoverDeck",
            subtitle = "${panel.widthPx}×${panel.heightPx} · ${panel.densityDpi} dpi",
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

        Spacer(Modifier.height(4.dp))

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
                    // Same pattern as Recents: the pill is the hold gesture (Back here).
                    trailing = {
                        TogglePill(checked = backOn, enabled = ready, onToggle = model::setBackLongPress)
                    },
                    onClick = { model.navigate(DeckRoute.Rotation) },
                )
            }
            item {
                DeckTile(
                    icon = Icons.Rounded.Layers,
                    title = "Recents",
                    status = "Hold Home",
                    active = navOn,
                    enabled = ready,
                    trailing = {
                        TogglePill(checked = navOn, enabled = ready, onToggle = model::setHomeLongPress)
                    },
                    onClick = { model.navigate(DeckRoute.Recents) },
                )
            }
            item {
                DeckTile(
                    icon = Icons.Rounded.Cast,
                    title = "Mirror",
                    status = when {
                        mirrorState.streaming -> "Live"
                        mirrorState.active -> "Paused"
                        else -> "Inner screen"
                    },
                    active = mirrorState.active,
                    enabled = ready,
                    onClick = { model.navigate(DeckRoute.Mirror) },
                )
            }
            item {
                DeckTile(
                    icon = Icons.Rounded.Timer,
                    title = "Screen timeout",
                    status = ScreenTimeout.label(timeout.coverSeconds),
                    enabled = ready,
                    onClick = { model.navigate(DeckRoute.Timeout) },
                )
            }
        }
    }
}

@Composable
internal fun NoticeBanner(text: String, onDismiss: () -> Unit) {
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
