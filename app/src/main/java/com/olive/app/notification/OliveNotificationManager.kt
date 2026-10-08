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
import com.olive.app.MainActivity
import com.olive.app.R
import com.olive.app.data.AccountRole
import com.olive.app.data.CoverType

object OliveNotificationManager {
    const val CHANNEL_FOOD="olive_food_channel"
    const val CHANNEL_NEWS="olive_news_channel"
    const val CHANNEL_CALCULATOR="olive_calc_channel"
    const val CHANNEL_GAME="olive_game_channel"
    const val CHANNEL_BRANCH="olive_branch_channel"
    const val CHANNEL_TREE="olive_tree_channel"

    // Each cover gets a matching public-facing notification category in Android settings.
    fun createNotificationChannels(context:Context) {
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_FOOD,"Olive Order Updates",NotificationManager.IMPORTANCE_DEFAULT).apply {
                description="Order status and delivery notifications for Olive"
            },
            NotificationChannel(CHANNEL_NEWS,"Daily Digest Briefings",NotificationManager.IMPORTANCE_DEFAULT).apply {
                description="Daily headlines and news digest notifications"
            },
            NotificationChannel(CHANNEL_CALCULATOR,"Better Calculator Reminders",NotificationManager.IMPORTANCE_DEFAULT).apply {
                description="Utility and calculation reminders"
            },
            NotificationChannel(CHANNEL_GAME,"App Updates",NotificationManager.IMPORTANCE_DEFAULT).apply {
                description="General app activity notifications"
            },
            NotificationChannel(CHANNEL_BRANCH,"Olive Branch Updates",NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_TREE,"Olive Tree Updates",NotificationManager.IMPORTANCE_DEFAULT),
        ))
    }

    internal fun contentFor(title:String?,body:String?,coverType:CoverType)=
        AppNotifications.contentFor(title,body,coverType)

    fun showDiscreetNotification(context:Context,title:String?,body:String?,coverType:CoverType=CoverType.FOOD,
        role:AccountRole=AccountRole.USER,notificationId:Int=(System.currentTimeMillis()%100000).toInt()) {
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
        createNotificationChannels(context)
        val content=AppNotifications.contentFor(title,body,coverType,role)
        val smallIcon=iconFor(coverType,role)
        val intent=Intent(context,MainActivity::class.java).apply {
            flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent=PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(context,content.channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId,notification) }
    }

    internal fun iconFor(coverType:CoverType,role:AccountRole=AccountRole.USER)=when {
        role==AccountRole.SAFE_CONTACT -> R.drawable.ic_notification_olive_branch
        role==AccountRole.STAFF || role==AccountRole.ADMIN -> R.drawable.ic_notification_olive_tree
        coverType==CoverType.NEWS -> R.drawable.ic_notification_daily_digest
        coverType==CoverType.CALCULATOR -> R.drawable.ic_notification_calculator
        // Game branding is still in development, so it temporarily uses the generic Olive status icon.
        coverType==CoverType.GAME -> R.drawable.ic_notification_olive
        else -> R.drawable.ic_notification_olive
    }
}
