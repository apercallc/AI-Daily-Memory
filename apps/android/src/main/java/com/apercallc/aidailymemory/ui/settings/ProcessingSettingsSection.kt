package com.apercallc.aidailymemory.ui.settings

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import com.apercallc.aidailymemory.settings.ProcessingSettings
import androidx.compose.foundation.layout.heightIn
import com.apercallc.aidailymemory.recording.processing.ConfigGap
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme

/** State for the processing settings section. */
data class ProcessingUi(
    val enabled: Boolean = false,
    val transcriptionProvider: String = ProcessingSettings.PROVIDER_DISABLED,
    val transcriptionApiKey: String = "",
    val transcriptionApiUrl: String = "",
    val transcriptionApiUrlInvalid: Boolean = false,
    val extractionProvider: String = ProcessingSettings.PROVIDER_DISABLED,
    val extractionApiKey: String = "",
    val extractionApiUrl: String = "",
    val extractionApiUrlInvalid: Boolean = false,
    val extractionModel: String = "",
    val deleteAudioAfterTranscription: Boolean = true,
    /** Settings still missing; non-empty while enabled means sessions wait, not fail. */
    val configGaps: List<ConfigGap> = emptyList(),
)

/** State for the sync settings section. */
data class SyncUi(
    val enabled: Boolean = false,
    val url: String = "",
    val urlInvalid: Boolean = false,
    val token: String = "",
    val includeTranscripts: Boolean = false,
    val lastSyncEpochMs: Long? = null,
    val lastSyncFailed: Boolean = false,
)

/** Events from the processing settings section. */
data class ProcessingActions(
    val onEnableChange: (Boolean) -> Unit = {},
    val onTranscriptionProviderChange: (String) -> Unit = {},
    val onTranscriptionApiKeyChange: (String) -> Unit = {},
    val onTranscriptionApiUrlChange: (String) -> Unit = {},
    val onExtractionProviderChange: (String) -> Unit = {},
    val onExtractionApiKeyChange: (String) -> Unit = {},
    val onExtractionApiUrlChange: (String) -> Unit = {},
    val onExtractionModelChange: (String) -> Unit = {},
    val onDeleteAudioChange: (Boolean) -> Unit = {},
)

/** Events from the sync settings section. */
data class SyncActions(
    val onEnableChange: (Boolean) -> Unit = {},
    val onUrlChange: (String) -> Unit = {},
    val onTokenChange: (String) -> Unit = {},
    val onIncludeTranscriptsChange: (Boolean) -> Unit = {},
)

/**
 * Stateless: the body of the "Memory processing" group. The group's title, summary and
 * expand/collapse live in the parent card; this renders what is inside it.
 */
@Composable
fun ProcessingSettingsSection(
    processing: ProcessingUi,
    actions: ProcessingActions,
    modifier: Modifier = Modifier,
) {
    val spacing = AiDailyMemoryTheme.spacing
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Text(
            text = stringResource(R.string.processing_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.processing_enabled),
                checked = processing.enabled,
                onCheckedChange = actions.onEnableChange,
            )
        }
        if (!processing.enabled) {
            Text(
                text = stringResource(R.string.processing_disabled_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        if (processing.configGaps.isNotEmpty()) {
            Text(
                text = stringResource(R.string.processing_incomplete),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        ProviderCard(title = R.string.processing_transcription) {
            ApiUrlField(
                value = processing.transcriptionApiUrl,
                invalid = processing.transcriptionApiUrlInvalid,
                hint = R.string.processing_transcription_url_hint,
                onChange = actions.onTranscriptionApiUrlChange,
            )
            ApiKeyField(processing.transcriptionApiKey, actions.onTranscriptionApiKeyChange)
        }
        ProviderCard(title = R.string.processing_extraction) {
            ApiUrlField(
                value = processing.extractionApiUrl,
                invalid = processing.extractionApiUrlInvalid,
                hint = R.string.processing_extraction_url_hint,
                onChange = actions.onExtractionApiUrlChange,
            )
            OutlinedTextField(
                value = processing.extractionModel,
                onValueChange = actions.onExtractionModelChange,
                label = { Text(stringResource(R.string.processing_model)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            ApiKeyField(processing.extractionApiKey, actions.onExtractionApiKeyChange)
        }
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.processing_delete_audio),
                sublabel = stringResource(R.string.processing_delete_audio_body),
                checked = processing.deleteAudioAfterTranscription,
                onCheckedChange = actions.onDeleteAudioChange,
            )
        }
    }
}

@Composable
private fun ProviderCard(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
    val spacing = AiDailyMemoryTheme.spacing
    SettingsCard {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(text = stringResource(title), style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun ApiUrlField(value: String, invalid: Boolean, @StringRes hint: Int, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.processing_api_url)) },
        isError = invalid,
        supportingText = {
            Text(stringResource(if (invalid) R.string.url_must_be_https else hint))
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
private fun ApiKeyField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.processing_api_key)) },
        supportingText = { Text(stringResource(R.string.processing_api_key_hint)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
    )
}

/** Stateless: the body of the "Send to your own server" group (title lives in the parent card). */
@Composable
fun SyncSettingsSection(
    sync: SyncUi,
    actions: SyncActions,
    modifier: Modifier = Modifier,
) {
    val spacing = AiDailyMemoryTheme.spacing
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Text(
            text = stringResource(R.string.sync_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.sync_enabled),
                checked = sync.enabled,
                onCheckedChange = actions.onEnableChange,
            )
        }
        if (sync.enabled) {
            SettingsCard {
                Column(
                    modifier = Modifier.padding(spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    if (sync.lastSyncFailed) {
                        Text(
                            text = stringResource(R.string.sync_failed),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    OutlinedTextField(
                        value = sync.url,
                        onValueChange = actions.onUrlChange,
                        label = { Text(stringResource(R.string.sync_url)) },
                        isError = sync.urlInvalid,
                        supportingText = if (sync.urlInvalid) {
                            { Text(stringResource(R.string.url_must_be_https)) }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = sync.token,
                        onValueChange = actions.onTokenChange,
                        label = { Text(stringResource(R.string.sync_token)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    ToggleRow(
                        label = stringResource(R.string.sync_include_transcripts),
                        checked = sync.includeTranscripts,
                        onCheckedChange = actions.onIncludeTranscriptsChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        content()
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    sublabel: String? = null,
) {
    val spacing = AiDailyMemoryTheme.spacing
    // The whole row toggles, so the label is part of the touch target and TalkBack reads one
    // switch with its label instead of a bare control.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.minTouchTarget)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
            )
            sublabel?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
