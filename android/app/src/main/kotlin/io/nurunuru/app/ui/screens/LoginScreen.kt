package io.nurunuru.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nurunuru.app.R
import io.nurunuru.app.data.ExternalSigner
import io.nurunuru.app.ui.components.SignUpModal
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import io.nurunuru.app.viewmodel.AuthState
import io.nurunuru.app.viewmodel.AuthViewModel

@Composable
fun LoginScreen(
    viewModel: AuthViewModel
) {
    val authState by viewModel.authState.collectAsState()
    val nuruColors = LocalNuruColors.current

    var nsecInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var showSignUp by remember { mutableStateOf(false) }
    var showNsecLogin by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val amberLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val pubkey = result.data?.getStringExtra("pubKey") ?: result.data?.getStringExtra("signature")
            if (pubkey != null) {
                viewModel.loginWithAmber(pubkey)
            }
        }
    }

    val isLoading = authState is AuthState.Checking
    val errorMsg = (authState as? AuthState.Error)?.message

    // Match Web version's initial loading state
    if (isLoading && !showNsecLogin && !showSignUp) {
        Box(
            modifier = Modifier.fillMaxSize().background(nuruColors.bgPrimary),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val infiniteTransition = rememberInfiniteTransition(label = "logo_pulse")
                val scale by infiniteTransition.animateFloat(
                    initialValue = 0.95f,
                    targetValue = 1.05f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "scale"
                )

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .graphicsLayer(scaleX = scale, scaleY = scale)
                        .clip(RoundedCornerShape(20.dp))
                ) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(id = R.drawable.logo),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "読み込み中...",
                    color = nuruColors.textTertiary,
                    fontSize = 14.sp
                )
            }
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(nuruColors.bgPrimary),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            // Logo / title
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .shadow(12.dp, RoundedCornerShape(32.dp))
                        .clip(RoundedCornerShape(32.dp))
                        .background(nuruColors.bgSecondary)
                ) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(id = R.drawable.logo),
                        contentDescription = "ぬるぬる",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                Text(
                    text = "ぬるぬる",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = nuruColors.textPrimary
                )
            }

            // Options
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (!showNsecLogin) {
                    // Sign Up Button
                    Button(
                        onClick = { showSignUp = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .shadow(8.dp, RoundedCornerShape(20.dp), spotColor = LineGreen.copy(alpha = 0.1f)),
                        colors = ButtonDefaults.buttonColors(containerColor = LineGreen),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(
                                imageVector = Icons.Default.PersonAdd,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                            Text("新規登録", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Login Button (Combined)
                    Button(
                        onClick = { showNsecLogin = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = nuruColors.bgSecondary),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(
                                imageVector = Icons.Default.Login,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = nuruColors.textPrimary
                            )
                            Text("ログイン", fontSize = 16.sp, color = nuruColors.textPrimary)
                        }
                    }
                } else {
                    // Login options: Passkey or nsec
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Passkey Login Button
                        Button(
                            onClick = { viewModel.loginWithPasskey(context) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = LineGreen.copy(alpha = 0.1f)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, LineGreen),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Fingerprint,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = LineGreen
                                )
                                Text("パスキーでログイン", fontSize = 16.sp, color = LineGreen, fontWeight = FontWeight.Bold)
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                            HorizontalDivider(modifier = Modifier.weight(1f), color = nuruColors.bgTertiary)
                            Text("または", modifier = Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = nuruColors.textTertiary)
                            HorizontalDivider(modifier = Modifier.weight(1f), color = nuruColors.bgTertiary)
                        }

                    // External Signer Button (NIP-55)
                    Button(
                        onClick = {
                            try {
                                amberLauncher.launch(ExternalSigner.createGetPublicKeyIntent(context))
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "外部署名アプリが見つかりません", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = nuruColors.bgSecondary),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(
                                imageVector = Icons.Default.AppShortcut,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = nuruColors.textPrimary
                            )
                            Text("外部アプリでログイン (Amber等)", fontSize = 16.sp, color = nuruColors.textPrimary)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        HorizontalDivider(modifier = Modifier.weight(1f), color = nuruColors.bgTertiary)
                        Text("または秘密鍵を入力", modifier = Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = nuruColors.textTertiary)
                        HorizontalDivider(modifier = Modifier.weight(1f), color = nuruColors.bgTertiary)
                    }

                        OutlinedTextField(
                            value = nsecInput,
                            onValueChange = {
                                nsecInput = it
                                if (errorMsg != null) viewModel.clearError()
                            },
                            label = { Text("秘密鍵 (nsec1...)") },
                            placeholder = { Text("nsec1...") },
                            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            trailingIcon = {
                                IconButton(onClick = { showKey = !showKey }) {
                                    Icon(
                                        imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (showKey) "隠す" else "表示",
                                        tint = nuruColors.textTertiary
                                    )
                                }
                            },
                            isError = errorMsg != null,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = LineGreen,
                                focusedLabelColor = LineGreen,
                                cursorColor = LineGreen,
                                unfocusedBorderColor = nuruColors.bgTertiary
                            ),
                            shape = RoundedCornerShape(16.dp)
                        )

                        AnimatedVisibility(visible = errorMsg != null) {
                            Text(
                                text = errorMsg ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }

                        Button(
                            onClick = { viewModel.login(nsecInput) },
                            enabled = nsecInput.isNotBlank() && !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LineGreen,
                                disabledContainerColor = LineGreen.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("ログイン", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                        }

                        TextButton(
                            onClick = { showNsecLogin = false },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("キャンセル", color = nuruColors.textTertiary)
                        }
                    }
                }
            }

            // Footer
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Powered by Nostr",
                    style = MaterialTheme.typography.bodySmall,
                    color = nuruColors.textTertiary
                )
                Text(
                    text = "秘密鍵はデバイス外に送信されません",
                    fontSize = 10.sp,
                    color = nuruColors.textTertiary.copy(alpha = 0.7f)
                )
            }
        }
    }

    if (showSignUp) {
        SignUpModal(
            viewModel = viewModel,
            onClose = { showSignUp = false },
            onSuccess = { pubkey ->
                showSignUp = false
                // Logged in via completeRegistration in Modal
            }
        )
    }
}
