// Signed-in users: connects cover, private note, activity, and privacy screens to their workflows.
package com.olive.app.ui.users.shared.navigation

import com.olive.app.data.DeliveryAddress
import com.olive.app.data.NewsArticle
import com.olive.app.data.OrderLine
import com.olive.app.data.PrivateEntryKind
import com.olive.app.ui.session.SessionRuntime

internal interface SessionCoverNotesActivityActions {
    val runtime: SessionRuntime

    // returns the note interaction for shared safe app session cover notes activity actions.
    fun noteInteraction() = runtime.privacy.noteInteraction()
    // Shared safe app session cover notes activity actions: returns the capture detected.
    fun captureDetected(kind: String) = runtime.privacy.captureDetected(kind)
    // returns the recording stopped for shared safe app session cover notes activity actions.
    fun recordingStopped() = runtime.privacy.recordingStopped()
    // Shared safe app session cover notes activity actions: Records settings interaction.
    fun recordSettingsInteraction(area: String, action: String) =
        runtime.history.recordSettingsInteraction(area, action)

    // Shared safe app session cover notes activity actions: Saves food cover.
    fun saveFoodCover(
        cart: Map<String, Int>,
        favorites: Set<String>,
        customizations: Map<String, Set<String>>,
    ) = runtime.covers.saveFoodCover(cart, favorites, customizations)

    // returns the toggle news bookmark for shared safe app session cover notes activity actions.
    fun toggleNewsBookmark(article: NewsArticle) = runtime.covers.toggleNewsBookmark(article)
    // Shared safe app session cover notes activity actions: Records checkout.
    suspend fun recordCheckout(foods: List<OrderLine>, totalCents: Int, error: String) =
        runtime.covers.recordCheckout(foods, totalCents, error)

    // Shared safe app session cover notes activity actions: Stops address prompt.
    fun dismissAddressPrompt() = runtime.covers.dismissAddressPrompt()
    // Saves address for shared safe app session cover notes activity actions.
    fun saveAddress(address: DeliveryAddress) = runtime.covers.saveAddress(address)

    // Starts activity category for shared safe app session cover notes activity actions.
    fun showActivityCategory(value: ActivityCategory) = runtime.history.showActivityCategory(value)
    // Shared safe app session cover notes activity actions: returns the activity back.
    fun activityBack() = runtime.history.activityBack()

    // Removes private entry status for shared safe app session cover notes activity actions.
    fun clearPrivateEntryStatus() = runtime.notes.clearPrivateEntryStatus()
    // Shared safe app session cover notes activity actions: Saves private entry.
    fun savePrivateEntry(id: String?, kind: PrivateEntryKind?, title: String, body: String) =
        runtime.notes.savePrivateEntry(id, kind, title, body)

    // Shared safe app session cover notes activity actions: returns the toggle private entry pin.
    fun togglePrivateEntryPin(id: String) = runtime.notes.togglePrivateEntryPin(id)
    // returns the trash private entry for shared safe app session cover notes activity actions.
    fun trashPrivateEntry(id: String) = runtime.notes.trashPrivateEntry(id)
    // Shared safe app session cover notes activity actions: Restores private entry.
    fun restorePrivateEntry(id: String) = runtime.notes.restorePrivateEntry(id)
}
