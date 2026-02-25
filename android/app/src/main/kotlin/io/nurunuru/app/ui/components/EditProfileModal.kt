package io.nurunuru.app.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nurunuru.app.data.models.UserProfile
import io.nurunuru.app.ui.theme.*
import io.nurunuru.app.viewmodel.HomeViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileModal(
    profile: UserProfile,
    onDismiss: () -> Unit,
    onSave: (UserProfile) -> Unit,
    viewModel: HomeViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    var name by remember { mutableStateOf(profile.name ?: "") }
    var about by remember { mutableStateOf(profile.about ?: "") }
    var picture by remember { mutableStateOf(profile.picture ?: "") }
    var banner by remember { mutableStateOf(profile.banner ?: "") }
    var nip05 by remember { mutableStateOf(profile.nip05 ?: "") }
    var lud16 by remember { mutableStateOf(profile.lud16 ?: "") }
    var website by remember { mutableStateOf(profile.website ?: "") }
    var birthday by remember { mutableStateOf(profile.birthday ?: "") }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isUploadingPicture by remember { mutableStateOf(false) }
    var isUploadingBanner by remember { mutableStateOf(false) }
    val isUploading = isUploadingPicture || isUploadingBanner

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch {
                isUploadingPicture = true
                val bytes = context.contentResolver.openInputStream(it)?.readBytes()
                val url = viewModel.uploadImage(bytes ?: byteArrayOf(), context.contentResolver.getType(it) ?: "image/jpeg")
                if (url != null) picture = url
                isUploadingPicture = false
            }
        }
    }

    val bannerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch {
                isUploadingBanner = true
                val bytes = context.contentResolver.openInputStream(it)?.readBytes()
                val url = viewModel.uploadImage(bytes ?: byteArrayOf(), context.contentResolver.getType(it) ?: "image/jpeg")
                if (url != null) banner = url
                isUploadingBanner = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Black,
        dragHandle = null
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("キャンセル", color = TextSecondary) }
                Text("プロフィール編集", fontWeight = FontWeight.Bold, color = TextPrimary)
                TextButton(onClick = { onSave(profile.copy(name=name, about=about, picture=picture, banner=banner, nip05=nip05, lud16=lud16, website=website, birthday=birthday)) }, enabled = !isUploading) {
                    if (isUploading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LineGreen)
                    else Text("保存", color = LineGreen, fontWeight = FontWeight.Bold)
                }
            }
            HorizontalDivider(color = BorderColor, thickness = 0.5.dp)

            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ProfileEditField("名前", name, { name = it }, "表示名")

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("アップロード先", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("nostr.build", "https://blossom.nostr.build").forEach { server ->
                            FilterChip(
                                selected = uiState.uploadServer == server,
                                onClick = { viewModel.setUploadServer(server) },
                                label = { Text(if (server == "nostr.build") "nostr.build" else "Blossom", fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LineGreen,
                                    selectedLabelColor = Color.White,
                                    containerColor = BgSecondary,
                                    labelColor = TextSecondary
                                ),
                                border = null,
                                shape = RoundedCornerShape(16.dp)
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("アイコン画像", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { NuruTextField(picture, { picture = it }, "https://...", singleLine = true) }
                        IconButton(
                            onClick = { imageLauncher.launch("image/*") },
                            enabled = !isUploading,
                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(BgTertiary)
                        ) {
                            if (isUploadingPicture) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LineGreen)
                            else Icon(Icons.Default.FileUpload, null, tint = TextPrimary)
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("バナー画像", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { NuruTextField(banner, { banner = it }, "https://...", singleLine = true) }
                        IconButton(
                            onClick = { bannerLauncher.launch("image/*") },
                            enabled = !isUploading,
                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(BgTertiary)
                        ) {
                            if (isUploadingBanner) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = LineGreen)
                            else Icon(Icons.Default.FileUpload, null, tint = TextPrimary)
                        }
                    }
                }

                ProfileEditField("自己紹介", about, { about = it }, "自己紹介", minLines = 3)
                ProfileEditField("NIP-05", nip05, { nip05 = it }, "name@example.com")
                ProfileEditField("ライトニングアドレス", lud16, { lud16 = it }, "you@wallet.com")
                ProfileEditField("ウェブサイト", website, { website = it }, "https://example.com")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProfileEditField("誕生日", birthday, { birthday = it }, "MM-DD または YYYY-MM-DD")
                    Text("例: 01-15 または 2000-01-15", fontSize = 12.sp, color = TextTertiary)
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
fun ProfileEditField(label: String, value: String, onValueChange: (String) -> Unit, placeholder: String, minLines: Int = 1) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        NuruTextField(value, onValueChange, placeholder, minLines)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NuruTextField(value: String, onValueChange: (String) -> Unit, placeholder: String, minLines: Int = 1, singleLine: Boolean = false) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = TextTertiary, fontSize = 14.sp) },
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(0.5.dp, BorderColor, RoundedCornerShape(8.dp)),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = BgTertiary,
            unfocusedContainerColor = BgSecondary,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            cursorColor = LineGreen,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary
        ),
        textStyle = TextStyle(fontSize = 14.sp),
        minLines = minLines,
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else Int.MAX_VALUE
    )
}
