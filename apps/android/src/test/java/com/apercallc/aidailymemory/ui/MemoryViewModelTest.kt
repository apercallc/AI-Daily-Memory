package com.apercallc.aidailymemory.ui

import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.AiDailyMemoryApplication
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.data.SessionInfoEntity
import com.apercallc.aidailymemory.recording.session.PartOfDay
import com.apercallc.aidailymemory.recording.session.SessionTitle
import com.apercallc.aidailymemory.ui.memory.DayLabel
import com.apercallc.aidailymemory.ui.memory.MemoryLoadState
import com.apercallc.aidailymemory.ui.memory.MemoryViewModel
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MemoryViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<AiDailyMemoryApplication>()
    private val container = app.container
    private val noon = Instant.parse("2026-10-12T12:00:00Z")

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private suspend fun register(id: String, day: String, zone: String, startedAt: Long, title: String? = null) =
        container.database.sessions().register(SessionInfoEntity(id, day, zone, startedAt, title))

    private fun content(viewModel: MemoryViewModel): MemoryLoadState.Content = runBlocking {
        val keepWarm = launch { viewModel.state.collect {} }
        val loaded = withTimeout(5_000) { viewModel.state.first { it is MemoryLoadState.Content } }
        keepWarm.cancel()
        assertIs<MemoryLoadState.Content>(loaded)
    }

    @Test
    fun `days are labelled structurally and titles keep their part of day`() = runBlocking {
        register("today", "2026-10-12", "UTC", Instant.parse("2026-10-12T09:00:00Z").toEpochMilli())
        register("yesterday", "2026-10-11", "UTC", Instant.parse("2026-10-11T20:00:00Z").toEpochMilli(), "Planning")

        val days = content(MemoryViewModel(container, { noon }, { ZoneId.of("UTC") })).days

        assertEquals(DayLabel.Today, days[0].dayLabel)
        assertEquals(SessionTitle.Default(PartOfDay.MORNING, null), days[0].sessions.single().title)
        assertEquals(DayLabel.Yesterday, days[1].dayLabel)
        assertEquals(SessionTitle.Custom("Planning"), days[1].sessions.single().title)
    }

    @Test
    fun `a session with an unknown zone id still loads using the device zone`() = runBlocking {
        register("odd", "2026-10-12", "Mars/Olympus", Instant.parse("2026-10-12T09:00:00Z").toEpochMilli())

        val days = content(MemoryViewModel(container, { noon }, { ZoneId.of("UTC") })).days

        assertEquals("odd", days.single().sessions.single().sessionId)
    }

    @Test
    fun `an unparseable day key is kept as its own label`() = runBlocking {
        register("bad", "not-a-date", "UTC", Instant.parse("2026-10-12T09:00:00Z").toEpochMilli())

        val days = content(MemoryViewModel(container, { noon }, { ZoneId.of("UTC") })).days

        // "not-a-date" sorts after the cutoff string, so the row is returned and labelled raw.
        assertEquals(DayLabel.Date("not-a-date"), days.single().dayLabel)
    }

    @Test
    fun `no sessions is an empty timeline, not an error`() {
        val days = content(MemoryViewModel(container, { noon }, { ZoneId.of("UTC") })).days

        assertEquals(emptyList(), days)
    }

    @Test
    fun `dismissing an item removes it without rewriting the extracted row`() = runBlocking {
        register("s1", "2026-10-12", "UTC", Instant.parse("2026-10-12T09:00:00Z").toEpochMilli())
        container.database.memoryItems().insertAll(listOf(item("i1", "s1", "2026-10-12")))
        val vm = MemoryViewModel(container, { noon }, { ZoneId.of("UTC") })
        var latest: MemoryLoadState = MemoryLoadState.Loading
        val collector = launch { vm.state.collect { latest = it } }
        withTimeout(5_000) { while (latest.itemCount() != 1) kotlinx.coroutines.yield() }

        vm.dismiss("i1")
        withTimeout(5_000) { while (latest.itemCount() != 0) kotlinx.coroutines.yield() }
        collector.cancel()

        val raw = container.database.openHelper.readableDatabase
            .query("SELECT text FROM memory_item WHERE id = 'i1'")
        raw.use {
            it.moveToFirst()
            assertEquals("Original text", it.getString(0))
        }
    }

    @Test
    fun `correcting an item shows the corrected text without losing the original`() = runBlocking {
        register("s1", "2026-10-12", "UTC", Instant.parse("2026-10-12T09:00:00Z").toEpochMilli())
        container.database.memoryItems().insertAll(listOf(item("i1", "s1", "2026-10-12")))
        val vm = MemoryViewModel(container, { noon }, { ZoneId.of("UTC") })
        var latest: MemoryLoadState = MemoryLoadState.Loading
        val collector = launch { vm.state.collect { latest = it } }
        withTimeout(5_000) { while (latest.itemCount() != 1) kotlinx.coroutines.yield() }

        vm.correct("i1", "The corrected version")
        withTimeout(5_000) { while (latest.firstItemOrNull()?.text != "The corrected version") kotlinx.coroutines.yield() }
        collector.cancel()

        assertEquals(true, latest.firstItemOrNull()?.isCorrected)
    }

    @Test
    fun `report observes the stored entity for a memory day, not other days`() = runBlocking {
        container.database.dailyReports().upsert(reportEntity("2026-10-12"))
        container.database.dailyReports().upsert(reportEntity("2026-10-11"))
        val vm = MemoryViewModel(container, { noon }, { ZoneId.of("UTC") })

        val report = withTimeout(5_000) { vm.report("2026-10-12").first() }

        assertEquals("2026-10-12", report?.memoryDay)
    }

    @Test
    fun `report is null for a day that never generated one`() = runBlocking {
        val vm = MemoryViewModel(container, { noon }, { ZoneId.of("UTC") })

        val report = withTimeout(5_000) { vm.report("2026-10-12").first() }

        assertEquals(null, report)
    }

    @Test
    fun `two close short sessions default to one joined event`() = runBlocking {
        register("a", "2026-10-12", "UTC", Instant.parse("2026-10-12T09:00:00Z").toEpochMilli())
        register("b", "2026-10-12", "UTC", Instant.parse("2026-10-12T09:01:00Z").toEpochMilli())

        val days = content(MemoryViewModel(container, { noon }, { ZoneId.of("UTC") })).days
        val sessions = days.single().sessions.associateBy { it.sessionId }

        // Newest first: "b" is the later session and is the one carrying the boundary flag.
        assertEquals(true, sessions.getValue("b").joinedWithOlderNeighbor)
        assertEquals(false, sessions.getValue("a").joinedWithOlderNeighbor)
    }

    @Test
    fun `toggling a joined boundary force-splits it, and toggling again restores automatic grouping`() = runBlocking {
        register("a", "2026-10-12", "UTC", Instant.parse("2026-10-12T09:00:00Z").toEpochMilli())
        register("b", "2026-10-12", "UTC", Instant.parse("2026-10-12T09:01:00Z").toEpochMilli())
        val vm = MemoryViewModel(container, { noon }, { ZoneId.of("UTC") })
        var latest: MemoryLoadState = MemoryLoadState.Loading
        val collector = launch { vm.state.collect { latest = it } }
        withTimeout(5_000) { while (latest.sessionJoined("b") != true) kotlinx.coroutines.yield() }

        vm.toggleEventBoundary("b", currentlyJoined = true)
        withTimeout(5_000) { while (latest.sessionJoined("b") != false) kotlinx.coroutines.yield() }

        vm.clearEventBoundaryOverride("b")
        withTimeout(5_000) { while (latest.sessionJoined("b") != true) kotlinx.coroutines.yield() }
        collector.cancel()
    }

    @Test
    fun `dismissing surfaces an error instead of crashing when the write fails`() = runBlocking {
        register("s1", "2026-10-12", "UTC", Instant.parse("2026-10-12T09:00:00Z").toEpochMilli())
        container.database.memoryItems().insertAll(listOf(item("i1", "s1", "2026-10-12")))
        val vm = MemoryViewModel(container, { noon }, { ZoneId.of("UTC") })
        container.database.openHelper.writableDatabase.execSQL("DROP TABLE memory_item")

        vm.dismiss("i1")

        assertEquals(R.string.memory_dismiss_failed, withTimeout(5_000) { vm.messages.first() })
    }

    @Test
    fun `correcting surfaces an error instead of crashing when the write fails`() = runBlocking {
        register("s1", "2026-10-12", "UTC", Instant.parse("2026-10-12T09:00:00Z").toEpochMilli())
        container.database.memoryItems().insertAll(listOf(item("i1", "s1", "2026-10-12")))
        val vm = MemoryViewModel(container, { noon }, { ZoneId.of("UTC") })
        container.database.openHelper.writableDatabase.execSQL("DROP TABLE memory_item_correction")

        vm.correct("i1", "The corrected version")

        assertEquals(R.string.memory_correct_failed, withTimeout(5_000) { vm.messages.first() })
    }

    @Test
    fun `toggling an event boundary surfaces an error instead of crashing when the write fails`() = runBlocking {
        register("a", "2026-10-12", "UTC", Instant.parse("2026-10-12T09:00:00Z").toEpochMilli())
        val vm = MemoryViewModel(container, { noon }, { ZoneId.of("UTC") })
        container.database.openHelper.writableDatabase.execSQL("DROP TABLE session_event_override")

        vm.toggleEventBoundary("a", currentlyJoined = false)

        assertEquals(R.string.memory_event_boundary_failed, withTimeout(5_000) { vm.messages.first() })
    }

    @Test
    fun `clearing an event boundary override surfaces an error instead of crashing when the write fails`() = runBlocking {
        register("a", "2026-10-12", "UTC", Instant.parse("2026-10-12T09:00:00Z").toEpochMilli())
        val vm = MemoryViewModel(container, { noon }, { ZoneId.of("UTC") })
        container.database.openHelper.writableDatabase.execSQL("DROP TABLE session_event_override")

        vm.clearEventBoundaryOverride("a")

        assertEquals(R.string.memory_event_boundary_failed, withTimeout(5_000) { vm.messages.first() })
    }

    @Test
    fun `project memory groups open commitments and actions by counterpart`() = runBlocking {
        register("s1", "2026-10-12", "UTC", Instant.parse("2026-10-12T09:00:00Z").toEpochMilli())
        container.database.memoryItems().insertAll(
            listOf(
                item("i1", "s1", "2026-10-12", kind = "commitment", counterpart = "Alex", text = "Send the deck"),
                item("i2", "s1", "2026-10-12", kind = "action", counterpart = "Alex", text = "Follow up"),
                item("i3", "s1", "2026-10-12", kind = "decision", counterpart = "Alex", text = "Not a commitment"),
            ),
        )
        val vm = MemoryViewModel(container, { noon }, { ZoneId.of("UTC") })

        val sections = withTimeout(5_000) { vm.projectMemory.first { it.isNotEmpty() } }

        assertEquals(1, sections.size)
        assertEquals("Alex", sections.single().subject)
        assertEquals(setOf("Send the deck", "Follow up"), sections.single().items.map { it.text }.toSet())
    }

    @Test
    fun `project memory falls back to owner when no counterpart was extracted`() = runBlocking {
        register("s1", "2026-10-12", "UTC", Instant.parse("2026-10-12T09:00:00Z").toEpochMilli())
        container.database.memoryItems().insertAll(
            listOf(item("i1", "s1", "2026-10-12", kind = "action", counterpart = null, owner = "Sam")),
        )
        val vm = MemoryViewModel(container, { noon }, { ZoneId.of("UTC") })

        val sections = withTimeout(5_000) { vm.projectMemory.first { it.isNotEmpty() } }

        assertEquals("Sam", sections.single().subject)
    }

    @Test
    fun `project memory excludes dismissed items and reflects a correction's text`() = runBlocking {
        register("s1", "2026-10-12", "UTC", Instant.parse("2026-10-12T09:00:00Z").toEpochMilli())
        container.database.memoryItems().insertAll(
            listOf(
                item("i1", "s1", "2026-10-12", kind = "commitment", counterpart = "Alex"),
                item("i2", "s1", "2026-10-12", kind = "action", counterpart = "Alex"),
            ),
        )
        container.database.memoryItems().dismiss("i2", 1L)
        val vm = MemoryViewModel(container, { noon }, { ZoneId.of("UTC") })
        vm.correct("i1", "Updated text")

        val sections = withTimeout(5_000) {
            vm.projectMemory.first { it.isNotEmpty() && it.single().items.single().text == "Updated text" }
        }

        assertEquals(1, sections.single().items.size)
        assertEquals(true, sections.single().items.single().isCorrected)
    }

    private fun MemoryLoadState.sessionJoined(sessionId: String): Boolean? = (this as? MemoryLoadState.Content)
        ?.days?.flatMap { it.sessions }?.firstOrNull { it.sessionId == sessionId }?.joinedWithOlderNeighbor

    private fun MemoryLoadState.itemCount(): Int = (this as? MemoryLoadState.Content)
        ?.days?.flatMap { it.sessions }?.flatMap { it.items }?.size ?: -1

    private fun MemoryLoadState.firstItemOrNull() = (this as? MemoryLoadState.Content)
        ?.days?.flatMap { it.sessions }?.flatMap { it.items }?.firstOrNull()

    private fun item(
        id: String,
        sessionId: String,
        memoryDay: String,
        kind: String = "decision",
        text: String = "Original text",
        counterpart: String? = null,
        owner: String? = null,
    ) = com.apercallc.aidailymemory.data.MemoryItemEntity(
        id = id,
        sessionId = sessionId,
        memoryDay = memoryDay,
        kind = kind,
        text = text,
        confidence = "likely",
        quote = null,
        owner = owner,
        due = null,
        direction = null,
        counterpart = counterpart,
        extractionModel = "user-key:m",
        extractionVersion = 1,
        evidenceEpochMs = null,
        dismissedAtEpochMs = null,
        extractedAtEpochMs = 1L,
    )

    private fun reportEntity(memoryDay: String) = com.apercallc.aidailymemory.data.DailyReportEntity(
        id = "report-$memoryDay",
        memoryDay = memoryDay,
        zoneId = "UTC",
        headline = "1 decision",
        summary = "Decided: ship",
        reportJson = "{}",
        generatedAtEpochMs = 1L,
        extractionModel = "user-key:m",
        extractionVersion = 1,
    )
}
