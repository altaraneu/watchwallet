package com.altaran.watchwallet

import android.app.Application
import com.altaran.watchwallet.core.data.platform.AppInfo
import com.altaran.watchwallet.core.data.platform.AppLanguageController
import com.altaran.watchwallet.core.designsystem.theme.isDynamicColorSupported
import com.altaran.watchwallet.shared.sharedModules
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

class WatchWalletApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@WatchWalletApplication)
            modules(sharedModules + androidPlatformModule)
        }
    }

    private val androidPlatformModule = module {
        single<AppLanguageController> { AndroidAppLanguageController(BuildConfig.SUPPORTED_LANGUAGES) }
        single {
            AppInfo(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                sourceCodeUrl = BuildConfig.SOURCE_CODE_URL,
                isDynamicColorAvailable = isDynamicColorSupported,
                isDebug = BuildConfig.DEBUG,
            )
        }
    }
}
