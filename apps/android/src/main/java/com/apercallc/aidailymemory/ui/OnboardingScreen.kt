package com.apercallc.aidailymemory.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme

/** What the first-run walkthrough hands back when it ends. */
enum class OnboardingResult {
    /** Stay offline; go to Today. */
    STAY_OFFLINE,

    /** The person chose to connect an account; open Settings where that is done. */
    CONNECT_ACCOUNT,
}

private enum class Page(
    @StringRes val title: Int,
    @StringRes val body: Int,
) {
    WELCOME(R.string.onboarding_welcome_title, R.string.onboarding_welcome_body),
    WHERE(R.string.onboarding_where_title, R.string.onboarding_where_body),
    ALLOW(R.string.onboarding_allow_title, R.string.onboarding_allow_body),
    READY(R.string.onboarding_ready_title, R.string.onboarding_ready_body),
}

/**
 * The first thing a new install shows: what the app does, where memory lives (offline is the
 * default and needs no account), what permission prompts to expect, and how to start. Skippable
 * at every step; nothing here is required, and every choice is changeable later in Settings.
 */
@Composable
fun OnboardingScreen(onFinish: (OnboardingResult) -> Unit) {
    val spacing = AiDailyMemoryTheme.spacing
    var index by rememberSaveable { mutableIntStateOf(0) }
    var connect by rememberSaveable { mutableStateOf(false) }
    val page = Page.entries[index]
    val last = index == Page.entries.lastIndex
    val finish = { onFinish(if (connect) OnboardingResult.CONNECT_ACCOUNT else OnboardingResult.STAY_OFFLINE) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = spacing.xxl, vertical = spacing.xl),
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = stringResource(R.string.onboarding_step, index + 1, Page.entries.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.md),
            )
            if (!last) {
                TextButton(
                    onClick = finish,
                    modifier = Modifier.heightIn(min = spacing.minTouchTarget),
                ) { Text(stringResource(R.string.onboarding_skip)) }
            }
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            Text(
                text = stringResource(page.title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = stringResource(page.body), style = MaterialTheme.typography.bodyLarge)
            when (page) {
                Page.WHERE -> WhereChoice(connect = connect, onChange = { connect = it })
                Page.ALLOW -> Text(
                    text = stringResource(R.string.onboarding_allow_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Unit
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index > 0) {
                TextButton(
                    onClick = { index-- },
                    modifier = Modifier.heightIn(min = spacing.minTouchTarget),
                ) { Text(stringResource(R.string.nav_back)) }
            }
            Button(
                onClick = { if (last) finish() else index++ },
                modifier = Modifier.weight(1f).heightIn(min = spacing.minTouchTarget),
            ) {
                Text(
                    stringResource(
                        when {
                            !last -> R.string.onboarding_next
                            connect -> R.string.onboarding_finish_connect
                            else -> R.string.onboarding_finish
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun WhereChoice(connect: Boolean, onChange: (Boolean) -> Unit) {
    val spacing = AiDailyMemoryTheme.spacing
    Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Choice(
            selected = !connect,
            title = R.string.onboarding_offline_title,
            body = R.string.onboarding_offline_body,
            onSelect = { onChange(false) },
        )
        Choice(
            selected = connect,
            title = R.string.onboarding_connect_title,
            body = R.string.onboarding_connect_body,
            onSelect = { onChange(true) },
        )
    }
}

@Composable
private fun Choice(selected: Boolean, @StringRes title: Int, @StringRes body: Int, onSelect: () -> Unit) {
    val spacing = AiDailyMemoryTheme.spacing
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.padding(spacing.lg).heightIn(min = spacing.minTouchTarget),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
