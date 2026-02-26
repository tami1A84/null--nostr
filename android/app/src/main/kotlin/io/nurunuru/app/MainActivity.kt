package io.nurunuru.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import io.nurunuru.app.data.ExternalSigner
import io.nurunuru.app.ui.screens.LoginScreen
import io.nurunuru.app.ui.screens.MainScreen
import io.nurunuru.app.ui.theme.NuruNuruTheme
import io.nurunuru.app.viewmodel.AuthState
import io.nurunuru.app.viewmodel.AuthViewModel

class MainActivity : ComponentActivity() {

    companion object {
        var instance: MainActivity? = null
            private set
    }

    private var authViewModel: AuthViewModel? = null
    private lateinit var signerLauncher: ActivityResultLauncher<Intent>

    fun launchExternalSigner(intent: Intent) {
        signerLauncher.launch(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        enableEdgeToEdge()

        signerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                ExternalSigner.onResult(result.data)
            } else {
                ExternalSigner.onResult(null)
            }
        }

        val app = application as NuruNuruApp

        setContent {
            NuruNuruTheme {
                io.nurunuru.app.ui.components.ToastProvider {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        val viewModel: AuthViewModel = viewModel()
                        authViewModel = viewModel
                        val authState by viewModel.authState.collectAsState()

                        // Handle initial intent if present
                        LaunchedEffect(intent) {
                            handleIntent(intent)
                        }

                        when (val state = authState) {
                            is AuthState.LoggedIn -> MainScreen(
                                pubkeyHex = state.pubkeyHex,
                                privateKeyHex = state.privateKeyHex,
                                authViewModel = viewModel,
                                app = app
                            )

                            else -> LoginScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        intent?.data?.let { uri ->
            android.util.Log.d("MainActivity", "Handling deep link: $uri")
            if (uri.scheme == "io.nurunuru.app" && uri.host == "login") {
                val nsec = uri.getQueryParameter("nsec")
                if (!nsec.isNullOrBlank()) {
                    android.util.Log.d("MainActivity", "Found nsec in deep link, logging in...")
                    authViewModel?.login(nsec)
                }
            }
        }
    }
}
