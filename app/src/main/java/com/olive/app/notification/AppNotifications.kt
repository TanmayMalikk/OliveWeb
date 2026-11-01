// Cover users: chooses notification text that does not reveal private activity.
package com.olive.app.notification

import com.olive.app.R

import android.content.Context
import com.olive.app.data.AccountRole
import com.olive.app.data.CoverType
import kotlin.random.Random

internal data class CoverNotificationContent(
    val channelId: String,
    val title: String,
    val body: String
)

// Select cover-specific resource IDs here; notification wording lives in Android language resources.
object AppNotifications {
    // DEFAULT COVER NOTIFICATIONS: these are used whenever a local title or body is left blank.
    private val OLIVE_DEFAULT_TITLE = R.string.ui_olive_order_update
    private val OLIVE_DEFAULT_BODY = R.string.ui_your_order_is_being_prepared
    private val NEWS_DEFAULT_TITLE = R.string.ui_daily_digest
    private val NEWS_DEFAULT_BODY = R.string.ui_new_headlines_are_available
    private val FITNESS_DEFAULT_TITLE = R.string.fitness_tracker
    private val FITNESS_DEFAULT_BODY = R.string.fitness_reminder
    private val GAME_DEFAULT_TITLE = R.string.app_name
    private val GAME_DEFAULT_BODY = R.string.ui_a_new_update_is_available
    private val BRANCH_DEFAULT_TITLE = R.string.olive_branch
    private val BRANCH_DEFAULT_BODY = R.string.ui_a_new_update_is_available
    private val TREE_DEFAULT_TITLE = R.string.olive_tree
    private val TREE_DEFAULT_BODY = R.string.ui_an_account_update_is_available

    //these stay cover-safe and never include a sender or private message text.
    private val OLIVE_SAFE_UPDATES = listOf(
        R.string.ui_fresh_menu_updates_are_available,
        R.string.ui_see_what_s_new_from_olive,
        R.string.ui_new_kitchen_picks_are_ready_to_browse,
    )
    private val NEWS_SAFE_UPDATES = listOf(
        R.string.ui_new_headlines_are_available,
        R.string.ui_your_latest_digest_is_ready,
        R.string.ui_there_are_new_stories_to_browse,
    )
    private val FITNESS_SAFE_UPDATES = listOf(R.string.fitness_reminder)
    private val GAME_SAFE_UPDATES = listOf(
        R.string.ui_a_new_update_is_available,
        R.string.ui_open_the_app_to_see_what_s_new,
        R.string.ui_new_activity_is_waiting,
    )

    // Chooses a harmless update message that matches the active cover and posts it locally.
    fun showRandomSafeUpdate(
        context: Context, coverType: CoverType, role: AccountRole = AccountRole.USER,
        random: Random = Random.Default
    ) {
        val choices = when (coverType) {
            CoverType.NEWS -> NEWS_SAFE_UPDATES
            CoverType.FITNESS -> FITNESS_SAFE_UPDATES
            CoverType.GAME -> GAME_SAFE_UPDATES
            CoverType.FOOD -> OLIVE_SAFE_UPDATES
        }
        val bodyId = when (role) {
            AccountRole.SAFE_CONTACT -> BRANCH_DEFAULT_BODY
            AccountRole.STAFF, AccountRole.ADMIN -> TREE_DEFAULT_BODY
            AccountRole.USER -> choices[random.nextInt(choices.size)]
        }
        OliveNotificationManager.showDiscreetNotification(
            context,
            "",
            context.getString(bodyId),
            coverType,
            role
        )
    }

    internal fun contentFor(
        title: String?,
        body: String?,
        coverType: CoverType,
        role: AccountRole = AccountRole.USER,
        text: com.olive.app.ui.shared.ResourceText
    ) = when {
        role == AccountRole.SAFE_CONTACT -> CoverNotificationContent(
            OliveNotificationManager.CHANNEL_BRANCH,
            title.orDefault(text(BRANCH_DEFAULT_TITLE)),
            body.orDefault(text(BRANCH_DEFAULT_BODY)),
        )

        role == AccountRole.STAFF || role == AccountRole.ADMIN -> CoverNotificationContent(
            OliveNotificationManager.CHANNEL_TREE,
            title.orDefault(text(TREE_DEFAULT_TITLE)),
            body.orDefault(text(TREE_DEFAULT_BODY)),
        )

        coverType == CoverType.NEWS -> CoverNotificationContent(
            OliveNotificationManager.CHANNEL_NEWS,
            title.orDefault(text(NEWS_DEFAULT_TITLE)),
            body.orDefault(text(NEWS_DEFAULT_BODY)),
        )

        coverType == CoverType.FITNESS -> CoverNotificationContent(
            OliveNotificationManager.CHANNEL_FITNESS,
            title.orDefault(text(FITNESS_DEFAULT_TITLE)),
            body.orDefault(text(FITNESS_DEFAULT_BODY)),
        )

        coverType == CoverType.GAME -> CoverNotificationContent(
            OliveNotificationManager.CHANNEL_GAME,
            title.orDefault(text(GAME_DEFAULT_TITLE)),
            body.orDefault(text(GAME_DEFAULT_BODY)),
        )

        else -> CoverNotificationContent(
            OliveNotificationManager.CHANNEL_FOOD,
            title.orDefault(text(OLIVE_DEFAULT_TITLE)),
            body.orDefault(text(OLIVE_DEFAULT_BODY)),
        )
    }

    private fun String?.orDefault(fallback: String) = takeUnless { it.isNullOrBlank() } ?: fallback
}
