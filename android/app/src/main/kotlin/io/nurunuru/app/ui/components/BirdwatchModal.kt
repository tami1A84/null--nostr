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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.nurunuru.app.data.models.NostrEvent
import io.nurunuru.app.ui.theme.LocalNuruColors

data class BirdwatchType(val value: String, val label: String, val icon: String)

private val BIRDWATCH_TYPES = listOf(
    BirdwatchType("misleading", "誤解を招く情報", "!"),
    BirdwatchType("missing_context", "背景情報が不足", "?"),
    BirdwatchType("factual_error", "事実誤認", "x"),
    BirdwatchType("outdated", "古い情報", "o"),
    BirdwatchType("satire", "風刺・ジョーク", "s")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirdwatchModal(
    onDismiss: () -> Unit,
    onSubmit: (String, String, String) -> Unit, // type, content, sourceUrl
    existingNotes: List<NostrEvent> = emptyList(),
    isSubmitting: Boolean = false
) {
    val nuruColors = LocalNuruColors.current
    var selectedType by remember { mutableStateOf<String?>(null) }
    var noteContent by remember { mutableStateOf("") }
    var sourceUrl by remember { mutableStateOf("") }
    var showExisting by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .heightIn(max = 650.dp),
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Info, null, tint = Color(0xFF2196F3), modifier = Modifier.size(24.dp))
                        Text(
                            text = "Birdwatch",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
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
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (existingNotes.isNotEmpty()) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showExisting = !showExisting }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (showExisting) Icons.Default.Warning else Icons.Default.Info,
                                    contentDescription = null,
                                    tint = nuruColors.textSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "既存のコンテキスト (${existingNotes.size}件)",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = nuruColors.textSecondary
                                )
                            }
                            if (showExisting) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(nuruColors.bgSecondary, RoundedCornerShape(8.dp))
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    existingNotes.take(3).forEach { note ->
                                        Text(text = note.content, style = MaterialTheme.typography.labelSmall, color = nuruColors.textSecondary, maxLines = 2)
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        text = "この投稿に追加のコンテキストを提供してください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = nuruColors.textSecondary
                    )

                    // Type selection
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("コンテキストの種類", style = MaterialTheme.typography.labelMedium, color = nuruColors.textSecondary)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BIRDWATCH_TYPES.take(2).forEach { type ->
                                BirdwatchTypeItem(type = type, isSelected = selectedType == type.value, onClick = { selectedType = type.value }, modifier = Modifier.weight(1f))
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BIRDWATCH_TYPES.drop(2).take(2).forEach { type ->
                                BirdwatchTypeItem(type = type, isSelected = selectedType == type.value, onClick = { selectedType = type.value }, modifier = Modifier.weight(1f))
                            }
                            BirdwatchTypeItem(type = BIRDWATCH_TYPES.last(), isSelected = selectedType == BIRDWATCH_TYPES.last().value, onClick = { selectedType = BIRDWATCH_TYPES.last().value }, modifier = Modifier.weight(1f))
                        }
                    }

                    // Content
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("コンテキストの内容 *", style = MaterialTheme.typography.labelMedium, color = nuruColors.textSecondary)
                        OutlinedTextField(
                            value = noteContent,
                            onValueChange = { if (it.length <= 1000) noteContent = it },
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            placeholder = { Text("この投稿に関する追加情報を入力してください...", fontSize = 14.sp) },
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                focusedBorderColor = Color(0xFF2196F3),
                                unfocusedBorderColor = nuruColors.border
                            )
                        )
                    }

                    // Source URL
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("ソースURL (任意)", style = MaterialTheme.typography.labelMedium, color = nuruColors.textSecondary)
                        OutlinedTextField(
                            value = sourceUrl,
                            onValueChange = { sourceUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("https://example.com/source", fontSize = 14.sp) },
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                focusedBorderColor = Color(0xFF2196F3),
                                unfocusedBorderColor = nuruColors.border
                            ),
                            singleLine = true
                        )
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
                        onClick = { selectedType?.let { onSubmit(it, noteContent, sourceUrl) } },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                        enabled = selectedType != null && noteContent.isNotBlank() && !isSubmitting
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(size = 20.dp, color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("追加する", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BirdwatchTypeItem(
    type: BirdwatchType,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nuruColors = LocalNuruColors.current
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) Color(0xFF2196F3) else nuruColors.border),
        color = if (isSelected) Color(0xFF2196F3).copy(alpha = 0.1f) else Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(if (isSelected) Color(0xFF2196F3) else nuruColors.bgTertiary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(text = type.icon, color = if (isSelected) Color.White else nuruColors.textTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text(text = type.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}
