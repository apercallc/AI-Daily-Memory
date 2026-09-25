package com.apercallc.aidailymemory.recording.id

import java.util.UUID
import kotlin.random.Random

/**
 * RFC 9562 UUIDv7 generator for client-created IDs (ADR-0006).
 *
 * IDs are strictly increasing within one generator, even when many are created in the same
 * millisecond or the wall clock steps backwards (NTP sync, manual change): the 12-bit
 * `rand_a` field is used as a counter seeded at a random point in its lower half, and the
 * timestamp never moves backwards. Uniqueness across devices comes from the 62 random bits.
 *
 * Share one instance per process. Compare IDs by their canonical string form: it sorts in
 * creation order, while [UUID.compareTo] uses signed comparison.
 */
class UuidV7Generator(
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    private var lastMillis = -1L
    private var counter = 0

    @Synchronized
    fun next(): UUID {
        val now = clockMillis()
        require(now in 0..MAX_MILLIS) { "clock value $now is outside the UUIDv7 range" }

        val millis = if (now > lastMillis) {
            counter = random.nextInt(COUNTER_SEED_BOUND)
            now
        } else {
            counter += 1
            if (counter > MAX_COUNTER) {
                // 4096+ IDs in one millisecond: borrow the next millisecond to stay ordered.
                counter = random.nextInt(COUNTER_SEED_BOUND)
                lastMillis + 1
            } else {
                lastMillis
            }
        }
        check(millis <= MAX_MILLIS) { "UUIDv7 timestamp overflow" }
        lastMillis = millis

        val mostSignificant = (millis shl 16) or VERSION_BITS or counter.toLong()
        val leastSignificant = (random.nextLong() and RANDOM_MASK) or VARIANT_BITS
        return UUID(mostSignificant, leastSignificant)
    }

    private companion object {
        const val MAX_MILLIS = (1L shl 48) - 1
        const val MAX_COUNTER = 0xFFF
        const val COUNTER_SEED_BOUND = 0x800
        const val VERSION_BITS = 0x7000L
        const val RANDOM_MASK = 0x3FFF_FFFF_FFFF_FFFFL
        const val VARIANT_BITS = Long.MIN_VALUE // binary 10 in the top two bits
    }
}
