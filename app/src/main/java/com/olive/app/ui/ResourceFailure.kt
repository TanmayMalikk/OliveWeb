package com.olive.app.ui

import androidx.annotation.StringRes

// Pure calculators carry a message ID; the UI resolves it using its own Android resource locale.
internal class ResourceFailure(@StringRes val textId:Int,vararg val arguments:Any?):IllegalArgumentException()

internal inline fun requireText(condition:Boolean,message:()->ResourceFailure) {
    if(!condition) throw message()
}

internal fun ResourceText.failure(error:Throwable,@StringRes fallback:Int):String =
    if(error is ResourceFailure) this(error.textId,*error.arguments) else this(fallback)
