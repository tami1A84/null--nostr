package io.nurunuru.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import io.nurunuru.app.ui.screens.LoginScreen
import io.nurunuru.app.ui.screens.MainScreen
import io.nurunuru.app.ui.theme.NuruNuruTheme
import io.nurunuru.app.viewmodel.AuthState
import io.nurunuru.app.viewmodel.AuthViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as NuruNuruApp

        setContent {
            NuruNuruTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val authViewModel: AuthViewModel = viewModel()
                    val authState by authViewModel.authState.collectAsState()

                    when (val state = authState) {
                        is AuthState.LoggedIn -> MainScreen(
                            pubkeyHex = state.pubkeyHex,
                            privateKeyHex = state.privateKeyHex,
                            authViewModel = authViewModel,
                            app = app
                        )
                        else -> LoginScreen(viewModel = authViewModel)
                    }
                }
            }
        }
    }
}
