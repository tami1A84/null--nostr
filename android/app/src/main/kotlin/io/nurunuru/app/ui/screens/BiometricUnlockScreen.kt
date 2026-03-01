package io.nurunuru.app.ui.screens

import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.nurunuru.app.R
import io.nurunuru.app.ui.theme.LineGreen
import io.nurunuru.app.ui.theme.LocalNuruColors
import io.nurunuru.app.viewmodel.AuthViewModel

/**
 * 生体認証を要求する画面。
 * AuthState.BiometricRequired の時に表示される。
 */
@Composable
fun BiometricUnlockScreen(viewModel: AuthViewModel) {
    val context = LocalContext.current
    val nuruColors = LocalNuruColors.current
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun showBiometricPrompt() {
        val activity = context as? FragmentActivity
        if (activity == null) {
            viewModel.onBiometricFailure()
            return
        }

        val cipher = viewModel.keyManager.getCipherForDecryption()
        if (cipher == null) {
            viewModel.onBiometricFailure()
            return
        }

        val executor = ContextCompat.getMainExecutor(context)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                result.cryptoObject?.cipher?.let { authenticatedCipher ->
                    viewModel.onBiometricSuccess(authenticatedCipher)
                } ?: run {
                    errorMessage = "認証に成功しましたが、暗号オブジェクトを取得できませんでした"
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Log.w("BiometricUnlock", "Auth error: $errorCode - $errString")
                errorMessage = errString.toString()
            }

            override fun onAuthenticationFailed() {
                errorMessage = "認証に失敗しました。もう一度お試しください。"
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("ぬるぬる")
            .setSubtitle("秘密鍵のアンロック")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    // 初回表示時に自動でプロンプト表示
    LaunchedEffect(Unit) {
        showBiometricPrompt()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(nuruColors.bgPrimary),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(id = R.drawable.logo),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            Text(
                text = "認証が必要です",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = nuruColors.textPrimary
            )

            Text(
                text = "秘密鍵にアクセスするには\n生体認証またはデバイス認証が必要です",
                fontSize = 14.sp,
                color = nuruColors.textTertiary,
                lineHeight = 20.sp
            )

            if (errorMessage != null) {
                Text(
                    text = errorMessage ?: "",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp
                )
            }

            Button(
                onClick = { showBiometricPrompt() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LineGreen),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("認証する", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            TextButton(
                onClick = { viewModel.onBiometricFailure() }
            ) {
                Text("キャンセル", color = nuruColors.textTertiary)
            }
        }
    }
}
