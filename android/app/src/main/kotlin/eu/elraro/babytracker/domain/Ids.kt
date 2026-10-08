package eu.elraro.babytracker.domain

import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/** UUID version 7: time-ordered, generated on the device (docs/SYNC.md §2). */
object Ids {
    private val random = SecureRandom()

    // The numbers below are the bit layout of RFC 9562 §5.7; naming each would not help.
    @Suppress("MagicNumber")
    fun uuidV7(now: Instant = Instant.now()): String {
        val randomBits = ByteArray(10).also(random::nextBytes)
        // 48 bits of Unix milliseconds, 4 bits version, 12 random bits.
        val high =
            (now.toEpochMilli() shl 16) or
                0x7000L or
                ((randomBits[0].toLong() and 0x0F) shl 8) or
                (randomBits[1].toLong() and 0xFF)
        // 2 bits variant (binary 10), 62 random bits.
        var low = 0L
        for (i in 2 until 10) low = (low shl 8) or (randomBits[i].toLong() and 0xFF)
        low = (low and 0x3FFFFFFFFFFFFFFFL) or (1L shl 63)
        return UUID(high, low).toString()
    }
}
