package com.altaran.watchwallet.platform

import android.content.Context
import android.content.Intent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.altaran.watchwallet.core.data.platform.AppRestarter
import com.altaran.watchwallet.core.data.sync.SyncEngine
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Authenticators accepted by the app lock: strong biometrics or the screen lock (PIN, pattern…). */
internal const val LOCK_AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

internal fun isAppLockAvailable(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(LOCK_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

/** Relaunches the app in a fresh process so network settings (proxy, endpoints) apply. */
internal class AndroidAppRestarter(private val context: Context) : AppRestarter {
    override fun restart() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?: return
        context.startActivity(intent)
        exitProcess(0)
    }
}

/** Periodic background sync of all wallets and prices. */
internal class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val syncEngine: SyncEngine by inject()

    override suspend fun doWork(): Result {
        syncEngine.syncAll().join()
        return Result.success()
    }
}

internal object BackgroundRefresh {
    private const val WORK_NAME = "wallet-sync"

    /** Schedules or cancels the periodic sync according to the user's settings. */
    fun apply(context: Context, hours: Int, wifiOnly: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (hours <= 0) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
