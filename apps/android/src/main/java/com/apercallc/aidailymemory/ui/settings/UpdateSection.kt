package com.apercallc.aidailymemory.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.update.UpdateState
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme


/**
 * Self-update section: version line, check button, download/install. Auto-checked once per
 * app open; this is also where the capture screen's "Update available" snackbar lands.
 */
@Composable
fun UpdateSection(
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AiDailyMemoryTheme.spacing
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        Text(
            text = stringResource(R.string.update_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = statusText(state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state) {
                is UpdateState.Available -> Button(
                    onClick = onDownload,
                    modifier = Modifier.heightIn(min = spacing.minTouchTarget),
                ) { Text(stringResource(R.string.update_download)) }

                is UpdateState.Downloading -> OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.heightIn(min = spacing.minTouchTarget),
                ) { Text(stringResource(R.string.update_downloading)) }

                is UpdateState.Ready -> Button(
                    onClick = onInstall,
                    modifier = Modifier.heightIn(min = spacing.minTouchTarget),
                ) { Text(stringResource(R.string.update_install)) }

                is UpdateState.InstallFailed -> Button(
                    onClick = onInstall,
                    modifier = Modifier.heightIn(min = spacing.minTouchTarget),
                ) { Text(stringResource(R.string.update_install)) }

                is UpdateState.DownloadFailed -> Button(
                    onClick = onDownload,
                    modifier = Modifier.heightIn(min = spacing.minTouchTarget),
                ) { Text(stringResource(R.string.update_download_retry)) }

                else -> TextButton(
                    onClick = onCheck,
                    enabled = state != UpdateState.Checking,
                    modifier = Modifier.heightIn(min = spacing.minTouchTarget),
                ) { Text(stringResource(R.string.update_check)) }
            }
        }
    }
}

@Composable
private fun statusText(state: UpdateState): String = when (state) {
    is UpdateState.Idle -> stringResource(R.string.update_idle)
    is UpdateState.Checking -> stringResource(R.string.update_checking)
    is UpdateState.UpToDate -> stringResource(R.string.update_up_to_date, state.versionName)
    is UpdateState.Available -> stringResource(R.string.update_available, state.release.versionName)
    is UpdateState.Downloading -> stringResource(R.string.update_downloading_hint)
    is UpdateState.Ready -> stringResource(R.string.update_ready, state.release.versionName)
    is UpdateState.InstallFailed -> stringResource(R.string.update_install_failed)
    is UpdateState.CheckFailed -> stringResource(R.string.update_check_failed)
    is UpdateState.DownloadFailed -> stringResource(R.string.update_download_failed)
}
