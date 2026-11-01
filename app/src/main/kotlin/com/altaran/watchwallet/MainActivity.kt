package com.altaran.watchwallet

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.altaran.watchwallet.core.data.DemoWallets
import com.altaran.watchwallet.core.data.platform.AppLanguageController
import com.altaran.watchwallet.core.designsystem.icon.WwIcon
import com.altaran.watchwallet.core.designsystem.icon.painter
import com.altaran.watchwallet.core.designsystem.theme.WatchWalletTheme
import com.altaran.watchwallet.platform.LOCK_AUTHENTICATORS
import com.altaran.watchwallet.shared.AppUiState
import com.altaran.watchwallet.shared.AppViewModel
import com.altaran.watchwallet.shared.WatchWalletApp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/** AppCompatActivity (a FragmentActivity) is required for per-app language and BiometricPrompt. */
class MainActivity : AppCompatActivity() {
    private val appViewModel: AppViewModel by viewModel()
    private val languageController: AppLanguageController by inject()
    private val demoWallets: DemoWallets by inject()

    /** `true` while the app lock covers the content. */
    private var locked by mutableStateOf(true)
    private var backgroundedAt = 0L
    private var appLockEnabled = false

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
        observeSecuritySettings()

        setContent {
            val state by appViewModel.uiState.collectAsStateWithLifecycle()
            if (locked && appLockEnabled && state is AppUiState.Ready) {
                WatchWalletTheme { LockScreen(onUnlock = ::authenticate) }
            } else {
                WatchWalletApp(viewModel = appViewModel)
            }
        }
    }

    private fun observeSecuritySettings() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                appViewModel.uiState.filterIsInstance<AppUiState.Ready>().map { it.settings }
                    .distinctUntilChanged()
                    .collect { settings ->
                        if (settings.secureScreen) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                        val wasEnabled = appLockEnabled
                        appLockEnabled = settings.appLock
                        // Enabling the lock while using the app must not lock the user out immediately.
                        if (!wasEnabled && settings.appLock) locked = false
                        if (!settings.appLock) locked = false
                    }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val away = SystemClock.elapsedRealtime() - backgroundedAt
        if (backgroundedAt != 0L && away > LOCK_AFTER_MS) locked = true
        if (locked && appLockEnabled) authenticate()
    }

    override fun onStop() {
        super.onStop()
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    private fun authenticate() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    locked = false
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.lock_prompt_title))
                .setAllowedAuthenticators(LOCK_AUTHENTICATORS)
                .build(),
        )
    }

    private companion object {
        const val EXTRA_SEED_DEMO = "seed_demo"

        /** Lock again when the app was in the background for longer than this. */
        const val LOCK_AFTER_MS = 30_000L
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(WwIcon.Visibility.painter(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.titleLarge)
            Button(onClick = onUnlock) { Text(stringResource(R.string.lock_unlock)) }
        }
    }
}
