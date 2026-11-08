package com.apercallc.aidailymemory.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apercallc.aidailymemory.AppContainer
import com.apercallc.aidailymemory.data.ListItemDao
import com.apercallc.aidailymemory.data.ListItemEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One named list ("shopping") with its open items, unchecked first. */
data class ListGroup(val name: String, val items: List<ListItemEntity>) {
    val doneCount: Int get() = items.count { it.checkedAtEpochMs != null }
}

/** Voice lists (ADR-0030): shows what the person asked for out loud and lets them tick it off. */
class ListsViewModel(
    private val dao: ListItemDao,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    /** Null while the first read is pending, so the screen does not flash "no lists". */
    val lists: StateFlow<List<ListGroup>?> = dao.observeOpen()
        .map { items -> items.groupBy { it.listName }.map { (name, rows) -> ListGroup(name, rows) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun setChecked(item: ListItemEntity, checked: Boolean) {
        viewModelScope.launch { dao.setChecked(item.itemId, if (checked) now() else null) }
    }

    fun remove(item: ListItemEntity) {
        viewModelScope.launch { dao.dismiss(item.itemId, now()) }
    }

    fun clearDone(listName: String) {
        viewModelScope.launch { dao.dismissChecked(listName, now()) }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ListsViewModel(container.database.listItems()) as T
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
