package com.roamly.ui.kit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.AtlasType
import com.roamly.ui.theme.SquircleRadius
import com.roamly.ui.theme.SquircleShape

/**
 * Bottom space the floating dock takes over a tab's content. A tab's scrolling
 * content pads its end by this so the last item clears the dock; pushed
 * screens (no dock) see 0.
 */
val LocalDockPadding = staticCompositionLocalOf { 0.dp }

/**
 * Set by the shell on the screens that used to be tabs (Trips, Ask, You) and are
 * now pushed from the map's sheet: [ScreenHeader] draws a back button when it is
 * non-null. Null (the default) draws none.
 */
val LocalScreenBack = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Room under content for the dock (or the nav bar when there's no dock). */
@Composable
fun dockAwareBottom(extra: Dp = 24.dp): Dp = LocalDockPadding.current + extra

@Composable
fun ProvideDockPadding(padding: Dp, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalDockPadding provides padding, content = content)

/** A small plain label above a title. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Atlas.colors.textMuted) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier, maxLines = 1)
}

/** A tab's large title block. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        LocalScreenBack.current?.let { back ->
            IconAction(Icons.Rounded.ArrowBack, "Back", back, container = Atlas.colors.surfaceHigh, modifier = Modifier.padding(end = 12.dp))
        }
        Column(Modifier.weight(1f)) {
            if (eyebrow != null) {
                Eyebrow(eyebrow)
                Spacer(Modifier.height(6.dp))
            }
            Text(title, style = MaterialTheme.typography.headlineLarge, color = Atlas.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Atlas.colors.textMuted)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = actions)
    }
}

/** A pushed screen's bar: a round back button, a title, actions. */
@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 60.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconAction(Icons.Rounded.ArrowBack, "Back", onBack, container = Atlas.colors.surfaceHigh)
            Spacer(Modifier.width(12.dp))
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Atlas.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Atlas.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = actions)
    }
}

/** A pushed screen: page ground, [TopBar], content filling the rest. */
@Composable
fun PushedScreen(
    title: String,
    onBack: (() -> Unit)?,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Atlas.colors.bg)) {
        TopBar(title, onBack, subtitle = subtitle, actions = actions)
        content()
    }
}

/** A section heading inside a screen, with an optional trailing action. */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Atlas.colors.textMuted, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                color = Atlas.colors.accent,
                modifier = Modifier.clip(SquircleShape(8.dp)).tappable(feel = Feel.Tick, onClick = onAction).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** A row in a grouped list: icon tile, title, subtitle, trailing slot or chevron. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Atlas.colors.accent,
    titleColor: Color = Atlas.colors.text,
    /** Null = a chevron exactly when the row is tappable. */
    showChevron: Boolean? = null,
    trailing: (@Composable () -> Unit)? = null,
    // Last, so `ListRow(...) { open() }` is the click — never the trailing slot,
    // which would run during composition.
    onClick: (() -> Unit)? = null,
) {
    val chevron = showChevron ?: (onClick != null)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.tappable(pressedScale = 0.99f, onClick = onClick) else Modifier)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconTile(icon, tint = iconTint, size = 36.dp, iconSize = 18.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = titleColor)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Atlas.colors.textMuted)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        } else if (chevron) {
            Icon(Icons.Rounded.ChevronRight, null, tint = Atlas.colors.textFaint, modifier = Modifier.size(20.dp))
        }
    }
}

/** A [ListRow] with a switch. The whole row toggles. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Atlas.colors.accent,
    enabled: Boolean = true,
) {
    val haptics = rememberHaptics()
    ListRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconTint = iconTint,
        modifier = modifier,
        titleColor = if (enabled) Atlas.colors.text else Atlas.colors.textFaint,
        onClick = if (enabled) ({ haptics.toggle(!checked); onCheckedChange(!checked) }) else null,
        showChevron = false,
        trailing = { AtlasSwitch(checked, onCheckedChange, enabled) },
    )
}

/** A rounded group of rows divided by hairlines. */
@Composable
fun RowGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Panel(modifier = modifier.fillMaxWidth(), padding = PaddingValues(vertical = 4.dp), content = content)
}

@Composable
fun RowDivider(inset: Dp = 66.dp) {
    HorizontalDivider(Modifier.padding(start = inset), thickness = 1.dp, color = Atlas.colors.line)
}

/** A big figure with a unit and a label beneath. */
@Composable
fun StatBlock(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    color: Color = Atlas.colors.text,
    small: Boolean = false,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = if (small) AtlasType.figureSmall else AtlasType.figure, color = color, maxLines = 1)
            if (unit != null) {
                Spacer(Modifier.width(3.dp))
                Text(unit, style = MaterialTheme.typography.labelLarge, color = Atlas.colors.textMuted, modifier = Modifier.padding(bottom = if (small) 1.dp else 4.dp))
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Atlas.colors.textMuted, maxLines = 1)
    }
}

