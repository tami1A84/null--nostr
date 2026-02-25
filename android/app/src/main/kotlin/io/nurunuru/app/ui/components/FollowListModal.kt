package io.nurunuru.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.models.UserProfile
import io.nurunuru.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowListModal(
    pubkeys: List<String>,
    profiles: Map<String, UserProfile>,
    onDismiss: () -> Unit,
    onUnfollow: (String) -> Unit,
    onProfileClick: (String) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.Black) {
        Column(Modifier.fillMaxWidth().heightIn(min = 400.dp)) {
            Text(
                "フォロー中 (${pubkeys.size})",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.padding(16.dp)
            )
            HorizontalDivider(color = BorderColor, thickness = 0.5.dp)
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(pubkeys) { pk ->
                    val p = profiles[pk]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onProfileClick(pk) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UserAvatar(p?.picture, p?.displayedName ?: "", 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                p?.displayedName ?: NostrKeyUtils.shortenPubkey(pk),
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            if (p?.nip05 != null) {
                                Text(
                                    p.nip05,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LineGreen
                                )
                            }
                        }
                        Button(
                            onClick = { onUnfollow(pk) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = Color.Red
                            ),
                            border = BorderStroke(1.dp, Color.Red),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("解除", fontSize = 12.sp)
                        }
                    }
                    HorizontalDivider(color = BorderColor, thickness = 0.5.dp)
                }
            }
        }
    }
}
