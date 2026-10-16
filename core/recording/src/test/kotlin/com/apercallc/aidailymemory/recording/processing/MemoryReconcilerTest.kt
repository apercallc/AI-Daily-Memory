package com.apercallc.aidailymemory.recording.processing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryReconcilerTest {
    private fun item(id: String, kind: String = "action", evidenceEpochMs: Long? = 100L) =
        IdentifiedMemoryItem(id, MemoryItemIdentity(kind, evidenceEpochMs))

    @Test
    fun `no previous items carries nothing forward`() {
        val result = MemoryReconciler.reconcile(emptyList(), emptyMap(), listOf(item("new")))

        assertEquals(MemoryReconciliation.NONE, result)
    }

    @Test
    fun `no current items carries nothing forward`() {
        val result = MemoryReconciler.reconcile(listOf(item("old")), emptyMap(), emptyList())

        assertEquals(MemoryReconciliation.NONE, result)
    }

    @Test
    fun `a matching item's correction is reassigned to the new id`() {
        val old = item("old-1")
        val new = item("new-1")

        val result = MemoryReconciler.reconcile(listOf(old), emptyMap(), listOf(new))

        assertEquals(mapOf("old-1" to "new-1"), result.correctionReassignments)
        assertTrue(result.carriedDismissals.isEmpty())
    }

    @Test
    fun `a dismissed item's dismissal carries forward to the new id at its original time`() {
        val old = item("old-1")
        val new = item("new-1")

        val result = MemoryReconciler.reconcile(listOf(old), mapOf("old-1" to 500L), listOf(new))

        assertEquals(mapOf("new-1" to 500L), result.carriedDismissals)
        assertEquals(mapOf("old-1" to "new-1"), result.correctionReassignments)
    }

    @Test
    fun `an unmatched previous item is left out entirely, not guessed at`() {
        val old = item("old-1", kind = "idea")
        val new = item("new-1", kind = "action")

        val result = MemoryReconciler.reconcile(listOf(old), mapOf("old-1" to 500L), listOf(new))

        assertEquals(MemoryReconciliation.NONE, result)
    }

    @Test
    fun `a changed structured field breaks the match even with the same evidence anchor`() {
        val old = IdentifiedMemoryItem("old-1", MemoryItemIdentity("commitment", 100L, due = "2026-01-01"))
        val new = IdentifiedMemoryItem("new-1", MemoryItemIdentity("commitment", 100L, due = "2026-01-02"))

        val result = MemoryReconciler.reconcile(listOf(old), emptyMap(), listOf(new))

        assertEquals(MemoryReconciliation.NONE, result)
    }

    @Test
    fun `items with no evidence anchor still match on their other structured fields`() {
        val old = IdentifiedMemoryItem("old-1", MemoryItemIdentity("action", null, owner = "Sam"))
        val new = IdentifiedMemoryItem("new-1", MemoryItemIdentity("action", null, owner = "Sam"))

        val result = MemoryReconciler.reconcile(listOf(old), emptyMap(), listOf(new))

        assertEquals(mapOf("old-1" to "new-1"), result.correctionReassignments)
    }

    @Test
    fun `matching is one-to-one, never merging two old items into one new one`() {
        val old1 = item("old-1")
        val old2 = item("old-2")
        val new1 = item("new-1")

        val result = MemoryReconciler.reconcile(listOf(old1, old2), emptyMap(), listOf(new1))

        assertEquals(1, result.correctionReassignments.size)
        assertTrue(result.correctionReassignments.containsKey("old-1") || result.correctionReassignments.containsKey("old-2"))
    }

    @Test
    fun `each current item is claimed by at most one previous item`() {
        val old1 = item("old-1")
        val old2 = item("old-2")
        val new1 = item("new-1")
        val new2 = item("new-2")

        val result = MemoryReconciler.reconcile(listOf(old1, old2), emptyMap(), listOf(new1, new2))

        assertEquals(setOf("new-1", "new-2"), result.correctionReassignments.values.toSet())
        assertEquals(2, result.correctionReassignments.values.toSet().size)
    }

    @Test
    fun `several items of different real-world facts all match correctly`() {
        val oldAction = IdentifiedMemoryItem("a-old", MemoryItemIdentity("action", 10L, owner = "Sam"))
        val oldCommitment =
            IdentifiedMemoryItem("c-old", MemoryItemIdentity("commitment", 20L, counterpart = "Alex", direction = "i_owe"))
        val newAction = IdentifiedMemoryItem("a-new", MemoryItemIdentity("action", 10L, owner = "Sam"))
        val newCommitment =
            IdentifiedMemoryItem("c-new", MemoryItemIdentity("commitment", 20L, counterpart = "Alex", direction = "i_owe"))

        val result = MemoryReconciler.reconcile(
            listOf(oldAction, oldCommitment),
            mapOf("c-old" to 900L),
            listOf(newAction, newCommitment),
        )

        assertEquals(mapOf("a-old" to "a-new", "c-old" to "c-new"), result.correctionReassignments)
        assertEquals(mapOf("c-new" to 900L), result.carriedDismissals)
    }

    @Test
    fun `a previous dismissal with no matching current item carries nothing forward`() {
        val old = item("old-1", kind = "idea")

        val result = MemoryReconciler.reconcile(listOf(old), mapOf("old-1" to 500L), listOf(item("new-1", kind = "action")))

        assertEquals(MemoryReconciliation.NONE, result)
    }
}
