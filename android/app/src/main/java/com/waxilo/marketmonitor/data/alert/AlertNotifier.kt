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
        // 渠道的振动样式一经创建就无法被应用覆盖（系统会把用户改过的设置视为最终值），
        // 所以长震动与普通震动各占一条渠道 id；顺手删掉上一版的旧渠道，避免系统设置里出现重复项。
        LEGACY_CHANNELS.forEach(manager::deleteNotificationChannel)
        createChannel(
            CHANNEL_ALERT,
            R.string.channel_alert_long_name,
            R.string.channel_alert_desc,
            importance = NotificationManager.IMPORTANCE_HIGH,
            sound = true,
            buzz = Buzz.LONG,
        )
        createChannel(
            CHANNEL_ALERT_NORMAL,
            R.string.channel_alert_normal_name,
            R.string.channel_alert_desc,
            importance = NotificationManager.IMPORTANCE_HIGH,
            sound = true,
            buzz = Buzz.DEFAULT,
        )
        createChannel(
            CHANNEL_ALERT_SILENT,
            R.string.channel_alert_silent_name,
            R.string.channel_alert_desc,
            importance = NotificationManager.IMPORTANCE_DEFAULT,
            sound = false,
            buzz = Buzz.NONE,
        )
        createChannel(
            CHANNEL_MONITOR,
            R.string.channel_service_name,
            R.string.channel_service_desc,
            importance = NotificationManager.IMPORTANCE_LOW,
            sound = false,
            buzz = Buzz.NONE,
        )
    }

    /**
     * 规则的响铃/振动都关掉时走静默渠道；否则按设置页的「长震动」开关在两条响铃渠道里选：
     * 长震动用三轮长振，关闭后换默认节奏的短振（渠道样式改不了，只能换渠道）。
     */
    fun notify(message: AlertMessage, rule: AlertRule, longVibrate: Boolean) {
        if (!manager.areNotificationsEnabled()) return
        val channel = when {
            !(rule.playSound || rule.vibrate) -> CHANNEL_ALERT_SILENT
            longVibrate -> CHANNEL_ALERT
            else -> CHANNEL_ALERT_NORMAL
        }
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
        detailContentIntent(message)?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    /** 点监控常驻通知打开消息中心（与导航路由使用同一串 extra）。 */
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

    /**
     * 点预警通知直达该标的的行情图表页。
     *
     * market/symbol 放进 extras，导航层据此把详情页作起始页（见 AppNavHost），
     * 不再先落在预警列表让用户自己找那条消息。消息 id 一并带上：进入时顺手把这条
     * 标记已读，免得人都到图表了，消息中心还挂着「未读」。
     *
     * requestCode 按消息取不同值：PendingIntent 只认「code + Intent 内容」，extras
     * 不参与比较——共用一个 code 的话，新通知会把旧通知的点击目标悄悄改掉。
     */
    private fun detailContentIntent(message: AlertMessage): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        launch.putExtra(EXTRA_DETAIL_MARKET, message.market.key)
        launch.putExtra(EXTRA_DETAIL_SYMBOL, message.symbol)
        launch.putExtra(EXTRA_ALERT_MESSAGE_ID, message.id)
        return PendingIntent.getActivity(
            context,
            notificationId(message.id),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel(
        id: String,
        nameRes: Int,
        descRes: Int,
        importance: Int,
        sound: Boolean,
        buzz: Buzz,
    ) {
        val channel = NotificationChannel(id, context.getString(nameRes), importance).apply {
            description = context.getString(descRes)
            if (!sound) {
                setSound(null, null)
            }
            enableVibration(buzz.enabled)
            // 不给节奏就用系统默认（两下短振）；长震动是首段静默后连续三轮长振
            buzz.pattern?.let { vibrationPattern = it }
            lockscreenVisibility = if (sound) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    private fun notificationId(messageId: Long): Int =
        (messageId % Int.MAX_VALUE).toInt() + 1

    companion object {
        const val EXTRA_OPEN_ALERTS = "com.waxilo.marketmonitor.OPEN_ALERTS"
        const val EXTRA_DETAIL_MARKET = "com.waxilo.marketmonitor.DETAIL_MARKET"
        const val EXTRA_DETAIL_SYMBOL = "com.waxilo.marketmonitor.DETAIL_SYMBOL"
        const val EXTRA_ALERT_MESSAGE_ID = "com.waxilo.marketmonitor.ALERT_MESSAGE_ID"

        /** 长震动渠道。 */
        const val CHANNEL_ALERT = "price_alerts_long_buzz"

        /** 普通（默认节奏）震动渠道：设置页关掉「长震动」后走这条。 */
        const val CHANNEL_ALERT_NORMAL = "price_alerts_default_buzz"
        const val CHANNEL_ALERT_SILENT = "price_alerts_silent_v2"
        const val CHANNEL_MONITOR = "alert_monitor"

        /** 上一版渠道（默认短震动），创建新渠道时一并清理。 */
        private val LEGACY_CHANNELS = listOf("price_alerts", "price_alerts_silent")

        private const val REQUEST_CODE = 4100
    }

    /** 渠道的振动样式：渠道建成后只能由用户在系统设置里改，所以三种样式各占一条渠道 id。 */
    private enum class Buzz(val enabled: Boolean, val pattern: LongArray?) {
        /** 不振动（静默 / 常驻通知）。 */
        NONE(false, null),

        /** 系统默认节奏（两下短振）。 */
        DEFAULT(true, null),

        /** 长震动：立即开始、连续三轮长振（间隔 200ms），总时长约 3.2s。 */
        LONG(true, longArrayOf(0L, 900L, 200L, 900L, 200L, 900L)),
    }
}

/** 是否已授予通知权限（Android 13 起为运行时权限）。 */
fun Context.notificationsAllowed(): Boolean =
    NotificationManagerCompat.from(this).areNotificationsEnabled()
