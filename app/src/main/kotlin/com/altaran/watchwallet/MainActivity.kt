package com.altaran.watchwallet

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.altaran.watchwallet.core.data.DemoWallets
import com.altaran.watchwallet.core.data.platform.AppLanguageController
import kotlinx.coroutines.launch
import com.altaran.watchwallet.shared.AppUiState
import com.altaran.watchwallet.shared.AppViewModel
import com.altaran.watchwallet.shared.WatchWalletApp
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/** AppCompatActivity (not ComponentActivity) is required for per-app language on Android < 13. */
class MainActivity : AppCompatActivity() {
    private val appViewModel: AppViewModel by viewModel()
    private val languageController: AppLanguageController by inject()
    private val demoWallets: DemoWallets by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Keep the splash until settings (theme, discreet mode) are loaded: no flash of wrong theme.
        splashScreen.setKeepOnScreenCondition { appViewModel.uiState.value is AppUiState.Loading }
        enableEdgeToEdge()
        (languageController as? AndroidAppLanguageController)?.refresh()
        // Debug builds only: `adb shell am start -n <pkg>/com.altaran.watchwallet.MainActivity --ez seed_demo true`.
        if (BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_SEED_DEMO, false) == true) {
            lifecycleScope.launch { demoWallets.seed() }
        }

        setContent {
            WatchWalletApp(viewModel = appViewModel)
        }
    }

    private companion object {
        const val EXTRA_SEED_DEMO = "seed_demo"
    }
}
