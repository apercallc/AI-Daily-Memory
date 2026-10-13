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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
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
import com.apercallc.aidailymemory.recording.server.HostedServer
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme
import java.net.URI
import java.net.URISyntaxException

/** What the account section shows; [host] is null while not connected (offline). */
data class AccountUi(
    val host: String? = null,
    /** The server the next sign-in goes to: hosted by default, or the person's own. */
    val serverUrl: String = HostedServer.BASE_URL,
    /** What the person typed in the server field when it is not a usable address yet. */
    val serverDraft: String? = null,
    val problem: LinkProblem = LinkProblem.NONE,
    val connecting: Boolean = false,
    /** "Test connection" is running. */
    val testing: Boolean = false,
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

/** Everything the account section can ask for, so the signature stays readable. */
data class AccountCallbacks(
    val onConnectToken: (token: String, includeBacklog: Boolean) -> Unit = { _, _ -> },
    val onSignUp: (email: String, password: String, includeBacklog: Boolean) -> Unit = { _, _, _ -> },
    val onLogIn: (email: String, password: String, includeBacklog: Boolean) -> Unit = { _, _, _ -> },
    val onEdit: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onRetryUploads: () -> Unit = {},
    val onTest: () -> Unit = {},
    val onStorageModeChange: (String) -> Unit = {},
    val onStorageModeRetry: () -> Unit = {},
    val onOpenWebsite: () -> Unit = {},
    /** Text typed in the server field; blank returns to the hosted server. */
    val onServerChange: (String) -> Unit = {},
    /** Text currently on the clipboard, for the Paste button; null when it has none. */
    val clipboardText: () -> String? = { null },
)

/**
 * The one place that talks to a server, and there is only one at a time. A phone is either Offline
 * (nothing leaves it) or Connected to [HostedServer], or, under Advanced, to the person's own copy
 * of the open-source backend. Connecting
 * takes an email and password (or a token pasted from the website), checks it with the server,
 * and saves it. Passwords and tokens are held in plain `remember`, never `rememberSaveable`: a
 * secret must not land in saved instance state.
 */
@Composable
fun AccountSection(account: AccountUi, callbacks: AccountCallbacks) {
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
            Connected(account, callbacks)
        } else {
            Offline(account, callbacks)
        }
    }
}

private val STORAGE_MODES = listOf(
    "phone_only" to R.string.storage_mode_phone_only,
    "reports_cloud" to R.string.storage_mode_reports_cloud,
    "full_cloud" to R.string.storage_mode_full_cloud,
)

