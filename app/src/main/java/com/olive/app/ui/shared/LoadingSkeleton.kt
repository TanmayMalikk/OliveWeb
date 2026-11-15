package com.olive.app.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.R

/** A neutral placeholder for data that has yet to arrive. */
@Composable
internal fun LoadingSkeleton(
    modifier: Modifier = Modifier.fillMaxWidth(),
    rows: Int = 2,
    preview: Boolean = false,
) {
    val label = stringResource(R.string.ui_loading)
    Column(
        modifier.testTag("loading_skeleton").semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(rows) {
            Surface(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SkeletonBlock(Modifier.fillMaxWidth(.56f).height(16.dp))
                    SkeletonBlock(Modifier.fillMaxWidth(.83f).height(10.dp))
                    if (preview) SkeletonBlock(Modifier.fillMaxWidth().height(140.dp))
                }
            }
        }
    }
}

@Composable
internal fun SkeletonBlock(modifier: Modifier) {
    Spacer(modifier.background(MaterialTheme.colorScheme.outline.copy(alpha = .22f), MaterialTheme.shapes.small))
}
