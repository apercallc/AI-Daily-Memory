package com.apercallc.aidailymemory.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.data.ListItemEntity
import com.apercallc.aidailymemory.ui.EmptyState
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme

/** Voice lists (ADR-0030). State down, events up; nothing here decides what belongs on a list. */
@Composable
fun ListsScreen(
    lists: List<ListGroup>?,
    onSetChecked: (ListItemEntity, Boolean) -> Unit,
    onRemove: (ListItemEntity) -> Unit,
    onClearDone: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AiDailyMemoryTheme.spacing
    when {
        lists == null -> Unit
        lists.isEmpty() -> EmptyState(
            icon = R.drawable.ic_empty_memory,
            title = stringResource(R.string.lists_empty_title),
            body = stringResource(R.string.lists_empty_body),
            modifier = modifier,
        )
        else -> LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            lists.forEach { group ->
                item(key = "header-${group.name}") {
                    GroupHeader(group, onClearDone)
                }
                items(group.items, key = { it.itemId }) { item ->
                    ListItemRow(item, onSetChecked, onRemove)
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: ListGroup, onClearDone: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.name.replaceFirstChar { it.titlecase() },
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        if (group.doneCount > 0) {
            TextButton(onClick = { onClearDone(group.name) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(pluralStringResource(R.plurals.lists_clear_done, group.doneCount, group.doneCount))
            }
        }
    }
}

@Composable
private fun ListItemRow(
    item: ListItemEntity,
    onSetChecked: (ListItemEntity, Boolean) -> Unit,
    onRemove: (ListItemEntity) -> Unit,
) {
    val checked = item.checkedAtEpochMs != null
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onSetChecked(item, it) })
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (checked) TextDecoration.LineThrough else null,
                color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            // The evidence: the exact words that put this here.
            Text(
                text = stringResource(R.string.lists_heard, item.sentence),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { onRemove(item) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.lists_remove))
        }
    }
}
