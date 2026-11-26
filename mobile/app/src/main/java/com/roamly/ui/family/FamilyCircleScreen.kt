package com.roamly.ui.family

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.roamly.data.api.FamilyMemberInfo
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.AtlasDialog
import com.roamly.ui.kit.AtlasField
import com.roamly.ui.kit.ButtonKind
import com.roamly.ui.kit.IconAction
import com.roamly.ui.kit.MEMBER_TRACK_COLORS
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.RowGroup
import com.roamly.ui.kit.SectionTitle
import com.roamly.ui.kit.Tag
import com.roamly.ui.kit.ToggleRow
import com.roamly.ui.kit.dockAwareBottom
import com.roamly.ui.kit.rememberHaptics
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.AtlasType
import com.roamly.ui.theme.SquircleShape

/** Members, the invite link, and this account's share / leave / manage actions.
 *  Any accepted member sees the roster and can invite or leave; the creator
 *  can also rename, delete and remove members. */
@Composable
fun FamilyCircleContent(viewModel: FamilyViewModel, state: FamilyUiState, onCreateAnother: () -> Unit) {
    val circle = state.selectedCircle ?: return
    val c = Atlas.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptics = rememberHaptics()
    val me = circle.members.firstOrNull { it.isYou }
    val isCreator = me?.role == "creator"

    var inviteUrl by remember(circle.id) { mutableStateOf<String?>(null) }
    var inviteError by remember(circle.id) { mutableStateOf<String?>(null) }
    var inviteBusy by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showLeave by remember { mutableStateOf(false) }
    var toRemove by remember { mutableStateOf<FamilyMemberInfo?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = dockAwareBottom()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            RowGroup {
                ToggleRow("Share my live location", me?.shareLocation ?: false, { viewModel.setShareLocation(it) },
                    subtitle = "Everyone else in this circle sees where you are while this is on", icon = Icons.Rounded.MyLocation, iconTint = c.success)
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                SectionTitle("Invite by link")
                Text("Anyone with this link can join and see everyone's live location.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                Spacer(Modifier.height(12.dp))
                val url = inviteUrl
                if (url == null) {
                    AtlasButton("Get invite link", {
                        inviteBusy = true
                        viewModel.invite(rotate = false) { u, err -> inviteBusy = false; inviteUrl = u; inviteError = err }
                    }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Link, loading = inviteBusy, kind = ButtonKind.Secondary)
                    inviteError?.let { Spacer(Modifier.height(6.dp)); Text(it, color = c.danger, style = MaterialTheme.typography.bodySmall) }
                } else {
                    Box(Modifier.fillMaxWidth().clip(SquircleShape(14.dp)).background(c.surfaceHigh).padding(12.dp)) {
                        Text(url, style = AtlasType.mono, color = c.accent)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AtlasButton("Copy", { clipboard.setText(AnnotatedString(url)); haptics.confirm() }, Modifier.weight(1f),
                            kind = ButtonKind.Secondary, compact = true, icon = Icons.Rounded.ContentCopy)
                        AtlasButton("Share", {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, url)
                            }
                            context.startActivity(android.content.Intent.createChooser(send, "Share invite"))
                        }, Modifier.weight(1f), compact = true, icon = Icons.Rounded.Share)
                    }
                    AtlasButton("Reset link", {
                        viewModel.invite(rotate = true) { u, err -> inviteUrl = u; inviteError = err }
                    }, kind = ButtonKind.Ghost, compact = true)
                }
            }
        }
        item { SectionTitle("Members · ${circle.members.size}", Modifier.padding(top = 6.dp)) }
        itemsIndexed(circle.members) { idx, member ->
            Panel(Modifier.fillMaxWidth(), padding = PaddingValues(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(SquircleShape(50)).background(Color(MEMBER_TRACK_COLORS[idx % MEMBER_TRACK_COLORS.size])), contentAlignment = Alignment.Center) {
                        Text(member.displayName.take(1).uppercase(), style = MaterialTheme.typography.titleSmall, color = Color.White)
                    }
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (member.isYou) "${member.displayName} (you)" else member.displayName, style = MaterialTheme.typography.titleSmall, color = c.text)
                        Text(
                            when { !member.accepted -> "Invite pending"; member.shareLocation -> "Sharing location"; else -> "Not sharing" },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (member.accepted && member.shareLocation) c.success else c.textMuted,
                        )
                    }
                    if (member.role == "creator") Tag("Creator", color = c.amber)
                    if (isCreator && !member.isYou) IconAction(Icons.Rounded.PersonRemove, "Remove", { toRemove = member }, container = null, tint = c.textMuted, size = 38.dp)
                }
            }
        }
        item {
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isCreator) {
                    AtlasButton("Rename circle", { showRename = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, icon = Icons.Rounded.Edit)
                    AtlasButton("Delete circle", { showDelete = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Danger, icon = Icons.Rounded.DeleteOutline)
                } else {
                    AtlasButton("Leave circle", { showLeave = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Danger, icon = Icons.Rounded.Logout)
                }
                AtlasButton("Create another circle", onCreateAnother, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost, compact = true)
            }
        }
    }

    if (showRename) {
        var name by remember { mutableStateOf(circle.name) }
        AtlasDialog(
            title = "Rename circle",
            onDismiss = { showRename = false },
            confirmText = "Save",
            onConfirm = { if (name.isNotBlank()) { viewModel.renameCircle(name.trim()) { _, _ -> }; showRename = false } },
            body = { AtlasField(name, { name = it }) },
        )
    }
    if (showDelete) {
        AtlasDialog(
            title = "Delete “${circle.name}”?",
            onDismiss = { showDelete = false },
            confirmText = "Delete",
            onConfirm = { viewModel.deleteCircle { _, _ -> }; showDelete = false },
            destructive = true, icon = Icons.Rounded.DeleteOutline,
            body = { Text("This removes the circle, its places and everyone's membership. It can't be undone.") },
        )
    }
    if (showLeave) {
        AtlasDialog(
            title = "Leave “${circle.name}”?",
            onDismiss = { showLeave = false },
            confirmText = "Leave",
            onConfirm = { viewModel.leaveCircle { _, _ -> }; showLeave = false },
            destructive = true, icon = Icons.Rounded.Logout,
            body = { Text("You'll stop sharing your location with this circle and lose access to its places.") },
        )
    }
    toRemove?.let { target ->
        AtlasDialog(
            title = "Remove ${target.displayName}?",
            onDismiss = { toRemove = null },
            confirmText = "Remove",
            onConfirm = { viewModel.removeMember(target.userId) { _, _ -> }; toRemove = null },
            destructive = true, icon = Icons.Rounded.PersonRemove,
            body = { Text("They are taken out of “${circle.name}”.") },
        )
    }
}
