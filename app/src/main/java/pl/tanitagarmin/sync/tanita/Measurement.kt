package pl.tanitagarmin.sync.tanita

import java.security.MessageDigest
import java.time.LocalDateTime

data class Measurement(
    val date: LocalDateTime,
    val weightKg: Double?,
    val bmi: Double?,
    val bodyFatPercent: Double?,
    val visceralFat: Double?,
    val muscleMassKg: Double?,
    val muscleQuality: Double?,
    val boneMassKg: Double?,
    val bmrKcal: Double?,
    val metabolicAge: Double?,
    val bodyWaterPercent: Double?,
    val physiqueRating: Double?,
    val muscleMassRightArm: Double?,
    val muscleMassLeftArm: Double?,
    val muscleMassRightLeg: Double?,
    val muscleMassLeftLeg: Double?,
    val muscleMassTrunk: Double?,
    val muscleQualityRightArm: Double?,
    val muscleQualityLeftArm: Double?,
    val muscleQualityRightLeg: Double?,
    val muscleQualityLeftLeg: Double?,
    val muscleQualityTrunk: Double?,
    val bodyFatRightArm: Double?,
    val bodyFatLeftArm: Double?,
    val bodyFatRightLeg: Double?,
    val bodyFatLeftLeg: Double?,
    val bodyFatTrunk: Double?,
    val heartRate: Double?
) {
    fun fingerprint(): String {
        val canonical = listOf(
            date.toString(), weightKg, bmi, bodyFatPercent, visceralFat,
            muscleMassKg, muscleQuality, boneMassKg, bmrKcal, metabolicAge,
            bodyWaterPercent, physiqueRating, muscleMassRightArm, muscleMassLeftArm,
            muscleMassRightLeg, muscleMassLeftLeg, muscleMassTrunk,
            muscleQualityRightArm, muscleQualityLeftArm, muscleQualityRightLeg,
            muscleQualityLeftLeg, muscleQualityTrunk, bodyFatRightArm,
            bodyFatLeftArm, bodyFatRightLeg, bodyFatLeftLeg, bodyFatTrunk, heartRate
        ).joinToString("|") { it?.toString() ?: "-" }

        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

data class ParseResult(
    val allRows: Int,
    val measurements: List<Measurement>
) {
    val uniqueRows: Int get() = measurements.size
    val duplicateRows: Int get() = allRows - uniqueRows
    val latest: Measurement? get() = measurements.maxByOrNull { it.date }
}
