package com.apercallc.aidailymemory.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.net.LinkInput
import com.apercallc.aidailymemory.ui.theme.AiDailyMemoryTheme
import java.net.URI
import java.net.URISyntaxException

/** What the account section shows; [host] is null while not connected. */
data class AccountUi(val host: String?, val rejected: Boolean, val saveFailed: Boolean = false)

/**
 * Connects the phone to the user's account so speech clips can be transcribed (Phase 1).
 * Stateless apart from the text fields. The token field is deliberately not `rememberSaveable`:
 * a secret must not land in saved instance state.
 */
@Composable
fun AccountSection(
    account: AccountUi,
    onConnect: (url: String, token: String) -> LinkInput,
    onDisconnect: () -> Unit,
) {
    val spacing = AiDailyMemoryTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(
            text = stringResource(R.string.account_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        if (account.host != null) {
            Text(
                text = stringResource(R.string.account_connected, account.host),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.account_upload_rules),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.heightIn(min = spacing.minTouchTarget),
            ) { Text(stringResource(R.string.account_disconnect)) }
        } else {
            ConnectForm(account, onConnect)
        }
    }
}

@Composable
private fun ConnectForm(account: AccountUi, onConnect: (String, String) -> LinkInput) {
    val spacing = AiDailyMemoryTheme.spacing
    var url by rememberSaveable { mutableStateOf("https://") }
    var token by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<LinkInput?>(null) }
    Text(
        text = stringResource(if (account.rejected) R.string.account_rejected else R.string.account_explainer),
        style = MaterialTheme.typography.bodyMedium,
        color = if (account.rejected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val urlError = problem == LinkInput.InvalidUrl
    val tokenError = problem == LinkInput.InvalidToken
    val urlErrorText = stringResource(R.string.account_url_invalid)
    val tokenErrorText = stringResource(R.string.account_token_invalid)
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text(stringResource(R.string.account_url)) },
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
        onValueChange = { token = it },
        label = { Text(stringResource(R.string.account_token)) },
        singleLine = true,
        isError = tokenError,
        supportingText = { if (tokenError) Text(tokenErrorText) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { if (tokenError) error(tokenErrorText) },
    )
    if (account.saveFailed) {
        Text(
            text = stringResource(R.string.account_save_failed),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Button(
        onClick = {
            val result = onConnect(url, token)
            problem = result.takeUnless { it is LinkInput.Valid }
            if (result is LinkInput.Valid) token = ""
        },
        modifier = Modifier.heightIn(min = spacing.minTouchTarget),
    ) { Text(stringResource(R.string.account_connect)) }
}

/** Shown instead of the full URL: enough to recognize the server, nothing more. */
fun hostOf(baseUrl: String): String = try {
    URI(baseUrl).host ?: baseUrl
} catch (_: URISyntaxException) {
    baseUrl
}
