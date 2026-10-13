package com.apercallc.aidailymemory.ui

import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.AiDailyMemoryApplication
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
}
