package com.raihan.coverdeck.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.raihan.coverdeck.feature.DensityController
import com.raihan.coverdeck.feature.MirrorController
import com.raihan.coverdeck.feature.RotationController
import com.raihan.coverdeck.overlay.CoverDeckService
import com.raihan.coverdeck.privileged.Privileged
import com.raihan.coverdeck.ui.theme.DeckColors
import kotlin.math.roundToInt

// =========================================================================
// Rotation
// =========================================================================

@Composable
fun RotationPage(model: DeckViewModel) {
    val target by model.target.collectAsState()
    val cover by model.coverPanel.collectAsState()
    val main by model.mainPanel.collectAsState()
    val panel = if (target == Target.Cover) cover else main
    val state by model.rotationState(panel.displayId).collectAsState()

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
        DeckHeader(
            title = "Rotation",
            subtitle = "${panel.name} · display ${panel.displayId}",
            onBack = model::back,
        )

        SegmentedSelector(
            options = Target.entries.map { it.label },
            selectedIndex = Target.entries.indexOf(target),
            modifier = Modifier.fillMaxWidth(),
            onSelect = { model.setTarget(Target.entries[it]) },
        )
        Spacer(Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RotationController.Mode.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { mode ->
                        DeckButton(
                            text = mode.label,
                            tone = if (state.mode == mode) Tone.Active else Tone.Neutral,
                            filled = state.mode == mode,
                            modifier = Modifier.weight(1f),
                            onClick = { model.applyRotation(mode, state.forceAppsToObey) },
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        DeckCard {
            Column {
                ToggleRow(
                    label = "Force apps to obey",
                    description = "Ignore each app's requested orientation",
                    checked = state.forceAppsToObey,
                    onChange = { model.applyRotation(state.mode, it) },
                )
                Spacer(Modifier.height(6.dp))
                InfoRow("Current rotation", "${state.currentRotation * 90}°")
                InfoRow(
                    "Lock state",
                    if (state.frozen) "Frozen" else "Following sensor",
                    tone = if (state.frozen) Tone.Active else Tone.Neutral,
                )
            }
        }
    }
}

// =========================================================================
// Density
// =========================================================================

@Composable
fun DensityPage(model: DeckViewModel) {
    val target by model.target.collectAsState()
    val cover by model.coverPanel.collectAsState()
    val main by model.mainPanel.collectAsState()
    val panel = if (target == Target.Cover) cover else main
    val state by model.density.state.collectAsState()

    val range = model.density.range(panel)
    var slider by remember(state.baseDpi, panel.displayId) {
        mutableFloatStateOf(
            (if (state.effectiveDpi > 0) state.effectiveDpi else panel.densityDpi).toFloat(),
        )
    }
    val chosen = slider.roundToInt()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        DeckHeader(
            title = "Density",
            subtitle = "${panel.name} · applies to every app",
            onBack = model::back,
        )

        SegmentedSelector(
            options = Target.entries.map { it.label },
            selectedIndex = Target.entries.indexOf(target),
            modifier = Modifier.fillMaxWidth(),
            onSelect = { model.setTarget(Target.entries[it]) },
        )
        Spacer(Modifier.height(12.dp))

        if (state.awaitingConfirmation) {
            // The dead-man's switch. If the new density made the screen unreadable,
            // doing nothing is the safe path and gets the old value back.
            DeckCard {
                Column {
                    Text(
                        "Keep ${state.pendingDpi} dpi?",
                        style = MaterialTheme.typography.titleMedium,
                        color = DeckColors.Warning,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Reverting in ${state.secondsLeft}s if you do nothing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DeckColors.TextSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DeckButton(
                            text = "Keep",
                            icon = Icons.Rounded.Check,
                            tone = Tone.Success,
                            modifier = Modifier.weight(1f),
                            onClick = { model.density.confirm(panel.displayId) },
                        )
                        DeckButton(
                            text = "Undo",
                            icon = Icons.AutoMirrored.Rounded.Undo,
                            tone = Tone.Danger,
                            modifier = Modifier.weight(1f),
                            onClick = { model.density.revert(panel.displayId) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        DeckCard {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$chosen dpi",
                        style = MaterialTheme.typography.titleLarge,
                        color = DeckColors.Accent,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "≈ ${model.density.smallestWidthDp(panel, chosen)} dp wide",
                        style = MaterialTheme.typography.bodySmall,
                        color = DeckColors.TextTertiary,
                    )
                }
                Slider(
                    value = slider,
                    onValueChange = { slider = it },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = DeckColors.Accent,
                        activeTrackColor = DeckColors.Accent,
                        inactiveTrackColor = DeckColors.SurfaceRaised,
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val base = if (state.baseDpi > 0) state.baseDpi else panel.densityDpi
                    listOf(
                        "More" to (base * 0.75f).roundToInt(),
                        "Stock" to base,
                        "Bigger" to (base * 1.2f).roundToInt(),
                    ).forEach { (label, dpi) ->
                        DeckButton(
                            text = label,
                            filled = false,
                            tone = Tone.Neutral,
                            modifier = Modifier.weight(1f),
                            onClick = { slider = dpi.toFloat() },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                DeckButton(
                    text = "Apply $chosen dpi",
                    enabled = chosen != state.effectiveDpi,
                    onClick = { model.density.apply(panel.displayId, chosen) },
                )
                Spacer(Modifier.height(6.dp))
                // Two distinct undos: back to what *you* had before CoverDeck, or all the
                // way to the panel's factory value. They differ whenever a density was
                // already overridden before CoverDeck arrived.
                val original = state.originalDpi
                if (original != null && original != state.effectiveDpi) {
                    DeckButton(
                        text = "Restore original ($original dpi)",
                        filled = false,
                        tone = Tone.Active,
                        onClick = { model.density.restoreOriginal(panel.displayId) },
                    )
                    Spacer(Modifier.height(6.dp))
                }
                DeckButton(
                    text = "Factory density (${state.baseDpi} dpi)",
                    filled = false,
                    tone = Tone.Danger,
                    enabled = state.isModified,
                    onClick = { model.density.resetToFactory(panel.displayId) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        DeckCard {
            Column {
                InfoRow("Panel", "${panel.widthPx} × ${panel.heightPx}")
                InfoRow("Factory density", "${state.baseDpi} dpi")
                InfoRow(
                    "Applied",
                    "${state.effectiveDpi} dpi",
                    tone = if (state.isModified) Tone.Active else Tone.Neutral,
                )
                Text(
                    "Changing density restarts every app drawing on this screen. " +
                        "Below about ${DensityController.MIN_DPI + 60} dpi One UI's own panels start to break.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DeckColors.TextTertiary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = DeckColors.TextPrimary)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = DeckColors.TextTertiary,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DeckColors.Background,
                checkedTrackColor = DeckColors.Accent,
                uncheckedThumbColor = DeckColors.TextTertiary,
                uncheckedTrackColor = DeckColors.SurfaceRaised,
            ),
        )
    }
}

// =========================================================================
// Recents
// =========================================================================

@Composable
fun RecentsPage(model: DeckViewModel) {
    val entries by model.recents.entries.collectAsState()
    val loading by model.recents.loading.collectAsState()
    val cover by model.coverPanel.collectAsState()
    val main by model.mainPanel.collectAsState()

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
        DeckHeader(
            title = "Recents",
            subtitle = "${entries.size} task${if (entries.size == 1) "" else "s"}",
            onBack = model::back,
            trailing = {
                DeckButton(
                    text = "Refresh",
                    icon = Icons.Rounded.Refresh,
                    filled = false,
                    modifier = Modifier.width(112.dp),
                    onClick = model::refreshRecents,
                )
            },
        )

        RecentsCarousel(
            entries = entries,
            loading = loading,
            modifier = Modifier.fillMaxWidth().weight(1f),
            onOpen = { model.recents.resume(it, cover.displayId) },
            onClose = { model.recents.close(it) },
            onSendToOtherScreen = { model.recents.resume(it, main.displayId) },
        )

        Spacer(Modifier.height(6.dp))
        Text(
            "Tap to resume on the cover screen · flick a card up to close · " +
                "the corner button sends it to the inner screen.",
            style = MaterialTheme.typography.bodySmall,
            color = DeckColors.TextTertiary,
        )
        Spacer(Modifier.height(8.dp))
        CloseAllButton { model.recents.closeAll() }
    }
}

// =========================================================================
// Mirror
// =========================================================================

@Composable
fun MirrorPage(model: DeckViewModel) {
    val state by model.mirror.state.collectAsState()
    val cover by model.coverPanel.collectAsState()
    val main by model.mainPanel.collectAsState()
    val context = LocalContext.current

    val (vw, vh) = model.mirror.virtualSize(main, cover, state.fitMode)

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        DeckHeader(
            title = "Mirror",
            subtitle = "Inner screen on the cover",
            onBack = model::back,
        )

        DeckCard {
            Column {
                Text(
                    "Continuity",
                    style = MaterialTheme.typography.titleMedium,
                    color = DeckColors.TextPrimary,
                )
                Text(
                    "Move a running app onto the cover screen. Reliable, no device-state " +
                        "override, no extra battery cost. Start here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DeckColors.TextSecondary,
                    modifier = Modifier.padding(top = 3.dp, bottom = 10.dp),
                )
                DeckButton(
                    text = "Pick an app to move",
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    onClick = { model.navigate(DeckRoute.Recents) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        DeckCard {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "True mirror",
                        style = MaterialTheme.typography.titleMedium,
                        color = DeckColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip("Experimental", Tone.Warning)
                }
                Text(
                    "Closing the hinge powers the inner panel down, so CoverDeck overrides " +
                        "the reported device state to keep it rendering, then mirrors it. " +
                        "Expect real heat and battery drain while this is on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DeckColors.TextSecondary,
                    modifier = Modifier.padding(top = 3.dp, bottom = 10.dp),
                )

                SegmentedSelector(
                    options = MirrorController.FitMode.entries.map { it.label },
                    selectedIndex = MirrorController.FitMode.entries.indexOf(state.fitMode),
                    modifier = Modifier.fillMaxWidth(),
                    onSelect = { model.mirror.setFitMode(MirrorController.FitMode.entries[it]) },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    state.fitMode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = DeckColors.TextTertiary,
                )

                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    label = "Touch passthrough",
                    description = "Taps on the cover drive the inner screen",
                    checked = state.touchEnabled,
                    onChange = model.mirror::setTouchEnabled,
                )

                Spacer(Modifier.height(10.dp))
                if (state.running) {
                    DeckButton(
                        text = "Stop mirroring",
                        icon = Icons.Rounded.CastConnected,
                        tone = Tone.Danger,
                        onClick = {
                            CoverDeckService.send(context, CoverDeckService.ACTION_STOP_MIRROR)
                            model.mirror.stop()
                        },
                    )
                } else {
                    DeckButton(
                        text = "Start mirroring",
                        icon = Icons.Rounded.Cast,
                        enabled = model.hasOverlayPermission(),
                        onClick = {
                            if (!CoverDeckService.isRunning) CoverDeckService.start(context)
                            CoverDeckService.send(context, CoverDeckService.ACTION_START_MIRROR)
                        },
                    )
                    if (!model.hasOverlayPermission()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Needs \"Display over other apps\" — grant it in Setup.",
                            style = MaterialTheme.typography.bodySmall,
                            color = DeckColors.Warning,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        DeckCard {
            Column {
                InfoRow("Source", "${main.widthPx} × ${main.heightPx} (display ${main.displayId})")
                InfoRow("Mirror buffer", "$vw × $vh")
                InfoRow("Engine", state.engine, tone = if (state.running) Tone.Active else Tone.Neutral)
                InfoRow(
                    "Device state",
                    state.availableStates.firstOrNull { it.first == state.overriddenState }?.second
                        ?: "not overridden",
                    tone = if (state.overriddenState != null) Tone.Warning else Tone.Neutral,
                )
                state.lastError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = DeckColors.Danger,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

// =========================================================================
// Setup
// =========================================================================

@Composable
fun SetupPage(model: DeckViewModel) {
    val status by model.privilegedStatus.collectAsState()
    val context = LocalContext.current
    val cover by model.coverPanel.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        DeckHeader(title = "Setup", subtitle = "Permissions and cover screen access", onBack = model::back)

        DeckCard {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Shizuku",
                        style = MaterialTheme.typography.titleMedium,
                        color = DeckColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(status.shortLabel(), status.tone())
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when (val s = status) {
                        is Privileged.Status.Ready -> s.detail
                        is Privileged.Status.Failed -> s.reason
                        Privileged.Status.NotInstalled -> "Install Shizuku, then start it over wireless debugging."
                        Privileged.Status.NotRunning -> "Shizuku is installed but not started. Start it, then come back."
                        Privileged.Status.PermissionRequired -> "CoverDeck needs to be authorised in Shizuku."
                        Privileged.Status.Connecting -> "Connecting to the privileged service…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = DeckColors.TextSecondary,
                )
                if (status is Privileged.Status.PermissionRequired) {
                    Spacer(Modifier.height(10.dp))
                    DeckButton(text = "Authorise CoverDeck") { Privileged.requestPermission() }
                }
                if (status is Privileged.Status.NotRunning || status is Privileged.Status.Failed) {
                    Spacer(Modifier.height(10.dp))
                    DeckButton(text = "Retry", filled = false) { Privileged.refresh() }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        DeckCard {
            Column {
                Text(
                    "Display over other apps",
                    style = MaterialTheme.typography.titleMedium,
                    color = DeckColors.TextPrimary,
                )
                Text(
                    "Required for the gesture strip, the recents panel and the mirror window.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DeckColors.TextSecondary,
                    modifier = Modifier.padding(top = 3.dp, bottom = 10.dp),
                )
                DeckButton(
                    text = if (model.hasOverlayPermission()) "Granted" else "Grant permission",
                    tone = if (model.hasOverlayPermission()) Tone.Success else Tone.Active,
                    enabled = !model.hasOverlayPermission(),
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        DeckCard {
            Column {
                Text(
                    "Running on the cover screen",
                    style = MaterialTheme.typography.titleMedium,
                    color = DeckColors.TextPrimary,
                )
                Text(
                    "Two routes. Good Lock → MultiStar → I ♥ Galaxy Foldable lets you add " +
                        "CoverDeck to the cover screen app list, which is the tidy way. " +
                        "The button below skips that entirely by launching straight onto " +
                        "display ${cover.displayId} through Shizuku.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DeckColors.TextSecondary,
                    modifier = Modifier.padding(top = 3.dp, bottom = 10.dp),
                )
                DeckButton(
                    text = "Open on cover screen",
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    enabled = status is Privileged.Status.Ready,
                    onClick = {
                        Privileged.with { it.launchPackage(context.packageName, cover.displayId) }
                    },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth()) {
            DeckButton(
                text = "Undo all CoverDeck changes",
                tone = Tone.Danger,
                filled = false,
                onClick = model::resetEverything,
            )
        }
    }
}
