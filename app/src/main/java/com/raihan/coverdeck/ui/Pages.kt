package com.raihan.coverdeck.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.raihan.coverdeck.feature.AutoRotate
import com.raihan.coverdeck.mirror.MirrorHostService
import com.raihan.coverdeck.mirror.MirrorSession
import com.raihan.coverdeck.feature.RotationController
import com.raihan.coverdeck.feature.ScreenTimeout
import com.raihan.coverdeck.privileged.Privileged
import com.raihan.coverdeck.ui.theme.DeckColors

// =========================================================================
// Rotation
// =========================================================================

@Composable
fun RotationPage(model: DeckViewModel) {
    // Cover only. The inner screen is only ever seen through the mirror, and the mirror
    // holds it upright itself for the whole session.
    val panel by model.coverPanel.collectAsState()
    val state by model.rotationState(panel.displayId).collectAsState()
    val modes = RotationController.modesFor(panel.displayId)

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        DeckHeader(
            title = "Rotation",
            subtitle = "Cover screen",
            onBack = model::back,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            modes.chunked(3).forEach { row ->
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

        BackHoldCard(model)
        Spacer(Modifier.height(10.dp))

        if (state.autoRotate) {
            AutoRotateCard()
            Spacer(Modifier.height(10.dp))
        }

        DeckCard {
            Column {
                // Obeying only means something while CoverDeck is pinning the display.
                val pinning = state.mode.rotation != null || state.mode == RotationController.Mode.AUTO
                if (pinning) {
                    ToggleRow(
                        label = "Force apps to obey",
                        description = "Ignore each app's and the cover launcher's requested orientation",
                        checked = state.forceAppsToObey,
                        onChange = { model.applyRotation(state.mode, it) },
                    )
                    Spacer(Modifier.height(6.dp))
                }
                InfoRow("Current rotation", "${state.currentRotation * 90}°")
                InfoRow(
                    "Controlled by",
                    when {
                        state.autoRotate -> "CoverDeck, following the phone"
                        state.frozen -> "CoverDeck, locked"
                        else -> "One UI (Samsung default)"
                    },
                    tone = if (state.frozen || state.autoRotate) Tone.Active else Tone.Neutral,
                )
                if (state.mode == RotationController.Mode.SYSTEM) {
                    Text(
                        "Samsung's default. The cover home screen and most cover apps force " +
                            "their natural orientation, so the cover usually won't turn. " +
                            "Pick Auto to have it follow the phone everywhere.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DeckColors.TextTertiary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/** The "hold Back to switch rotation" switch, shown on the cover's rotation page. */
@Composable
private fun BackHoldCard(model: DeckViewModel) {
    val enabled by model.backLongPressEnabled.collectAsState()
    val ready = model.privilegedStatus.collectAsState().value is Privileged.Status.Ready

    DeckCard {
        Column {
            ToggleRow(
                label = "Hold Back to switch rotation",
                description = when {
                    !ready -> "Needs Shizuku"
                    enabled -> "On"
                    else -> "Off"
                },
                checked = enabled,
                onChange = { if (ready) model.setBackLongPress(it) },
            )
            Text(
                "Hold the Back button on the cover screen for a moment to switch between Auto " +
                    "and locked at 0°. A short confirmation shows on the cover, and a quick tap " +
                    "on Back still goes back. Works everywhere on the cover while the phone is " +
                    "folded, and keeps the CoverDeck notification showing.",
                style = MaterialTheme.typography.bodySmall,
                color = DeckColors.TextSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * Live view of the cover auto-rotate loop, plus a two-step calibration. The loop's
 * readings come straight from the service, so what is shown is what it is acting on.
 */
@Composable
private fun AutoRotateCard() {
    val raw by AutoRotate.rawReading.collectAsState()
    val applied by AutoRotate.appliedRotation.collectAsState()
    val source by AutoRotate.source.collectAsState()
    val upsideDown by AutoRotate.allowUpsideDown.collectAsState()
    val calibrated by AutoRotate.calibrated.collectAsState()

    // 0 = idle, 1 = waiting for the normal position, 2 = waiting for a clockwise turn.
    var step by remember { mutableIntStateOf(0) }
    var normalReading by remember { mutableStateOf<Int?>(null) }
    var calibrationError by remember { mutableStateOf<String?>(null) }

    // Leaving the page mid-calibration must not leave rotation paused.
    DisposableEffect(Unit) { onDispose { AutoRotate.calibrating = false } }

    val live = source == "device orientation sensor" || source == "accelerometer"

    fun cancel() {
        AutoRotate.calibrating = false
        step = 0
        normalReading = null
    }

    DeckCard {
        Column {
            Text("Cover auto-rotate", style = MaterialTheme.typography.titleMedium, color = DeckColors.TextPrimary)
            Text(
                "CoverDeck turns the cover to match the phone, in every app and on the cover home screen.",
                style = MaterialTheme.typography.bodySmall,
                color = DeckColors.TextSecondary,
                modifier = Modifier.padding(top = 3.dp, bottom = 8.dp),
            )
            ToggleRow(
                label = "Allow upside-down",
                description = "Off matches how the inner screen behaves",
                checked = upsideDown,
                onChange = AutoRotate::setAllowUpsideDown,
            )
            Spacer(Modifier.height(6.dp))
            InfoRow("Sensor", source, tone = if (live) Tone.Success else Tone.Warning)
            InfoRow("Applied", applied?.let { "${it * 90}°" } ?: "waiting for movement")
            InfoRow("Mapping", if (calibrated) "Calibrated" else "Default")
            Spacer(Modifier.height(8.dp))

            when (step) {
                0 -> {
                    calibrationError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = DeckColors.Warning)
                        Spacer(Modifier.height(6.dp))
                    }
                    DeckButton(
                        text = "Turns the wrong way? Calibrate",
                        filled = false,
                        enabled = live,
                        onClick = {
                            AutoRotate.calibrating = true
                            calibrationError = null
                            step = 1
                        },
                    )
                    if (calibrated) {
                        Spacer(Modifier.height(6.dp))
                        DeckButton(
                            text = "Reset calibration",
                            filled = false,
                            tone = Tone.Neutral,
                            onClick = {
                                AutoRotate.resetCalibration()
                                AutoRotate.requestReapply()
                            },
                        )
                    }
                }

                1 -> CalibrationStep(
                    text = "1 of 2 · Hold the folded phone the way you normally read the cover, then tap Next.",
                    actionLabel = "Next",
                    actionEnabled = raw != null,
                    onCancel = ::cancel,
                    onAction = {
                        normalReading = raw
                        step = 2
                    },
                )

                else -> CalibrationStep(
                    text = "2 of 2 · Turn it a quarter turn clockwise, so the right side points down, then tap Done.",
                    actionLabel = "Done",
                    actionEnabled = raw != null && raw != normalReading,
                    onCancel = ::cancel,
                    onAction = {
                        val ok = AutoRotate.calibrate(normalReading ?: 0, raw ?: 0)
                        calibrationError = if (ok) null else
                            "Those two positions weren't a quarter turn apart. Try again."
                        cancel()
                        if (ok) AutoRotate.requestReapply()
                    },
                )
            }
        }
    }
}

@Composable
private fun CalibrationStep(
    text: String,
    actionLabel: String,
    actionEnabled: Boolean,
    onCancel: () -> Unit,
    onAction: () -> Unit,
) {
    Column {
        Text(text, style = MaterialTheme.typography.bodySmall, color = DeckColors.AccentBright)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeckButton(
                text = "Cancel",
                filled = false,
                tone = Tone.Neutral,
                modifier = Modifier.weight(1f),
                onClick = onCancel,
            )
            DeckButton(
                text = actionLabel,
                enabled = actionEnabled,
                modifier = Modifier.weight(1f),
                onClick = onAction,
            )
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
// Screen timeout
// =========================================================================

@Composable
fun TimeoutPage(model: DeckViewModel) {
    val state by ScreenTimeout.state.collectAsState()
    val ready = model.privilegedStatus.collectAsState().value is Privileged.Status.Ready
    val everywhere = state.coverSeconds == state.appSeconds

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        DeckHeader(title = "Screen timeout", subtitle = "Cover screen", onBack = model::back)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ScreenTimeout.options.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { option ->
                        val selected = everywhere && state.coverSeconds == option.seconds
                        DeckButton(
                            text = option.label,
                            tone = if (selected) Tone.Active else Tone.Neutral,
                            filled = selected,
                            enabled = ready,
                            modifier = Modifier.weight(1f),
                            onClick = { model.setScreenTimeout(option.seconds) },
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        DeckCard {
            Column {
                InfoRow("Cover home and quick panel", ScreenTimeout.label(state.coverSeconds))
                InfoRow("Apps on the cover", ScreenTimeout.label(state.appSeconds))
                Text(
                    if (everywhere) {
                        "One UI keeps a separate timeout for apps; CoverDeck sets both, so the " +
                            "cover behaves the same everywhere."
                    } else {
                        "One UI keeps a separate timeout for apps, and they differ right now. " +
                            "Pick a time to use it everywhere on the cover."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = DeckColors.TextTertiary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (state.stayAwakeWhileCharging) {
                    Text(
                        "Stay awake is on in Developer options, so while the phone is charging " +
                            "the screen never turns off. The timeout applies on battery.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DeckColors.Warning,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

// =========================================================================
// Recents
// =========================================================================

@Composable
fun RecentsPage(model: DeckViewModel) {
    val enabled by model.homeLongPressEnabled.collectAsState()
    val ready = model.privilegedStatus.collectAsState().value is Privileged.Status.Ready

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        DeckHeader(title = "Recents", subtitle = "Recent apps on the cover screen", onBack = model::back)

        DeckCard {
            Column {
                ToggleRow(
                    label = "Hold Home for recents",
                    description = when {
                        !ready -> "Needs Shizuku"
                        enabled -> "On"
                        else -> "Off"
                    },
                    checked = enabled,
                    onChange = { if (ready) model.setHomeLongPress(it) },
                )
                Text(
                    "Hold the Home button on the cover screen to open recents, then tap Home or " +
                        "Back to close them. One UI has no recents on the cover, so CoverDeck " +
                        "adds them. This runs only while the phone is folded with the cover " +
                        "screen on, and keeps the CoverDeck notification showing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DeckColors.TextSecondary,
                    modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                )
                DeckButton(
                    text = "Show recents now",
                    icon = Icons.Rounded.Layers,
                    enabled = ready,
                    onClick = model::previewCoverRecents,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        DeckCard {
            Column {
                Text("Using recents", style = MaterialTheme.typography.titleMedium, color = DeckColors.TextPrimary)
                Text(
                    "Swipe sideways to browse and tap an app to open it on the cover. Swipe an " +
                        "app up to close it. Tap an app's name for App info, Keep open, or " +
                        "opening it on the other screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DeckColors.TextSecondary,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

// =========================================================================
// Mirror
// =========================================================================

@Composable
fun MirrorPage(model: DeckViewModel) {
    val state by MirrorSession.state.collectAsState()
    val hostOn by MirrorHostService.connected.collectAsState()
    val ready = model.privilegedStatus.collectAsState().value is Privileged.Status.Ready
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        DeckHeader(
            title = "Mirror",
            subtitle = "Use the inner screen from the cover",
            onBack = model::back,
        )

        if (!hostOn) {
            DeckCard {
                Column {
                    Text("One-time setup", style = MaterialTheme.typography.titleMedium, color = DeckColors.TextPrimary)
                    Text(
                        "The mirror is drawn by CoverDeck's accessibility service. On this phone, " +
                            "that's the only kind of window that can cover the whole cover " +
                            "screen, including its navigation bar and quick panel, and stay " +
                            "visible over Settings. The service is used only to show that " +
                            "window and CoverDeck's short confirmations. It doesn't read the " +
                            "screen or intercept keys.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DeckColors.TextSecondary,
                        modifier = Modifier.padding(top = 3.dp, bottom = 10.dp),
                    )
                    DeckButton(
                        text = "Turn on with Shizuku",
                        enabled = ready,
                        onClick = { MirrorHostService.enableWithShizuku(context) },
                    )
                    Spacer(Modifier.height(6.dp))
                    DeckButton(
                        text = "Open Accessibility settings",
                        filled = false,
                        tone = Tone.Neutral,
                        onClick = { MirrorHostService.openSettings(context) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        DeckCard {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Mirror mode",
                        style = MaterialTheme.typography.titleMedium,
                        color = DeckColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(
                        when {
                            state.streaming -> "Live"
                            state.active -> "Paused"
                            else -> "Off"
                        },
                        if (state.streaming) Tone.Success else Tone.Neutral,
                    )
                }
                Text(
                    "Uses the whole cover for the inner screen, held upright, and touch works " +
                        "as if you were using it, including the inner screen's own navigation " +
                        "bar. The strip beside the cameras has Recents, Home and Back too, and " +
                        "⋯ opens the menu, where you stop mirroring. The cover stays awake " +
                        "while mirroring; the power button still turns it off, and the " +
                        "inner panel sleeps with it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DeckColors.TextSecondary,
                    modifier = Modifier.padding(top = 3.dp, bottom = 10.dp),
                )

                SegmentedSelector(
                    options = MirrorSession.Shape.entries.map { it.label },
                    selectedIndex = MirrorSession.Shape.entries.indexOf(state.shape),
                    modifier = Modifier.fillMaxWidth(),
                    onSelect = { MirrorSession.setShape(MirrorSession.Shape.entries[it]) },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    state.shape.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = DeckColors.TextTertiary,
                )

                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    label = "Hide navigation bar",
                    description = "More room for apps. Navigate with the strip beside the cameras",
                    checked = state.hideNavBar,
                    onChange = MirrorSession::setHideNavBar,
                )
                Spacer(Modifier.height(4.dp))
                ToggleRow(
                    label = "Turn off inner screen",
                    description = "Saves battery. The mirror, touch from the cover and scrcpy keep working",
                    checked = state.innerOff,
                    onChange = MirrorSession::setInnerOff,
                )

                Spacer(Modifier.height(10.dp))
                DeckButton(
                    text = "Start mirroring",
                    icon = Icons.Rounded.Cast,
                    enabled = ready && hostOn,
                    onClick = { MirrorSession.start() },
                )
                if (state.active) {
                    Spacer(Modifier.height(6.dp))
                    DeckButton(
                        text = "Stop mirroring",
                        icon = Icons.Rounded.CastConnected,
                        tone = Tone.Danger,
                        filled = false,
                        onClick = { model.stopMirror() },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        DeckCard {
            Column {
                InfoRow(
                    "Inner screen while mirroring",
                    if (state.sourceWidth > 0) "${state.sourceWidth} × ${state.sourceHeight}" else "—",
                )
                InfoRow("Engine", state.engine, tone = if (state.streaming) Tone.Active else Tone.Neutral)
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
fun SetupPage(model: DeckViewModel) = SetupPageContent(model)

@Composable
private fun SetupPageContent(model: DeckViewModel) {
    val status by model.privilegedStatus.collectAsState()
    val context = LocalContext.current
    val cover by model.coverPanel.collectAsState()
    val notice by model.notice.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }

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
                text = "Reset settings",
                tone = Tone.Danger,
                filled = false,
                onClick = { confirmReset = true },
            )
        }
        notice?.let {
            Spacer(Modifier.height(8.dp))
            NoticeBanner(it) { model.dismissNotice() }
        }
    }

    if (confirmReset) {
        ResetConfirmDialog(
            onConfirm = {
                confirmReset = false
                model.resetEverything()
            },
            onDismiss = { confirmReset = false },
        )
    }
}

/** Reset touches rotation, mirroring and both gestures at once, so ask first. */
@Composable
private fun ResetConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DeckColors.SurfaceRaised,
        titleContentColor = DeckColors.TextPrimary,
        textContentColor = DeckColors.TextSecondary,
        title = { Text("Reset settings?") },
        text = {
            Text(
                "Stops mirroring, turns off cover auto-rotate and the Home and Back hold " +
                    "gestures, and puts screen rotation and screen timeout back to how they " +
                    "were before CoverDeck changed them.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Reset", color = DeckColors.Danger) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = DeckColors.TextSecondary) }
        },
    )
}
