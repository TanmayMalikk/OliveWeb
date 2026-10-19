package com.olive.app.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

// Capture native resources during composition so event callbacks can format translated messages too.
internal class ResourceText(private val resolve:(Int,Array<out Any?>)->String) {
    constructor(resources:Resources):this({ id,arguments ->
        if(arguments.isEmpty()) resources.getString(id) else resources.getString(id,*arguments)
    })
    operator fun invoke(@StringRes id: Int, vararg arguments: Any?): String =
        resolve(id,arguments)
}

@Composable internal fun rememberResourceText(): ResourceText {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) { ResourceText(context.resources) }
}
