package com.roamly.ui.trips

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.data.api.Comment
import com.roamly.data.api.TimelineEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(
    tripId: Int,
    onBack: () -> Unit,
    viewModel: TripDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(tripId) { viewModel.load(tripId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.trip?.name ?: "Trip") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = viewModel::togglePublic) {
                        Icon(Icons.Filled.Share, "Toggle public")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::showAddTypeDialog) {
                Icon(Icons.Filled.Add, "Add")
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // Trip header
                    state.trip?.let { trip ->
                        item {
                            Column(modifier = Modifier.padding(16.dp)) {
                                trip.description?.let {
                                    if (it.isNotBlank()) {
                                        Text(it, style = MaterialTheme.typography.bodyMedium)
                                        Spacer(Modifier.height(4.dp))
                                    }
                                }
                                val dateRange = listOfNotNull(
                                    trip.startTime?.take(10),
                                    trip.endTime?.take(10)
                                ).joinToString(" → ")
                                if (dateRange.isNotEmpty()) {
                                    Text(dateRange, style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("${trip.memberCount} members · ${trip.locationCount} pts",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (trip.isPublic) {
                                        Text("· public",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
                            }
                        }
                    }

                    // Timeline events
                    if (state.events.isEmpty() && !state.isLoading) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No updates yet. Tap + to add one.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        items(state.events) { event ->
                            if (event.type == "milestone") {
                                TripMilestoneCard(event)
                            } else {
                                TripBlurbCard(
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

            state.error?.let {
                Card(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(it, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }

    // --- Add type picker ---
    if (state.showAddTypeDialog) {
        AlertDialog(
            onDismissRequest = viewModel::hideAddTypeDialog,
            title = { Text("What do you want to add?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::showBlurbDialog, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Chat, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Update / Post")
                    }
                    OutlinedButton(onClick = viewModel::showMilestoneDialog, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.EmojiEvents, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Milestone")
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
            title = { Text("Add Update") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("What's happening?") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.createBlurb(text) }) { Text("Post") } },
            dismissButton = { TextButton(onClick = viewModel::hideBlurbDialog) { Text("Cancel") } }
        )
    }

    if (state.showMilestoneDialog) {
        TripMilestoneCreateDialog(
            onDismiss = viewModel::hideMilestoneDialog,
            onConfirm = { emoji, title, desc, date -> viewModel.createMilestone(emoji, title, desc, date) }
        )
    }
}

@Composable
private fun TripBlurbCard(
    event: TimelineEvent,
    comments: List<Comment>?,
    isExpanded: Boolean,
    onDelete: () -> Unit,
    onToggleComments: () -> Unit,
    onPostComment: (String) -> Unit,
    onDeleteComment: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        event.author ?: "",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(event.createdAt?.take(10) ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (event.canDelete) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Delete, "Delete",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (!event.text.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(event.text, style = MaterialTheme.typography.bodyMedium)
            }
            event.locationName?.let {
                Spacer(Modifier.height(4.dp))
                Text("📍 $it", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onToggleComments, modifier = Modifier.padding(0.dp)) {
                Icon(Icons.Filled.Chat, null, Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                val count = comments?.size ?: event.commentCount
                Text(
                    if (count > 0) "$count comment${if (count != 1) "s" else ""}" else "Comment",
                    style = MaterialTheme.typography.labelMedium
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    if (comments == null) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp).align(Alignment.CenterHorizontally))
                    } else {
                        comments.forEach { comment ->
                            TripCommentRow(comment, onDelete = { onDeleteComment(comment.id) })
                        }
                    }
                    var commentText by remember { mutableStateOf("") }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = commentText,
                            onValueChange = { commentText = it },
                            placeholder = { Text("Add a comment…", style = MaterialTheme.typography.bodySmall) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = {
                            if (commentText.isNotBlank()) {
                                onPostComment(commentText)
                                commentText = ""
                            }
                        }) {
                            Icon(Icons.Filled.CheckCircle, "Post",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TripCommentRow(comment: Comment, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                comment.author.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(comment.author, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
            Text(comment.text, style = MaterialTheme.typography.bodySmall)
        }
        if (comment.canDelete) {
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Delete, "Delete",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TripMilestoneCard(event: TimelineEvent) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(event.emoji ?: "🏆", style = MaterialTheme.typography.headlineMedium)
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(event.title ?: "", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                event.description?.let {
                    if (it.isNotBlank()) Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                event.date?.let {
                    Text(it.take(10), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }
    }
}

@Composable
private fun TripMilestoneCreateDialog(
    onDismiss: () -> Unit,
    onConfirm: (emoji: String, title: String, description: String, date: String) -> Unit
) {
    var emoji by remember { mutableStateOf("🏁") }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Milestone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = emoji,
                        onValueChange = { emoji = it },
                        label = { Text("Emoji") },
                        modifier = Modifier.width(80.dp),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title *") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("Date (YYYY-MM-DD) *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("2025-01-15") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(emoji, title, description, date) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
