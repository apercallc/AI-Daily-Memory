package com.apercallc.aidailymemory.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer as LayoutSpacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme

/** State for the processing settings section. */
data class ProcessingUi(
    val enabled: Boolean = false,
    val transcriptionProvider: String = "disabled",
    val transcriptionApiKey: String = "",
    val transcriptionApiUrl: String = "",
    val extractionProvider: String = "disabled",
    val extractionApiKey: String = "",
    val extractionApiUrl: String = "",
    val extractionModel: String = "",
    val deleteAudioAfterTranscription: Boolean = true,
)

/** State for the sync settings section. */
data class SyncUi(
    val enabled: Boolean = false,
    val url: String = "",
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

/** Stateless: renders the processing and sync settings sections. */
@Composable
fun ProcessingSettingsSection(
    processing: ProcessingUi,
    actions: ProcessingActions,
    modifier: Modifier = Modifier,
) {
    val spacing = AiDailyMemoryTheme.spacing
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        HorizontalDivider()

        // Processing section
        Text(
            text = stringResource(R.string.processing_section),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.processing_explainer),
            style = MaterialTheme.typography.bodySmall,
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
        }
        if (processing.enabled) {
            // Transcription provider
            SettingsCard {
                Column {
                    Text(
                        text = stringResource(R.string.processing_transcription),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(spacing.md),
                    )
                    ProviderSelector(
                        selected = processing.transcriptionProvider,
                        onSelect = actions.onTranscriptionProviderChange,
                    )
                    if (processing.transcriptionProvider == "user-api") {
                        Column(modifier = Modifier.padding(spacing.md)) {
                            OutlinedTextField(
                                value = processing.transcriptionApiUrl,
                                onValueChange = actions.onTranscriptionApiUrlChange,
                                label = { Text(stringResource(R.string.processing_api_url)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer()
                            OutlinedTextField(
                                value = processing.transcriptionApiKey,
                                onValueChange = actions.onTranscriptionApiKeyChange,
                                label = { Text(stringResource(R.string.processing_api_key)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                            )
                            Text(
                                text = stringResource(R.string.processing_api_key_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = spacing.xs),
                            )
                        }
                    }
                }
            }

            // Extraction provider
            SettingsCard {
                Column {
                    Text(
                        text = stringResource(R.string.processing_extraction),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(spacing.md),
                    )
                    ProviderSelector(
                        selected = processing.extractionProvider,
                        onSelect = actions.onExtractionProviderChange,
                    )
                    if (processing.extractionProvider == "user-api") {
                        Column(modifier = Modifier.padding(spacing.md)) {
                            OutlinedTextField(
                                value = processing.extractionApiUrl,
                                onValueChange = actions.onExtractionApiUrlChange,
                                label = { Text(stringResource(R.string.processing_api_url)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer()
                            OutlinedTextField(
                                value = processing.extractionModel,
                                onValueChange = actions.onExtractionModelChange,
                                label = { Text(stringResource(R.string.processing_model)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer()
                            OutlinedTextField(
                                value = processing.extractionApiKey,
                                onValueChange = actions.onExtractionApiKeyChange,
                                label = { Text(stringResource(R.string.processing_api_key)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                            )
                            Text(
                                text = stringResource(R.string.processing_api_key_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = spacing.xs),
                            )
                        }
                    }
                }
            }

            // Audio deletion toggle
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
}

/** Stateless: renders the sync settings section. */
@Composable
fun SyncSettingsSection(
    sync: SyncUi,
    actions: SyncActions,
    modifier: Modifier = Modifier,
) {
    val spacing = AiDailyMemoryTheme.spacing
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.lg),
    ) {
        HorizontalDivider()

        Text(
            text = stringResource(R.string.sync_section),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.sync_explainer),
            style = MaterialTheme.typography.bodySmall,
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
                Column(modifier = Modifier.padding(spacing.md)) {
                    OutlinedTextField(
                        value = sync.url,
                        onValueChange = actions.onUrlChange,
                        label = { Text(stringResource(R.string.sync_url)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer()
                    OutlinedTextField(
                        value = sync.token,
                        onValueChange = actions.onTokenChange,
                        label = { Text(stringResource(R.string.sync_token)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Spacer()
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
private fun ProviderSelector(selected: String, onSelect: (String) -> Unit) {
    val spacing = AiDailyMemoryTheme.spacing
    val options = listOf(
        "disabled" to R.string.processing_provider_disabled,
        "on-device" to R.string.processing_provider_on_device,
        "user-api" to R.string.processing_provider_user_api,
    )
    Column(
        modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        options.forEach { (value, labelRes) ->
            val available = value != "on-device"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = available) { onSelect(value) }
                    .padding(vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                androidx.compose.material3.RadioButton(
                    selected = selected == value,
                    onClick = if (available) ({ onSelect(value) }) else null,
                    enabled = available,
                )
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
private fun Spacer() {
    LayoutSpacer(modifier = Modifier.padding(top = AiDailyMemoryTheme.spacing.sm))
}