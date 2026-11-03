// Signed-in users: connects cover, private note, activity, and privacy screens to their workflows.
package com.olive.app.ui.users.shared.navigation

import com.olive.app.data.DeliveryAddress
import com.olive.app.data.NewsArticle
import com.olive.app.data.OrderLine
import com.olive.app.data.PrivateEntryKind
import com.olive.app.ui.session.SessionRuntime

internal interface SessionCoverNotesActivityActions {
    val runtime: SessionRuntime

    // Passes the private-note button action to the session privacy controller.
    fun noteInteraction() = runtime.privacy.noteInteraction()
    // Records that Android detected a screenshot or screen recording.
    fun captureDetected(kind: String) = runtime.privacy.captureDetected(kind)
    // Records when Android reports that screen recording has stopped.
    fun recordingStopped() = runtime.privacy.recordingStopped()
    // Adds a settings change to the user's private activity history.
    fun recordSettingsInteraction(area: String, action: String) =
        runtime.history.recordSettingsInteraction(area, action)

    // Saves the food cover's cart, favourites, and meal choices on this device.
    fun saveFoodCover(
        cart: Map<String, Int>,
        favorites: Set<String>,
        customizations: Map<String, Set<String>>,
    ) = runtime.covers.saveFoodCover(cart, favorites, customizations)

    // Adds or removes an article from the news cover's bookmarks.
    fun toggleNewsBookmark(article: NewsArticle) = runtime.covers.toggleNewsBookmark(article)
    // Stores the result of a food-cover checkout in the activity history.
    suspend fun recordCheckout(foods: List<OrderLine>, totalCents: Int, error: String) =
        runtime.covers.recordCheckout(foods, totalCents, error)

    // Hides the food-cover address prompt until it is needed again.
    fun dismissAddressPrompt() = runtime.covers.dismissAddressPrompt()
    // Saves the delivery address entered in the food cover.
    fun saveAddress(address: DeliveryAddress) = runtime.covers.saveAddress(address)

    // Opens one category in the private activity-history screen.
    fun showActivityCategory(value: ActivityCategory) = runtime.history.showActivityCategory(value)
    // Returns from an activity category to the full history list.
    fun activityBack() = runtime.history.activityBack()

    // Clears the note editor's success or error message before the next action.
    fun clearPrivateEntryStatus() = runtime.notes.clearPrivateEntryStatus()
    // Creates a private note or updates the note with the matching ID.
    fun savePrivateEntry(id: String?, kind: PrivateEntryKind?, title: String, body: String) =
        runtime.notes.savePrivateEntry(id, kind, title, body)

    // Pins or unpins a private note so important notes stay easy to find.
    fun togglePrivateEntryPin(id: String) = runtime.notes.togglePrivateEntryPin(id)
    // Moves a private note into the note trash.
    fun trashPrivateEntry(id: String) = runtime.notes.trashPrivateEntry(id)
    // Restores a private note from the note trash.
    fun restorePrivateEntry(id: String) = runtime.notes.restorePrivateEntry(id)
}
