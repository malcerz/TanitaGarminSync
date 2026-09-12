package pl.tanitagarmin.sync.garmin

import pl.tanitagarmin.sync.tanita.Measurement
import java.io.ByteArrayOutputStream
import java.time.ZoneId

/** Minimal FIT Weight encoder for one MyTANITA measurement.
 *
 * File ID global message: 0
 * Weight Scale global message: 30
 * FIT epoch: 1989-12-31T00:00:00Z
 */
object GarminFitEncoder {
    private const val FIT_EPOCH_UNIX = 631065600L
    private const val FILE_TYPE_WEIGHT = 9
    private const val MANUFACTURER_GARMIN = 1
    private const val PRODUCT_GARMIN_INDEX = 2429
    private val zone = ZoneId.of("Europe/Warsaw")

    private data class FieldDef(val num: Int, val size: Int, val baseType: Int)

    fun encode(measurement: Measurement): ByteArray {
        val weight = measurement.weightKg ?: error("Pomiar nie zawiera wagi")
        val fitTimestamp = measurement.date.atZone(zone).toEpochSecond() - FIT_EPOCH_UNIX
        require(fitTimestamp in 0..0xFFFF_FFFFL) { "Data pomiaru poza zakresem FIT" }

        val data = ByteArrayOutputStream()

        // Local message 0: file_id (global message 0)
        val fileFields = listOf(
            FieldDef(3, 4, 0x8C), // serial_number uint32z
            FieldDef(4, 4, 0x86), // time_created uint32
            FieldDef(1, 2, 0x84), // manufacturer uint16
            FieldDef(2, 2, 0x84), // product uint16
            FieldDef(0, 1, 0x00)  // type enum
        )
        writeDefinition(data, local = 0, global = 0, fields = fileFields)
        data.write(0x00)
        writeU32(data, 1234)
        writeU32(data, fitTimestamp)
        writeU16(data, MANUFACTURER_GARMIN)
        writeU16(data, PRODUCT_GARMIN_INDEX)
        data.write(FILE_TYPE_WEIGHT)

        // Local message 1: weight_scale (global message 30).
        // Only define fields for values actually available from RD-953.
        val weightFields = mutableListOf<FieldDef>()
        val values = ByteArrayOutputStream()

        fun u32(num: Int, v: Long) {
            weightFields += FieldDef(num, 4, 0x86)
            writeU32(values, v)
        }
        fun u16(num: Int, v: Int) {
            weightFields += FieldDef(num, 2, 0x84)
            writeU16(values, v.coerceIn(0, 0xFFFE))
        }
        fun u8(num: Int, v: Int) {
            weightFields += FieldDef(num, 1, 0x02)
            values.write(v.coerceIn(0, 0xFE))
        }

        u32(253, fitTimestamp)
        u16(0, (weight * 100.0).roundFit())
        measurement.bodyFatPercent?.let { u16(1, (it * 100.0).roundFit()) }
        measurement.bodyWaterPercent?.let { u16(2, (it * 100.0).roundFit()) }
        measurement.boneMassKg?.let { u16(4, (it * 100.0).roundFit()) }
        measurement.muscleMassKg?.let { u16(5, (it * 100.0).roundFit()) }
        measurement.bmrKcal?.let { u16(7, (it * 4.0).roundFit()) }
        measurement.physiqueRating?.let { u8(8, it.roundFit()) }
        measurement.metabolicAge?.let { u8(10, it.roundFit()) }
        measurement.visceralFat?.let { u8(11, it.roundFit()) }
        measurement.bmi?.let { u16(13, (it * 10.0).roundFit()) }

        writeDefinition(data, local = 1, global = 30, fields = weightFields)
        data.write(0x01)
        data.write(values.toByteArray())

        return buildFitFile(data.toByteArray())
    }

    private fun writeDefinition(
        out: ByteArrayOutputStream,
        local: Int,
        global: Int,
        fields: List<FieldDef>
    ) {
        require(local in 0..15)
        out.write(0x40 or local) // definition message header
        out.write(0) // reserved
        out.write(0) // architecture: little endian
        writeU16(out, global)
        out.write(fields.size)
        fields.forEach {
            out.write(it.num)
            out.write(it.size)
            out.write(it.baseType)
        }
    }

    private fun buildFitFile(data: ByteArray): ByteArray {
        val file = ByteArrayOutputStream()
        file.write(12) // header size (header without header CRC)
        file.write(0x10) // protocol 1.0
        writeU16(file, 21214) // FIT profile 21.214
        writeU32(file, data.size.toLong())
        file.write('.'.code)
        file.write('F'.code)
        file.write('I'.code)
        file.write('T'.code)
        file.write(data)

        val bytesWithoutCrc = file.toByteArray()
        val crc = crc16(bytesWithoutCrc)
        writeU16(file, crc)
        return file.toByteArray()
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Long) {
        out.write((value and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 24) and 0xFF).toInt())
    }

    private fun Double.roundFit(): Int = kotlin.math.round(this).toInt()

    /** FIT CRC-16 nibble algorithm from the FIT protocol. */
    private fun crc16(bytes: ByteArray): Int {
        val table = intArrayOf(
            0x0000, 0xCC01, 0xD801, 0x1400,
            0xF001, 0x3C00, 0x2800, 0xE401,
            0xA001, 0x6C00, 0x7800, 0xB401,
            0x5000, 0x9C01, 0x8801, 0x4400
        )
        var crc = 0
        for (b in bytes) {
            var tmp = table[crc and 0xF]
            crc = (crc ushr 4) and 0x0FFF
            crc = crc xor tmp xor table[b.toInt() and 0xF]
            tmp = table[crc and 0xF]
            crc = (crc ushr 4) and 0x0FFF
            crc = crc xor tmp xor table[(b.toInt() ushr 4) and 0xF]
        }
        return crc and 0xFFFF
    }
}
