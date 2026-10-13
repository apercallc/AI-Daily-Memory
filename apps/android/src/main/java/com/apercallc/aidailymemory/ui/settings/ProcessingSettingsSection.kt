package com.apercallc.aidailymemory.ui.settings

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import com.apercallc.aidailymemory.settings.ProcessingSettings
import androidx.compose.foundation.layout.heightIn
import com.apercallc.aidailymemory.recording.processing.ConfigGap
import com.apercallc.aidailymemory.recording.server.ServerMode
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.role
import com.apercallc.aidailymemory.recording.processing.ProviderPreset
import com.apercallc.aidailymemory.recording.processing.ProviderPresets
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    val enabled: Boolean = true,
    val transcriptionProvider: String = ProcessingSettings.PROVIDER_DISABLED,
    val transcriptionApiKey: String = "",
    val transcriptionApiUrl: String = "",
    val transcriptionApiUrlInvalid: Boolean = false,
    val extractionProvider: String = ProcessingSettings.PROVIDER_DISABLED,
    val extractionApiKey: String = "",
    val extractionApiUrl: String = "",
    val extractionApiUrlInvalid: Boolean = false,
    val extractionModel: String = "",
    /** Which ready-made provider the stored addresses belong to; [ProviderPresets.CUSTOM_ID] if none. */
    val transcriptionPresetId: String = ProviderPresets.CUSTOM_ID,
    val transcriptionModel: String = "",
    val extractionPresetId: String = ProviderPresets.CUSTOM_ID,
    val deleteAudioAfterTranscription: Boolean = true,
    /** Own-provider settings still missing; only meaningful while [ownProvider] is on. */
    val configGaps: List<ConfigGap> = emptyList(),
    /** The optional own-provider path (advanced) is switched on. */
    val ownProvider: Boolean = false,
    /** Offline unless an account is connected; processing happens on the hosted server only then. */
    val mode: ServerMode = ServerMode.OFFLINE,
)

/** State for the daily-report sync section. */
data class SyncUi(
    val enabled: Boolean = true,
    val connected: Boolean = false,
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
    val onOwnProviderChange: (Boolean) -> Unit = {},
    val onTranscriptionPresetChange: (String) -> Unit = {},
    val onTranscriptionModelChange: (String) -> Unit = {},
    val onExtractionPresetChange: (String) -> Unit = {},
    /** Opens a web page (where to get an API key). */
    val onOpenLink: (String) -> Unit = {},
    val onTest: () -> Unit = {},
    val onSaveProvider: () -> Unit = {},
)

