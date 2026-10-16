package com.olive.app.notification

import android.content.Context
import com.olive.app.data.AccountRole
import com.olive.app.data.CoverType
import kotlin.random.Random

internal data class CoverNotificationContent(val channelId:String,val title:String,val body:String)

// All notification wording lives here so each cover can be edited without hunting through UI files.
object AppNotifications {
    // DEFAULT COVER NOTIFICATIONS: these are used whenever a local title or body is left blank.
    private const val OLIVE_DEFAULT_TITLE="Olive Order Update"
    private const val OLIVE_DEFAULT_BODY="Your order is being prepared."
    private const val NEWS_DEFAULT_TITLE="Daily Digest"
    private const val NEWS_DEFAULT_BODY="New headlines are available."
    private const val CALCULATOR_DEFAULT_TITLE="Better Calculator"
    private const val CALCULATOR_DEFAULT_BODY="A calculator reminder is available."
    private const val GAME_DEFAULT_TITLE="App"
    private const val GAME_DEFAULT_BODY="A new update is available."
    private const val BRANCH_DEFAULT_TITLE="Olive Branch"
    private const val BRANCH_DEFAULT_BODY="A new update is available."
    private const val TREE_DEFAULT_TITLE="Olive Tree"
    private const val TREE_DEFAULT_BODY="An account update is available."

    //these stay cover-safe and never include a sender or private message text.
    private val OLIVE_SAFE_UPDATES=listOf(
        "Fresh menu updates are available.",
        "See what's new from Olive.",
        "New kitchen picks are ready to browse.",
    )
    private val NEWS_SAFE_UPDATES=listOf(
        "New headlines are available.",
        "Your latest digest is ready.",
        "There are new stories to browse.",
    )
    private val CALCULATOR_SAFE_UPDATES=listOf(
        "A calculator update is available.",
        "Your calculation tools are ready.",
        "Open Better Calculator for an update.",
    )
    private val GAME_SAFE_UPDATES=listOf(
        "A new update is available.",
        "Open the app to see what's new.",
        "New activity is waiting.",
    )

    fun showRandomSafeUpdate(context:Context,coverType:CoverType,role:AccountRole=AccountRole.USER,
        random:Random=Random.Default) {
        val choices=when(coverType) {
            CoverType.NEWS -> NEWS_SAFE_UPDATES
            CoverType.CALCULATOR -> CALCULATOR_SAFE_UPDATES
            CoverType.GAME -> GAME_SAFE_UPDATES
            CoverType.FOOD -> OLIVE_SAFE_UPDATES
        }
        val body=when(role) {
            AccountRole.SAFE_CONTACT -> BRANCH_DEFAULT_BODY
            AccountRole.STAFF,AccountRole.ADMIN -> TREE_DEFAULT_BODY
            AccountRole.USER -> choices[random.nextInt(choices.size)]
        }
        OliveNotificationManager.showDiscreetNotification(context,"",body,coverType,role)
    }

    internal fun contentFor(title:String?,body:String?,coverType:CoverType,role:AccountRole=AccountRole.USER)=when {
        role==AccountRole.SAFE_CONTACT -> CoverNotificationContent(
            OliveNotificationManager.CHANNEL_BRANCH,title.orDefault(BRANCH_DEFAULT_TITLE),body.orDefault(BRANCH_DEFAULT_BODY),
        )
        role==AccountRole.STAFF || role==AccountRole.ADMIN -> CoverNotificationContent(
            OliveNotificationManager.CHANNEL_TREE,title.orDefault(TREE_DEFAULT_TITLE),body.orDefault(TREE_DEFAULT_BODY),
        )
        coverType==CoverType.NEWS -> CoverNotificationContent(
            OliveNotificationManager.CHANNEL_NEWS,
            title.orDefault(NEWS_DEFAULT_TITLE),
            body.orDefault(NEWS_DEFAULT_BODY),
        )
        coverType==CoverType.CALCULATOR -> CoverNotificationContent(
            OliveNotificationManager.CHANNEL_CALCULATOR,
            title.orDefault(CALCULATOR_DEFAULT_TITLE),
            body.orDefault(CALCULATOR_DEFAULT_BODY),
        )
        coverType==CoverType.GAME -> CoverNotificationContent(
            OliveNotificationManager.CHANNEL_GAME,
            title.orDefault(GAME_DEFAULT_TITLE),
            body.orDefault(GAME_DEFAULT_BODY),
        )
        else -> CoverNotificationContent(
            OliveNotificationManager.CHANNEL_FOOD,
            title.orDefault(OLIVE_DEFAULT_TITLE),
            body.orDefault(OLIVE_DEFAULT_BODY),
        )
    }

    private fun String?.orDefault(fallback:String)=takeUnless { it.isNullOrBlank() } ?: fallback
}
