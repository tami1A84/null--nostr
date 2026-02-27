package io.nurunuru.app.ui.miniapps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nurunuru.app.data.prefs.AppPreferences
import io.nurunuru.app.ui.icons.NuruIcons
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZapSettings(prefs: AppPreferences) {
    val nuruColors = LocalNuruColors.current
    var defaultZap by remember { mutableStateOf(prefs.defaultZapAmount) }
    var showZapInput by remember { mutableStateOf(false) }
    var customZap by remember { mutableStateOf("") }
    val presets = listOf(21, 100, 500, 1000, 5000, 10000)

    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            color = nuruColors.bgSecondary,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("デフォルトZap金額", fontWeight = FontWeight.Bold)

                // Grid of presets
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val rows = presets.chunked(3)
                    rows.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { amount ->
                                val isSelected = defaultZap == amount
                                Surface(
                                    modifier = Modifier.weight(1f).height(48.dp).clickable {
                                        defaultZap = amount
                                        prefs.defaultZapAmount = amount
                                        showZapInput = false
                                    },
                                    color = if (isSelected) LineGreen else nuruColors.bgTertiary,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            amount.toString(),
                                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (showZapInput) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = customZap,
                            onValueChange = { if (it.all { c -> c.isDigit() }) customZap = it },
                            placeholder = { Text("金額", fontSize = 14.sp) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = LineGreen,
                                unfocusedBorderColor = nuruColors.border
                            )
                        )
                        Button(
                            onClick = {
                                customZap.toIntOrNull()?.let {
                                    if (it > 0) {
                                        defaultZap = it
                                        prefs.defaultZapAmount = it
                                        showZapInput = false
                                        customZap = ""
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = LineGreen),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("設定")
                        }
                        IconButton(
                            onClick = {
                                showZapInput = false
                                customZap = ""
                            }
                        ) {
                            Icon(
                                imageVector = NuruIcons.Close,
                                contentDescription = "キャンセル",
                                tint = nuruColors.textTertiary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                } else {
                    TextButton(onClick = { showZapInput = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("カスタム金額を設定", color = LineGreen)
                    }
                }
            }
        }
        Text("現在の設定: $defaultZap sats", fontSize = 12.sp, color = nuruColors.textTertiary, modifier = Modifier.padding(horizontal = 8.dp))
    }
}
