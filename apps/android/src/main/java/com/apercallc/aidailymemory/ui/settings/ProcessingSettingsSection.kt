package com.apercallc.aidailymemory.ui.settings

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import com.apercallc.aidailymemory.recording.processing.ConfigGap
import com.apercallc.aidailymemory.ondevice.ModelFailure
import com.apercallc.aidailymemory.ondevice.ModelState
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import com.apercallc.aidailymemory.recording.processing.ProviderPreset
import com.apercallc.aidailymemory.recording.processing.ProviderPresets
import com.apercallc.aidailymemory.processing.ProviderHealthResult
import com.apercallc.aidailymemory.recording.processing.ProviderCostEstimate
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
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
    val transcriptionApiKey: String = "",
    val transcriptionApiUrl: String = "",
    val transcriptionApiUrlInvalid: Boolean = false,
    val extractionApiKey: String = "",
    val extractionApiUrl: String = "",
    val extractionApiUrlInvalid: Boolean = false,
    val extractionModel: String = "",
    /** Which ready-made provider the stored addresses belong to; [ProviderPresets.CUSTOM_ID] if none. */
    val transcriptionPresetId: String = ProviderPresets.CUSTOM_ID,
    val transcriptionModel: String = "",
    val extractionPresetId: String = ProviderPresets.CUSTOM_ID,
    val deleteAudioAfterTranscription: Boolean = true,
    /** What is still missing before processing can run; empty when fully configured. */
    val configGaps: List<ConfigGap> = emptyList(),
    /** Transcription goes to the person's own service instead of the phone's free model. */
    val transcriptionOwnKey: Boolean = false,
    /** Summaries and extracted decisions are on (they need the person's own key). */
    val summaries: Boolean = false,
    /** Download state of the free on-phone speech model. */
    val modelState: ModelState = ModelState.NotInstalled,
    /** Download size of the speech model, in whole megabytes. */
    val modelSizeMb: Int = 40,
    /** Sessions whose processing gave up and can be retried from locally retained evidence. */
    val failedSessions: Int = 0,
    /** Safe, machine-classified reason for the most recent failed session. */
    val failureCode: String? = null,
    val transcriptionHealth: ProviderHealthResult = ProviderHealthResult.IDLE,
    val extractionHealth: ProviderHealthResult = ProviderHealthResult.IDLE,
    val costEstimate: ProviderCostEstimate? = null,
)

/** State for the daily-report sync section. */
data class SyncUi(
    val enabled: Boolean = true,
    val connected: Boolean = false,
    val includeTranscripts: Boolean = false,
    val lastSyncSucceededAtEpochMs: Long? = null,
    val lastSyncFailed: Boolean = false,
    /** Report dates still awaiting server acknowledgement, not a backup guarantee. */
    val pendingDays: List<String> = emptyList(),
    /** Deletions awaiting server acknowledgement, independent of report sync being enabled. */
    val pendingDeletions: Int = 0,
)

/** Events from the processing settings section. */
data class ProcessingActions(
    val onEnableChange: (Boolean) -> Unit = {},
    val onTranscriptionOwnKeyChange: (Boolean) -> Unit = {},
    val onSummariesChange: (Boolean) -> Unit = {},
    val onDownloadModel: () -> Unit = {},
    val onRemoveModel: () -> Unit = {},
    val onTranscriptionApiKeyChange: (String) -> Unit = {},
    val onTranscriptionApiUrlChange: (String) -> Unit = {},
    val onExtractionApiKeyChange: (String) -> Unit = {},
    val onExtractionApiUrlChange: (String) -> Unit = {},
    val onExtractionModelChange: (String) -> Unit = {},
    val onDeleteAudioChange: (Boolean) -> Unit = {},
    val onRetryFailed: () -> Unit = {},
    val onTranscriptionPresetChange: (String) -> Unit = {},
    val onTranscriptionModelChange: (String) -> Unit = {},
    val onExtractionPresetChange: (String) -> Unit = {},
    /** Opens a web page (where to get an API key). */
    val onOpenLink: (String) -> Unit = {},
    val onSaveProvider: () -> Unit = {},
    val onTestTranscriptionProvider: () -> Unit = {},
    val onTestExtractionProvider: () -> Unit = {},
)

