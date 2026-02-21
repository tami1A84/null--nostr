package io.nurunuru.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nurunuru.app.data.NostrKeyUtils
import io.nurunuru.app.data.models.ScoredPost
import io.nurunuru.app.ui.theme.LocalNuruColors
import kotlinx.coroutines.launch

private val ZAP_PRESETS = listOf(21L, 100L, 500L, 1000L, 5000L)
private val ZapYellow = Color(0xFFF59E0B)

/**
 * ModalBottomSheet for sending Lightning Zaps (NIP-57).
 * Fetches a BOLT11 invoice via LNURL-pay and copies it to clipboard.
 *
 * @param post The Nostr event being zapped
 * @param onDismiss Dismiss callback
 * @param onFetchInvoice (lud16, amountSats, comment) → suspend String?
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZapModal(
    post: ScoredPost,
    onDismiss: () -> Unit,
    onFetchInvoice: suspend (lud16: String, amountSats: Long, comment: String) -> String?
) {
    val nuruColors = LocalNuruColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val lud16 = post.profile?.lud16
    val authorName = post.profile?.displayedName
        ?: NostrKeyUtils.shortenPubkey(post.event.pubkey)

    var selectedAmount by remember { mutableLongStateOf(21L) }
    var customAmount by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var invoice by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val effectiveAmount = customAmount.toLongOrNull() ?: selectedAmount

    fun dismissSheet() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⚡ Zap送信",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = { dismissSheet() }) {
                    Icon(Icons.Default.Close, contentDescription = "閉じる",
                        tint = nuruColors.textTertiary)
                }
            }
            Text(
                text = authorName,
                style = MaterialTheme.typography.bodySmall,
                color = nuruColors.textTertiary
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (lud16 == null) {
                // No Lightning address
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "この投稿者はLightningアドレスを設定していません",
                        color = nuruColors.textTertiary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                val currentInvoice = invoice
                if (currentInvoice != null) {
                    // Show invoice to copy
                    InvoiceCopiedSection(
                        invoice = currentInvoice,
                        amountSats = effectiveAmount,
                        onCopy = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("invoice", currentInvoice))
                        },
                        onDismiss = { dismissSheet() }
                    )
                } else {
                // Amount presets
                Text(
                    text = "金額 (sats)",
                    style = MaterialTheme.typography.labelMedium,
                    color = nuruColors.textTertiary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ZAP_PRESETS.forEach { amount ->
                        val isSelected = customAmount.isEmpty() && selectedAmount == amount
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedAmount = amount
                                customAmount = ""
                            },
                            label = { Text("⚡$amount", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ZapYellow,
                                selectedLabelColor = Color.Black
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Custom amount
                OutlinedTextField(
                    value = customAmount,
                    onValueChange = { customAmount = it.filter { c -> c.isDigit() } },
                    label = { Text("カスタム金額 (sats)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ZapYellow,
                        cursorColor = ZapYellow
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Comment
                OutlinedTextField(
                    value = comment,
                    onValueChange = { if (it.length <= 100) comment = it },
                    label = { Text("コメント (任意)") },
                    placeholder = { Text("Zap!") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ZapYellow,
                        cursorColor = ZapYellow
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                errorMsg?.let { err ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(err, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Send button
                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            errorMsg = null
                            val result = onFetchInvoice(lud16, effectiveAmount, comment)
                            if (result != null) {
                                invoice = result
                            } else {
                                errorMsg = "インボイスの作成に失敗しました"
                            }
                            isLoading = false
                        }
                    },
                    enabled = !isLoading && effectiveAmount > 0,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ZapYellow,
                        contentColor = Color.Black),
                    shape = RoundedCornerShape(26.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            "⚡ $effectiveAmount sats のインボイスを作成",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                } // end else (no invoice yet)
            } // end else (has lud16)

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun InvoiceCopiedSection(
    invoice: String,
    amountSats: Long,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    var copied by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        Text(
            "⚡ $amountSats sats のインボイス",
            fontWeight = FontWeight.SemiBold,
            color = ZapYellow
        )
        Text(
            invoice.take(40) + "...",
            style = MaterialTheme.typography.bodySmall,
            color = LocalNuruColors.current.textTertiary
        )
        Button(
            onClick = {
                onCopy()
                copied = true
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (copied) MaterialTheme.colorScheme.surfaceVariant else ZapYellow,
                contentColor = if (copied) MaterialTheme.colorScheme.onSurface else Color.Black
            ),
            shape = RoundedCornerShape(26.dp)
        ) {
            Text(if (copied) "コピーしました！" else "インボイスをコピー", fontWeight = FontWeight.SemiBold)
        }
        TextButton(onClick = onDismiss) {
            Text("閉じる", color = LocalNuruColors.current.textTertiary)
        }
    }
}
