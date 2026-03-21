package io.nurunuru.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.nurunuru.app.data.models.UserProfile
import io.nurunuru.app.ui.theme.*

/**
 * Modal for creating a multi-person MLS group chat.
 *
 * @param followingProfiles Candidates from the user's follow list.
 * @param isLoadingFollowing True while the follow list is being fetched.
 * @param onCreate Called with (groupName, selectedPubkeys) when confirmed.
 * @param onDismiss Called when the modal is dismissed.
 */
@Composable
fun CreateGroupModal(
    followingProfiles: List<UserProfile>,
    isLoadingFollowing: Boolean,
    onCreate: (name: String, memberPubkeys: List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var groupName by remember { mutableStateOf("") }
    var selectedPubkeys by remember { mutableStateOf(setOf<String>()) }
    var isCreating by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = BgPrimary,
            tonalElevation = 0.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "グループ作成",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "閉じる",
                        tint = TextTertiary,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { onDismiss() }
                    )
                }

                HorizontalDivider(color = BorderColor, thickness = 0.5.dp)

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Group name input
                    Text(
                        "グループ名",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary
                    )
                    BasicTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgPrimary)
                            .border(1.dp, LineGreen, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                        cursorBrush = SolidColor(LineGreen),
                        singleLine = true,
                        decorationBox = { inner ->
                            Box {
                                if (groupName.isEmpty()) {
                                    Text("グループ名を入力", color = TextTertiary, fontSize = 14.sp)
                                }
                                inner()
                            }
                        }
                    )

                    // Member count badge
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "メンバーを選択",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextSecondary
                        )
                        if (selectedPubkeys.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = LineGreen,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    "${selectedPubkeys.size}人",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    // Member picker
                    MemberPicker(
                        profiles = followingProfiles,
                        selectedPubkeys = selectedPubkeys,
                        onToggle = { pk ->
                            selectedPubkeys = if (pk in selectedPubkeys)
                                selectedPubkeys - pk
                            else
                                selectedPubkeys + pk
                        },
                        isLoading = isLoadingFollowing,
                        modifier = Modifier.weight(1f)
                    )
                }

                HorizontalDivider(color = BorderColor, thickness = 0.5.dp)

                // Footer buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BgTertiary,
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("キャンセル", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            if (!isCreating && groupName.isNotBlank() && selectedPubkeys.isNotEmpty()) {
                                isCreating = true
                                onCreate(groupName.trim(), selectedPubkeys.toList())
                            }
                        },
                        modifier = Modifier.weight(1f).height(44.dp),
                        enabled = groupName.isNotBlank() && selectedPubkeys.isNotEmpty() && !isCreating,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LineGreen,
                            contentColor = Color.White,
                            disabledContainerColor = LineGreen.copy(alpha = 0.5f),
                            disabledContentColor = Color.White.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        if (isCreating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("作成", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
