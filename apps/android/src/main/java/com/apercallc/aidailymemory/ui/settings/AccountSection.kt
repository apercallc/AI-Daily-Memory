package com.apercallc.aidailymemory.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.net.ConnectError
import com.apercallc.aidailymemory.net.LinkProblem
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme
import java.net.URI
import java.net.URISyntaxException

/** What the account section shows; [host] is null while not connected. */
data class AccountUi(
    val host: String? = null,
    val problem: LinkProblem = LinkProblem.NONE,
    val connecting: Boolean = false,
    val error: ConnectError? = null,
    /** Clips that gave up plus segments that couldn't be read; their audio stays on the phone. */
    val uploadProblems: Int = 0,
    /** A disconnected token the server hasn't revoked yet. */
    val revokePending: Boolean = false,
    /** A disconnected token this phone can't revoke itself (it has to be done server-side). */
    val revokeManual: Boolean = false,
    /** Where memory lives (ADR-0014); null while connected but not yet fetched. */
    val storageMode: String? = null,
    val storageModeUpdating: Boolean = false,
    /** Fetching or changing the mode failed; distinct from "still loading" (mode == null). */
    val storageModeError: StorageModeError? = null,
)

enum class StorageModeError { LOAD_FAILED, CHANGE_FAILED }

/**
 * Connects the phone to the user's account so speech clips can be transcribed (Phase 1).
 * Stateless apart from the text fields. The token field is deliberately not `rememberSaveable`:
 * a secret must not land in saved instance state.
 */
@Composable
fun AccountSection(
    account: AccountUi,
    onConnect: (url: String, token: String, includeBacklog: Boolean) -> Unit,
    onEdit: () -> Unit,
    onDisconnect: () -> Unit,
    onRetryUploads: () -> Unit,
    onStorageModeChange: (String) -> Unit = {},
    onStorageModeRetry: () -> Unit = {},
) {
    val spacing = AiDailyMemoryTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = stringResource(R.string.account_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        if (account.revokePending) Status(stringResource(R.string.account_revoke_pending), isError = false)
        if (account.revokeManual) Status(stringResource(R.string.account_revoke_manual), isError = true)
        if (account.host != null) {
            Connected(account, onDisconnect, onRetryUploads, onStorageModeChange, onStorageModeRetry)
        } else {
            ConnectForm(account, onConnect, onEdit)
        }
    }
}

private val STORAGE_MODES = listOf(
    "phone_only" to R.string.storage_mode_phone_only,
    "reports_cloud" to R.string.storage_mode_reports_cloud,
    "full_cloud" to R.string.storage_mode_full_cloud,
)

