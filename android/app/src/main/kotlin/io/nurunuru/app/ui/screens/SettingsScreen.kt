package io.nurunuru.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nurunuru.app.data.models.DEFAULT_RELAYS
import io.nurunuru.app.data.prefs.AppPreferences
import io.nurunuru.app.ui.components.UserAvatar
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.data.EngineManager
import io.nurunuru.app.ui.theme.LocalNuruColors
import io.nurunuru.app.viewmodel.AuthViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    authViewModel: AuthViewModel,
    prefs: AppPreferences,
    pubkeyHex: String,
    pictureUrl: String?
) {
    val nuruColors = LocalNuruColors.current
    var relays by remember { mutableStateOf(prefs.relays.toMutableList()) }
    var newRelayInput by remember { mutableStateOf("") }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showNpub by remember { mutableStateOf(false) }
    var npub by remember { mutableStateOf(pubkeyHex) }

    LaunchedEffect(pubkeyHex) {
        npub = EngineManager.pubkeyToNpub(pubkeyHex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "ミニアプリ & 設定",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // Profile section
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    UserAvatar(pictureUrl = pictureUrl, displayName = "", size = 48.dp)
                    Column {
                        Text(
                            text = if (showNpub) npub else npub.take(20) + "...",
                            style = MaterialTheme.typography.bodySmall,
                            color = nuruColors.textTertiary,
                            modifier = Modifier.clickable { showNpub = !showNpub }
                        )
                    }
                }
                HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
            }

            // Section: リレー管理
            item {
                SectionHeader(title = "リレー管理")
            }

            items(relays.toList()) { relay ->
                RelayItem(
                    relay = relay,
                    onDelete = {
                        relays = relays.toMutableList().also { it.remove(relay) }
                        prefs.relays = relays.toSet()
                    }
                )
            }

            item {
                // Add relay
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newRelayInput,
                        onValueChange = { newRelayInput = it },
                        placeholder = { Text("wss://relay.example.com") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LineGreen,
                            cursorColor = LineGreen
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                    IconButton(
                        onClick = {
                            val relay = newRelayInput.trim()
                            if (relay.startsWith("wss://") && !relays.contains(relay)) {
                                relays = relays.toMutableList().also { it.add(relay) }
                                prefs.relays = relays.toSet()
                                newRelayInput = ""
                            }
                        },
                        enabled = newRelayInput.startsWith("wss://")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "追加", tint = LineGreen)
                    }
                }

                // Reset to defaults
                TextButton(
                    onClick = {
                        relays = DEFAULT_RELAYS.toMutableList()
                        prefs.relays = DEFAULT_RELAYS.toSet()
                    },
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Text("デフォルトに戻す", color = nuruColors.textTertiary)
                }

                HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
            }

            // Section: その他
            item {
                SectionHeader(title = "その他")
            }

            item {
                SettingsRow(
                    icon = Icons.Outlined.Info,
                    title = "バージョン",
                    subtitle = "1.0.0 (Android)"
                ) {}
            }

            item {
                SettingsRow(
                    icon = Icons.Outlined.Logout,
                    title = "ログアウト",
                    titleColor = MaterialTheme.colorScheme.error,
                    showDivider = false
                ) { showLogoutDialog = true }
            }
        }
    }

    // Logout confirmation dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("ログアウト") },
            text = { Text("ログアウトします。秘密鍵はこのデバイスから削除されます。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        authViewModel.logout()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("ログアウト") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("キャンセル") }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    val nuruColors = LocalNuruColors.current
    Text(
        text = title,
        style = MaterialTheme.typography.bodySmall,
        color = nuruColors.textTertiary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
    )
}

@Composable
private fun RelayItem(relay: String, onDelete: () -> Unit) {
    val nuruColors = LocalNuruColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(LineGreen, shape = androidx.compose.foundation.shape.CircleShape)
            )
            Text(
                text = relay,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "削除",
                tint = nuruColors.textTertiary, modifier = Modifier.size(16.dp))
        }
    }
    HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onBackground,
    showDivider: Boolean = true,
    onClick: () -> Unit
) {
    val nuruColors = LocalNuruColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = nuruColors.textTertiary, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = titleColor)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = nuruColors.textTertiary)
            }
        }
    }
    if (showDivider) HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)
}
