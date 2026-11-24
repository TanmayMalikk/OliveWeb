package com.olive.app.ui.users.user.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.users.shared.navigation.Field
import com.olive.app.ui.users.shared.navigation.SessionViewModel

/**
 * Settings section for zero-knowledge password recovery failsafe.
 * Passkeys are always preferred first; password recovery is offered only as a backup.
 */
@Composable
internal fun PasswordRecoverySection(session: SessionViewModel) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }

    var passwordInput by remember { mutableStateOf("") }

    HorizontalDivider()
    Text(
        stringResource(R.string.password_recovery_title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        stringResource(R.string.password_recovery_help),
        style = MaterialTheme.typography.bodySmall,
        color = Muted,
    )
    Text(
        stringResource(R.string.password_recovery_warning),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )

    val isEnrolled = session.passwordRecoveryEnrolled == true
    if (isEnrolled) {
        OutlinedButton(
            onClick = { showRemoveDialog = true },
            enabled = !session.busy,
            modifier = Modifier.testTag("remove_recovery_password_button"),
        ) {
            Text(stringResource(R.string.password_recovery_remove))
        }
    } else {
        Button(
            onClick = { showAddDialog = true },
            enabled = !session.busy,
            modifier = Modifier.testTag("add_recovery_password_button"),
        ) {
            Text(stringResource(R.string.password_recovery_add))
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                passwordInput = ""
            },
            title = { Text(stringResource(R.string.password_recovery_add)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.password_recovery_dialog_prompt),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Field(
                        label = stringResource(R.string.passkey_recovery_reauth_password),
                        value = passwordInput,
                        tag = "password_recovery_enroll_input",
                        secret = true,
                        showSecretToggle = true,
                        enabled = !session.busy,
                    ) { passwordInput = it }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pwd = passwordInput
                        showAddDialog = false
                        passwordInput = ""
                        session.enablePasswordRecovery(pwd)
                    },
                    enabled = passwordInput.isNotEmpty() && !session.busy,
                    modifier = Modifier.testTag("confirm_add_password_recovery"),
                ) {
                    Text(stringResource(R.string.password_recovery_add))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddDialog = false
                    passwordInput = ""
                }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showRemoveDialog) {
        val passkeys = session.passkeyRecoveryCredentials
        val isBlocked = passkeys.isNullOrEmpty()
        AlertDialog(
            onDismissRequest = {
                showRemoveDialog = false
                passwordInput = ""
            },
            title = { Text(stringResource(R.string.password_recovery_remove)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (isBlocked) {
                        Text(
                            stringResource(R.string.password_recovery_remove_blocked_last),
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(stringResource(R.string.password_recovery_warning))
                        Field(
                            label = stringResource(R.string.passkey_recovery_reauth_password),
                            value = passwordInput,
                            tag = "password_recovery_remove_password_input",
                            secret = true,
                            showSecretToggle = true,
                            enabled = !session.busy,
                        ) { passwordInput = it }
                    }
                }
            },
            confirmButton = {
                if (isBlocked) {
                    TextButton(onClick = {
                        showRemoveDialog = false
                        passwordInput = ""
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                } else {
                    Button(
                        onClick = {
                            val pwd = passwordInput
                            showRemoveDialog = false
                            passwordInput = ""
                            session.removePasswordRecovery(pwd)
                        },
                        enabled = passwordInput.isNotEmpty() && !session.busy,
                        modifier = Modifier.testTag("confirm_remove_password_recovery"),
                    ) {
                        Text(stringResource(R.string.password_recovery_remove))
                    }
                }
            },
            dismissButton = {
                if (!isBlocked) {
                    TextButton(onClick = {
                        showRemoveDialog = false
                        passwordInput = ""
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            },
        )
    }
}
