package pl.tanitagarmin.sync.storage

import android.content.Context
import pl.tanitagarmin.sync.tanita.ParseResult
import pl.tanitagarmin.sync.tanita.TanitaCsvParser
import java.io.File

class MeasurementStore(context: Context) {
    private val csvFile = File(context.filesDir, "mytanita_measurements.csv")
    private val sentFile = File(context.filesDir, "garmin_sent_fingerprints.txt")

    fun saveCsv(csv: String) {
        csvFile.writeText(csv, Charsets.UTF_8)
    }

    fun load(): ParseResult? {
        if (!csvFile.exists()) return null
        return runCatching { TanitaCsvParser.parse(csvFile.readText(Charsets.UTF_8)) }.getOrNull()
    }

    fun sentFingerprints(): MutableSet<String> {
        if (!sentFile.exists()) return mutableSetOf()
        return sentFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableSet()
    }

    fun markSent(fingerprints: Collection<String>) {
        val all = sentFingerprints()
        all.addAll(fingerprints)
        sentFile.writeText(all.sorted().joinToString("\n", postfix = if (all.isEmpty()) "" else "\n"))
    }
}
