package com.apercallc.aidailymemory.analysis

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A stand-in for a person talking: a harmonic source whose pitch glides between about 95 and
 * 145 Hz and whose level rises and falls about four times a second, falling silent between
 * syllables. The voice detector accepts
 * it; a plain tone, hiss or silence it does not.
 */
object SyntheticVoice {
    fun sample(index: Int, rateHz: Int, amplitude: Double = 3_000.0): Short {
        val t = index.toDouble() / rateHz
        val phase = 2 * PI * 120.0 * t + (25.0 / 0.7) * (1 - cos(2 * PI * 0.7 * t))
        var source = 0.0
        for (h in 1..12) source += sin(h * phase) / h
        // Four syllables a second with a brief silence between them, as real speech has.
        val hump = abs(sin(2 * PI * 2.0 * t))
        val syllables = if (hump < 0.25) 0.0 else hump
        return (amplitude * syllables * source).toInt().coerceIn(-32768, 32767).toShort()
    }
}
