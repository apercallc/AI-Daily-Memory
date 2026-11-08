package com.apercallc.aidailymemory.ui.ask

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.data.CaptureDatabase
import com.apercallc.aidailymemory.data.MemoryItemCorrectionEntity
import com.apercallc.aidailymemory.data.MemoryItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
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
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AskViewModelTest {
    private lateinit var db: CaptureDatabase
    private lateinit var model: AskViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CaptureDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        model = AskViewModel(
            db.memoryItems(),
            db.memoryCorrections(),
            today = { LocalDate.of(2026, 10, 14) },
            io = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() = runBlocking {
        model.viewModelScope.coroutineContext.job.cancelAndJoin()
        db.close()
        Dispatchers.resetMain()
    }

    private fun item(id: String, text: String, day: String = "2026-10-14", kind: String = "decision", dismissed: Long? = null) =
        MemoryItemEntity(
            id = id, sessionId = "s", memoryDay = day, kind = kind, text = text, confidence = "likely",
            quote = null, owner = null, due = null, direction = null, counterpart = null,
            extractionModel = "m", extractionVersion = 1, evidenceEpochMs = null,
            dismissedAtEpochMs = dismissed, extractedAtEpochMs = 1,
        )

    private suspend fun answered(): AskState.Answered =
        withTimeout(5_000) { model.state.first { it is AskState.Answered } as AskState.Answered }

    @Test
    fun `a question is answered from the person's own items and cites them`() = runBlocking {
        db.memoryItems().insertAll(listOf(item("a", "Chose Postgres")))
        model.ask("What did I decide today?")
        val state = answered()
        assertEquals(listOf("a"), state.answer.citedIds)
        assertEquals("What did I decide today?", state.question)
    }

    @Test
    fun `dismissed items are never read back`() = runBlocking {
        db.memoryItems().insertAll(listOf(item("a", "Secret layoffs plan", dismissed = 5)))
        model.ask("What did I decide today?")
        assertTrue(answered().answer.abstained)
    }

    @Test
    fun `the corrected wording is what is read back`() = runBlocking {
        db.memoryItems().insertAll(listOf(item("a", "Vague thing")))
        db.memoryCorrections().insert(MemoryItemCorrectionEntity("c1", "a", "Clear thing about the launch", 9))
        model.ask("What did I decide today?")
        assertTrue("Clear thing about the launch" in answered().answer.text)
    }

    @Test
    fun `an item with an unreadable day is skipped, not fatal`() = runBlocking {
        db.memoryItems().insertAll(listOf(item("bad", "Broken day", day = "not-a-date"), item("ok", "Fine")))
        model.ask("What did I decide today?")
        assertEquals(listOf("ok"), answered().answer.citedIds)
    }

    @Test
    fun `a blank question does nothing and clear returns to idle`() = runBlocking {
        model.ask("   ")
        assertIs<AskState.Idle>(model.state.value)
        model.ask("anything at all")
        answered()
        model.clear()
        assertIs<AskState.Idle>(model.state.value)
        Unit // JUnit4 needs a Unit test method; assertIs returns its argument.
    }

    @Test
    fun `a new question replaces the old answer`() = runBlocking {
        db.memoryItems().insertAll(listOf(item("a", "Chose Postgres")))
        model.ask("What did I decide today?")
        answered()
        model.ask("quantum computing")
        assertEquals("quantum computing", answered().question)
    }
}
