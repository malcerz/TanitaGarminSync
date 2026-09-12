package pl.tanitagarmin.sync.tanita

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TanitaCsvParserTest {
    @Test
    fun parsesRd953AndRemovesExactDuplicates() {
        val csv = """Date,Weight (kg),BMI,Body Fat (%),Visc Fat,Muscle Mass (kg),Muscle Quality,Bone Mass (kg),BMR (kcal),Metab Age,Body Water (%),Physique Rating,Muscle mass - right arm,Muscle mass - left arm,Muscle mass - right leg,Muscle mass - left leg,Muscle mass - trunk,Muscle quality - right arm,Muscle quality - left arm,Muscle quality - right leg,Muscle quality - left leg,Muscle quality - trunk,Body fat (%) - right arm,Body fat (%) - left arm,Body fat (%) - right leg,Body fat (%) - left leg,Body fat (%) - trunk,Heart rate
2026-09-10 06:03:25,75.55,23.30,11.50,6.00,63.55,68.00,3.30,1899.00,37.00,57.40,5.00,-,-,-,-,-,-,-,-,-,-,-,-,-,-,-,-
2026-09-10 06:03:25,75.55,23.30,11.50,6.00,63.55,68.00,3.30,1899.00,37.00,57.40,5.00,-,-,-,-,-,-,-,-,-,-,-,-,-,-,-,-
"""

        val result = TanitaCsvParser.parse(csv)
        assertEquals(2, result.allRows)
        assertEquals(1, result.uniqueRows)
        assertEquals(1, result.duplicateRows)
        assertEquals(75.55, result.latest!!.weightKg!!, 0.0001)
        assertEquals(63.55, result.latest!!.muscleMassKg!!, 0.0001)
        assertNull(result.latest!!.heartRate)
    }
}
