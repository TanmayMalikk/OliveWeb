package com.olive.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.olive.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedEntryViewer(session: SessionViewModel) {
    val entry = session.viewedSharedEntry ?: return

    // Immediately clear the state if the user uses the system back swipe
    BackHandler {
        session.closeSharedPrivateEntry()
    }

    Dialog(
        onDismissRequest = { session.closeSharedPrivateEntry() },
        properties = DialogProperties(usePlatformDefaultWidth = false) // Allows full-screen
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Toolbar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.secure_shared_entry),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 12.dp).semantics { heading() },
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(onClick = { session.closeSharedPrivateEntry() }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close_shared_entry))
                    }
                }

                HorizontalDivider()

                // Decrypted Content Payload
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp)
                ) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = entry.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}
