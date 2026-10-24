package dev.hermano.hreceiver

/**
 * Разбор Heart Rate Measurement (0x2A37), Bluetooth SIG стандарт.
 *
 * Формат:
 *  байт 0 — флаги:
 *    бит 0: 0 = пульс 8 бит, 1 = 16 бит
 *    бит 2: есть ли энергия
 *    бит 3: contact status (есть/нет контакт с кожей)
 *    бит 4: есть ли RR-интервалы (единица 1/1024 с)
 *  далее — пульс (1 или 2 байта, little-endian),
 *  далее — опционально энергия (2 байта),
 *  далее — RR-интервалы (по 2 байта, little-endian, 1/1024 с).
 */
object HrsParser {

    data class Measurement(
        val bpm: Int,
        val contactSupported: Boolean,
        val contactDetected: Boolean,
        val rrMs: List<Double>,
    )

    fun parse(value: ByteArray): Measurement? {
        if (value.size < 2) return null
        val flags = value[0].toInt() and 0xFF
        val is16 = flags and 0x01 != 0
        val energyPresent = flags and 0x04 != 0
        val contactSupported = flags and 0x08 != 0
        val contactDetected = flags and 0x06 == 0x06
        val rrPresent = flags and 0x10 != 0

        var i = 1
        val bpm: Int
        if (is16) {
            if (value.size < 3) return null
            bpm = (value[i].toInt() and 0xFF) or ((value[i + 1].toInt() and 0xFF) shl 8)
            i += 2
        } else {
            bpm = value[i].toInt() and 0xFF
            i += 1
        }
        if (energyPresent) i += 2

        val rr = mutableListOf<Double>()
        if (rrPresent) {
            while (i + 1 < value.size) {
                val raw = (value[i].toInt() and 0xFF) or ((value[i + 1].toInt() and 0xFF) shl 8)
                rr.add(raw * 1000.0 / 1024.0) // 1/1024 с -> мс
                i += 2
            }
        }
        return Measurement(bpm, contactSupported, contactDetected, rr)
    }
}
