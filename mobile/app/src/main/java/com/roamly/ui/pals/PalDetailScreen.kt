package com.roamly.ui.pals

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
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
import com.roamly.data.api.PalMember
import com.roamly.data.api.TimelineEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PalDetailScreen(
    palId: Int,
    onBack: () -> Unit,
    viewModel: PalDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(palId) { viewModel.load(palId) }

    val isCreator = state.pal?.role == "creator"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.pal?.name ?: "Group Trip") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
                }
            )
        },
        floatingActionButton = {
            if (state.selectedTab == PalTab.TIMELINE) {
                FloatingActionButton(onClick = viewModel::showAddTypeDialog) {
                    Icon(Icons.Filled.Add, "Add")
                }
            }
            if (state.selectedTab == PalTab.MEMBERS && isCreator) {
                FloatingActionButton(onClick = viewModel::showAddMemberDialog) {
                    Icon(Icons.Filled.PersonAdd, "Add member")
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header
                    state.pal?.let { pal ->
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            pal.description?.let {
                                if (it.isNotBlank()) {
                                    Text(it, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                            val dateRange = listOfNotNull(pal.startDate, pal.endDate).joinToString(" → ")
                            if (dateRange.isNotEmpty()) {
                                Text(dateRange, style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "${pal.memberCount} members",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (pal.isPublic) {
                                    Text("· public",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                                pal.role?.let {
                                    Text("· $it",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary)
                                }
                            }
                        }
                        HorizontalDivider()
                    }

                    // Tabs
                    SecondaryTabRow(selectedTabIndex = state.selectedTab.ordinal) {
                        Tab(
                            selected = state.selectedTab == PalTab.TIMELINE,
                            onClick = { viewModel.selectTab(PalTab.TIMELINE) },
                            icon = { Icon(Icons.Filled.Flag, null, Modifier.size(18.dp)) },
                            text = { Text("Timeline") }
                        )
                        Tab(
                            selected = state.selectedTab == PalTab.MEMBERS,
                            onClick = { viewModel.selectTab(PalTab.MEMBERS) },
                            icon = { Icon(Icons.Filled.People, null, Modifier.size(18.dp)) },
                            text = { Text("Members") }
                        )
                        Tab(
                            selected = state.selectedTab == PalTab.SETTINGS,
                            onClick = { viewModel.selectTab(PalTab.SETTINGS) },
                            icon = { Icon(Icons.Filled.Settings, null, Modifier.size(18.dp)) },
                            text = { Text("Settings") }
                        )
                    }

                    // Tab content
                    when (state.selectedTab) {
                        PalTab.TIMELINE -> TimelineTab(state, viewModel)
                        PalTab.MEMBERS -> MembersTab(state, viewModel, isCreator)
                        PalTab.SETTINGS -> SettingsTab(state, viewModel, isCreator, onBack)
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

    // --- Dialogs ---

    if (state.showAddTypeDialog) {
        AlertDialog(
            onDismissRequest = viewModel::hideAddTypeDialog,
            title = { Text("What do you want to add?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = viewModel::showBlurbDialog,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Chat, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Update / Post")
                    }
                    OutlinedButton(
                        onClick = viewModel::showMilestoneDialog,
                        modifier = Modifier.fillMaxWidth()
                    ) {
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
        MilestoneCreateDialog(
            onDismiss = viewModel::hideMilestoneDialog,
            onConfirm = { emoji, title, desc, date -> viewModel.createMilestone(emoji, title, desc, date) }
        )
    }

    if (state.showAddMemberDialog) {
        var username by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = viewModel::hideAddMemberDialog,
            title = { Text("Add Member") },
            text = {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.addMember(username) }) { Text("Add") } },
            dismissButton = { TextButton(onClick = viewModel::hideAddMemberDialog) { Text("Cancel") } }
        )
    }

    if (state.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::hideDeleteConfirm,
            title = { Text("Delete Pal?") },
            text = { Text("This will permanently delete the group trip and all its content.") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deletePal(onBack) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = viewModel::hideDeleteConfirm) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TimelineTab(state: PalDetailUiState, viewModel: PalDetailViewModel) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (state.events.isEmpty()) {
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
                    MilestoneCard(event)
                } else {
                    BlurbCard(
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

@Composable
private fun MembersTab(state: PalDetailUiState, viewModel: PalDetailViewModel, isCreator: Boolean) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (state.members.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No members", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(state.members) { member ->
                MemberRow(member, isCreator, onRemove = { viewModel.removeMember(member.userId) })
            }
        }
    }
}

@Composable
private fun SettingsTab(
    state: PalDetailUiState,
    viewModel: PalDetailViewModel,
    isCreator: Boolean,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Details", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.editName,
                onValueChange = viewModel::onEditNameChange,
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = isCreator
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.editDescription,
                onValueChange = viewModel::onEditDescriptionChange,
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                enabled = isCreator
            )
            if (isCreator) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = viewModel::saveSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Save Changes")
                }
            }
        }

        item {
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Public", style = MaterialTheme.typography.bodyMedium)
                    Text("Anyone with the link can view",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = state.pal?.isPublic == true,
                    onCheckedChange = { viewModel.togglePublic() },
                    enabled = isCreator
                )
            }
        }

        if (isCreator) {
            item {
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = viewModel::showDeleteConfirm,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Delete Group Trip")
                }
            }
        }
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
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
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
                    Text(
                        event.createdAt?.take(10) ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                Text("📍 $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(8.dp))

            // Comment toggle button
            TextButton(
                onClick = onToggleComments,
                modifier = Modifier.padding(0.dp)
            ) {
                Icon(Icons.Filled.Chat, null, Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                val count = comments?.size ?: event.commentCount
                Text(
                    if (count > 0) "$count comment${if (count != 1) "s" else ""}" else "Comment",
                    style = MaterialTheme.typography.labelMedium
                )
            }

            // Expanded comments section
            AnimatedVisibility(visible = isExpanded) {
                Column {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    if (comments == null) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp).align(Alignment.CenterHorizontally))
                    } else {
                        comments.forEach { comment ->
                            CommentRow(comment, onDelete = { onDeleteComment(comment.id) })
                        }
                    }
                    // New comment input
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
                        IconButton(
                            onClick = {
                                if (commentText.isNotBlank()) {
                                    onPostComment(commentText)
                                    commentText = ""
                                }
                            }
                        ) {
                            Icon(Icons.Filled.CheckCircle, "Post comment",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentRow(comment: Comment, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Avatar initial circle
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
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
                Icon(Icons.Filled.Delete, "Delete comment",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MilestoneCard(event: TimelineEvent) {
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
private fun MemberRow(member: PalMember, isCreator: Boolean, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                member.username.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(member.username, style = MaterialTheme.typography.bodyMedium)
            Text(member.role, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isCreator && member.role != "creator") {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.PersonRemove, "Remove member",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
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
