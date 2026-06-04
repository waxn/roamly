package com.roamly.ui.trips

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.data.api.Comment
import com.roamly.data.api.TimelineEvent
import com.roamly.ui.theme.Clay
import com.roamly.ui.theme.ClayCard
import com.roamly.ui.theme.ClayIconBadge

@Composable
fun TripDetailScreen(
    tripId: Int,
    onBack: () -> Unit,
    viewModel: TripDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val clay = Clay.colors
    LaunchedEffect(tripId) { viewModel.load(tripId) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ClayBackButton(onBack)
                Spacer(Modifier.width(8.dp))
                Text(
                    state.trip?.name ?: "Adventure",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = viewModel::togglePublic) {
                    Icon(
                        Icons.Rounded.Public,
                        "Toggle public",
                        tint = if (state.trip?.isPublic == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 4.dp, 16.dp, 120.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.trip?.let { trip ->
                        item {
                            // Hero card
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(26.dp))
                                    .background(Brush.linearGradient(clay.primaryGradient))
                                    .padding(20.dp)
                            ) {
                                Column {
                                    Text(trip.name, style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                                    trip.description?.takeIf { it.isNotBlank() }?.let {
                                        Spacer(Modifier.height(4.dp))
                                        Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        HeroStat("${trip.locationCount}", "points")
                                        HeroStat("${trip.memberCount}", "members")
                                        val days = listOfNotNull(trip.startTime?.take(10), trip.endTime?.take(10)).joinToString(" → ")
                                        if (days.isNotEmpty()) HeroStat(days, "")
                                    }
                                }
                            }
                        }
                    }

                    if (state.events.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                ClayIconBadge(Icons.Rounded.ChatBubbleOutline, size = 56.dp, cornerRadius = 20.dp)
                                Spacer(Modifier.height(12.dp))
                                Text("No updates yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                                Text("Tap + to add an update or milestone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        items(state.events) { event ->
                            if (event.type == "milestone") MilestoneCard(event)
                            else BlurbCard(
                                event = event,
                                comments = state.comments[event.id],
                                isExpanded = state.expandedBlurbId == event.id,
                                onDelete = { viewModel.deleteBlurb(event.id) },
                                onToggleComments = { viewModel.toggleComments(event.id) },
                                onPostComment = { text -> viewModel.createComment(event.id, text) },
                                onDeleteComment = { cid -> viewModel.deleteComment(event.id, cid) },
                            )
                        }
                    }
                }
            }
        }

        // FAB
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 24.dp)
                .size(60.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.verticalGradient(clay.primaryGradient))
                .clickable { viewModel.showAddTypeDialog() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Add, "Add", tint = Color.White, modifier = Modifier.size(28.dp))
        }

        state.error?.let {
            Text(
                it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
            )
        }
    }

    // --- Dialogs ---
    if (state.showAddTypeDialog) {
        AlertDialog(
            onDismissRequest = viewModel::hideAddTypeDialog,
            title = { Text("What do you want to add?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::showBlurbDialog, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.ChatBubbleOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Update / Post")
                    }
                    OutlinedButton(onClick = viewModel::showMilestoneDialog, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.EmojiEvents, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Milestone")
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = viewModel::hideAddTypeDialog) { Text("Cancel") } }
        )
    }

    if (state.showBlurbDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = viewModel::hideBlurbDialog,
            title = { Text("Add update") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("What's happening?") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            },
            confirmButton = { TextButton(onClick = { viewModel.createBlurb(text) }) { Text("Post") } },
            dismissButton = { TextButton(onClick = viewModel::hideBlurbDialog) { Text("Cancel") } }
        )
    }

    if (state.showMilestoneDialog) {
        MilestoneCreateDialog(
            onDismiss = viewModel::hideMilestoneDialog,
            onConfirm = { emoji, title, desc, date -> viewModel.createMilestone(emoji, title, desc, date) }
        )
    }
}

@Composable
private fun ClayBackButton(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun HeroStat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
    }
}

@Composable
private fun BlurbCard(
    event: TimelineEvent,
    comments: List<Comment>?,
    isExpanded: Boolean,
    onDelete: () -> Unit,
    onToggleComments: () -> Unit,
    onPostComment: (String) -> Unit,
    onDeleteComment: (Int) -> Unit,
) {
    ClayCard(contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(event.author)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(event.author ?: "", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(event.createdAt?.take(10) ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (event.canDelete) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
        if (!event.text.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(event.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
        }
        event.locationName?.let {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Place, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onToggleComments).padding(vertical = 4.dp, horizontal = 2.dp),
        ) {
            Icon(Icons.Rounded.ChatBubbleOutline, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            val count = comments?.size ?: event.commentCount
            Text(if (count > 0) "$count comment${if (count != 1) "s" else ""}" else "Comment", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        AnimatedVisibility(visible = isExpanded) {
            Column {
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                if (comments == null) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.primary)
                } else {
                    comments.forEach { c -> CommentRow(c, onDelete = { onDeleteComment(c.id) }) }
                }
                var commentText by remember { mutableStateOf("") }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = commentText, onValueChange = { commentText = it },
                        placeholder = { Text("Add a comment…", style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(14.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = {
                        if (commentText.isNotBlank()) { onPostComment(commentText); commentText = "" }
                    }) { Icon(Icons.Rounded.Send, "Post", tint = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
}

@Composable
private fun Avatar(name: String?) {
    val clay = Clay.colors
    Box(
        modifier = Modifier.size(34.dp).clip(CircleShape).background(Brush.linearGradient(clay.tertiaryGradient)),
        contentAlignment = Alignment.Center,
    ) {
        Text(name?.firstOrNull()?.uppercaseChar()?.toString() ?: "?", style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CommentRow(comment: Comment, onDelete: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Avatar(comment.author)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(comment.author, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(comment.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground)
        }
        if (comment.canDelete) {
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Rounded.Delete, "Delete", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MilestoneCard(event: TimelineEvent) {
    val clay = Clay.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(clay.secondaryGradient))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(event.emoji ?: "🏆", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(event.title ?: "", style = MaterialTheme.typography.titleSmall, color = Color(0xFF052B26), fontWeight = FontWeight.Bold)
                event.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF052B26).copy(alpha = 0.85f))
                }
                event.date?.let { Text(it.take(10), style = MaterialTheme.typography.labelSmall, color = Color(0xFF052B26).copy(alpha = 0.7f)) }
            }
        }
    }
}

@Composable
private fun MilestoneCreateDialog(
    onDismiss: () -> Unit,
    onConfirm: (emoji: String, title: String, description: String, date: String) -> Unit
) {
    var emoji by remember { mutableStateOf("🏁") }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add milestone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = emoji, onValueChange = { emoji = it }, label = { Text("Emoji") }, modifier = Modifier.width(84.dp), singleLine = true)
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title *") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text("Date (YYYY-MM-DD) *") }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("2025-01-15") })
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(emoji, title, description, date) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
