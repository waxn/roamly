package com.roamly.ui.ask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight as SpanFontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * The AI chat half of the Search tab. [showHeader] is off when hosted there —
 * the tab's Ask/Search toggle is the header — and the host also supplies the
 * status-bar padding.
 */
@Composable
fun AskScreen(
    viewModel: AskViewModel = hiltViewModel(),
    onOpenMapDate: (String) -> Unit = {},
    showHeader: Boolean = true,
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (showHeader) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "Ask",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }

        if (state.messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Ask about your location history — \"where was I last weekend?\", \"how far did I travel in June?\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.messages) { msg -> ChatBubble(msg, onOpenMapDate) }
                if (state.sending) {
                    item { TypingBubble() }
                }
            }
        }

        if (state.error != null) {
            Text(
                state.error ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.input,
                onValueChange = viewModel::setInput,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask something…") },
                shape = RoundedCornerShape(16.dp),
                enabled = !state.sending,
            )
            IconButton(
                onClick = viewModel::send,
                enabled = !state.sending && state.input.isNotBlank(),
            ) {
                Icon(Icons.Rounded.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMessage, onOpenMapDate: (String) -> Unit) {
    val isUser = msg.role == "user"
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            AskMarkdownText(
                text = msg.content,
                color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                onOpenMapDate = onOpenMapDate,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

private val LINK_REGEX = Regex("""\[([^\]]+)]\((/[^)\s"'<>]+)\)""")
private val BOLD_REGEX = Regex("""\*\*([^*]+)\*\*""")
private val MAP_DATE_REGEX = Regex("""/map/\?date=(\d{4}-\d{2}-\d{2})""")

/** Renders the AI reply's markdown-lite: `[label](/path)` internal links (only
 * date links to the map are actually clickable) and `**bold**`. Mirrors
 * `format()` in the web Ask template. */
@Composable
private fun AskMarkdownText(
    text: String,
    color: Color,
    onOpenMapDate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = buildAnnotatedString {
        var cursor = 0
        for (match in LINK_REGEX.findAll(text)) {
            appendWithBold(text.substring(cursor, match.range.first), color)
            val label = match.groupValues[1]
            val path = match.groupValues[2]
            val date = MAP_DATE_REGEX.find(path)?.groupValues?.get(1)
            if (date != null) {
                pushStringAnnotation(tag = "date", annotation = date)
                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    appendWithBold(label, color)
                }
                pop()
            } else {
                appendWithBold(label, color)
            }
            cursor = match.range.last + 1
        }
        appendWithBold(text.substring(cursor), color)
    }

    ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(color = color),
        modifier = modifier,
        onClick = { offset ->
            annotated.getStringAnnotations(tag = "date", start = offset, end = offset)
                .firstOrNull()?.let { onOpenMapDate(it.item) }
        },
    )
}

private fun AnnotatedString.Builder.appendWithBold(segment: String, color: Color) {
    var cursor = 0
    for (match in BOLD_REGEX.findAll(segment)) {
        append(segment.substring(cursor, match.range.first))
        withStyle(SpanStyle(fontWeight = SpanFontWeight.Bold, color = color)) {
            append(match.groupValues[1])
        }
        cursor = match.range.last + 1
    }
    append(segment.substring(cursor))
}

@Composable
private fun TypingBubble() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
        ) {
            Box(modifier = Modifier.padding(14.dp)) {
                CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
            }
        }
    }
}
