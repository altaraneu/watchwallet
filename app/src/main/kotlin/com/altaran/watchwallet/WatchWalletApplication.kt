package com.altaran.watchwallet

import android.app.Application
import com.altaran.watchwallet.core.data.platform.AppInfo
import com.altaran.watchwallet.core.data.platform.AppLanguageController
import com.altaran.watchwallet.core.data.platform.AppRestarter
import com.altaran.watchwallet.core.data.repository.SettingsRepository
import com.altaran.watchwallet.core.designsystem.theme.isDynamicColorSupported
import com.altaran.watchwallet.platform.AndroidAppRestarter
import com.altaran.watchwallet.platform.BackgroundRefresh
import com.altaran.watchwallet.platform.isAppLockAvailable
import com.altaran.watchwallet.shared.sharedModules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

class WatchWalletApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@WatchWalletApplication)
            modules(sharedModules + androidPlatformModule)
        }
        scheduleBackgroundRefresh()
    }

    private fun scheduleBackgroundRefresh() {
        val settings: SettingsRepository by inject()
        appScope.launch {
            settings.settings
                .map { it.backgroundRefreshHours to it.backgroundRefreshWifiOnly }
                .distinctUntilChanged()
                .collect { (hours, wifiOnly) -> BackgroundRefresh.apply(this@WatchWalletApplication, hours, wifiOnly) }
        }
    }

    private val androidPlatformModule = module {
        single<AppLanguageController> { AndroidAppLanguageController(BuildConfig.SUPPORTED_LANGUAGES) }
        single<AppRestarter> { AndroidAppRestarter(this@WatchWalletApplication) }
        single {
            AppInfo(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                sourceCodeUrl = BuildConfig.SOURCE_CODE_URL,
                isDynamicColorAvailable = isDynamicColorSupported,
                isDebug = BuildConfig.DEBUG,
                isAppLockAvailable = isAppLockAvailable(this@WatchWalletApplication),
            )
        }
    }
}
