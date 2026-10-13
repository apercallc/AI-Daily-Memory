package com.apercallc.aidailymemory.recording.speech

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Synthetic stand-ins: a "voice" is a harmonic source whose pitch glides and whose loudness
 * rises and falls at syllable pace; an "instrument" holds pitch with a flat level; "nature" is
 * noise. They check the rules, not real-world accuracy.
 */
class VoiceVadTest {
    private val rate = 16_000

    /** Harmonic source with gliding pitch, ~4 syllables/s and a pause every other second. */
    private fun voice(ms: Int, amplitude: Double = 4_000.0, seed: Long = 1): ShortArray {
        val random = Random(seed)
        val out = ShortArray(rate * ms / 1000)
        var phase = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / rate
            val f0 = 120.0 + 25.0 * sin(2 * PI * 0.7 * t)
            phase += 2 * PI * f0 / rate
            var sample = 0.0
            for (h in 1..12) sample += sin(h * phase) / h
            val syllable = max(0.0, sin(2 * PI * 4.0 * t))
            val phrase = if ((t % 2.0) < 1.4) 1.0 else 0.0
            val envelope = syllable * phrase
            out[i] = (amplitude * envelope * sample + random.nextGaussian() * 15).toInt().toShort()
        }
        return out
    }

    /** A run of voiced speech with no pause at all: only the gliding pitch and syllables say "voice". */
    private fun unbrokenVoice(ms: Int): ShortArray = ShortArray(rate * ms / 1000) {
        val t = it.toDouble() / rate
        val phase = 2 * PI * 120.0 * t + (25.0 / 0.7) * (1 - kotlin.math.cos(2 * PI * 0.7 * t))
        var sample = 0.0
        for (h in 1..12) sample += sin(h * phase) / h
        val syllable = max(0.0, abs(sin(2 * PI * 2.0 * t)) - 0.25)
        (4_000.0 * syllable * sample).toInt().toShort()
    }

    private fun sustainedTone(ms: Int, hz: Double = 440.0, amplitude: Double = 4_000.0) =
        ShortArray(rate * ms / 1000) {
            var sample = 0.0
            for (h in 1..6) sample += sin(2 * PI * hz * h * it / rate) / h
            (amplitude * sample).toInt().toShort()
        }

    /** A melody: a new note every 500 ms with a short decay, so the level is not perfectly flat. */
    private fun melody(ms: Int): ShortArray {
        val notes = doubleArrayOf(262.0, 330.0, 392.0, 523.0, 392.0, 330.0)
        return ShortArray(rate * ms / 1000) {
            val t = it.toDouble() / rate
            val hz = notes[((t / 0.5).toInt()) % notes.size]
            val decay = 1.0 - 0.3 * ((t % 0.5) / 0.5)
            var sample = 0.0
            for (h in 1..5) sample += sin(2 * PI * hz * h * it / rate) / h
            (4_000.0 * decay * sample).toInt().toShort()
        }
    }

    private fun noise(ms: Int, amplitude: Double = 2_500.0, seed: Long = 7): ShortArray {
        val random = Random(seed)
        return ShortArray(rate * ms / 1000) { (random.nextGaussian() * amplitude).toInt().toShort() }
    }

    /** Low rumble: noise smoothed so almost all energy sits below 100 Hz. */
    private fun rumble(ms: Int): ShortArray {
        val random = Random(3)
        var state = 0.0
        return ShortArray(rate * ms / 1000) {
            state = 0.995 * state + random.nextGaussian() * 220
            state.toInt().toShort()
        }
    }

    private fun mix(a: ShortArray, b: ShortArray) =
        ShortArray(a.size) { (a[it] + b[it]).coerceIn(-32768, 32767).toShort() }

    private fun decide(audio: ShortArray, chunk: Int = 1_000): List<Boolean> {
        val vad = VoiceVad(rate)
        var offset = 0
        while (offset < audio.size) {
            val size = minOf(chunk, audio.size - offset)
            vad.process(audio.copyOfRange(offset, offset + size))
            offset += size
        }
        val out = mutableListOf<Boolean>()
        vad.finish { out += it }
        return out
    }

    private fun share(decisions: List<Boolean>) = decisions.count { it }.toDouble() / decisions.size

    @Test
    fun `a voice is kept`() {
        assertTrue(share(decide(voice(6_000))) > 0.4)
    }

    @Test
    fun `voiced speech with no pauses is kept because its pitch moves`() {
        assertTrue(share(decide(unbrokenVoice(6_000))) > 0.5)
    }

    @Test
    fun `a quiet voice over steady background noise is still kept`() {
        val audio = mix(voice(6_000, amplitude = 5_000.0), noise(6_000, amplitude = 250.0))
        assertTrue(share(decide(audio)) > 0.35)
    }

    @Test
    fun `silence is dropped`() {
        assertEquals(0.0, share(decide(ShortArray(rate * 4))))
    }

    @Test
    fun `steady noise like rain or wind is dropped`() {
        assertTrue(share(decide(noise(6_000))) < 0.05)
    }

    @Test
    fun `low rumble is dropped`() {
        assertTrue(share(decide(rumble(6_000))) < 0.05)
    }

    @Test
    fun `a held instrument note is dropped`() {
        assertTrue(share(decide(sustainedTone(6_000))) < 0.05)
    }

    @Test
    fun `a melody is dropped`() {
        assertTrue(share(decide(melody(6_000))) < 0.1)
    }

    @Test
    fun `decisions arrive once per completed frame, whatever the chunk size`() {
        val audio = voice(3_000)
        val whole = decide(audio, chunk = audio.size)
        val small = decide(audio, chunk = 77)
        assertEquals(audio.size / (rate * 30 / 1000), whole.size)
        assertEquals(whole, small)
    }

    @Test
    fun `finish forgets the frames so one instance can be reused`() {
        val vad = VoiceVad(rate)
        vad.process(voice(1_000))
        var first = 0
        vad.finish { first++ }
        var second = 0
        vad.finish { second++ }
        assertTrue(first > 0)
        assertEquals(0, second)
    }

    @Test
    fun `a partial trailing frame is not reported`() {
        val vad = VoiceVad(rate)
        vad.process(ShortArray(rate * 30 / 1000 + 100))
        var count = 0
        vad.finish { count++ }
        assertEquals(1, count)
    }

    @Test
    fun `works at other sample rates`() {
        val vad = VoiceVad(8_000)
        vad.process(ShortArray(2_400))
        var count = 0
        vad.finish { count++ }
        assertEquals(10, count)
        val high = VoiceVad(44_100)
        high.process(ShortArray(44_100 * 30 / 1000 * 2))
        var highCount = 0
        high.finish { highCount++ }
        assertEquals(2, highCount)
    }

    @Test
    fun `rejects unusable settings`() {
        assertFailsWith<IllegalArgumentException> { VoiceVad(4_000) }
        assertFailsWith<IllegalArgumentException> { VoiceVad(rate, frameMs = 10) }
        assertFailsWith<IllegalArgumentException> { VoiceVad(rate, frameMs = 101) }
        assertFailsWith<IllegalArgumentException> { VoiceVad(rate).process(ShortArray(4), count = 5) }
    }

    @Test
    fun `no decision depends on sign or DC offset`() {
        val shifted = voice(4_000).map { (it + 600).coerceIn(-32768, 32767).toShort() }.toShortArray()
        val plain = share(decide(voice(4_000)))
        val offset = share(decide(shifted))
        assertTrue(abs(plain - offset) < 0.15, "plain=$plain offset=$offset")
    }
}
