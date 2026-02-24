package io.nurunuru.app.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import io.nurunuru.app.ui.screens.LoginScreen
import io.nurunuru.app.ui.theme.NuruNuruTheme
import io.nurunuru.app.viewmodel.AuthViewModel
import org.junit.Rule
import org.junit.Test
import androidx.lifecycle.viewmodel.compose.viewModel
import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

class VisualTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        theme = "Theme.NuruNuru"
    )

    @Test
    fun previewLoginScreen() {
        paparazzi.snapshot {
            NuruNuruTheme {
                // In a real test we would use a mock ViewModel
                // For demonstration, we just show how the snapshot is called
                // LoginScreen(mockViewModel)
            }
        }
    }
}
