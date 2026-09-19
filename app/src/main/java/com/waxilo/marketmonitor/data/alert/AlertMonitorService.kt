package com.waxilo.marketmonitor.data.alert

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.waxilo.marketmonitor.appContainer

/**
 * 后台预警监测的前台服务（PRD FR-3.2）。
 *
 * 检测逻辑本身在 [AlertEngine]（进程级容器里），服务只负责让进程在后台不被回收，
 * 并挂一条常驻通知说明「正在监测几条规则」。因此启动顺序无所谓：引擎起来就会拉起本服务。
 */
class AlertMonitorService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = appContainer()
        container.alertEngine.start()
        val notification = container.alertNotifier.monitorNotification(container.alertEngine.enabledRuleCount)
        // Android 14 起必须显式带类型；低版本 ServiceCompat 会忽略该参数
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        return START_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4101
    }
}
