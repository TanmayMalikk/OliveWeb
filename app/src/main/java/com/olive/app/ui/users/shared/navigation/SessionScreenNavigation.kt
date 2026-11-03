// All app roles: opens and closes the app's root screens and safe-app pages.
package com.olive.app.ui.users.shared.navigation

import com.olive.app.ui.session.SessionRuntime

internal class SessionScreenNavigation(private val runtime: SessionRuntime) {
    // Gives navigation screens the same quick-exit action from the current session.
    fun quickExit() = runtime.navigation.quickExit()
    // Sends the app to the background when the user presses Quick Exit.
    fun background() = runtime.navigation.background()
    // Returns to the previous Safe App screen.
    fun back() = runtime.navigation.back()
    // Opens the requested Safe App page from shared navigation.
    fun openSafePage(page: SafePage) = runtime.navigation.navigate(page)
}
