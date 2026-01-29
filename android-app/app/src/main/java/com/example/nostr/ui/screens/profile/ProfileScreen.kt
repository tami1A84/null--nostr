package com.example.nostr.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.example.nostr.ui.theme.Nip05Verified

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showEditDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadFollowList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                actions = {
                    if (uiState.isLoggedIn && uiState.isOwnProfile) {
                        IconButton(onClick = { showEditDialog = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Profile")
                        }
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (!uiState.isLoggedIn) {
            // Show login prompt
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Not logged in",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Go to Settings to login",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Profile header
                item {
                    ProfileHeader(
                        profile = uiState.profile,
                        npub = uiState.npub,
                        followingCount = uiState.followingCount,
                        isLoading = uiState.isLoading
                    )
                }

                // Posts section header
                item {
                    Text(
                        text = "Posts",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                // User's posts
                if (uiState.posts.isEmpty() && !uiState.isLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No posts yet",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(
                        items = uiState.posts,
                        key = { it.id }
                    ) { event ->
                        ProfilePostItem(event = event)
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    // Edit profile dialog
    if (showEditDialog) {
        EditProfileDialog(
            currentProfile = uiState.profile,
            onDismiss = { showEditDialog = false },
            onSave = { name, about, picture, nip05, lud16 ->
                viewModel.updateProfile(name, about, picture, nip05, lud16)
                showEditDialog = false
            }
        )
    }
}

@Composable
fun ProfileHeader(
    profile: com.example.nostr.data.database.entity.ProfileEntity?,
    npub: String?,
    followingCount: Int,
    isLoading: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(MaterialTheme.colorScheme.primaryContainer)
        ) {
            if (profile?.banner != null) {
                AsyncImage(
                    model = profile.banner,
                    contentDescription = "Banner",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        // Avatar and info
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // Avatar (overlapping banner)
            Box(
                modifier = Modifier
                    .offset(y = (-40).dp)
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                AsyncImage(
                    model = profile?.picture,
                    contentDescription = "Avatar",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            }

            // Name
            Text(
                text = profile?.getDisplayNameOrName() ?: "Anonymous",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.offset(y = (-32).dp)
            )

            // NIP-05
            if (profile?.nip05 != null) {
                Row(
                    modifier = Modifier.offset(y = (-28).dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Verified,
                        contentDescription = "Verified",
                        modifier = Modifier.size(16.dp),
                        tint = Nip05Verified
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = profile.nip05,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Nip05Verified
                    )
                }
            }

            // npub
            if (npub != null) {
                Row(
                    modifier = Modifier.offset(y = (-24).dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${npub.take(16)}...${npub.takeLast(8)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(
                        onClick = { /* TODO: Copy to clipboard */ },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // About
            if (profile?.about != null) {
                Text(
                    text = profile.about,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.offset(y = (-16).dp)
                )
            }

            // Stats
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column {
                    Text(
                        text = followingCount.toString(),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Following",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Loading indicator
            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun ProfilePostItem(
    event: com.example.nostr.nostr.event.NostrEvent
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = event.content,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = formatTimestamp(event.createdAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun EditProfileDialog(
    currentProfile: com.example.nostr.data.database.entity.ProfileEntity?,
    onDismiss: () -> Unit,
    onSave: (name: String?, about: String?, picture: String?, nip05: String?, lud16: String?) -> Unit
) {
    var name by remember { mutableStateOf(currentProfile?.name ?: "") }
    var about by remember { mutableStateOf(currentProfile?.about ?: "") }
    var picture by remember { mutableStateOf(currentProfile?.picture ?: "") }
    var nip05 by remember { mutableStateOf(currentProfile?.nip05 ?: "") }
    var lud16 by remember { mutableStateOf(currentProfile?.lud16 ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Profile") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = about,
                    onValueChange = { about = it },
                    label = { Text("About") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = picture,
                    onValueChange = { picture = it },
                    label = { Text("Picture URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = nip05,
                    onValueChange = { nip05 = it },
                    label = { Text("NIP-05") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = lud16,
                    onValueChange = { lud16 = it },
                    label = { Text("Lightning Address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        name.takeIf { it.isNotBlank() },
                        about.takeIf { it.isNotBlank() },
                        picture.takeIf { it.isNotBlank() },
                        nip05.takeIf { it.isNotBlank() },
                        lud16.takeIf { it.isNotBlank() }
                    )
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatTimestamp(timestamp: Long): String {
    val date = java.util.Date(timestamp * 1000)
    return java.text.SimpleDateFormat("MMM d, yyyy 'at' HH:mm", java.util.Locale.getDefault()).format(date)
}
