package io.nurunuru.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.nurunuru.app.data.NostrRepository
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.ui.theme.LocalNuruColors
import kotlinx.coroutines.launch

@Composable
private fun PresetButton(
    preset: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nuruColors = LocalNuruColors.current
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) Color(0xFFFFC107) else nuruColors.bgSecondary,
        contentColor = if (isSelected) Color.Black else nuruColors.textPrimary
    ) {
        Text(
            text = "⚡$preset",
            modifier = Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZapModal(
    post: ScoredPost,
    repository: NostrRepository,
    onDismiss: () -> Unit,
    onSuccess: (String) -> Unit
) {
    val nuruColors = LocalNuruColors.current
    val profile = post.profile
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    var amount by remember { mutableStateOf("21") }
    var comment by remember { mutableStateOf("") }
    var isZapping by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val presetAmounts = listOf(21, 100, 500, 1000, 5000)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = nuruColors.bgPrimary
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚡ Zap送信",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "閉じる", tint = nuruColors.textTertiary)
                    }
                }

                Text(
                    text = profile?.displayedName ?: "Anonymous",
                    style = MaterialTheme.typography.bodyMedium,
                    color = nuruColors.textSecondary
                )

                // Amount
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "金額 (sats)",
                        style = MaterialTheme.typography.labelMedium,
                        color = nuruColors.textSecondary
                    )
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { if (it.all { c -> c.isDigit() }) amount = it },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = Color(0xFFFFC107),
                            unfocusedBorderColor = nuruColors.border
                        ),
                        singleLine = true
                    )
                }

                // Presets
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presetAmounts.take(3).forEach { preset ->
                            PresetButton(
                                preset = preset,
                                isSelected = amount == preset.toString(),
                                onClick = { amount = preset.toString() },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presetAmounts.drop(3).forEach { preset ->
                            PresetButton(
                                preset = preset,
                                isSelected = amount == preset.toString(),
                                onClick = { amount = preset.toString() },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // Spacer to keep buttons same width if only 2 in row
                        if (presetAmounts.drop(3).size == 2) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                // Comment
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "コメント (任意)",
                        style = MaterialTheme.typography.labelMedium,
                        color = nuruColors.textSecondary
                    )
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { if (it.length <= 100) comment = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Zap!", fontSize = 14.sp) },
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = Color(0xFFFFC107),
                            unfocusedBorderColor = nuruColors.border
                        ),
                        maxLines = 2
                    )
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Action
                Button(
                    onClick = {
                        val amountSats = amount.toLongOrNull() ?: 0L
                        if (amountSats <= 0) {
                            errorMessage = "有効な金額を入力してください"
                            return@Button
                        }
                        if (profile?.lud16 == null) {
                            errorMessage = "Lightningアドレスが設定されていません"
                            return@Button
                        }

                        isZapping = true
                        errorMessage = null
                        scope.launch {
                            try {
                                val invoice = repository.fetchLightningInvoice(profile.lud16, amountSats, comment)
                                if (invoice != null) {
                                    clipboardManager.setText(AnnotatedString(invoice))
                                    onSuccess(invoice)
                                } else {
                                    errorMessage = "インボイスの作成に失敗しました"
                                }
                            } catch (e: Exception) {
                                errorMessage = "エラーが発生しました: ${e.message}"
                            } finally {
                                isZapping = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC107), contentColor = Color.Black),
                    shape = RoundedCornerShape(26.dp),
                    enabled = !isZapping && amount.isNotBlank()
                ) {
                    if (isZapping) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.Black, strokeWidth = 2.dp)
                    } else {
                        Text("⚡ ${amount.ifBlank { "0" }} sats のインボイスを作成", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
