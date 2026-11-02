package com.altaran.watchwallet

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.altaran.watchwallet.core.common.AppLockGate
import com.altaran.watchwallet.core.data.DemoWallets
import com.altaran.watchwallet.core.data.platform.AppLanguageController
import com.altaran.watchwallet.core.data.sync.SyncEngine
import com.altaran.watchwallet.core.designsystem.icon.WwIcon
import com.altaran.watchwallet.core.designsystem.icon.painter
import com.altaran.watchwallet.core.designsystem.theme.WatchWalletTheme
import com.altaran.watchwallet.platform.ActivityLockAuthenticator
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
    private val syncEngine: SyncEngine by inject()

    /** Outlives this screen, so a new instance (rotation, widget, another app) is still locked. */
    private val lockGate: AppLockGate by inject()
    private val lockAuthenticator: ActivityLockAuthenticator by inject()

    /** Read during composition; set in the same step as the gate, so no frame shows old content. */
    private var display by mutableStateOf(AppLockGate.Display.HIDDEN_UNTIL_SETTINGS_LOADED)
    private var promptShowing = false
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Secure until the settings say otherwise: no screenshot or task switcher image while loading.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        display = lockGate.display
        // Keep the splash until settings (theme, discreet mode) are loaded: no flash of wrong theme.
        splashScreen.setKeepOnScreenCondition { appViewModel.uiState.value is AppUiState.Loading }
        enableEdgeToEdge()
        (languageController as? AndroidAppLanguageController)?.refresh()
        // Debug builds only: `adb shell am start -n <pkg>/com.altaran.watchwallet.MainActivity --ez seed_demo true`.
        if (BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_SEED_DEMO, false) == true) {
            lifecycleScope.launch {
                val added = runCatching { demoWallets.seed() }
                Log.i(DEBUG_TAG, "Demo seed: $added")
            }
        }
        if (BuildConfig.DEBUG) logSyncErrors()
        observeSecuritySettings()

        setContent {
            when (display) {
                // The splash is still up; nothing may be composed behind it.
                AppLockGate.Display.HIDDEN_UNTIL_SETTINGS_LOADED -> Unit

                AppLockGate.Display.LOCKED -> WatchWalletTheme { LockScreen(onUnlock = ::unlock) }

                AppLockGate.Display.CONTENT -> WatchWalletApp(viewModel = appViewModel)
            }
        }
    }

    /** Debug builds only: sync failures are otherwise visible only as a generic row warning. */
    private fun logSyncErrors() {
        lifecycleScope.launch {
            syncEngine.state.map { it.errors }.distinctUntilChanged().collect { errors ->
                errors.forEach { (walletId, message) -> Log.w(DEBUG_TAG, "Sync failed for wallet $walletId: $message") }
            }
        }
    }

    private fun observeSecuritySettings() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                appViewModel.uiState.filterIsInstance<AppUiState.Ready>().map { it.settings }
                    .distinctUntilChanged()
                    .collect { settings ->
                        if (AppLockGate.isWindowSecure(settings.secureScreen, settings.appLock)) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                        lockGate.onSettings(settings.appLock)
                        display = lockGate.display
                        if (started) unlock()
                    }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        lockGate.onForegrounded(SystemClock.elapsedRealtime())
        display = lockGate.display
        lockAuthenticator.host = this
        unlock()
    }

    override fun onStop() {
        started = false
        lockGate.onBackgrounded(SystemClock.elapsedRealtime())
        if (lockAuthenticator.host === this) lockAuthenticator.host = null
        super.onStop()
    }

    /** Asks for authentication when the lock is closed; only a success (or no credential left) opens it. */
    private fun unlock() {
        if (!lockGate.needsAuthentication) return
        authenticate { passed ->
            if (passed) {
                lockGate.onAuthenticated()
                display = lockGate.display
            }
        }
    }

    /**
     * [onResult] gets `true` after a success, and when the device has no biometrics or screen lock
     * left: without a credential nobody could ever pass, and removing the screen lock takes the
     * credential itself. Failing, cancelling or a temporary lockout give `false`.
     */
    internal fun authenticate(onResult: (Boolean) -> Unit) {
        if (promptShowing) {
            onResult(false)
            return
        }
        if (hasNoCredential()) {
            onResult(true)
            return
        }
        promptShowing = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    promptShowing = false
                    onResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    promptShowing = false
                    onResult(hasNoCredential())
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

    private fun hasNoCredential(): Boolean = when (BiometricManager.from(this).canAuthenticate(LOCK_AUTHENTICATORS)) {
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
        BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
        -> true

        else -> false
    }

    private companion object {
        const val EXTRA_SEED_DEMO = "seed_demo"
        const val DEBUG_TAG = "WatchWallet"
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
