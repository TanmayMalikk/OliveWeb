package com.olive.app.notification

import com.olive.app.data.CoverType
import com.olive.app.data.AccountRole
import com.olive.app.R
import org.junit.Assert.assertEquals
import org.junit.Test

class OliveNotificationManagerTest {
    @Test fun coverTypesUseMatchingDiscreetDefaults() {
        assertEquals(CoverNotificationContent("olive_food_channel","Olive Order Update","Your order is being prepared."),
            OliveNotificationManager.contentFor(null,null,CoverType.FOOD))
        assertEquals(CoverNotificationContent("olive_news_channel","Daily Digest","New headlines are available."),
            OliveNotificationManager.contentFor("","",CoverType.NEWS))
        assertEquals(CoverNotificationContent("olive_calc_channel","Better Calculator","A calculator reminder is available."),
            OliveNotificationManager.contentFor(null,null,CoverType.CALCULATOR))
        assertEquals(CoverNotificationContent("olive_game_channel","App","A new update is available."),
            OliveNotificationManager.contentFor(null,null,CoverType.GAME))
    }

    @Test fun suppliedCoverTextIsPreserved() {
        assertEquals(CoverNotificationContent("olive_news_channel","Local update","New story"),
            OliveNotificationManager.contentFor("Local update","New story",CoverType.NEWS))
    }

    @Test fun everyCoverUsesItsOwnStatusIcon() {
        assertEquals(R.drawable.ic_notification_olive,OliveNotificationManager.iconFor(CoverType.FOOD))
        assertEquals(R.drawable.ic_notification_daily_digest,OliveNotificationManager.iconFor(CoverType.NEWS))
        assertEquals(R.drawable.ic_notification_calculator,OliveNotificationManager.iconFor(CoverType.CALCULATOR))
        assertEquals(R.drawable.ic_notification_olive,OliveNotificationManager.iconFor(CoverType.GAME))
        assertEquals(R.drawable.ic_notification_olive_branch,
            OliveNotificationManager.iconFor(CoverType.FOOD,AccountRole.SAFE_CONTACT))
        assertEquals(R.drawable.ic_notification_olive_tree,
            OliveNotificationManager.iconFor(CoverType.FOOD,AccountRole.STAFF))
        assertEquals(R.drawable.ic_notification_olive_tree,
            OliveNotificationManager.iconFor(CoverType.FOOD,AccountRole.ADMIN))
    }
}