/** Events from the sync settings section. */
data class SyncActions(
    val onEnableChange: (Boolean) -> Unit = {},
    val onIncludeTranscriptsChange: (Boolean) -> Unit = {},
    val onRetry: () -> Unit = {},
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
        processing.costEstimate?.let { estimate ->
            ProviderUsageCard(estimate, actions)
        }
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

        if (processing.failedSessions > 0) {
            Column(
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.processing_failed_sessions,
                        processing.failedSessions,
                        processing.failedSessions,
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (processing.failureCode != null) {
                    Text(
                        text = stringResource(processingFailureMessageResource(processing.failureCode)),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            OutlinedButton(
                onClick = actions.onRetryFailed,
                modifier = Modifier.heightIn(min = spacing.minTouchTarget),
            ) { Text(stringResource(R.string.processing_retry_failed)) }
        }
        SettingsCard {
            ToggleRow(
                label = stringResource(R.string.processing_delete_audio),
                sublabel = stringResource(R.string.processing_delete_audio_body),
                checked = processing.deleteAudioAfterTranscription,
                onCheckedChange = actions.onDeleteAudioChange,
            )
        }

        // Transcription: the phone's free model, or the person's own service.
        ProviderCard(title = R.string.processing_transcription) {
            if (!processing.transcriptionOwnKey) {
                SpeechModelRow(processing, actions)
            }
            ToggleRow(
                label = stringResource(R.string.processing_transcription_own),
                sublabel = stringResource(R.string.processing_transcription_own_body),
                checked = processing.transcriptionOwnKey,
                onCheckedChange = actions.onTranscriptionOwnKeyChange,
            )
            if (processing.transcriptionOwnKey) {
                val preset = ProviderPresets.transcription.firstOrNull { it.id == processing.transcriptionPresetId }
                ProviderFields(
                    presets = ProviderPresets.transcription,
                    presetId = processing.transcriptionPresetId,
                    onPresetChange = actions.onTranscriptionPresetChange,
                ) {
                    if (preset != null) {
                        DropdownField(
                            label = stringResource(R.string.processing_model),
                            options = preset.models.map { it to it },
                            selectedId = processing.transcriptionModel,
                            onSelect = actions.onTranscriptionModelChange,
                        )
                        GetKeyLink(preset, actions.onOpenLink)
                    } else {
                        ApiUrlField(
                            value = processing.transcriptionApiUrl,
                            invalid = processing.transcriptionApiUrlInvalid,
                            hint = R.string.processing_transcription_url_hint,
                            onChange = actions.onTranscriptionApiUrlChange,
                        )
                    }
                    ApiKeyField(processing.transcriptionApiKey, actions.onTranscriptionApiKeyChange)
                    ProviderHealthButton(
                        label = R.string.processing_test_transcription,
                        result = processing.transcriptionHealth,
                        enabled = !processing.transcriptionApiUrlInvalid && processing.transcriptionApiKey.isNotBlank(),
                        onClick = actions.onTestTranscriptionProvider,
                    )
                }
            }
        }

        // Summaries: off by default. There is no free summary engine, so they need a key.
        ProviderCard(title = R.string.processing_extraction) {
            ToggleRow(
                label = stringResource(R.string.processing_summaries),
                sublabel = stringResource(R.string.processing_summaries_body),
                checked = processing.summaries,
                onCheckedChange = actions.onSummariesChange,
            )
            if (processing.summaries) {
                val preset = ProviderPresets.summary.firstOrNull { it.id == processing.extractionPresetId }
                ProviderFields(
                    presets = ProviderPresets.summary,
                    presetId = processing.extractionPresetId,
                    onPresetChange = actions.onExtractionPresetChange,
                ) {
                    if (preset != null) {
                        ModelChoice(preset, processing.extractionModel, actions.onExtractionModelChange)
                        GetKeyLink(preset, actions.onOpenLink)
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
                    ProviderHealthButton(
                        label = R.string.processing_test_summary,
                        result = processing.extractionHealth,
                        enabled = !processing.extractionApiUrlInvalid &&
                            processing.extractionApiKey.isNotBlank() && processing.extractionModel.isNotBlank(),
                        onClick = actions.onTestExtractionProvider,
                    )
                }
            }
        }
        if (processing.transcriptionOwnKey || processing.summaries) {
            Text(
                text = stringResource(R.string.processing_own_key_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.processing_provider_test_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (processing.configGaps.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.processing_incomplete),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Button(
                onClick = actions.onSaveProvider,
                modifier = Modifier.heightIn(min = spacing.minTouchTarget),
            ) { Text(stringResource(R.string.processing_save)) }
        }
    }
}

@Composable
private fun ProviderUsageCard(estimate: ProviderCostEstimate, actions: ProcessingActions) {
    val spacing = AiDailyMemoryTheme.spacing
    SettingsCard {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(stringResource(R.string.processing_usage_title), style = MaterialTheme.typography.titleSmall)
            if (estimate.requestCount == 0) {
                Text(
                    stringResource(R.string.processing_usage_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Text(
                pluralStringResource(
                    R.plurals.processing_usage_counts,
                    estimate.requestCount,
                    estimate.requestCount,
                    estimate.audioMs / 60_000,
                    estimate.inputTokens?.toString() ?: stringResource(R.string.processing_usage_unknown_value),
                    estimate.outputTokens?.toString() ?: stringResource(R.string.processing_usage_unknown_value),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            val totalUsd = estimate.totalUsd
            if (estimate.pricingUnknown || totalUsd == null) {
                Text(
                    stringResource(R.string.processing_usage_unknown_cost),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    stringResource(
                        R.string.processing_usage_estimate,
                        formatUsd(totalUsd),
                        formatUsd(estimate.averageDailyUsd ?: 0.0),
                        estimate.usdPerAudioHour?.let(::formatUsd)
                            ?: stringResource(R.string.processing_usage_hour_unavailable),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            estimate.pricingAsOf?.let { date ->
                Text(
                    stringResource(R.string.processing_usage_disclaimer, date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            estimate.pricingSourceUrls.forEach { url ->
                val host = runCatching { java.net.URL(url).host }.getOrNull() ?: url
                TextButton(
                    onClick = { actions.onOpenLink(url) },
                    modifier = Modifier.heightIn(min = spacing.minTouchTarget),
                ) {
                    Text(stringResource(R.string.processing_usage_pricing_source, host))
                }
            }
        }
    }
}

private fun formatUsd(amount: Double): String =
    java.lang.String.format(java.util.Locale.getDefault(), "\$%.4f", amount)

internal fun processingFailureMessageResource(code: String): Int = when (code) {
    "provider_quota_exhausted" -> R.string.processing_provider_quota_exhausted
    "provider_rate_limited" -> R.string.processing_provider_rate_limited
    "provider_auth_failed" -> R.string.processing_provider_auth_failed
    else -> R.string.processing_provider_failed_hint
}

@Composable
private fun ProviderHealthButton(
    @StringRes label: Int,
    result: ProviderHealthResult,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val spacing = AiDailyMemoryTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled && result != ProviderHealthResult.CHECKING,
            modifier = Modifier.heightIn(min = spacing.minTouchTarget),
        ) {
            Text(
                stringResource(
                    if (result == ProviderHealthResult.CHECKING) {
                        R.string.processing_provider_testing
                    } else {
                        label
                    },
                ),
            )
        }
        val message = providerHealthMessageResource(result)
        if (message != null) {
            val isError = result in setOf(
                ProviderHealthResult.AUTH_FAILED,
                ProviderHealthResult.QUOTA_EXHAUSTED,
                ProviderHealthResult.MODEL_UNAVAILABLE,
                ProviderHealthResult.INVALID_CONFIGURATION,
                ProviderHealthResult.ENDPOINT_ERROR,
                ProviderHealthResult.UNSUPPORTED_CHECK,
            )
            Text(
                text = stringResource(message),
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@StringRes
private fun providerHealthMessageResource(result: ProviderHealthResult): Int? = when (result) {
    ProviderHealthResult.IDLE -> null
    ProviderHealthResult.CHECKING -> R.string.processing_provider_testing
    ProviderHealthResult.CONNECTED -> R.string.processing_provider_connected
    ProviderHealthResult.AUTH_FAILED -> R.string.processing_provider_auth_failed
    ProviderHealthResult.QUOTA_EXHAUSTED -> R.string.processing_provider_quota_exhausted
    ProviderHealthResult.RATE_LIMITED -> R.string.processing_provider_rate_limited
    ProviderHealthResult.MODEL_UNAVAILABLE -> R.string.processing_provider_model_unavailable
    ProviderHealthResult.INVALID_CONFIGURATION -> R.string.processing_provider_test_invalid
    ProviderHealthResult.UNSUPPORTED_CHECK -> R.string.processing_provider_test_unsupported
    ProviderHealthResult.ENDPOINT_ERROR -> R.string.processing_provider_test_error
}

/** Provider list followed by whatever fields that provider needs. */
@Composable
private fun ProviderFields(
    presets: List<ProviderPreset>,
    presetId: String,
    onPresetChange: (String) -> Unit,
    fields: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AiDailyMemoryTheme.spacing.sm)) {
        DropdownField(
            label = stringResource(R.string.processing_provider),
            options = presets.map { it.id to it.label } +
                (ProviderPresets.CUSTOM_ID to stringResource(R.string.processing_custom)),
            selectedId = presetId,
            onSelect = onPresetChange,
        )
        fields()
    }
}

/** The free speech model: what it is, its download state, and the button for the next step. */
@Composable
private fun SpeechModelRow(processing: ProcessingUi, actions: ProcessingActions) {
    val spacing = AiDailyMemoryTheme.spacing
    // A mis-tap must never instantly throw away an already-downloaded model, so removal is
    // confirmed first, the same way disconnecting the server account is (AccountSection.kt).
    var confirmingRemove by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = stringResource(R.string.processing_model_free, processing.modelSizeMb),
            style = MaterialTheme.typography.bodyMedium,
        )
        val state = processing.modelState
        when (state) {
            ModelState.Installed -> {
                Text(
                    text = stringResource(R.string.processing_model_ready),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                TextButton(
                    onClick = { confirmingRemove = true },
                    modifier = Modifier.heightIn(min = spacing.minTouchTarget),
                ) { Text(stringResource(R.string.processing_model_remove)) }
                if (confirmingRemove) {
                    AlertDialog(
                        onDismissRequest = { confirmingRemove = false },
                        title = { Text(stringResource(R.string.processing_model_remove_title)) },
                        text = { Text(stringResource(R.string.processing_model_remove_body, processing.modelSizeMb)) },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmingRemove = false
                                actions.onRemoveModel()
                            }) { Text(stringResource(R.string.processing_model_remove)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmingRemove = false }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        },
                    )
                }
            }
            is ModelState.Downloading -> Text(
                text = stringResource(R.string.processing_model_downloading, state.percent),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            ModelState.NotInstalled, is ModelState.Failed -> {
                if (state is ModelState.Failed) {
                    Text(
                        text = stringResource(
                            when (state.reason) {
                                ModelFailure.OFFLINE -> R.string.processing_model_failed_offline
                                ModelFailure.SERVER -> R.string.processing_model_failed_server
                                ModelFailure.CORRUPT -> R.string.processing_model_failed_corrupt
                                ModelFailure.STORAGE -> R.string.processing_model_failed_storage
                            },
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Button(
                    onClick = actions.onDownloadModel,
                    modifier = Modifier.heightIn(min = spacing.minTouchTarget),
                ) { Text(stringResource(R.string.processing_model_download, processing.modelSizeMb)) }
            }
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
            // Decorative: the button already reads as a list, so the glyph must not be announced.
            Text(text = "\u25BE", modifier = Modifier.clearAndSetSemantics {})
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
        Text(
            text = stringResource(R.string.notes_phone_loss_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = AiDailyMemoryTheme.statusColors.warning,
        )
        val lastSyncTime = remember(sync.lastSyncSucceededAtEpochMs) {
            sync.lastSyncSucceededAtEpochMs?.let { epochMs ->
                java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.MEDIUM,
                    java.text.DateFormat.SHORT,
                ).format(java.util.Date(epochMs))
            }
        }
        Text(
            text = lastSyncTime?.let { stringResource(R.string.sync_last_success, it) }
                ?: stringResource(R.string.sync_never_succeeded),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!sync.connected && (sync.enabled || sync.pendingDays.isNotEmpty() || sync.pendingDeletions > 0)) {
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
        if (sync.pendingDays.isNotEmpty()) {
            Text(
                text = stringResource(if (sync.enabled) R.string.sync_pending else R.string.sync_pending_off),
                style = MaterialTheme.typography.bodyMedium,
            )
            // A failed attempt is not proof that every pending report was attempted or failed.
            // Keep exact dates pending until the supplied, acknowledged state changes.
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                sync.pendingDays.forEach { day ->
                    Text(
                        text = stringResource(R.string.sync_pending_day, day),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (sync.pendingDeletions > 0) {
            Column(
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.sync_pending_deletions,
                        sync.pendingDeletions,
                        sync.pendingDeletions,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.sync_pending_deletions_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (sync.lastSyncFailed || sync.pendingDays.isNotEmpty() || sync.pendingDeletions > 0) {
            Text(
                text = stringResource(R.string.sync_retry_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = actions.onRetry,
                enabled = sync.connected && (sync.enabled || sync.pendingDeletions > 0),
                modifier = Modifier.heightIn(min = spacing.minTouchTarget),
            ) { Text(stringResource(R.string.sync_retry)) }
        }
        if (sync.enabled) {
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
        shape = RoundedCornerShape(AiDailyMemoryTheme.spacing.cardCorner),
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
