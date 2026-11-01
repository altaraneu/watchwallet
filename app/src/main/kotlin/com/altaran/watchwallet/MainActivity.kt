package com.altaran.watchwallet

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.altaran.watchwallet.core.data.platform.AppLanguageController
import com.altaran.watchwallet.shared.AppUiState
import com.altaran.watchwallet.shared.AppViewModel
import com.altaran.watchwallet.shared.WatchWalletApp
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/** AppCompatActivity (not ComponentActivity) is required for per-app language on Android < 13. */
class MainActivity : AppCompatActivity() {
    private val appViewModel: AppViewModel by viewModel()
    private val languageController: AppLanguageController by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Keep the splash until settings (theme, discreet mode) are loaded: no flash of wrong theme.
        splashScreen.setKeepOnScreenCondition { appViewModel.uiState.value is AppUiState.Loading }
        enableEdgeToEdge()
        (languageController as? AndroidAppLanguageController)?.refresh()

        setContent {
            WatchWalletApp(viewModel = appViewModel)
        }
    }
}
