package com.olive.app.ui.users.user.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.ui.users.shared.navigation.Field
import com.olive.app.ui.users.shared.navigation.SessionViewModel

/**
 * Prompts the signed-in user for their backup recovery password when encrypted access
 * must be unlocked via OPAQUE on a new or cleared device.
 *
 * @param session Active session ViewModel holding any pending unlock request.
 */
@Composable
internal fun PasswordRecoveryUnlockDialog(session: SessionViewModel) {
    val request = session.passwordRecoveryUnlockRequest ?: return
    var passwordInput by remember(request) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = {
            passwordInput = ""
            request.complete(null)
        },
        title = { Text(stringResource(R.string.password_recovery_unlock_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.password_recovery_unlock_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Field(
                    label = stringResource(R.string.password_recovery_account_password_label),
                    value = passwordInput,
                    tag = "password_recovery_unlock_input",
                    secret = true,
                    showSecretToggle = true,
                    enabled = true,
                ) { passwordInput = it }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val pwd = passwordInput
                    passwordInput = ""
                    request.complete(pwd)
                },
                enabled = passwordInput.isNotEmpty(),
                modifier = Modifier.testTag("confirm_password_recovery_unlock"),
            ) {
                Text(stringResource(R.string.password_recovery_unlock_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                passwordInput = ""
                request.complete(null)
            }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
