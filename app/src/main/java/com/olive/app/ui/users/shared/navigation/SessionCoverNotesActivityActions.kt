// Signed-in users: connects cover, private note, activity, and privacy screens to their workflows.
package com.olive.app.ui.users.shared.navigation

import com.olive.app.data.DeliveryAddress
import com.olive.app.data.NewsArticle
import com.olive.app.data.OrderLine
import com.olive.app.data.PrivateEntryKind
import com.olive.app.ui.session.SessionRuntime

internal interface SessionCoverNotesActivityActions {
    val runtime: SessionRuntime

    fun noteInteraction() = runtime.privacy.noteInteraction()
    fun captureDetected(kind: String) = runtime.privacy.captureDetected(kind)
    fun recordingStopped() = runtime.privacy.recordingStopped()
    fun recordSettingsInteraction(area: String, action: String) =
        runtime.history.recordSettingsInteraction(area, action)

    fun saveFoodCover(
        cart: Map<String, Int>,
        favorites: Set<String>,
        customizations: Map<String, Set<String>>,
    ) = runtime.covers.saveFoodCover(cart, favorites, customizations)

    fun toggleNewsBookmark(article: NewsArticle) = runtime.covers.toggleNewsBookmark(article)
    suspend fun recordCheckout(foods: List<OrderLine>, totalCents: Int, error: String) =
        runtime.covers.recordCheckout(foods, totalCents, error)

    fun dismissAddressPrompt() = runtime.covers.dismissAddressPrompt()
    fun saveAddress(address: DeliveryAddress) = runtime.covers.saveAddress(address)

    fun showActivityCategory(value: ActivityCategory) = runtime.history.showActivityCategory(value)
    fun activityBack() = runtime.history.activityBack()

    fun clearPrivateEntryStatus() = runtime.notes.clearPrivateEntryStatus()
    fun savePrivateEntry(id: String?, kind: PrivateEntryKind?, title: String, body: String) =
        runtime.notes.savePrivateEntry(id, kind, title, body)

    fun togglePrivateEntryPin(id: String) = runtime.notes.togglePrivateEntryPin(id)
    fun trashPrivateEntry(id: String) = runtime.notes.trashPrivateEntry(id)
    fun restorePrivateEntry(id: String) = runtime.notes.restorePrivateEntry(id)
}
