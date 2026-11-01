// All app roles: opens and closes the app's root screens and safe-app pages.
package com.olive.app.ui.users.shared.navigation

import com.olive.app.ui.session.SessionRuntime

internal class SessionScreenNavigation(private val runtime: SessionRuntime) {
    // returns the quick exit for shared safe app session screen navigation.
    fun quickExit() = runtime.navigation.quickExit()
    // Shared safe app session screen navigation: Moves the app into its background state.
    fun background() = runtime.navigation.background()
    // Returns to the previous screen for shared safe app session screen navigation.
    fun back() = runtime.navigation.back()
    // Shared safe app session screen navigation: Starts safe page.
    fun openSafePage(page: SafePage) = runtime.navigation.navigate(page)
}
