package pl.tanitagarmin.sync.tanita

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object TanitaCsvParser {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun parse(csv: String): ParseResult {
        val rows = parseCsv(csv.removePrefix("\uFEFF"))
        require(rows.isNotEmpty()) { "CSV jest pusty" }

        val header = rows.first().map { it.trim().removePrefix("\uFEFF") }
        val index = header.withIndex().associate { it.value to it.index }

        fun required(name: String): Int = index[name]
            ?: error("Brak wymaganej kolumny MyTANITA: $name")

        val dateIndex = required("Date")
        required("Weight (kg)")
        required("BMI")
        required("Body Fat (%)")

        fun value(row: List<String>, name: String): Double? {
            val i = index[name] ?: return null
            if (i >= row.size) return null
            val raw = row[i].trim()
            if (raw.isEmpty() || raw == "-") return null
            return raw.replace(',', '.').toDoubleOrNull()
        }

        val parsed = rows.drop(1)
            .filter { row -> row.any { it.isNotBlank() } }
            .mapNotNull { row ->
                val rawDate = row.getOrNull(dateIndex)?.trim().orEmpty()
                if (rawDate.isBlank()) return@mapNotNull null

                Measurement(
                    date = LocalDateTime.parse(rawDate, dateFormatter),
                    weightKg = value(row, "Weight (kg)"),
                    bmi = value(row, "BMI"),
                    bodyFatPercent = value(row, "Body Fat (%)"),
                    visceralFat = value(row, "Visc Fat"),
                    muscleMassKg = value(row, "Muscle Mass (kg)"),
                    muscleQuality = value(row, "Muscle Quality"),
                    boneMassKg = value(row, "Bone Mass (kg)"),
                    bmrKcal = value(row, "BMR (kcal)"),
                    metabolicAge = value(row, "Metab Age"),
                    bodyWaterPercent = value(row, "Body Water (%)"),
                    physiqueRating = value(row, "Physique Rating"),
                    muscleMassRightArm = value(row, "Muscle mass - right arm"),
                    muscleMassLeftArm = value(row, "Muscle mass - left arm"),
                    muscleMassRightLeg = value(row, "Muscle mass - right leg"),
                    muscleMassLeftLeg = value(row, "Muscle mass - left leg"),
                    muscleMassTrunk = value(row, "Muscle mass - trunk"),
                    muscleQualityRightArm = value(row, "Muscle quality - right arm"),
                    muscleQualityLeftArm = value(row, "Muscle quality - left arm"),
                    muscleQualityRightLeg = value(row, "Muscle quality - right leg"),
                    muscleQualityLeftLeg = value(row, "Muscle quality - left leg"),
                    muscleQualityTrunk = value(row, "Muscle quality - trunk"),
                    bodyFatRightArm = value(row, "Body fat (%) - right arm"),
                    bodyFatLeftArm = value(row, "Body fat (%) - left arm"),
                    bodyFatRightLeg = value(row, "Body fat (%) - right leg"),
                    bodyFatLeftLeg = value(row, "Body fat (%) - left leg"),
                    bodyFatTrunk = value(row, "Body fat (%) - trunk"),
                    heartRate = value(row, "Heart rate")
                )
            }

        val unique = LinkedHashMap<String, Measurement>()
        parsed.forEach { unique.putIfAbsent(it.fingerprint(), it) }

        return ParseResult(
            allRows = parsed.size,
            measurements = unique.values.sortedByDescending { it.date }
        )
    }

    /**
     * Pełny, mały parser RFC-4180: obsługuje cudzysłowy, przecinki w polach,
     * CRLF/LF i podwójny cudzysłów jako escaped quote.
     */
    internal fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<MutableList<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0

        fun endField() {
            row.add(field.toString())
            field.setLength(0)
        }

        fun endRow() {
            endField()
            rows.add(row)
            row = mutableListOf()
        }

        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> endField()
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    endRow()
                }
                else -> field.append(c)
            }
            i++
        }

        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
