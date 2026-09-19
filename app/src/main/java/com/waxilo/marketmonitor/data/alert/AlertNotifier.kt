package com.waxilo.marketmonitor.data.alert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.waxilo.marketmonitor.R
import com.waxilo.marketmonitor.domain.alert.AlertRule
import com.waxilo.marketmonitor.domain.alert.AlertText
import com.waxilo.marketmonitor.domain.repository.AlertMessage

/**
 * 通知栏投递（PRD FR-3.3）。
 *
 * 声音与振动在 Android 8 之后由渠道决定、单条通知无法覆盖，因此按规则的开关
 * 准备「响铃」与「静默」两条渠道，而不是给每条通知设 flag。
 */
class AlertNotifier(private val context: Context) {

    private val manager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannel(
            CHANNEL_ALERT,
            R.string.channel_alert_name,
            R.string.channel_alert_desc,
            importance = NotificationManager.IMPORTANCE_HIGH,
            sound = true,
        )
        createChannel(
            CHANNEL_ALERT_SILENT,
            R.string.channel_alert_silent_name,
            R.string.channel_alert_desc,
            importance = NotificationManager.IMPORTANCE_DEFAULT,
            sound = false,
        )
        createChannel(
            CHANNEL_MONITOR,
            R.string.channel_service_name,
            R.string.channel_service_desc,
            importance = NotificationManager.IMPORTANCE_LOW,
            sound = false,
        )
    }

    /** 规则的响铃/振动都关掉时走静默渠道；系统级总开关在设置页与渠道里各有一层。 */
    fun notify(message: AlertMessage, rule: AlertRule) {
        if (!manager.areNotificationsEnabled()) return
        val channel = if (rule.playSound || rule.vibrate) CHANNEL_ALERT else CHANNEL_ALERT_SILENT
        manager.notify(notificationId(message.id), build(message, channel))
    }

    /** 后台监控常驻通知（PRD FR-3.2 前台服务），由服务自己持有 id。 */
    fun monitorNotification(ruleCount: Int): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(context.getString(R.string.notification_monitor_title))
            .setContentText(context.getString(R.string.notification_monitor_text, ruleCount))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        contentIntent()?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    private fun build(message: AlertMessage, channelId: String): Notification {
        val summary = AlertText.summary(message)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(message.alertName)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
        contentIntent()?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    /** 点击通知打开消息中心（与导航路由使用同一串 extra）。 */
    private fun contentIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        launch.putExtra(EXTRA_OPEN_ALERTS, true)
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel(id: String, nameRes: Int, descRes: Int, importance: Int, sound: Boolean) {
        val channel = NotificationChannel(id, context.getString(nameRes), importance).apply {
            description = context.getString(descRes)
            if (!sound) {
                setSound(null, null)
                enableVibration(false)
            }
            lockscreenVisibility = if (sound) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    private fun notificationId(messageId: Long): Int =
        (messageId % Int.MAX_VALUE).toInt() + 1

    companion object {
        const val EXTRA_OPEN_ALERTS = "com.waxilo.marketmonitor.OPEN_ALERTS"
        const val CHANNEL_ALERT = "price_alerts"
        const val CHANNEL_ALERT_SILENT = "price_alerts_silent"
        const val CHANNEL_MONITOR = "alert_monitor"

        private const val REQUEST_CODE = 4100
    }
}

/** 是否已授予通知权限（Android 13 起为运行时权限）。 */
fun Context.notificationsAllowed(): Boolean =
    NotificationManagerCompat.from(this).areNotificationsEnabled()
