package pl.tanitagarmin.sync.storage

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class SyncPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("auto_sync", Context.MODE_PRIVATE)

    var autoEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_ENABLED, value).putBoolean(KEY_CONFIGURED, true).apply() }

    val configured: Boolean get() = prefs.getBoolean(KEY_CONFIGURED, false)

    fun ensureHistoryBaseline(nowMillis: Long = System.currentTimeMillis()) {
        if (prefs.getLong(KEY_LAST_HISTORY_SWEEP, 0L) == 0L) {
            prefs.edit().putLong(KEY_LAST_HISTORY_SWEEP, nowMillis).apply()
        }
    }

    fun historySweepDue(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val last = prefs.getLong(KEY_LAST_HISTORY_SWEEP, 0L)
        return last > 0L && nowMillis - last >= TimeUnit.DAYS.toMillis(HISTORY_INTERVAL_DAYS)
    }

    fun markHistorySweep(nowMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_HISTORY_SWEEP, nowMillis).apply()
    }

    fun saveRun(success: Boolean, message: String, uploaded: Int, nowMillis: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(KEY_LAST_RUN, nowMillis)
            .putBoolean(KEY_LAST_SUCCESS, success)
            .putString(KEY_LAST_MESSAGE, message.take(500))
            .putInt(KEY_LAST_UPLOADED, uploaded)
            .apply()
    }

    data class LastRun(
        val timeMillis: Long,
        val success: Boolean,
        val message: String,
        val uploaded: Int
    )

    fun lastRun(): LastRun? {
        val ts = prefs.getLong(KEY_LAST_RUN, 0L)
        if (ts == 0L) return null
        return LastRun(
            timeMillis = ts,
            success = prefs.getBoolean(KEY_LAST_SUCCESS, false),
            message = prefs.getString(KEY_LAST_MESSAGE, "").orEmpty(),
            uploaded = prefs.getInt(KEY_LAST_UPLOADED, 0)
        )
    }

    fun lastRunText(): String? {
        val run = lastRun() ?: return null
        val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.of("Europe/Warsaw"))
        val state = if (run.success) "OK" else "błąd"
        return "Ostatnia automatyczna synchronizacja: ${fmt.format(Instant.ofEpochMilli(run.timeMillis))} • $state • ${run.message}"
    }

    companion object {
        const val HISTORY_INTERVAL_DAYS = 7L
        const val HISTORY_WINDOW_DAYS = 7L
        private const val KEY_AUTO_ENABLED = "enabled"
        private const val KEY_CONFIGURED = "configured"
        private const val KEY_LAST_HISTORY_SWEEP = "last_history_sweep"
        private const val KEY_LAST_RUN = "last_run"
        private const val KEY_LAST_SUCCESS = "last_success"
        private const val KEY_LAST_MESSAGE = "last_message"
        private const val KEY_LAST_UPLOADED = "last_uploaded"
    }
}
