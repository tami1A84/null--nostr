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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.ui.theme.*

@Composable
fun NewChatModal(
    onDismiss: () -> Unit,
    onStartChat: (String) -> Unit
) {
    var pubkeyInput by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = BgPrimary, // Match Web version's pure black background (#0A0A0A)
            tonalElevation = 0.dp // Avoid Material3 surface coloring
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "新しいトーク",
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

                // Body
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "相手の公開鍵",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary
                    )

                    BasicTextField(
                        value = pubkeyInput,
                        onValueChange = { pubkeyInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgPrimary)
                            .border(1.dp, LineGreen, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        textStyle = TextStyle(
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(LineGreen),
                        decorationBox = { innerTextField ->
                            Box {
                                if (pubkeyInput.isEmpty()) {
                                    Text(
                                        "npub1... または hex",
                                        color = TextTertiary,
                                        fontSize = 14.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }

                HorizontalDivider(color = BorderColor, thickness = 0.5.dp)

                // Footer
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Cancel Button
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BgTertiary,
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("キャンセル", fontWeight = FontWeight.Bold)
                    }

                    // Start Button
                    Button(
                        onClick = {
                            val pubkey = pubkeyInput.trim()
                            if (pubkey.isNotBlank()) {
                                val hex = NostrKeyUtils.parsePublicKey(pubkey)
                                onStartChat(hex ?: pubkey)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        enabled = pubkeyInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LineGreen,
                            contentColor = Color.White, // White text on green button
                            disabledContainerColor = LineGreen.copy(alpha = 0.5f),
                            disabledContentColor = Color.White.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("開始", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