@Composable
private fun Connected(account: AccountUi, callbacks: AccountCallbacks) {
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
    OutlinedButton(
        onClick = callbacks.onTest,
        enabled = !account.testing,
        modifier = Modifier.heightIn(min = spacing.minTouchTarget),
    ) {
        Text(stringResource(if (account.testing) R.string.test_running else R.string.test_connection))
    }
    StorageModePicker(account.storageMode, account.storageModeUpdating, callbacks.onStorageModeChange)
    when (account.storageModeError) {
        StorageModeError.LOAD_FAILED -> {
            Status(stringResource(R.string.storage_mode_load_failed), isError = true)
            OutlinedButton(
                onClick = callbacks.onStorageModeRetry,
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
            onClick = callbacks.onRetryUploads,
            modifier = Modifier.heightIn(min = spacing.minTouchTarget),
        ) { Text(stringResource(R.string.account_retry_uploads)) }
    }
    OutlinedButton(
        onClick = callbacks.onDisconnect,
        modifier = Modifier.heightIn(min = spacing.minTouchTarget),
    ) { Text(stringResource(R.string.account_disconnect)) }
}

/**
 * Not connected. Says plainly that Offline is a fine choice, then the three steps to connect.
 */
@Composable
private fun Offline(account: AccountUi, callbacks: AccountCallbacks) {
    val spacing = AiDailyMemoryTheme.spacing
    var creating by rememberSaveable { mutableStateOf(true) }
    var useToken by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var includeBacklog by rememberSaveable { mutableStateOf(false) }
    val busy = account.connecting

    when (account.problem) {
        LinkProblem.REJECTED -> Status(stringResource(R.string.account_rejected), isError = true)
        LinkProblem.UNREADABLE -> Status(stringResource(R.string.account_unreadable), isError = true)
        LinkProblem.OTHER_SERVER -> Status(stringResource(R.string.account_other_server), isError = true)
        LinkProblem.NONE -> Unit
    }
    Text(
        text = stringResource(R.string.account_offline_status),
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = stringResource(R.string.account_steps),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = if (HostedServer.isHostedUrl(account.serverUrl)) {
            stringResource(R.string.account_processing_disclosure)
        } else {
            stringResource(R.string.account_processing_disclosure_custom, hostOf(account.serverUrl))
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val isHosted = HostedServer.isHostedUrl(account.serverUrl)
    if (isHosted) {
        TextButton(
            onClick = callbacks.onOpenWebsite,
            modifier = Modifier.heightIn(min = spacing.minTouchTarget),
        ) { Text(stringResource(R.string.account_open_website, HostedServer.HOST)) }
    }
    ServerChoice(account, isHosted, callbacks.onServerChange, enabled = !busy)

    val error = account.error
    if (useToken) {
        val tokenError = error == ConnectError.INVALID_TOKEN || error == ConnectError.REJECTED
        val tokenErrorText = stringResource(
            if (error == ConnectError.REJECTED) R.string.account_token_rejected else R.string.account_token_invalid,
        )
        OutlinedTextField(
            value = token,
            onValueChange = {
                token = it
                callbacks.onEdit()
            },
            label = { Text(stringResource(R.string.account_token)) },
            enabled = !busy,
            singleLine = true,
            isError = tokenError,
            supportingText = { if (tokenError) Text(tokenErrorText) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { if (tokenError) error(tokenErrorText) },
        )
        TextButton(
            onClick = {
                callbacks.clipboardText()?.let {
                    token = it
                    callbacks.onEdit()
                }
            },
            enabled = !busy,
            modifier = Modifier.heightIn(min = spacing.minTouchTarget),
        ) { Text(stringResource(R.string.account_paste)) }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            FilterChip(
                selected = creating,
                onClick = { creating = true; callbacks.onEdit() },
                label = { Text(stringResource(R.string.account_create)) },
                modifier = Modifier.heightIn(min = spacing.minTouchTarget),
            )
            FilterChip(
                selected = !creating,
                onClick = { creating = false; callbacks.onEdit() },
                label = { Text(stringResource(R.string.account_sign_in)) },
                modifier = Modifier.heightIn(min = spacing.minTouchTarget),
            )
        }
        val emailError = error == ConnectError.INVALID_EMAIL || error == ConnectError.EMAIL_TAKEN
        val emailErrorText = stringResource(
            if (error == ConnectError.EMAIL_TAKEN) R.string.account_email_taken else R.string.account_email_invalid,
        )
        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it
                callbacks.onEdit()
            },
            label = { Text(stringResource(R.string.account_email)) },
            enabled = !busy,
            singleLine = true,
            isError = emailError,
            supportingText = { if (emailError) Text(emailErrorText) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { if (emailError) error(emailErrorText) },
        )
        val passwordError = error == ConnectError.WEAK_PASSWORD || error == ConnectError.WRONG_CREDENTIALS
        val passwordErrorText = stringResource(
            if (error == ConnectError.WRONG_CREDENTIALS) R.string.account_wrong_credentials else R.string.account_password_weak,
        )
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                callbacks.onEdit()
            },
            label = { Text(stringResource(R.string.account_password)) },
            enabled = !busy,
            singleLine = true,
            isError = passwordError,
            supportingText = {
                Text(if (passwordError) passwordErrorText else stringResource(R.string.account_password_hint))
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { if (passwordError) error(passwordErrorText) },
        )
    }
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
                enabled = !busy,
                onValueChange = { includeBacklog = it },
            ),
    ) {
        Checkbox(checked = includeBacklog, onCheckedChange = null, enabled = !busy)
        Text(stringResource(R.string.account_include_backlog), style = MaterialTheme.typography.bodyMedium)
    }
    when (error) {
        ConnectError.UNREACHABLE -> Status(stringResource(R.string.account_unreachable), isError = true)
        ConnectError.SAVE_FAILED -> Status(stringResource(R.string.account_save_failed), isError = true)
        ConnectError.TOO_MANY_ATTEMPTS -> Status(stringResource(R.string.account_too_many_attempts), isError = true)
        else -> Unit
    }
    Button(
        onClick = {
            when {
                useToken -> callbacks.onConnectToken(token, includeBacklog)
                creating -> callbacks.onSignUp(email, password, includeBacklog)
                else -> callbacks.onLogIn(email, password, includeBacklog)
            }
        },
        enabled = !busy,
        modifier = Modifier.heightIn(min = spacing.minTouchTarget),
    ) {
        Text(
            stringResource(
                when {
                    busy -> R.string.account_checking
                    useToken -> R.string.account_connect
                    creating -> R.string.account_create_and_connect
                    else -> R.string.account_sign_in_and_connect
                },
            ),
        )
    }
    TextButton(
        onClick = {
            useToken = !useToken
            callbacks.onEdit()
        },
        modifier = Modifier.heightIn(min = spacing.minTouchTarget),
    ) { Text(stringResource(if (useToken) R.string.account_use_email else R.string.account_use_token)) }
}

/**
 * Which server to connect to. Starts on the hosted one; "Use my own server" is for people running
 * their own copy of the backend. Only shown while offline: changing the server means signing in
 * again anyway.
 */
@Composable
private fun ServerChoice(account: AccountUi, isHosted: Boolean, onChange: (String) -> Unit, enabled: Boolean) {
    val spacing = AiDailyMemoryTheme.spacing
    var open by rememberSaveable { mutableStateOf(!isHosted || account.serverDraft != null) }
    Text(
        text = stringResource(
            if (isHosted) R.string.account_server_default else R.string.account_server_custom,
            hostOf(account.serverUrl),
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (!open) {
        TextButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.heightIn(min = spacing.minTouchTarget),
        ) { Text(stringResource(R.string.account_server_use_own)) }
        return
    }
    val invalid = account.serverDraft != null
    val errorText = stringResource(R.string.account_server_invalid)
    OutlinedTextField(
        value = account.serverDraft ?: if (isHosted) "" else account.serverUrl,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.account_server_label)) },
        placeholder = { Text(HostedServer.BASE_URL) },
        enabled = enabled,
        singleLine = true,
        isError = invalid,
        supportingText = { Text(if (invalid) errorText else stringResource(R.string.account_server_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { if (invalid) error(errorText) },
    )
    TextButton(
        onClick = {
            onChange("")
            open = false
        },
        enabled = enabled && !isHosted || invalid,
        modifier = Modifier.heightIn(min = spacing.minTouchTarget),
    ) { Text(stringResource(R.string.account_server_use_default)) }
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
