package com.olive.app.ui.shared

import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.R

@Composable
internal fun FloatingQuickExit(onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val label = stringResource(R.string.a11y_quick_exit)
    val shape = MaterialTheme.shapes.large
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier.border(1.dp, SageGreen, shape)
            .testTag("quick_exit").semantics { contentDescription = label },
        expanded = !compact,
        shape = shape,
        containerColor = OliveGreen,
        contentColor = Paper,
        icon = { Icon(Icons.Outlined.Close, contentDescription = null) },
        text = { Text(stringResource(R.string.quick_exit), Modifier.clearAndSetSemantics {}) },
    )
}