@Composable
private fun Connected(
    account: AccountUi,
    onDisconnect: () -> Unit,
    onRetryUploads: () -> Unit,
    onStorageModeChange: (String) -> Unit,
    onStorageModeRetry: () -> Unit,
) {
    val spacing = AiDailyMemoryTheme.spacing
    Text(
        text = stringResource(R.string.account_connected, account.host.orEmpty()),
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = stringResource(R.string.account_upload_rules),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    StorageModePicker(account.storageMode, account.storageModeUpdating, onStorageModeChange)
    when (account.storageModeError) {
        StorageModeError.LOAD_FAILED -> {
            Status(stringResource(R.string.storage_mode_load_failed), isError = true)
            OutlinedButton(
                onClick = onStorageModeRetry,
                modifier = Modifier.heightIn(min = spacing.minTouchTarget),
            ) { Text(stringResource(R.string.storage_mode_retry)) }
        }
        StorageModeError.CHANGE_FAILED ->
            Status(stringResource(R.string.storage_mode_change_failed), isError = true)
        null -> Unit
    }
    if (account.uploadProblems > 0) {
        Status(
            pluralStringResource(R.plurals.account_upload_problems, account.uploadProblems, account.uploadProblems),
            isError = true,
        )
        OutlinedButton(
            onClick = onRetryUploads,
            modifier = Modifier.heightIn(min = spacing.minTouchTarget),
        ) { Text(stringResource(R.string.account_retry_uploads)) }
    }
    OutlinedButton(
        onClick = onDisconnect,
        modifier = Modifier.heightIn(min = spacing.minTouchTarget),
    ) { Text(stringResource(R.string.account_disconnect)) }
}

@Composable
private fun ConnectForm(
    account: AccountUi,
    onConnect: (String, String, Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    val spacing = AiDailyMemoryTheme.spacing
    var url by rememberSaveable { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var includeBacklog by rememberSaveable { mutableStateOf(false) }
    when (account.problem) {
        LinkProblem.REJECTED -> Status(stringResource(R.string.account_rejected), isError = true)
        LinkProblem.UNREADABLE -> Status(stringResource(R.string.account_unreadable), isError = true)
        LinkProblem.NONE -> Text(
            text = stringResource(R.string.account_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        text = stringResource(R.string.account_processing_disclosure),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val urlError = account.error == ConnectError.INVALID_URL
    val tokenError = account.error == ConnectError.INVALID_TOKEN || account.error == ConnectError.REJECTED
    val urlErrorText = stringResource(R.string.account_url_invalid)
    val tokenErrorText = stringResource(
        if (account.error == ConnectError.REJECTED) R.string.account_token_rejected else R.string.account_token_invalid,
    )
    OutlinedTextField(
        value = url,
        onValueChange = {
            url = it
            onEdit()
        },
        label = { Text(stringResource(R.string.account_url)) },
        enabled = !account.connecting,
        singleLine = true,
        isError = urlError,
        supportingText = { if (urlError) Text(urlErrorText) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { if (urlError) error(urlErrorText) },
    )
    OutlinedTextField(
        value = token,
        onValueChange = {
            token = it
            onEdit()
        },
        label = { Text(stringResource(R.string.account_token)) },
        enabled = !account.connecting,
        singleLine = true,
        isError = tokenError,
        supportingText = { if (tokenError) Text(tokenErrorText) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { if (tokenError) error(tokenErrorText) },
    )
    // Off by default: connecting must not quietly upload everything recorded before.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.minTouchTarget)
            .toggleable(
                value = includeBacklog,
                role = Role.Checkbox,
                enabled = !account.connecting,
                onValueChange = { includeBacklog = it },
            ),
    ) {
        Checkbox(checked = includeBacklog, onCheckedChange = null, enabled = !account.connecting)
        Text(stringResource(R.string.account_include_backlog), style = MaterialTheme.typography.bodyMedium)
    }
    when (account.error) {
        ConnectError.UNREACHABLE -> Status(stringResource(R.string.account_unreachable), isError = true)
        ConnectError.SAVE_FAILED -> Status(stringResource(R.string.account_save_failed), isError = true)
        else -> Unit
    }
    Button(
        onClick = { onConnect(url, token, includeBacklog) },
        enabled = !account.connecting,
        modifier = Modifier.heightIn(min = spacing.minTouchTarget),
    ) {
        Text(stringResource(if (account.connecting) R.string.account_checking else R.string.account_connect))
    }
}

/** Where memory lives (ADR-0014). Shown only once the current mode is known; a switch is
 * disabled while a change is in flight so a slow network can't queue up two conflicting ones. */
@Composable
private fun StorageModePicker(mode: String?, updating: Boolean, onChange: (String) -> Unit) {
    val spacing = AiDailyMemoryTheme.spacing
    if (mode == null) return
    Text(
        text = stringResource(R.string.storage_mode_title),
        style = MaterialTheme.typography.titleSmall,
    )
    Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        STORAGE_MODES.forEach { (value, labelRes) ->
            val selected = value == mode
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = spacing.minTouchTarget)
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        enabled = !updating,
                        onClick = { if (!selected) onChange(value) },
                    ),
            ) {
                RadioButton(selected = selected, onClick = null, enabled = !updating)
                Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Announced by TalkBack when it appears, not only when focused. */
@Composable
private fun Status(text: String, isError: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Shown instead of the full URL: enough to recognize the server, nothing more. */
fun hostOf(baseUrl: String): String = try {
    URI(baseUrl).host ?: baseUrl
} catch (_: URISyntaxException) {
    baseUrl
}
