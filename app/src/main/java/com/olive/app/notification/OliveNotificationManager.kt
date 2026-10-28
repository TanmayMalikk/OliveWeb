// Contains the olive notification manager code for this part of Olive.
package com.olive.app.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.olive.app.OliveActivity
import com.olive.app.R
import com.olive.app.data.AccountRole
import com.olive.app.data.CoverType

// Groups the data and actions for olive notification manager.
object OliveNotificationManager {
    const val CHANNEL_FOOD = "olive_food_channel"
    const val CHANNEL_NEWS = "olive_news_channel"
    const val CHANNEL_CALCULATOR = "olive_calc_channel"
    const val CHANNEL_GAME = "olive_game_channel"
    const val CHANNEL_BRANCH = "olive_branch_channel"
    const val CHANNEL_TREE = "olive_tree_channel"

    // Each cover gets a matching public-facing notification category in Android settings.
    fun createNotificationChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_FOOD,
                    context.getString(R.string.ui_olive_order_updates),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description =
                        context.getString(R.string.ui_order_status_and_delivery_notifications_for_olive)
                },
                NotificationChannel(
                    CHANNEL_NEWS,
                    context.getString(R.string.ui_daily_digest_briefings),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description =
                        context.getString(R.string.ui_daily_headlines_and_news_digest_notifications)
                },
                NotificationChannel(
                    CHANNEL_CALCULATOR,
                    context.getString(R.string.ui_better_calculator_reminders),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.ui_utility_and_calculation_reminders)
                },
                NotificationChannel(
                    CHANNEL_GAME,
                    context.getString(R.string.ui_app_updates),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.ui_general_app_activity_notifications)
                },
                NotificationChannel(
                    CHANNEL_BRANCH,
                    context.getString(R.string.ui_olive_branch_updates),
                    NotificationManager.IMPORTANCE_DEFAULT
                ),
                NotificationChannel(
                    CHANNEL_TREE,
                    context.getString(R.string.ui_olive_tree_updates),
                    NotificationManager.IMPORTANCE_DEFAULT
                ),
            )
        )
    }

    internal fun contentFor(
        title: String?,
        body: String?,
        coverType: CoverType,
        text: com.olive.app.ui.shared.ResourceText
    ) =
        AppNotifications.contentFor(title, body, coverType, text = text)

    // Shows discreet notification.
    fun showDiscreetNotification(
        context: Context,
        title: String?,
        body: String?,
        coverType: CoverType = CoverType.FOOD,
        role: AccountRole = AccountRole.USER,
        notificationId: Int = (System.currentTimeMillis() % 100000).toInt()
    ) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        createNotificationChannels(context)
        val content = AppNotifications.contentFor(
            title,
            body,
            coverType,
            role,
            com.olive.app.ui.shared.ResourceText(context.resources)
        )
        val smallIcon = iconFor(coverType, role)
        val intent = Intent(context, OliveActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, content.channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, notification) }
    }

    internal fun iconFor(coverType: CoverType, role: AccountRole = AccountRole.USER) = when {
        role == AccountRole.SAFE_CONTACT -> R.drawable.ic_notification_olive_branch
        role == AccountRole.STAFF || role == AccountRole.ADMIN -> R.drawable.ic_notification_olive_tree
        coverType == CoverType.NEWS -> R.drawable.ic_notification_daily_digest
        coverType == CoverType.CALCULATOR -> R.drawable.ic_notification_calculator
        // Game branding is still in development, so it temporarily uses the generic Olive status icon.
        coverType == CoverType.GAME -> R.drawable.ic_notification_olive
        else -> R.drawable.ic_notification_olive
    }
}
