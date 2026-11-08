package com.apercallc.aidailymemory.ui.lists

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.apercallc.aidailymemory.data.ListItemEntity
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class ListsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun item(id: String, checked: Long? = null) = ListItemEntity(
        itemId = id, sessionId = "s", lineId = "l-$id", memoryDay = "2026-10-15", listName = "shopping",
        itemKey = id, text = id, sentence = "Add $id to the shopping list.", createdAtEpochMs = 1,
        checkedAtEpochMs = checked, dismissedAtEpochMs = null,
    )

    private fun show(
        lists: List<ListGroup>?,
        onRemove: (ListItemEntity) -> Unit = {},
        onClear: (String) -> Unit = {},
    ) = compose.setContent {
        AiDailyMemoryTheme {
            ListsScreen(lists, onSetChecked = { _, _ -> }, onRemove = onRemove, onClearDone = onClear)
        }
    }

    @Test
    fun `empty state teaches the voice command`() {
        show(emptyList())
        compose.onNodeWithText("No lists yet").assertExists()
        compose.onNodeWithText("add milk to the shopping list", substring = true).assertExists()
    }

    @Test
    fun `loading shows nothing instead of the empty state`() {
        show(null)
        compose.onNodeWithText("No lists yet").assertDoesNotExist()
    }

    @Test
    fun `an item shows the sentence it came from and can be removed`() {
        val removed = mutableListOf<String>()
        show(listOf(ListGroup("shopping", listOf(item("milk")))), onRemove = { removed += it.itemId })
        compose.onNodeWithText("Shopping").assertExists()
        compose.onNodeWithText("milk").assertExists()
        compose.onNodeWithText("You said: Add milk to the shopping list.").assertExists()
        compose.onNodeWithText("Remove").performClick()
        assertEquals(listOf("milk"), removed)
    }

    @Test
    fun `clear done appears only when something is checked`() {
        val cleared = mutableListOf<String>()
        show(listOf(ListGroup("shopping", listOf(item("milk"), item("eggs", checked = 1)))), onClear = { cleared += it })
        compose.onNodeWithText("Clear 1 done").performClick()
        assertEquals(listOf("shopping"), cleared)
    }

    @Test
    fun `no clear done when nothing is checked`() {
        show(listOf(ListGroup("shopping", listOf(item("milk")))))
        compose.onNodeWithText("Clear 0 done").assertDoesNotExist()
    }
}
