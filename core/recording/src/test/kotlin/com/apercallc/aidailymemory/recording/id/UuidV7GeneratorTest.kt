package com.apercallc.aidailymemory.recording.id

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UuidV7GeneratorTest {
    private var now = 1_758_800_000_000L
    private val generator = UuidV7Generator(clockMillis = { now }, random = Random(42))

    @Test
    fun `ids have version 7, RFC variant, and embed the timestamp`() {
        val id = generator.next()

        assertEquals(7, id.version())
        assertEquals(2, id.variant())
        assertEquals(now, id.mostSignificantBits ushr 16)
        assertTrue(CANONICAL.matches(id.toString()))
    }

    @Test
    fun `ids are strictly increasing within one millisecond`() {
        val ids = List(5_000) { generator.next().toString() }

        assertEquals(ids.sorted(), ids)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `counter overflow borrows the next millisecond instead of repeating`() {
        val ids = List(10_000) { generator.next() }

        assertTrue(ids.last().mostSignificantBits ushr 16 > now)
        assertEquals(ids.map { it.toString() }.sorted(), ids.map { it.toString() })
    }

    @Test
    fun `clock stepping backwards keeps ids ordered`() {
        val before = generator.next()
        now -= 60_000
        val after = generator.next()

        assertTrue(after.toString() > before.toString())
        assertEquals(before.mostSignificantBits ushr 16, after.mostSignificantBits ushr 16)
    }

    @Test
    fun `clock moving forward resets to the new timestamp`() {
        generator.next()
        now += 5
        val later = generator.next()

        assertEquals(now, later.mostSignificantBits ushr 16)
    }

    @Test
    fun `clock values outside the 48-bit range are rejected`() {
        now = -1
        assertFailsWith<IllegalArgumentException> { generator.next() }
        now = 1L shl 48
        assertFailsWith<IllegalArgumentException> { generator.next() }
    }

    @Test
    fun `overflow at the very end of the timestamp range fails loudly`() {
        now = (1L shl 48) - 1
        assertFailsWith<IllegalStateException> { repeat(10_000) { generator.next() } }
    }

    @Test
    fun `default generator uses the system clock`() {
        val before = System.currentTimeMillis()
        val id = UuidV7Generator().next()
        val after = System.currentTimeMillis()

        assertTrue((id.mostSignificantBits ushr 16) in before..after)
    }

    private companion object {
        val CANONICAL = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}
