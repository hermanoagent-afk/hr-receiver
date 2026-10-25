package dev.hermano.hreceiver

/**
 * Разбор Heart Rate Measurement (0x2A37), Bluetooth SIG стандарт (HRS v1.0, §3.1.1).
 *
 * Формат:
 *  байт 0 — флаги:
 *    бит 0:     0 = пульс 8 бит (UINT8), 1 = 16 бит (UINT16)
 *    биты 1-2:  Sensor Contact Status
 *               0b00/0b01 (бит 2 = 0) — контакт не поддерживается,
 *               0b10 (только бит 2)   — поддерживается, контакта НЕТ,
 *               0b11 (биты 1 и 2)     — поддерживается, контакт ЕСТЬ
 *    бит 3:     есть поле Energy Expended (2 байта)
 *    бит 4:     есть RR-интервалы (единица 1/1024 с)
 *  далее — пульс (1 или 2 байта, little-endian),
 *  далее — опционально энергия (2 байта),
 *  далее — RR-интервалы (по 2 байта, little-endian, 1/1024 с).
 *
 * ИСПРАВЛЕНО: раньше биты 2 и 3 были перепутаны (энергия = 0x04, контакт = 0x08),
 * из-за чего у датчиков с поддержкой контакта RR-интервалы читались со сдвигом.
 */
object HrsParser {

    data class Measurement(
        val bpm: Int,
        val contactSupported: Boolean,
        val contactDetected: Boolean,
        val energyKj: Int?,
        val rrMs: List<Double>,
    )

    private const val FLAG_HR_16 = 0x01
    private const val FLAG_CONTACT_DETECTED = 0x02
    private const val FLAG_CONTACT_SUPPORTED = 0x04
    private const val FLAG_ENERGY = 0x08
    private const val FLAG_RR = 0x10

    fun parse(value: ByteArray): Measurement? {
        if (value.size < 2) return null
        val flags = value[0].toInt() and 0xFF
        val is16 = (flags and FLAG_HR_16) != 0
        val contactSupported = (flags and FLAG_CONTACT_SUPPORTED) != 0
        val contactDetected = contactSupported && (flags and FLAG_CONTACT_DETECTED) != 0
        val energyPresent = (flags and FLAG_ENERGY) != 0
        val rrPresent = (flags and FLAG_RR) != 0

        var i = 1
        val bpm: Int
        if (is16) {
            if (value.size < 3) return null
            bpm = u16(value, i)
            i += 2
        } else {
            bpm = value[i].toInt() and 0xFF
            i += 1
        }

        var energy: Int? = null
        if (energyPresent) {
            if (i + 1 < value.size) energy = u16(value, i)
            i += 2
        }

        val rr = mutableListOf<Double>()
        if (rrPresent) {
            while (i + 1 < value.size) {
                rr.add(u16(value, i) * 1000.0 / 1024.0) // 1/1024 с -> мс
                i += 2
            }
        }
        return Measurement(bpm, contactSupported, contactDetected, energy, rr)
    }

    private fun u16(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
}
