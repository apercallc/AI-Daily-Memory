package com.apercallc.aidailymemory.ui.lists

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.data.CaptureDatabase
import com.apercallc.aidailymemory.data.ListItemEntity
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
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ListsViewModelTest {
    private lateinit var db: CaptureDatabase
    private lateinit var model: ListsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CaptureDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        model = ListsViewModel(db.listItems(), now = { 99L })
    }

    @After
    fun tearDown() = runBlocking {
        model.viewModelScope.coroutineContext.job.cancelAndJoin()
        db.close()
        Dispatchers.resetMain()
    }

    private fun item(id: String, list: String, created: Long, checked: Long? = null) = ListItemEntity(
        itemId = id, sessionId = "s", lineId = "l-$id", memoryDay = "2026-10-15", listName = list,
        itemKey = id, text = id, sentence = "Add $id to the $list list.", createdAtEpochMs = created,
        checkedAtEpochMs = checked, dismissedAtEpochMs = null,
    )

    private suspend fun current(): List<ListGroup> = await { true }

    /** Room delivers writes asynchronously, so wait for the state that proves the write landed. */
    private suspend fun await(until: (List<ListGroup>) -> Boolean): List<ListGroup> =
        withTimeout(5_000) { model.lists.first { it != null && until(it) }!! }

    @Test
    fun `items are grouped by list with unchecked first and newest first`() = runBlocking {
        db.listItems().insertAll(
            listOf(
                item("old", "shopping", 1),
                item("done", "shopping", 3, checked = 5),
                item("new", "shopping", 2),
                item("screws", "hardware", 1),
            ),
        )
        val groups = current()
        assertEquals(listOf("hardware", "shopping"), groups.map { it.name })
        assertEquals(listOf("new", "old", "done"), groups.last().items.map { it.itemId })
        assertEquals(1, groups.last().doneCount)
    }

    @Test
    fun `checking and unchecking an item records and clears the time`() = runBlocking {
        db.listItems().insertAll(listOf(item("milk", "shopping", 1)))
        model.setChecked(current().single().items.single(), true)
        val checked = await { it.singleOrNull()?.items?.singleOrNull()?.checkedAtEpochMs != null }
        assertEquals(99L, checked.single().items.single().checkedAtEpochMs)
        model.setChecked(checked.single().items.single(), false)
        val unchecked = await { it.singleOrNull()?.items?.singleOrNull()?.checkedAtEpochMs == null }
        assertNull(unchecked.single().items.single().checkedAtEpochMs)
    }

    @Test
    fun `removing an item hides it`() = runBlocking {
        db.listItems().insertAll(listOf(item("milk", "shopping", 1), item("eggs", "shopping", 2)))
        model.remove(current().single().items.first { it.itemId == "milk" })
        val left = await { it.singleOrNull()?.items?.size == 1 }
        assertEquals(listOf("eggs"), left.single().items.map { it.itemId })
    }

    @Test
    fun `clear done hides only checked items of that list`() = runBlocking {
        db.listItems().insertAll(
            listOf(
                item("a", "shopping", 1, checked = 1),
                item("b", "shopping", 2),
                item("c", "hardware", 3, checked = 1),
            ),
        )
        model.clearDone("shopping")
        val groups = await { g -> g.firstOrNull { it.name == "shopping" }?.items?.size == 1 }
        assertEquals(listOf("hardware", "shopping"), groups.map { it.name })
        assertEquals(listOf("b"), groups.last().items.map { it.itemId })
        assertEquals(listOf("c"), groups.first().items.map { it.itemId })
    }

    @Test
    fun `no lists is an empty list not a missing one`() = runBlocking {
        assertEquals(emptyList(), current())
    }
}
