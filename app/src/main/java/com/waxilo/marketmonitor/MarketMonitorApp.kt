package com.waxilo.marketmonitor

import android.app.Application
import android.content.Context
import com.waxilo.marketmonitor.di.AppContainer

class MarketMonitorApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Compose 里从 LocalContext 取容器，避免每个页面都手写类型转换。 */
fun Context.appContainer(): AppContainer =
    (applicationContext as MarketMonitorApp).container
