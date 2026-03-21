package io.nurunuru.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nurunuru.app.data.models.UserProfile
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors

/**
 * Multi-select member picker backed by the user's follow list.
 *
 * @param profiles       Candidates (from following list).
 * @param selectedPubkeys Currently selected pubkeys.
 * @param onToggle       Called when a candidate row is tapped.
 * @param isLoading      Show skeleton while loading.
 */
@Composable
fun MemberPicker(
    profiles: List<UserProfile>,
    selectedPubkeys: Set<String>,
    onToggle: (String) -> Unit,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    val nuruColors = LocalNuruColors.current
    var query by remember { mutableStateOf("") }

    val filtered = remember(profiles, query) {
        if (query.isBlank()) profiles
        else profiles.filter { p ->
            p.displayedName.contains(query, ignoreCase = true) ||
            p.pubkey.contains(query, ignoreCase = true)
        }
    }

    Column(modifier = modifier) {
        // Search bar
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .background(nuruColors.bgTertiary, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(LineGreen),
            singleLine = true,
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            "名前または公開鍵で検索",
                            style = MaterialTheme.typography.bodyMedium,
                            color = nuruColors.textTertiary
                        )
                    }
                    inner()
                }
            }
        )

        Spacer(Modifier.height(8.dp))

        when {
            isLoading -> Column { repeat(5) { ListItemSkeleton() } }
            filtered.isEmpty() -> Box(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (query.isBlank()) "フォロー中のユーザーがいません" else "該当なし",
                    style = MaterialTheme.typography.bodyMedium,
                    color = nuruColors.textTertiary
                )
            }
            else -> LazyColumn {
                items(filtered, key = { it.pubkey }) { profile ->
                    val selected = profile.pubkey in selectedPubkeys
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(profile.pubkey) }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        UserAvatar(
                            pictureUrl = profile.picture,
                            displayName = profile.displayedName,
                            size = 40.dp
                        )
                        Text(
                            profile.displayedName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(
                                    if (selected) LineGreen else nuruColors.bgTertiary,
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = androidx.compose.ui.graphics.Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp),
                        color = nuruColors.border,
                        thickness = 0.5.dp
                    )
                }
            }
        }
    }
}
