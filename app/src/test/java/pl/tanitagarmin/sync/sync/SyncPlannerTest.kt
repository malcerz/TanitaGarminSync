package pl.tanitagarmin.sync.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import pl.tanitagarmin.sync.tanita.Measurement
import java.time.LocalDateTime

class SyncPlannerTest {
    private fun m(daysAgo: Long, weight: Double): Measurement = Measurement(
        date = LocalDateTime.of(2026, 9, 11, 12, 0).minusDays(daysAgo),
        weightKg = weight,
        bmi = 23.0,
        bodyFatPercent = 12.0,
        visceralFat = 6.0,
        muscleMassKg = 63.0,
        muscleQuality = 70.0,
        boneMassKg = 3.2,
        bmrKcal = 1900.0,
        metabolicAge = 37.0,
        bodyWaterPercent = 58.0,
        physiqueRating = 5.0,
        muscleMassRightArm = null,
        muscleMassLeftArm = null,
        muscleMassRightLeg = null,
        muscleMassLeftLeg = null,
        muscleMassTrunk = null,
        muscleQualityRightArm = null,
        muscleQualityLeftArm = null,
        muscleQualityRightLeg = null,
        muscleQualityLeftLeg = null,
        muscleQualityTrunk = null,
        bodyFatRightArm = null,
        bodyFatLeftArm = null,
        bodyFatRightLeg = null,
        bodyFatLeftLeg = null,
        bodyFatTrunk = null,
        heartRate = null
    )

    @Test
    fun normalRunOnlyPlansLatest() {
        val list = listOf(m(10, 78.0), m(5, 77.0), m(0, 76.0))
        val plan = SyncPlanner.plan(list, emptySet(), LocalDateTime.of(2026, 9, 11, 12, 0), false)
        assertEquals(1, plan.size)
        assertEquals(76.0, plan.single().weightKg!!, 0.001)
    }

    @Test
    fun weeklySweepOnlyBackfillsLastSevenDaysAndSkipsSent() {
        val old = m(10, 78.0)
        val missed = m(5, 77.0)
        val latest = m(0, 76.0)
        val plan = SyncPlanner.plan(
            listOf(old, missed, latest),
            setOf(latest.fingerprint()),
            LocalDateTime.of(2026, 9, 11, 12, 0),
            true
        )
        assertEquals(1, plan.size)
        assertEquals(missed.fingerprint(), plan.single().fingerprint())
        assertFalse(plan.any { it.fingerprint() == old.fingerprint() })
    }
}
