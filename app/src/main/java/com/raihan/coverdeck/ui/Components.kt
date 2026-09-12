package com.raihan.coverdeck.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.raihan.coverdeck.ui.theme.DeckColors

enum class Tone { Neutral, Active, Warning, Danger, Success }

private fun Tone.color(): Color = when (this) {
    Tone.Neutral -> DeckColors.TextSecondary
    Tone.Active -> DeckColors.Accent
    Tone.Warning -> DeckColors.Warning
    Tone.Danger -> DeckColors.Danger
    Tone.Success -> DeckColors.Success
}

/**
 * The unit of the whole interface: an icon, a name, and a live status line. Active
 * tiles pick up an accent border and a lifted surface so state is readable at a glance
 * on a 3.4" panel without having to read the status text.
 */
@Composable
fun DeckTile(
    icon: ImageVector,
    title: String,
    status: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    tone: Tone = if (active) Tone.Active else Tone.Neutral,
    enabled: Boolean = true,
    // Fixed height rather than a square: the cover screen is only ~339 dp tall, and
    // square tiles fit barely a row and a half of a two-column grid.
    height: Dp = 92.dp,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(
        if (active) DeckColors.SurfaceActive else DeckColors.Surface,
        label = "tileBackground",
    )
    val borderColor by animateColorAsState(
        if (active) DeckColors.OutlineActive else DeckColors.Outline,
        label = "tileBorder",
    )

    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (active) DeckColors.Accent.copy(alpha = 0.18f) else DeckColors.SurfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) tone.color() else DeckColors.TextTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) DeckColors.TextPrimary else DeckColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) tone.color() else DeckColors.TextTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val color = tone.color()
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Two-to-four way selector used for display target, rotation and fit mode. */
@Composable
fun SegmentedSelector(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(DeckColors.Surface)
            .border(1.dp, DeckColors.Outline, RoundedCornerShape(14.dp))
            .padding(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (selected) DeckColors.Accent else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        !enabled -> DeckColors.TextTertiary
                        selected -> Color(0xFF04101F)
                        else -> DeckColors.TextSecondary
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun DeckHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(DeckColors.Surface)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.ArrowBackIosNew,
                    contentDescription = "Back",
                    tint = DeckColors.TextSecondary,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = DeckColors.TextPrimary)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = DeckColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/** Full-width action button. Used where a tile would be too small to be safe to tap. */
@Composable
fun DeckButton(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Active,
    filled: Boolean = true,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val color = if (enabled) tone.color() else DeckColors.TextTertiary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (filled) color.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, color.copy(alpha = if (filled) 0.45f else 0.25f), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
    }
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, tone: Tone = Tone.Neutral) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = DeckColors.TextTertiary)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = tone.color(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun DeckCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(DeckColors.Surface)
            .border(1.dp, DeckColors.Outline, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) { content() }
}
