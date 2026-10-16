package com.apercallc.aidailymemory.processing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.data.CaptureDatabase
import com.apercallc.aidailymemory.data.DailyReportEntity
import com.apercallc.aidailymemory.data.MemoryItemCorrectionEntity
import com.apercallc.aidailymemory.data.MemoryItemEntity
import com.apercallc.aidailymemory.recording.storage.MemoryRetention
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class MemoryRetentionSweepTest {
    private lateinit var database: CaptureDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    private fun sweep() = MemoryRetentionSweep(
        database,
        database.memoryItems(),
        database.memoryCorrections(),
        database.dailyReports(),
        now = { Instant.parse("2026-10-16T12:00:00Z") },
        zone = { java.time.ZoneId.of("UTC") },
    )

    private fun item(id: String, day: String) = MemoryItemEntity(
        id = id, sessionId = "s1", memoryDay = day, kind = "decision", text = "t",
        confidence = "likely", quote = null, owner = null, due = null, direction = null,
        counterpart = null, extractionModel = "m", extractionVersion = 1,
        evidenceEpochMs = null, dismissedAtEpochMs = null, extractedAtEpochMs = 1L,
    )

    @Test
    fun `keep until deleted deletes nothing`() = runTest {
        database.memoryItems().insertAll(listOf(item("old", "2020-01-01")))

        val deleted = sweep().apply(MemoryRetention.KEEP_UNTIL_DELETED)

        assertEquals(0, deleted)
        assertEquals(1, database.memoryItems().allForSession("s1").size)
    }

    @Test
    fun `items older than the window are deleted, newer items are kept`() = runTest {
        database.memoryItems().insertAll(
            listOf(item("old", "2026-01-01"), item("recent", "2026-10-15")),
        )

        val deleted = sweep().apply(MemoryRetention.KEEP_90_DAYS)

        assertEquals(1, deleted)
        val remaining = database.memoryItems().allForSession("s1")
        assertEquals(listOf("recent"), remaining.map { it.id })
    }

    @Test
    fun `a correction on a deleted item is deleted too, never orphaned`() = runTest {
        database.memoryItems().insertAll(listOf(item("old", "2020-01-01")))
        database.memoryCorrections().insert(
            MemoryItemCorrectionEntity(id = "c1", itemId = "old", correctedText = "x", correctedAtEpochMs = 1L),
        )

        sweep().apply(MemoryRetention.KEEP_90_DAYS)

        assertNull(database.memoryCorrections().latestForItem("old"))
    }

    @Test
    fun `a correction on a kept item survives the sweep`() = runTest {
        database.memoryItems().insertAll(listOf(item("recent", "2026-10-15")))
        database.memoryCorrections().insert(
            MemoryItemCorrectionEntity(id = "c1", itemId = "recent", correctedText = "x", correctedAtEpochMs = 1L),
        )

        sweep().apply(MemoryRetention.KEEP_90_DAYS)

        assertEquals("x", database.memoryCorrections().latestForItem("recent")?.correctedText)
    }

    @Test
    fun `an old daily report is deleted alongside its day's items`() = runTest {
        database.dailyReports().upsert(
            DailyReportEntity(
                id = "r1", memoryDay = "2020-01-01", zoneId = "UTC", headline = "h", summary = "s",
                reportJson = "{}", generatedAtEpochMs = 1L, extractionModel = "m", extractionVersion = 1,
            ),
        )

        sweep().apply(MemoryRetention.KEEP_90_DAYS)

        assertNull(database.dailyReports().forDay("2020-01-01"))
    }
}
