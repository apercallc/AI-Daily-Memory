package com.apercallc.aidailymemory.ui.ask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme

/** Ask your own memory one question and read one answer (ADR-0030). Answers only repeat memory items. */
@Composable
fun AskCard(
    state: AskState,
    onAsk: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AiDailyMemoryTheme.spacing
    var draft by rememberSaveable { mutableStateOf("") }
    val working = state == AskState.Working
    val submit = { if (draft.isNotBlank() && !working) onAsk(draft) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(AskViewModel.MAX_QUESTION_CHARS) },
                label = { Text(stringResource(R.string.ask_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { submit() }, enabled = draft.isNotBlank() && !working, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.ask_button))
            }
        }
        // Announced to screen readers when the answer arrives.
        Column(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            when (state) {
                AskState.Idle -> Unit
                AskState.Working -> Text(stringResource(R.string.ask_working), style = MaterialTheme.typography.bodyMedium)
                is AskState.Answered -> {
                    Text(state.answer.text, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.ask_clear))
                    }
                }
            }
        }
    }
}