/** Events from the sync settings section. */
data class SyncActions(
    val onEnableChange: (Boolean) -> Unit = {},
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

        Text(
            text = stringResource(
                if (processing.mode == ServerMode.CONNECTED) {
                    R.string.processing_mode_connected
                } else {
                    R.string.processing_mode_offline
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        OutlinedButton(
            onClick = actions.onTest,
            modifier = Modifier.heightIn(min = spacing.minTouchTarget),
        ) { Text(stringResource(R.string.test_connection)) }
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.processing_delete_audio),
                sublabel = stringResource(R.string.processing_delete_audio_body),
                checked = processing.deleteAudioAfterTranscription,
                onCheckedChange = actions.onDeleteAudioChange,
            )
        }

        // Advanced: bring your own AI provider. Off unless asked for; the hosted server needs no keys.
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.processing_own_provider),
                sublabel = stringResource(R.string.processing_own_provider_body),
                checked = processing.ownProvider,
                onCheckedChange = actions.onOwnProviderChange,
            )
        }
        if (processing.ownProvider) {
            if (processing.configGaps.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.processing_incomplete),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            val transcriptionPreset = ProviderPresets.transcription.firstOrNull { it.id == processing.transcriptionPresetId }
            ProviderCard(title = R.string.processing_transcription) {
                DropdownField(
                    label = stringResource(R.string.processing_provider),
                    options = ProviderPresets.transcription.map { it.id to it.label } +
                        (ProviderPresets.CUSTOM_ID to stringResource(R.string.processing_custom)),
                    selectedId = processing.transcriptionPresetId,
                    onSelect = actions.onTranscriptionPresetChange,
                )
                if (transcriptionPreset != null) {
                    DropdownField(
                        label = stringResource(R.string.processing_model),
                        options = transcriptionPreset.models.map { it to it },
                        selectedId = processing.transcriptionModel,
                        onSelect = actions.onTranscriptionModelChange,
                    )
                    GetKeyLink(transcriptionPreset, actions.onOpenLink)
                } else {
                    ApiUrlField(
                        value = processing.transcriptionApiUrl,
                        invalid = processing.transcriptionApiUrlInvalid,
                        hint = R.string.processing_transcription_url_hint,
                        onChange = actions.onTranscriptionApiUrlChange,
                    )
                }
                ApiKeyField(processing.transcriptionApiKey, actions.onTranscriptionApiKeyChange)
            }
            val extractionPreset = ProviderPresets.summary.firstOrNull { it.id == processing.extractionPresetId }
            ProviderCard(title = R.string.processing_extraction) {
                DropdownField(
                    label = stringResource(R.string.processing_provider),
                    options = ProviderPresets.summary.map { it.id to it.label } +
                        (ProviderPresets.CUSTOM_ID to stringResource(R.string.processing_custom)),
                    selectedId = processing.extractionPresetId,
                    onSelect = actions.onExtractionPresetChange,
                )
                if (extractionPreset != null) {
                    ModelChoice(extractionPreset, processing.extractionModel, actions.onExtractionModelChange)
                    GetKeyLink(extractionPreset, actions.onOpenLink)
                } else {
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
                }
                ApiKeyField(processing.extractionApiKey, actions.onExtractionApiKeyChange)
            }
            Button(
                onClick = actions.onSaveProvider,
                modifier = Modifier.heightIn(min = spacing.minTouchTarget),
            ) { Text(stringResource(R.string.processing_save)) }
        }
    }
}

/** A tap-to-open link to where this provider hands out API keys. */
@Composable
private fun GetKeyLink(preset: ProviderPreset, onOpen: (String) -> Unit) {
    TextButton(
        onClick = { onOpen(preset.keyUrl) },
        modifier = Modifier.heightIn(min = AiDailyMemoryTheme.spacing.minTouchTarget),
    ) { Text(stringResource(R.string.processing_get_key, preset.label)) }
}

/**
 * Model list for a preset plus "Other…" for a model that is not listed (lists go stale). A model
 * already stored that is not in the list shows the text field straight away.
 */
@Composable
private fun ModelChoice(preset: ProviderPreset, model: String, onChange: (String) -> Unit) {
    var other by remember(preset.id) { mutableStateOf(false) }
    val listed = model in preset.models
    val showText = other || (model.isNotBlank() && !listed)
    DropdownField(
        label = stringResource(R.string.processing_model),
        options = preset.models.map { it to it } + (OTHER_MODEL to stringResource(R.string.processing_model_other)),
        selectedId = if (showText) OTHER_MODEL else model,
        onSelect = { id ->
            if (id == OTHER_MODEL) {
                other = true
            } else {
                other = false
                onChange(id)
            }
        },
    )
    if (showText) {
        OutlinedTextField(
            value = model,
            onValueChange = onChange,
            label = { Text(stringResource(R.string.processing_model_name)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
}

private const val OTHER_MODEL = "\u0000other"

/** A full-width button that opens a menu: reads as one control to TalkBack, with its current value. */
@Composable
private fun DropdownField(
    label: String,
    options: List<Pair<String, String>>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.first == selectedId }?.second ?: selectedId
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AiDailyMemoryTheme.spacing.minTouchTarget)
                .semantics { role = Role.DropdownList },
        ) {
            Text(text = "$label: $selected", modifier = Modifier.weight(1f))
            Text(text = "\u25BE")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        open = false
                        onSelect(id)
                    },
                )
            }
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

/** Stateless: the body of the "Daily reports" group (title lives in the parent card). */
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
            if (!sync.connected) {
                Text(
                    text = stringResource(R.string.sync_needs_account),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (sync.lastSyncFailed) {
                Text(
                    text = stringResource(R.string.sync_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            SettingsCard {
                ToggleRow(
                    label = stringResource(R.string.sync_include_transcripts),
                    checked = sync.includeTranscripts,
                    onCheckedChange = actions.onIncludeTranscriptsChange,
                )
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
