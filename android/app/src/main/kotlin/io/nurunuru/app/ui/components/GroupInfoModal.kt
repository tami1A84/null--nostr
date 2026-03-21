package io.nurunuru.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nurunuru.app.data.models.MlsGroup
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors

/**
 * Bottom sheet showing MLS group metadata and management actions.
 *
 * @param group        The active group.
 * @param myPubkeyHex  Logged-in user's pubkey (used to determine admin status).
 * @param onDismiss    Called when the sheet is closed.
 * @param onLeave      Called when the user confirms leaving the group.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoModal(
    group: MlsGroup,
    myPubkeyHex: String,
    onDismiss: () -> Unit,
    onLeave: () -> Unit
) {
    val nuruColors = LocalNuruColors.current
    var showLeaveConfirm by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                group.name.ifBlank { "グループ情報" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (group.description.isNotBlank()) {
                Text(
                    group.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = nuruColors.textSecondary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            Text(
                "メンバー (${group.memberPubkeys.size}人)",
                style = MaterialTheme.typography.labelMedium,
                color = nuruColors.textSecondary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(group.memberPubkeys, key = { it }) { pubkey ->
                    val profile = group.memberProfiles[pubkey]
                    val name = profile?.displayedName ?: pubkey.take(12) + "..."
                    val isAdmin = group.adminPubkeys.contains(pubkey)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        UserAvatar(
                            pictureUrl = profile?.picture,
                            displayName = name,
                            size = 36.dp
                        )
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                        if (isAdmin) {
                            Text(
                                "管理者",
                                style = MaterialTheme.typography.labelSmall,
                                color = LineGreen
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = { showLeaveConfirm = true },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("グループを退出")
            }
        }
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text("グループを退出") },
            text = { Text("このグループを退出しますか？過去のメッセージは読めなくなります。") },
            confirmButton = {
                TextButton(
                    onClick = { showLeaveConfirm = false; onLeave(); onDismiss() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("退出する") }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) { Text("キャンセル") }
            }
        )
    }
}
