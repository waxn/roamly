package com.roamly.ui.ask

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.ui.kit.ErrorNote
import com.roamly.ui.kit.Feel
import com.roamly.ui.kit.IconTile
import com.roamly.ui.kit.LocalDockPadding
import com.roamly.ui.kit.rememberHaptics
import com.roamly.ui.kit.tappable
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.SquircleRadius
import com.roamly.ui.theme.SquircleShape

private val SUGGESTIONS = listOf(
    "Where was I last weekend?",
    "How far did I travel in June?",
    "Which countries did I visit this year?",
    "When was I last at the beach?",
)

/** The AI chat over your location history. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskScreen(
    viewModel: AskViewModel = hiltViewModel(),
    onOpenMapDate: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val c = Atlas.colors
    val haptics = rememberHaptics()
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.sending) {
        val n = state.messages.size + if (state.sending) 1 else 0
        if (n > 0) listState.animateScrollToItem(n - 1)
        // A reply landed.
        if (!state.sending && state.messages.lastOrNull()?.role == "assistant") haptics.tick()
    }

    // The composer sits above the dock, or above the keyboard when it's up.
    val ime = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val bottom = max(LocalDockPadding.current, ime)

    Column(Modifier.fillMaxSize()) {
        if (state.messages.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconTile(Icons.Rounded.AutoAwesome, tint = c.violet, size = 60.dp, iconSize = 28.dp)
                Spacer(Modifier.size(16.dp))
                Text("Ask your travels anything", style = MaterialTheme.typography.titleLarge, color = c.text, textAlign = TextAlign.Center)
                Spacer(Modifier.size(6.dp))
                Text("Answers come from your own history, with links straight to the day on the map.",
                    style = MaterialTheme.typography.bodyMedium, color = c.textMuted, textAlign = TextAlign.Center)
                Spacer(Modifier.size(22.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SUGGESTIONS.forEach { s ->
                        Text(
                            s,
                            style = MaterialTheme.typography.labelLarge,
                            color = c.text,
                            modifier = Modifier.clip(SquircleShape(50)).background(c.surfaceHigh)
                                .tappable(pressedScale = 0.94f) { viewModel.setInput(s); viewModel.send() }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(state.messages) { _, msg -> Message(msg, onOpenMapDate) }
                if (state.sending) item { Thinking() }
            }
        }

        state.error?.let { ErrorNote(it, Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }

        Composer(
            value = state.input,
            onValueChange = viewModel::setInput,
            enabled = !state.sending,
            onSend = { haptics.click(); viewModel.send() },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottom + 10.dp),
        )
    }
}

@Composable
private fun Composer(value: String, onValueChange: (String) -> Unit, enabled: Boolean, onSend: () -> Unit, modifier: Modifier) {
    val c = Atlas.colors
    val canSend = enabled && value.isNotBlank()
    val shape = SquircleShape(SquircleRadius.panel)
    Row(
        modifier.fillMaxWidth().clip(shape).background(c.surfaceHigh).border(1.dp, c.line, shape)
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(Modifier.weight(1f).heightIn(min = 44.dp).padding(vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text("Ask about your history…", style = MaterialTheme.typography.bodyLarge, color = c.textFaint)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(
            Modifier.size(44.dp).clip(SquircleShape(50)).background(if (canSend) c.accent else c.surfaceHighest)
                .tappable(enabled = canSend, feel = Feel.None, pressedScale = 0.85f, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.ArrowUpward, "Send", tint = if (canSend) c.onAccent else c.textFaint, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun Message(msg: ChatMessage, onOpenMapDate: (String) -> Unit) {
    val c = Atlas.colors
    if (msg.role == "user") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                msg.content,
                style = MaterialTheme.typography.bodyLarge,
                color = c.onAccent,
                modifier = Modifier.widthIn(max = 300.dp)
                    .clip(SquircleShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp))
                    .background(c.accent)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            )
        }
    } else {
        Row(Modifier.fillMaxWidth().animateContentSize()) {
            IconTile(Icons.Rounded.AutoAwesome, tint = c.violet, size = 30.dp, iconSize = 15.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                rememberReply(msg.content, c.accent, onOpenMapDate),
                style = MaterialTheme.typography.bodyLarge,
                color = c.text,
                modifier = Modifier.weight(1f).padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun Thinking() {
    val c = Atlas.colors
    val t = rememberInfiniteTransition(label = "thinking")
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconTile(Icons.Rounded.AutoAwesome, tint = c.violet, size = 30.dp, iconSize = 15.dp)
        Spacer(Modifier.width(14.dp))
        repeat(3) { i ->
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(520, delayMillis = i * 160), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.padding(horizontal = 3.dp).size(8.dp).graphicsLayer { alpha = a; scaleX = 0.7f + 0.3f * a; scaleY = 0.7f + 0.3f * a }
                .clip(SquircleShape(50)).background(c.violet))
        }
    }
}

private val LINK_REGEX = Regex("""\[([^\]]+)]\((/[^)\s"'<>]+)\)""")
private val BOLD_REGEX = Regex("""\*\*([^*]+)\*\*""")
private val MAP_DATE_REGEX = Regex("""/map/\?date=(\d{4}-\d{2}-\d{2})""")

/**
 * The reply's markdown-lite, as the web Ask renders it: `[label](/path)` links
 * (only map date links go anywhere — to that day on the map) and `**bold**`.
 */
@Composable
private fun rememberReply(text: String, linkColor: Color, onOpenMapDate: (String) -> Unit): AnnotatedString {
    val haptics = rememberHaptics()
    return buildAnnotatedString {
        var cursor = 0
        for (match in LINK_REGEX.findAll(text)) {
            appendBold(text.substring(cursor, match.range.first))
            val label = match.groupValues[1]
            val date = MAP_DATE_REGEX.find(match.groupValues[2])?.groupValues?.get(1)
            if (date != null) {
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "date",
                        styles = TextLinkStyles(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)),
                    ) { haptics.click(); onOpenMapDate(date) }
                ) { append(label) }
            } else {
                appendBold(label)
            }
            cursor = match.range.last + 1
        }
        appendBold(text.substring(cursor))
    }
}

private fun AnnotatedString.Builder.appendBold(segment: String) {
    var cursor = 0
    for (match in BOLD_REGEX.findAll(segment)) {
        append(segment.substring(cursor, match.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[1]) }
        cursor = match.range.last + 1
    }
    append(segment.substring(cursor))
}
