package com.apercallc.aidailymemory.recording.lists

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListCommandParserTest {

    private fun parse(text: String) = ListCommandParser.parse(text)

    @Test
    fun `add items to the shopping list`() {
        val result = parse("Add milk and eggs to the shopping list.")
        assertEquals(1, result.size)
        assertEquals("shopping", result[0].listName)
        assertEquals(listOf("milk", "eggs"), result[0].items)
        assertEquals("Add milk and eggs to the shopping list.", result[0].sentence)
    }

    @Test
    fun `put items on a named list`() {
        val result = parse("put bananas, oat milk and two avocados on my grocery list")
        assertEquals("grocery", result.single().listName)
        assertEquals(listOf("bananas", "oat milk", "two avocados"), result.single().items)
    }

    @Test
    fun `list name defaults to shopping when only the list is named`() {
        assertEquals("shopping", parse("add batteries to the list").single().listName)
        assertEquals("shopping", parse("add batteries to my list").single().listName)
    }

    @Test
    fun `groceries and grocery collapse to one list name`() {
        assertEquals("grocery", parse("add rice to the groceries list").single().listName)
    }

    @Test
    fun `multi word list names are kept and lowercased`() {
        assertEquals("hardware store", parse("Add screws to the Hardware Store list").single().listName)
    }

    @Test
    fun `finds commands in the middle of other speech`() {
        val result = parse("Okay so that went well. Add coffee to the shopping list. Anyway, back to the plan.")
        assertEquals(listOf("coffee"), result.single().items)
        assertEquals("Add coffee to the shopping list.", result.single().sentence)
    }

    @Test
    fun `finds several commands`() {
        val result = parse("Add milk to the shopping list. Put a drill on the hardware list.")
        assertEquals(listOf("shopping", "hardware"), result.map { it.listName })
    }

    @Test
    fun `casual need statements create nothing`() {
        assertTrue(parse("We need milk.").isEmpty())
        assertTrue(parse("I need to get this report done.").isEmpty())
        assertTrue(parse("We should buy a new couch someday.").isEmpty())
    }

    @Test
    fun `talking about a list is not a command`() {
        assertTrue(parse("The shopping list is on the fridge.").isEmpty())
        assertTrue(parse("Did you add milk to the shopping list?").isEmpty())
        assertTrue(parse("I didn't add milk to the shopping list.").isEmpty())
    }

    @Test
    fun `empty and blank input`() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("   ").isEmpty())
        assertTrue(parse("add to the shopping list").isEmpty())
    }

    @Test
    fun `items are trimmed deduplicated and bounded`() {
        val dup = parse("add milk, Milk and  eggs  to the shopping list").single()
        assertEquals(listOf("milk", "eggs"), dup.items)

        val long = "x".repeat(200)
        assertTrue(parse("add $long to the shopping list").isEmpty())

        val many = (1..25).joinToString(", ") { "item$it" }
        assertEquals(ListCommandParser.MAX_ITEMS_PER_COMMAND, parse("add $many to the shopping list").single().items.size)
    }

    @Test
    fun `runaway sentences are skipped rather than parsed`() {
        val runaway = (1..80).joinToString(", ") { "item$it" }
        assertTrue(parse("add $runaway to the shopping list").isEmpty())
    }

    @Test
    fun `list names that are too long are ignored`() {
        assertTrue(parse("add milk to the one two three four five list").isEmpty())
    }

    @Test
    fun `normalized key ignores case and spacing`() {
        assertEquals(ListCommandParser.key("  Oat   Milk "), ListCommandParser.key("oat milk"))
    }
}
