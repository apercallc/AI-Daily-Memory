package com.apercallc.aidailymemory.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One item the person asked for out loud ("add milk to the shopping list", ADR-0030). Derived
 * from a transcript line and never edits it: [sentence] is the evidence shown next to the item.
 * Checking off or dismissing is separate state, so the list stays correctable.
 *
 * Re-processing the same transcript inserts nothing new: the unique index on
 * (line, list, item key) ignores repeats, and a dismissed row keeps an item from coming back.
 */
@Entity(
    tableName = "list_item",
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["line_id", "list_name", "item_key"], unique = true),
    ],
)
data class ListItemEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "line_id") val lineId: String,
    @ColumnInfo(name = "memory_day") val memoryDay: String,
    @ColumnInfo(name = "list_name") val listName: String,
    @ColumnInfo(name = "item_key") val itemKey: String,
    val text: String,
    val sentence: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "checked_at_epoch_ms") val checkedAtEpochMs: Long?,
    @ColumnInfo(name = "dismissed_at_epoch_ms") val dismissedAtEpochMs: Long?,
)

@Dao
interface ListItemDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<ListItemEntity>)

    /** Open (not dismissed) items; within a list, unchecked first, newest first. */
    @Query(
        "SELECT * FROM list_item WHERE dismissed_at_epoch_ms IS NULL " +
            "ORDER BY list_name, checked_at_epoch_ms IS NOT NULL, created_at_epoch_ms DESC, item_id",
    )
    fun observeOpen(): Flow<List<ListItemEntity>>

    /** @param at check time, or null to uncheck. */
    @Query("UPDATE list_item SET checked_at_epoch_ms = :at WHERE item_id = :itemId")
    suspend fun setChecked(itemId: String, at: Long?)

    @Query("UPDATE list_item SET dismissed_at_epoch_ms = :at WHERE item_id = :itemId")
    suspend fun dismiss(itemId: String, at: Long)

    /** "Clear done": hides every checked item of one list. Hides, never deletes. */
    @Query(
        "UPDATE list_item SET dismissed_at_epoch_ms = :at WHERE list_name = :listName " +
            "AND checked_at_epoch_ms IS NOT NULL AND dismissed_at_epoch_ms IS NULL",
    )
    suspend fun dismissChecked(listName: String, at: Long)

    /** Retention sweep: derived data follows the memory-day cutoff like memory items do. */
    @Query("DELETE FROM list_item WHERE memory_day < :cutoffDay")
    suspend fun deleteOlderThan(cutoffDay: String): Int
}