/** An empty or error state: tile, title, body, optional action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    tint: Color = Atlas.colors.accent,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(icon, tint = tint, size = 64.dp, iconSize = 30.dp)
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = Atlas.colors.text, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Atlas.colors.textMuted, textAlign = TextAlign.Center)
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            AtlasButton(action, onAction, kind = ButtonKind.Secondary, compact = true)
        }
    }
}

/** An inline error banner. */
@Composable
fun ErrorNote(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    val c = Atlas.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(SquircleShape(SquircleRadius.control))
            .background(c.dangerSoft)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = c.danger, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
        if (onRetry != null) {
            Text(
                "Retry",
                style = MaterialTheme.typography.labelLarge,
                color = c.danger,
                modifier = Modifier.clip(SquircleShape(8.dp)).tappable(onClick = onRetry).padding(6.dp),
            )
        }
    }
}

/** A modal sheet in the app's style. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtlasSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) = AtlasSheet(onDismiss, rememberModalBottomSheetState(skipPartiallyExpanded = true), title, content)

/** [AtlasSheet] with a state the caller holds, to animate it away before acting. */
@ExperimentalMaterial3Api
@Composable
fun AtlasSheet(
    onDismiss: () -> Unit,
    sheetState: SheetState,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Atlas.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.surface,
        contentColor = c.text,
        scrimColor = Color.Black.copy(alpha = if (c.isDark) 0.6f else 0.35f),
        shape = SquircleShape(topStart = SquircleRadius.dialog, topEnd = SquircleRadius.dialog, bottomStart = 0.dp, bottomEnd = 0.dp),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 40.dp, height = 5.dp).clip(SquircleShape(50)).background(c.lineStrong))
        },
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = c.text, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 12.dp))
            }
            content()
        }
    }
}

/** A dialog in the app's style. [destructive] colours the confirm action red. */
@Composable
fun AtlasDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    dismissText: String? = "Cancel",
    /** What the dismiss button does, when it isn't the same as tapping outside. */
    onDismissAction: (() -> Unit)? = null,
    destructive: Boolean = false,
    icon: ImageVector? = null,
    body: (@Composable () -> Unit)? = null,
) {
    val c = Atlas.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = SquircleShape(SquircleRadius.dialog),
        tonalElevation = 0.dp,
        icon = icon?.let { { IconTile(it, tint = if (destructive) c.danger else c.accent, size = 48.dp, iconSize = 24.dp) } },
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = c.text, textAlign = if (icon != null) TextAlign.Center else TextAlign.Start) },
        text = body?.let { b -> { Box { CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides c.textMuted) { b() } } } },
        confirmButton = {
            if (confirmText != null && onConfirm != null) {
                AtlasButton(
                    confirmText, onConfirm,
                    kind = if (destructive) ButtonKind.Danger else ButtonKind.Primary,
                    compact = true,
                    feel = if (destructive) Feel.Heavy else Feel.Confirm,
                )
            }
        },
        dismissButton = dismissText?.let { t -> { AtlasButton(t, onDismissAction ?: onDismiss, kind = ButtonKind.Ghost, compact = true) } },
    )
}

/** A folding section whose head turns its chevron. */
@Composable
fun Foldout(
    title: String,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    badge: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val angle by animateFloatAsState(if (open) 90f else 0f, AtlasMotion.snappy(), label = "fold")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(SquircleShape(SquircleRadius.control))
                .tappable(feel = Feel.Tick, pressedScale = 0.99f, onClick = onToggle)
                .padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = Atlas.colors.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(title, style = MaterialTheme.typography.titleSmall, color = Atlas.colors.text, modifier = Modifier.weight(1f))
            if (badge != null) {
                Tag(badge, color = Atlas.colors.accent)
                Spacer(Modifier.width(8.dp))
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = Atlas.colors.textMuted, modifier = Modifier.size(20.dp).rotate(angle))
        }
        AnimatedVisibility(open, enter = expandVertically(AtlasMotion.glide()) + fadeIn(), exit = shrinkVertically(AtlasMotion.glide()) + fadeOut()) {
            Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

/** Standard content padding for a scrolling tab or pushed screen. */
@Composable
fun screenPadding(top: Dp = 4.dp): PaddingValues =
    PaddingValues(start = 20.dp, end = 20.dp, top = top, bottom = dockAwareBottom())

/**
 * Pull down from the top of a scrolling screen to refresh, with the elastic pull
 * indicator. [onRefresh] is fire-and-forget (the view-models repaint in place when
 * the data lands), so the spinner just holds long enough to be seen.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RefreshBox(
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var refreshing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(refreshing) {
        if (refreshing) { kotlinx.coroutines.delay(900); refreshing = false }
    }
    val pullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { refreshing = true; onRefresh() },
        modifier = modifier,
        state = pullState,
        indicator = {
            androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = Atlas.colors.surfaceHigh,
                color = Atlas.colors.accent,
            )
        },
        content = { content() },
    )
}
