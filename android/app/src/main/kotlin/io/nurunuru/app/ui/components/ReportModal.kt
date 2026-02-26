package io.nurunuru.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.nurunuru.app.ui.theme.LocalNuruColors
import kotlinx.coroutines.launch

data class ReportType(val value: String, val label: String, val description: String)

private val REPORT_TYPES = listOf(
    ReportType("spam", "スパム", "宣伝目的の迷惑投稿"),
    ReportType("nudity", "ヌード・性的コンテンツ", "露骨な性的コンテンツ"),
    ReportType("profanity", "ヘイトスピーチ", "差別的・攻撃的な表現"),
    ReportType("illegal", "違法コンテンツ", "法律に違反する可能性のある内容"),
    ReportType("impersonation", "なりすまし", "他人になりすましている"),
    ReportType("malware", "マルウェア", "悪意のあるリンクやファイル"),
    ReportType("other", "その他", "上記以外の問題")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportModal(
    onDismiss: () -> Unit,
    onReport: (String, String) -> Unit, // type, content
    isSubmitting: Boolean = false
) {
    val nuruColors = LocalNuruColors.current
    var selectedType by remember { mutableStateOf<String?>(null) }
    var additionalInfo by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .heightIn(max = 600.dp),
            shape = RoundedCornerShape(24.dp),
            color = nuruColors.bgPrimary
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "投稿を通報",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "閉じる", tint = nuruColors.textTertiary)
                    }
                }

                HorizontalDivider(color = nuruColors.border, thickness = 0.5.dp)

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 24.dp)
                        .padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "この投稿を通報する理由を選択してください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = nuruColors.textSecondary
                    )

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(REPORT_TYPES) { type ->
                            val isSelected = selectedType == type.value
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedType = type.value },
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) Color.Red else nuruColors.border
                                ),
                                color = if (isSelected) Color.Red.copy(alpha = 0.05f) else Color.Transparent
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .border(2.dp, if (isSelected) Color.Red else nuruColors.textTertiary, CircleShape)
                                            .padding(4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Box(Modifier.fillMaxSize().background(Color.Red, CircleShape))
                                        }
                                    }
                                    Column {
                                        Text(text = type.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        Text(text = type.description, style = MaterialTheme.typography.labelSmall, color = nuruColors.textTertiary)
                                    }
                                }
                            }
                        }

                        if (selectedType != null) {
                            item {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "追加情報 (任意)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = nuruColors.textSecondary
                                )
                                OutlinedTextField(
                                    value = additionalInfo,
                                    onValueChange = { if (it.length <= 500) additionalInfo = it },
                                    modifier = Modifier.fillMaxWidth().height(100.dp),
                                    placeholder = { Text("通報の詳細を入力...", fontSize = 14.sp) },
                                    colors = TextFieldDefaults.outlinedTextFieldColors(
                                        focusedBorderColor = Color.Red,
                                        unfocusedBorderColor = nuruColors.border
                                    )
                                )
                                Text(
                                    text = "${additionalInfo.length}/500",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = nuruColors.textTertiary,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                                )
                            }
                        }
                    }
                }

                // Footer
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, nuruColors.border)
                    ) {
                        Text("キャンセル", color = nuruColors.textPrimary)
                    }
                    Button(
                        onClick = { selectedType?.let { onReport(it, additionalInfo) } },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        enabled = selectedType != null && !isSubmitting
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(size = 20.dp, color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("通報する", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
