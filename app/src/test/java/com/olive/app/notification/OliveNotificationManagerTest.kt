// Tests discreet notification text and icons for each cover.
package com.olive.app.notification

import com.olive.app.data.CoverType
import com.olive.app.data.AccountRole
import com.olive.app.R
import org.junit.Assert.assertEquals
import org.junit.Test

class OliveNotificationManagerTest {
    // Inputs: the values shown below. Expected: cover types use matching discreet defaults.
    @Test
    fun coverTypesUseMatchingDiscreetDefaults() {
        assertEquals(
            CoverNotificationContent(
                "olive_food_channel",
                "Olive Order Update",
                "Your order is being prepared."
            ),
            OliveNotificationManager.contentFor(
                null,
                null,
                CoverType.FOOD,
                com.olive.app.ui.testResourceText()
            )
        )
        assertEquals(
            CoverNotificationContent(
                "olive_news_channel",
                "Daily Digest",
                "New headlines are available."
            ),
            OliveNotificationManager.contentFor(
                "",
                "",
                CoverType.NEWS,
                com.olive.app.ui.testResourceText()
            )
        )
        assertEquals(
            CoverNotificationContent(
                "olive_fitness_channel",
                "Fitness Tracker",
                "Your fitness summary is ready."
            ),
            OliveNotificationManager.contentFor(
                null,
                null,
                CoverType.FITNESS,
                com.olive.app.ui.testResourceText()
            )
        )
        assertEquals(
            CoverNotificationContent("olive_game_channel", "App", "A new update is available."),
            OliveNotificationManager.contentFor(
                null,
                null,
                CoverType.GAME,
                com.olive.app.ui.testResourceText()
            )
        )
    }

    // Inputs: the values shown below. Expected: supplied cover text is preserved.
    @Test
    fun suppliedCoverTextIsPreserved() {
        assertEquals(
            CoverNotificationContent("olive_news_channel", "Local update", "New story"),
            OliveNotificationManager.contentFor(
                "Local update",
                "New story",
                CoverType.NEWS,
                com.olive.app.ui.testResourceText()
            )
        )
    }

    // Inputs: the values shown below. Expected: every cover uses its own status icon.
    @Test
    fun everyCoverUsesItsOwnStatusIcon() {
        assertEquals(
            R.drawable.ic_notification_olive,
            OliveNotificationManager.iconFor(CoverType.FOOD)
        )
        assertEquals(
            R.drawable.ic_notification_daily_digest,
            OliveNotificationManager.iconFor(CoverType.NEWS)
        )
        assertEquals(
            R.drawable.ic_notification_fitness,
            OliveNotificationManager.iconFor(CoverType.FITNESS)
        )
        assertEquals(
            R.drawable.ic_notification_olive,
            OliveNotificationManager.iconFor(CoverType.GAME)
        )
        assertEquals(
            R.drawable.ic_notification_olive_branch,
            OliveNotificationManager.iconFor(CoverType.FOOD, AccountRole.SAFE_CONTACT)
        )
        assertEquals(
            R.drawable.ic_notification_olive_tree,
            OliveNotificationManager.iconFor(CoverType.FOOD, AccountRole.STAFF)
        )
        assertEquals(
            R.drawable.ic_notification_olive_tree,
            OliveNotificationManager.iconFor(CoverType.FOOD, AccountRole.ADMIN)
        )
    }
}
