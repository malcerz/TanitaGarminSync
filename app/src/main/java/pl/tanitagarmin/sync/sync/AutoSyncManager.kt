package pl.tanitagarmin.sync.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import pl.tanitagarmin.sync.storage.SyncPrefs
import java.util.concurrent.TimeUnit

object AutoSyncManager {
    const val PERIOD_HOURS = 6L
    private const val UNIQUE_PERIODIC = "tanita_garmin_auto_sync"
    private const val UNIQUE_NOW = "tanita_garmin_sync_now"

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun enable(context: Context, runNow: Boolean = true) {
        val app = context.applicationContext
        val prefs = SyncPrefs(app)
        prefs.autoEnabled = true
        // Pierwsze włączenie NIE importuje poprzednich 7 dni.
        prefs.ensureHistoryBaseline()

        val periodic = PeriodicWorkRequestBuilder<AutoSyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic
        )
        if (runNow) runNow(app)
    }

    fun disable(context: Context) {
        val app = context.applicationContext
        SyncPrefs(app).autoEnabled = false
        WorkManager.getInstance(app).cancelUniqueWork(UNIQUE_PERIODIC)
        WorkManager.getInstance(app).cancelUniqueWork(UNIQUE_NOW)
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<AutoSyncWorker>()
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_NOW, androidx.work.ExistingWorkPolicy.REPLACE, request)
    }
}
