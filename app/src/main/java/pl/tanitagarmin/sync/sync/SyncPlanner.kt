package pl.tanitagarmin.sync.sync

import pl.tanitagarmin.sync.tanita.Measurement
import java.time.LocalDateTime

/**
 * Plan synchronizacji:
 * - na każdym przebiegu sprawdza wyłącznie najnowszy pomiar,
 * - raz na 7 dni robi kontrolę braków z ostatnich 7 dni,
 * - nigdy nie planuje rekordów już oznaczonych jako wysłane.
 */
object SyncPlanner {
    fun plan(
        measurements: List<Measurement>,
        sentFingerprints: Set<String>,
        now: LocalDateTime,
        historySweepDue: Boolean,
        historyWindowDays: Long = 7
    ): List<Measurement> {
        if (measurements.isEmpty()) return emptyList()

        val planned = linkedMapOf<String, Measurement>()
        val latest = measurements.maxByOrNull { it.date }
        if (latest != null && latest.fingerprint() !in sentFingerprints) {
            planned[latest.fingerprint()] = latest
        }

        if (historySweepDue) {
            val cutoff = now.minusDays(historyWindowDays)
            measurements.asSequence()
                .filter { !it.date.isBefore(cutoff) && !it.date.isAfter(now.plusMinutes(10)) }
                .sortedBy { it.date }
                .forEach { m ->
                    val fp = m.fingerprint()
                    if (fp !in sentFingerprints) planned[fp] = m
                }
        }

        return planned.values.sortedBy { it.date }
    }
}
