// All app roles: opens and closes the app's root screens and safe-app pages.
package com.olive.app.ui.users.shared.navigation

import com.olive.app.ui.session.SessionRuntime

internal class SessionScreenNavigation(private val runtime: SessionRuntime) {
    fun quickExit() = runtime.navigation.quickExit()
    fun background() = runtime.navigation.background()
    fun back() = runtime.navigation.back()
    fun openSafePage(page: SafePage) = runtime.navigation.navigate(page)
}
