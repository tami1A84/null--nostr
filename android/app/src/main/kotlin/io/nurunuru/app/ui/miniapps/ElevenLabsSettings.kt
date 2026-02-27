package io.nurunuru.app.ui.miniapps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nurunuru.app.data.prefs.AppPreferences
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors

@Composable
fun ElevenLabsSettings(prefs: AppPreferences) {
    val nuruColors = LocalNuruColors.current
    var apiKey by remember { mutableStateOf(prefs.elevenLabsApiKey) }
    var selectedLanguage by remember { mutableStateOf(prefs.elevenLabsLanguage) }
    var expanded by remember { mutableStateOf(false) }

    val languages = listOf(
        "jpn" to "日本語 (Japanese)",
        "eng" to "英語 (English)",
        "cmn" to "中国語 (Chinese)",
        "spa" to "スペイン語 (Spanish)",
        "fra" to "フランス語 (French)",
        "deu" to "ドイツ語 (German)",
        "ita" to "イタリア語 (Italian)",
        "por" to "ポルトガル語 (Portuguese)",
        "hin" to "ヒンディー語 (Hindi)"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            color = nuruColors.bgSecondary,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "ElevenLabs音声入力設定",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = nuruColors.textPrimary
                )

                // API Key Section
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "APIキー",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = nuruColors.textSecondary
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            prefs.elevenLabsApiKey = it
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("xi-api-key...", fontSize = 14.sp) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LineGreen,
                            unfocusedBorderColor = nuruColors.border
                        )
                    )
                    Text(
                        "ElevenLabsダッシュボードから取得してください",
                        fontSize = 12.sp,
                        color = nuruColors.textTertiary
                    )
                }

                // Language Section
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "言語",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = nuruColors.textSecondary
                    )

                    Box {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .clickable { expanded = true },
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, nuruColors.border),
                            color = Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = languages.find { it.first == selectedLanguage }?.second ?: "日本語 (Japanese)",
                                    fontSize = 14.sp,
                                    color = nuruColors.textPrimary
                                )
                                Icon(
                                    imageVector = Icons.Outlined.ArrowDropDown,
                                    contentDescription = null,
                                    tint = nuruColors.textSecondary
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .background(nuruColors.bgSecondary)
                        ) {
                            languages.forEach { (code, name) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            name,
                                            fontSize = 14.sp,
                                            color = if (selectedLanguage == code) LineGreen else nuruColors.textPrimary
                                        )
                                    },
                                    onClick = {
                                        selectedLanguage = code
                                        prefs.elevenLabsLanguage = code
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
