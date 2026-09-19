package com.waxilo.marketmonitor

import android.app.Application
import android.content.Context
import com.waxilo.marketmonitor.di.AppContainer
import kotlinx.coroutines.launch

class MarketMonitorApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 提醒必须跨页面存活，所以引擎在进程启动时就跑起来；没有启用规则时它只在等时间片，不碰网络
        container.appScope.launch { container.alertEngine.start() }
    }
}

/** Compose 里从 LocalContext 取容器，避免每个页面都手写类型转换。 */
fun Context.appContainer(): AppContainer =
    (applicationContext as MarketMonitorApp).container
