// Safe-app users: inactivity warning dialog offering stay-here, idle-timeout extension, and Quick exit actions.
package com.olive.app.ui.users.shared.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.security.IdlePolicy
import com.olive.app.ui.shared.OliveOutlinedButton
import com.olive.app.ui.shared.OliveTextButton
import com.olive.app.ui.shared.rememberResourceText

/**
 * Displays the inactivity warning popup when the user is seconds away from locking, including
 * one-tap chips to extend the configured idle lock timeout without leaving the current screen.
 *
 * @param seconds Remaining countdown seconds before automatic exit.
 * @param usesCover Whether exiting returns to a cover screen instead of closing Safe Space.
 * @param currentTimeoutSeconds Currently active idle timeout preference in seconds.
 * @param onStayHere Callback resetting the idle timer while keeping the current timeout preference.
 * @param onExtendTimeout Callback persisting a larger timeout (in seconds) and resetting the idle timer.
 * @param onQuickExit Callback exiting Safe Space immediately.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IdleLockWarningDialog(
    seconds: Int,
    usesCover: Boolean,
    currentTimeoutSeconds: Int,
    onStayHere: () -> Unit,
    onExtendTimeout: (Int) -> Unit,
    onQuickExit: () -> Unit,
) {
    val uiText = rememberResourceText()
    val extendOptions = remember(currentTimeoutSeconds) {
        IdlePolicy.extendOptions(currentTimeoutSeconds)
    }
    AlertDialog(
        onDismissRequest = onStayHere,
        title = { Text(stringResource(R.string.locking_soon)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (usesCover) uiText(
                        R.string.ui_no_activity_returning_to_your_cover_in_value_seconds,
                        seconds,
                    ) else uiText(
                        R.string.ui_no_activity_locking_olive_branch_in_value_seconds,
                        seconds,
                    ),
                )
                if (extendOptions.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.idle_lock_extend_prompt),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            extendOptions.forEach { optionSeconds ->
                                OliveOutlinedButton(
                                    onClick = { onExtendTimeout(optionSeconds) },
                                    modifier = Modifier.testTag("idle_extend_$optionSeconds"),
                                ) {
                                    Text(stringResource(R.string.idle_lock_minutes, optionSeconds / 60))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            OliveTextButton(
                onClick = onStayHere,
                modifier = Modifier.testTag("idle_stay_here"),
            ) {
                Text(stringResource(R.string.stay_here))
            }
        },
        dismissButton = {
            OliveTextButton(
                onClick = onQuickExit,
                modifier = Modifier.testTag("idle_quick_exit"),
            ) {
                Text(stringResource(R.string.quick_exit))
            }
        },
    )
}
