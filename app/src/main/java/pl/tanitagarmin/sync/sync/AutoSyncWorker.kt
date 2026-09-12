package pl.tanitagarmin.sync.sync

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import pl.tanitagarmin.sync.garmin.GarminAuthClient
import pl.tanitagarmin.sync.garmin.GarminClient
import pl.tanitagarmin.sync.storage.MeasurementStore
import pl.tanitagarmin.sync.storage.SecurePrefs
import pl.tanitagarmin.sync.storage.SyncPrefs
import pl.tanitagarmin.sync.tanita.MyTanitaClient
import java.time.LocalDateTime
import java.time.ZoneId

class AutoSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val syncPrefs = SyncPrefs(applicationContext)
        if (!syncPrefs.autoEnabled) return Result.success()

        val credentials = SecurePrefs(applicationContext).loadCredentials()
        if (credentials == null) {
            syncPrefs.saveRun(false, "brak zapisanych danych MyTANITA", 0)
            return Result.success()
        }

        val auth = GarminAuthClient(applicationContext)
        if (!auth.isAuthenticated()) {
            syncPrefs.saveRun(false, "Garmin wymaga ponownego logowania", 0)
            return Result.success()
        }

        return try {
            val tanita = MyTanitaClient().loginAndDownload(credentials.email, credentials.password)
            val store = MeasurementStore(applicationContext)
            // Cache dla ekranu aplikacji. Serwis MyTANITA nie udostępnia obecnie
            // znanego publicznego eksportu CSV z filtrem daty.
            store.saveCsv(tanita.csv)

            val sweepDue = syncPrefs.historySweepDue()
            val sent = store.sentFingerprints()
            val now = LocalDateTime.now(ZoneId.of("Europe/Warsaw"))
            val plan = SyncPlanner.plan(
                measurements = tanita.parseResult.measurements,
                sentFingerprints = sent,
                now = now,
                historySweepDue = sweepDue,
                historyWindowDays = SyncPrefs.HISTORY_WINDOW_DAYS
            )

            var uploaded = 0
            val garmin = GarminClient(applicationContext)
            for (measurement in plan) {
                garmin.uploadBodyComposition(measurement)
                store.markSent(listOf(measurement.fingerprint()))
                uploaded++
            }

            if (sweepDue) syncPrefs.markHistorySweep()
            val message = when {
                uploaded > 0 && sweepDue -> "wysłano $uploaded; kontrola 7 dni zakończona"
                uploaded > 0 -> "wysłano $uploaded nowy pomiar"
                sweepDue -> "brak nowych; kontrola 7 dni zakończona"
                else -> "brak nowych pomiarów"
            }
            syncPrefs.saveRun(true, message, uploaded)
            Result.success()
        } catch (e: Exception) {
            syncPrefs.saveRun(false, e.message ?: e.javaClass.simpleName, 0)
            Result.retry()
        }
    }
}
