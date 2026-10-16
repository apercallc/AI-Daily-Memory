package com.apercallc.aidailymemory.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A correction supersedes an extracted item's text without rewriting the extracted row itself
 * (AGENTS.md: evidence and derived AI output are correctable, but a correction is stored
 * separately from what it corrects, never in place of it).
 */
@RunWith(RobolectricTestRunner::class)
class MemoryCorrectionTest {
    private lateinit var db: CaptureDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            CaptureDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun item(id: String, text: String) = MemoryItemEntity(
        id = id,
        sessionId = "s1",
        memoryDay = "2026-10-15",
        kind = "decision",
        text = text,
        confidence = "likely",
        quote = null,
        owner = null,
        due = null,
        direction = null,
        counterpart = null,
        extractionModel = "user-key:m",
        extractionVersion = 1,
        evidenceEpochMs = 1_000L,
        dismissedAtEpochMs = null,
        extractedAtEpochMs = 1L,
    )

    @Test
    fun `a correction supersedes the display text without rewriting the extracted item`() = runTest {
        db.memoryItems().insertAll(listOf(item("i1", "We decided to ship Friday")))

        db.memoryCorrections().insert(
            MemoryItemCorrectionEntity(
                id = "c1", itemId = "i1",
                correctedText = "We actually decided to ship Monday", correctedAtEpochMs = 2_000L,
            ),
        )

        val original = db.memoryItems().forSession("s1").single()
        assertEquals("We decided to ship Friday", original.text, "the extracted item is never rewritten")

        val latest = db.memoryCorrections().latestForItem("i1")
        assertEquals("We actually decided to ship Monday", latest?.correctedText)
    }

    @Test
    fun `only the most recent correction for an item is the current one`() = runTest {
        db.memoryItems().insertAll(listOf(item("i1", "Original")))
        db.memoryCorrections().insert(MemoryItemCorrectionEntity("c1", "i1", "First fix", 1_000L))
        db.memoryCorrections().insert(MemoryItemCorrectionEntity("c2", "i1", "Second fix", 2_000L))

        assertEquals("Second fix", db.memoryCorrections().latestForItem("i1")?.correctedText)
    }

    @Test
    fun `an item with no correction has none`() = runTest {
        assertNull(db.memoryCorrections().latestForItem("missing"))
    }

    @Test
    fun `corrections for a batch of items come back keyed by item`() = runTest {
        db.memoryCorrections().insert(MemoryItemCorrectionEntity("c1", "i1", "fix one", 1_000L))
        db.memoryCorrections().insert(MemoryItemCorrectionEntity("c2", "i2", "fix two", 1_000L))

        val found = db.memoryCorrections().forItems(listOf("i1", "i2", "i3"))
        assertEquals(setOf("i1", "i2"), found.map { it.itemId }.toSet())
    }
}
