package com.roamly.ui.family

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.roamly.data.api.FamilyMemberInfo
import com.roamly.ui.theme.Clay
import com.roamly.ui.theme.ClayButton
import com.roamly.ui.theme.ClayCard
import com.roamly.ui.theme.ClayOutlinedButton
import com.roamly.ui.theme.ClayTextButton

/** Members, invite link, and this account's own share/leave/manage actions.
 *  Any accepted member sees the roster and can invite/leave; role == "creator"
 *  additionally gets rename/delete/remove-member. */
@Composable
fun FamilyCircleContent(viewModel: FamilyViewModel, state: FamilyUiState, onCreateAnother: () -> Unit) {
    val circle = state.selectedCircle ?: return
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val me = circle.members.firstOrNull { it.isYou }
    val isCreator = me?.role == "creator"

    var inviteUrl by remember(circle.id) { mutableStateOf<String?>(null) }
    var inviteError by remember(circle.id) { mutableStateOf<String?>(null) }
    var showRename by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var memberToRemove by remember { mutableStateOf<FamilyMemberInfo?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ClayCard {
                Text("You", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Share my live location", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Everyone else in this circle can see where you are while this is on.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = me?.shareLocation ?: false,
                        onCheckedChange = { viewModel.setShareLocation(it) },
                    )
                }
            }
        }

        item {
            ClayCard {
                Text("Invite by link", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Anyone with this link can join and see everyone's live location.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                if (inviteUrl == null) {
                    ClayButton(
                        onClick = {
                            viewModel.invite(rotate = false) { url, err -> inviteUrl = url; inviteError = err }
                        },
                        gradient = Clay.colors.secondaryGradient,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Get invite link") }
                    inviteError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(
                        inviteUrl!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ClayOutlinedButton(
                            onClick = { clipboard.setText(AnnotatedString(inviteUrl!!)) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Copy") }
                        ClayOutlinedButton(
                            onClick = {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, inviteUrl)
                                }
                                context.startActivity(android.content.Intent.createChooser(send, "Share invite"))
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Share") }
                    }
                    Spacer(Modifier.height(6.dp))
                    ClayTextButton(onClick = {
                        viewModel.invite(rotate = true) { url, err -> inviteUrl = url; inviteError = err }
                    }) { Text("Reset link") }
                }
            }
        }

        item {
            Text("Members", style = MaterialTheme.typography.titleSmall)
        }
        items(circle.members) { member ->
            ClayCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (member.isYou) "${member.displayName} (you)" else member.displayName)
                        Text(
                            when {
                                !member.accepted -> "Invite pending"
                                member.shareLocation -> "Sharing location"
                                else -> "Not sharing location"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (member.role == "creator") {
                        AssistChip(onClick = {}, enabled = false, label = { Text("Creator") })
                    }
                    if (isCreator && !member.isYou) {
                        IconButton(onClick = { memberToRemove = member }) {
                            Icon(Icons.Rounded.PersonRemove, "Remove")
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            if (isCreator) {
                ClayOutlinedButton(onClick = { showRename = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Rename circle")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Delete circle") }
            } else {
                OutlinedButton(
                    onClick = { showLeaveConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Leave circle") }
            }
            Spacer(Modifier.height(8.dp))
            ClayTextButton(onClick = onCreateAnother) { Text("Create another circle") }
        }
    }

    if (showRename) {
        var name by remember { mutableStateOf(circle.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename circle") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameCircle(name.trim()) { _, _ -> }
                    showRename = false
                }, enabled = name.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete \"${circle.name}\"?") },
            text = { Text("This removes the circle, its places and everyone's membership. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteCircle { _, _ -> }; showDeleteConfirm = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text("Leave \"${circle.name}\"?") },
            text = { Text("You'll stop sharing your location with this circle and lose access to its places.") },
            confirmButton = {
                TextButton(onClick = { viewModel.leaveCircle { _, _ -> }; showLeaveConfirm = false }) {
                    Text("Leave", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showLeaveConfirm = false }) { Text("Cancel") } },
        )
    }

    memberToRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { memberToRemove = null },
            title = { Text("Remove ${target.displayName}?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeMember(target.userId) { _, _ -> }
                    memberToRemove = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { memberToRemove = null }) { Text("Cancel") } },
        )
    }
}
