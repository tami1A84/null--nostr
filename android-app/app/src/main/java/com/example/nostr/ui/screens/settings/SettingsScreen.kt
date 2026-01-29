package com.example.nostr.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showLoginDialog by remember { mutableStateOf(false) }
    var showAddRelayDialog by remember { mutableStateOf(false) }
    var showKeyExportDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Account section
            item {
                Text(
                    text = "Account",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                if (uiState.isLoggedIn) {
                    // Logged in state
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Logged in",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    uiState.npub?.let { npub ->
                                        Text(
                                            text = "${npub.take(16)}...${npub.takeLast(8)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            HorizontalDivider()

                            // Export keys button
                            if (uiState.nsec != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showKeyExportDialog = true }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Key,
                                        contentDescription = null
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("Export Keys")
                                }
                            }

                            // Logout button
                            Button(
                                onClick = { viewModel.logout() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Logout, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Logout")
                            }
                        }
                    }
                } else {
                    // Not logged in
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Not logged in",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Login to post, like, and send messages",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = { showLoginDialog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Login, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Login")
                            }
                        }
                    }
                }
            }

            // Relays section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Relays",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(onClick = { showAddRelayDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Relay")
                    }
                }
            }

            if (uiState.relays.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "No relays configured. Default relays will be used.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(
                    items = uiState.relays,
                    key = { it.url }
                ) { relay ->
                    RelayItem(
                        relay = relay,
                        onToggle = { enabled -> viewModel.toggleRelay(relay.url, enabled) },
                        onRemove = { viewModel.removeRelay(relay.url) }
                    )
                }
            }

            // App info section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "About",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Nostr Client",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Version 1.0.0",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "A native Android Nostr client built with Kotlin and Jetpack Compose.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // Login Dialog
    if (showLoginDialog) {
        LoginDialog(
            onDismiss = { showLoginDialog = false },
            onLoginWithNsec = { nsec ->
                viewModel.loginWithNsec(nsec)
                showLoginDialog = false
            },
            onLoginWithNpub = { npub ->
                viewModel.loginWithNpub(npub)
                showLoginDialog = false
            },
            onGenerateKey = {
                viewModel.generateNewKey()
                showLoginDialog = false
            },
            isLoading = uiState.isLoading,
            error = uiState.error
        )
    }

    // Add Relay Dialog
    if (showAddRelayDialog) {
        AddRelayDialog(
            onDismiss = { showAddRelayDialog = false },
            onAdd = { url ->
                viewModel.addRelay(url)
                showAddRelayDialog = false
            }
        )
    }

    // Key Export Dialog
    if (showKeyExportDialog) {
        KeyExportDialog(
            npub = uiState.npub,
            nsec = uiState.nsec,
            onDismiss = { showKeyExportDialog = false }
        )
    }
}

@Composable
fun RelayItem(
    relay: com.example.nostr.data.database.entity.RelayEntity,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Cloud,
                contentDescription = null,
                tint = if (relay.isEnabled)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = relay.url.removePrefix("wss://").removePrefix("ws://"),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row {
                    if (relay.isRead) {
                        Text(
                            text = "Read",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (relay.isRead && relay.isWrite) {
                        Text(" • ", style = MaterialTheme.typography.labelSmall)
                    }
                    if (relay.isWrite) {
                        Text(
                            text = "Write",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Switch(
                checked = relay.isEnabled,
                onCheckedChange = onToggle
            )
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun LoginDialog(
    onDismiss: () -> Unit,
    onLoginWithNsec: (String) -> Unit,
    onLoginWithNpub: (String) -> Unit,
    onGenerateKey: () -> Unit,
    isLoading: Boolean,
    error: String?
) {
    var selectedTab by remember { mutableStateOf(0) }
    var nsecInput by remember { mutableStateOf("") }
    var npubInput by remember { mutableStateOf("") }
    var showNsec by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Login") },
        text = {
            Column {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("nsec") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("npub") }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("New") }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (selectedTab) {
                    0 -> {
                        OutlinedTextField(
                            value = nsecInput,
                            onValueChange = { nsecInput = it },
                            label = { Text("nsec (Private Key)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = if (showNsec)
                                VisualTransformation.None
                            else
                                PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showNsec = !showNsec }) {
                                    Icon(
                                        if (showNsec) Icons.Default.VisibilityOff
                                        else Icons.Default.Visibility,
                                        contentDescription = "Toggle visibility"
                                    )
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Your private key. Keep this secret!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    1 -> {
                        OutlinedTextField(
                            value = npubInput,
                            onValueChange = { npubInput = it },
                            label = { Text("npub (Public Key)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Read-only mode. You can browse but not post.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    2 -> {
                        Text(
                            text = "Generate a new Nostr identity. Make sure to backup your keys!",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (isLoading) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (selectedTab) {
                        0 -> if (nsecInput.isNotBlank()) onLoginWithNsec(nsecInput)
                        1 -> if (npubInput.isNotBlank()) onLoginWithNpub(npubInput)
                        2 -> onGenerateKey()
                    }
                },
                enabled = !isLoading && when (selectedTab) {
                    0 -> nsecInput.isNotBlank()
                    1 -> npubInput.isNotBlank()
                    else -> true
                }
            ) {
                Text(if (selectedTab == 2) "Generate" else "Login")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun AddRelayDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit
) {
    var url by remember { mutableStateOf("wss://") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Relay") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Relay URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
        },
        confirmButton = {
            Button(
                onClick = { onAdd(url) },
                enabled = url.startsWith("wss://") || url.startsWith("ws://")
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun KeyExportDialog(
    npub: String?,
    nsec: String?,
    onDismiss: () -> Unit
) {
    var showNsec by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Keys") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // npub
                if (npub != null) {
                    Column {
                        Text(
                            text = "Public Key (npub)",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Text(
                                text = npub,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // nsec
                if (nsec != null) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Private Key (nsec)",
                                style = MaterialTheme.typography.labelMedium
                            )
                            IconButton(onClick = { showNsec = !showNsec }) {
                                Icon(
                                    if (showNsec) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    contentDescription = "Toggle visibility"
                                )
                            }
                        }
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                text = if (showNsec) nsec else "••••••••••••••••",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Text(
                            text = "Never share your private key!",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}
