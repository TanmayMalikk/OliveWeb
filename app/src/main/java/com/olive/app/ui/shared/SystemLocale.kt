package com.olive.app.ui.shared

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * Reads the first Android system language, even when Olive has no matching strings.
 *
 * @return The first Android system locale, or the default locale if the list is empty.
 */
@Composable
internal fun systemLocale(): Locale = remember(LocalConfiguration.current) {
    Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()
}
