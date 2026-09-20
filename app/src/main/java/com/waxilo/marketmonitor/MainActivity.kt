package com.waxilo.marketmonitor

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import com.waxilo.marketmonitor.data.alert.AlertNotifier
import com.waxilo.marketmonitor.data.alert.notificationsAllowed
import com.waxilo.marketmonitor.domain.repository.AppSettings
import com.waxilo.marketmonitor.ui.navigation.AppNavHost
import com.waxilo.marketmonitor.ui.theme.MarketMonitorTheme
import com.waxilo.marketmonitor.ui.theme.MarketTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 点通知进入时直接落在预警页，不让用户先看到行情列表再跳一次
        val openAlerts = intent?.getBooleanExtra(AlertNotifier.EXTRA_OPEN_ALERTS, false) == true
        requestNotificationPermissionIfNeeded()
        val container = appContainer()
        container.alertEngine.refreshMonitorService()
        setContent {
            // 主题模式跟随设置（PRD 4.5）：SYSTEM 时由系统决定，否则强制浅/深。
            // 这里读的是同一个 DataStore，所以设置页一改就立即整树重组换肤。
            val settings by container.settings.settings.collectAsState(initial = AppSettings())
            val dark = settings.themeMode.isDark ?: isSystemInDarkTheme()
            MarketMonitorTheme(darkTheme = dark) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MarketTheme.colors.paper,
                ) { innerPadding ->
                    AppNavHost(
                        openAlerts = openAlerts,
                        modifier = Modifier
                            .padding(innerPadding)
                            .background(MarketTheme.colors.paper),
                    )
                }
            }
        }
    }

    /**
     * Android 13 起通知是运行时权限。只在这里申请一次：被拒后系统不再弹，
     * 预警页会持续显示「通知权限未开启」的提示，用户可自行去系统设置里打开。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (notificationsAllowed()) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATIONS,
        )
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 41
    }
}
