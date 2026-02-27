package io.nurunuru.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.models.UserProfile
import io.nurunuru.app.ui.icons.NuruIcons
import io.nurunuru.app.ui.theme.*

@Composable
fun ProfileHeader(
    profile: UserProfile?,
    isOwnProfile: Boolean,
    isFollowing: Boolean,
    isNip05Verified: Boolean,
    followCount: Int,
    badges: List<io.nurunuru.app.data.models.NostrEvent>,
    onEditClick: () -> Unit,
    onFollowClick: () -> Unit,
    onFollowListClick: () -> Unit,
    clipboardManager: ClipboardManager
) {
    val bgPrimary = Color.Black

    Box(modifier = Modifier.fillMaxWidth()) {
        // Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
                .background(LineGreen)
        ) {
            if (profile?.banner != null && profile.banner.isNotBlank()) {
                AsyncImage(
                    model = profile.banner,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // The Overlapping Surface
        Surface(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .padding(top = 64.dp),
            shape = RoundedCornerShape(16.dp),
            color = bgPrimary,
            shadowElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 0.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Spacer(modifier = Modifier.size(80.dp))
                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = profile?.displayedName ?: "Anonymous",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = TextPrimary,
                                maxLines = 1
                            )
                            if (isOwnProfile) {
                                Icon(
                                    NuruIcons.Edit,
                                    contentDescription = "編集",
                                    tint = TextTertiary,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { onEditClick() }
                                )
                            }
                        }

                        if (profile?.nip05 != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Icon(
                                    imageVector = if (isNip05Verified) Icons.Default.Check else Icons.Default.Close,
                                    contentDescription = null,
                                    tint = if (isNip05Verified) LineGreen else Color.Gray,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = formatNip05(profile.nip05),
                                    fontSize = 13.sp,
                                    color = if (isNip05Verified) LineGreen else TextTertiary
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clickable {
                                    profile?.pubkey?.let {
                                        clipboardManager.setText(AnnotatedString(NostrKeyUtils.encodeNpub(it) ?: it))
                                    }
                                }
                        ) {
                            Text(
                                text = NostrKeyUtils.shortenPubkey(profile?.pubkey ?: "", 12),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextTertiary,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                            Icon(Icons.Default.ContentCopy, null, tint = TextTertiary, modifier = Modifier.size(12.dp))
                        }
                    }

                    if (!isOwnProfile) {
                        Button(
                            onClick = onFollowClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isFollowing) Color.Transparent else LineGreen,
                                contentColor = if (isFollowing) TextPrimary else Color.White
                            ),
                            border = if (isFollowing) androidx.compose.foundation.BorderStroke(1.dp, BorderColor) else null,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.height(34.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Text(if (isFollowing) "解除" else "フォロー", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (!profile?.about.isNullOrBlank()) {
                    ProfileAbout(about = profile!!.about!!)
                }

                Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!profile?.lud16.isNullOrBlank()) {
                        MetaInfoItem(NuruIcons.Zap(false), profile!!.lud16!!)
                    }
                    if (!profile?.website.isNullOrBlank()) {
                        MetaInfoItem(NuruIcons.Website, profile!!.website!!, color = LineGreen)
                    }
                    if (!profile?.birthday.isNullOrBlank()) {
                        val isToday = remember(profile?.birthday) {
                            try {
                                val today = java.util.Calendar.getInstance()
                                val month = today.get(java.util.Calendar.MONTH) + 1
                                val day = today.get(java.util.Calendar.DAY_OF_MONTH)
                                val parts = profile!!.birthday!!.split("-")
                                if (parts.size == 3) {
                                    parts[1].toIntOrNull() == month && parts[2].toIntOrNull() == day
                                } else if (parts.size == 2) {
                                    parts[0].toIntOrNull() == month && parts[1].toIntOrNull() == day
                                } else false
                            } catch (e: Exception) { false }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetaInfoItem(NuruIcons.Cake, profile!!.birthday!!)
                            if (isToday) {
                                Surface(
                                    color = Color(0xFFFFEBEE),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.padding(start = 4.dp)
                                ) {
                                    Text(
                                        "今日は誕生日！",
                                        color = Color(0xFFE91E63),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (badges.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        badges.take(3).forEach { badge ->
                            val thumb = badge.getTagValue("thumb") ?: badge.getTagValue("image")
                            if (thumb != null) {
                                AsyncImage(
                                    model = thumb,
                                    contentDescription = badge.getTagValue("name"),
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .clickable { onFollowListClick() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.People, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                    Text(text = followCount.toString(), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                    Text(text = "フォロー中", fontSize = 14.sp, color = TextSecondary)
                }
            }
        }

        // Avatar
        Box(
            modifier = Modifier
                .padding(start = 32.dp)
                .offset(y = 24.dp)
                .size(80.dp)
                .clip(CircleShape)
                .background(bgPrimary)
                .padding(4.dp)
        ) {
            UserAvatar(
                pictureUrl = profile?.picture,
                displayName = profile?.displayedName ?: "",
                size = 72.dp
            )
        }
    }
}

@Composable
fun ProfileTabs(
    activeTab: Int,
    onTabSelected: (Int) -> Unit,
    postCount: Int,
    likeCount: Int
) {
    val bgPrimary = Color.Black
    Surface(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth(),
        color = bgPrimary
    ) {
        Column {
            TabRow(
                selectedTabIndex = activeTab,
                containerColor = bgPrimary,
                contentColor = LineGreen,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[activeTab]),
                        color = LineGreen,
                        height = 2.dp
                    )
                },
                divider = {},
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Tab(
                    selected = activeTab == 0,
                    onClick = { onTabSelected(0) },
                    text = {
                        Text(
                            "投稿 ($postCount)",
                            color = if (activeTab == 0) LineGreen else TextTertiary,
                            fontWeight = if (activeTab == 0) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 14.sp
                        )
                    }
                )
                Tab(
                    selected = activeTab == 1,
                    onClick = { onTabSelected(1) },
                    text = {
                        Text(
                            "いいね ($likeCount)",
                            color = if (activeTab == 1) LineGreen else TextTertiary,
                            fontWeight = if (activeTab == 1) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 14.sp
                        )
                    }
                )
            }
            HorizontalDivider(color = BorderColor, thickness = 0.5.dp)
        }
    }
}

@Composable
fun ProfileAbout(about: String) {
    val nuruColors = LocalNuruColors.current
    val annotated = remember(about) {
        buildAnnotatedString {
            val regex = Regex("(https?://[^\\s]+|nostr:(?:note1|nevent1|npub1|nprofile1|naddr1)[a-z0-9]{58,})")
            var lastIdx = 0
            regex.findAll(about).forEach { match ->
                append(about.substring(lastIdx, match.range.first))
                val value = match.value
                withStyle(SpanStyle(color = nuruColors.lineGreen)) {
                    append(if (value.length > 40) value.take(40) + "..." else value)
                }
                lastIdx = match.range.last + 1
            }
            append(about.substring(lastIdx))
        }
    }

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
        modifier = Modifier.padding(top = 4.dp),
        lineHeight = 20.sp
    )
}

@Composable
fun MetaInfoItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color = TextTertiary) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = TextTertiary, modifier = Modifier.size(16.dp))
        Text(text, fontSize = 14.sp, color = color, maxLines = 1)
    }
}

fun formatNip05(nip05: String): String {
    if (nip05.startsWith("_@")) return nip05.substring(2)
    return nip05
}

@Composable
fun ProfileSkeletonPostItem() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(BgTertiary))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(100.dp).height(12.dp).background(BgTertiary, RoundedCornerShape(4.dp)))
                Box(Modifier.fillMaxWidth().height(12.dp).background(BgTertiary, RoundedCornerShape(4.dp)))
                Box(Modifier.width(200.dp).height(12.dp).background(BgTertiary, RoundedCornerShape(4.dp)))
            }
        }
        HorizontalDivider(color = BorderColor, thickness = 0.5.dp)
    }
}
