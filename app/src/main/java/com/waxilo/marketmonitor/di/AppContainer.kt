package com.waxilo.marketmonitor.di

import android.content.Context
import android.os.Build
import com.waxilo.marketmonitor.BuildConfig
import com.waxilo.marketmonitor.data.local.EncryptedWebhookStore
import com.waxilo.marketmonitor.data.local.SettingsDataStore
import com.waxilo.marketmonitor.data.remote.BinanceMarketApi
import com.waxilo.marketmonitor.data.remote.DefaultRestHosts
import com.waxilo.marketmonitor.data.remote.DefaultWsHosts
import com.waxilo.marketmonitor.data.remote.GithubReleaseApi
import com.waxilo.marketmonitor.data.remote.ws.MarketWebSocket
import com.waxilo.marketmonitor.data.repository.AlertRepositoryImpl
import com.waxilo.marketmonitor.data.repository.ApkInstaller
import com.waxilo.marketmonitor.data.repository.MarketRepositoryImpl
import com.waxilo.marketmonitor.data.repository.UpdateRepositoryImpl
import com.waxilo.marketmonitor.data.repository.WatchlistRepositoryImpl
import com.waxilo.marketmonitor.domain.repository.AlertRepository
import com.waxilo.marketmonitor.domain.repository.AppSettings
import com.waxilo.marketmonitor.domain.repository.SettingsRepository
import com.waxilo.marketmonitor.domain.repository.UpdateRepository
import com.waxilo.marketmonitor.domain.repository.WatchlistRepository
import com.waxilo.marketmonitor.domain.repository.WebhookRepository
import com.waxilo.marketmonitor.data.local.room.MarketDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 手工依赖容器（PRD 7 已定：不用 Hilt）。
 * 全部懒加载，主线程不做磁盘与网络初始化；需要后台常驻的对象由 [appScope] 持有。
 */
class AppContainer(private val context: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 共享连接池：REST 与下载复用同一客户端（PRD 7 网络选型）。 */
    private val restClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** WS 客户端需要长连接 + 心跳，超时策略与 REST 不同，因此单独一个实例。 */
    private val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    /** 更新包下载可能持续数十秒，只放宽整体 callTimeout。 */
    private val downloadClient: OkHttpClient by lazy {
        restClient.newBuilder().callTimeout(5, TimeUnit.MINUTES).build()
    }

    private val database: MarketDatabase by lazy { MarketDatabase.create(context) }

    val settings: SettingsRepository by lazy { SettingsDataStore.create(context) }

    /** 域名等设置以快照形式提供给网络层，避免在同步路径里挂起读盘。 */
    private val settingsSnapshot by lazy {
        settings.settings.stateIn(appScope, SharingStarted.Eagerly, AppSettings())
    }

    private val restHosts by lazy {
        DefaultRestHosts(
            spotMirror = { settingsSnapshot.value.spotRestMirror },
            futuresMirror = { settingsSnapshot.value.futuresRestMirror },
        )
    }

    private val wsHosts by lazy {
        DefaultWsHosts(
            // 只有一个 WS 镜像设置：应用内不会同时连两个市场（PRD 3.2）
            spotMirror = { settingsSnapshot.value.wsMirror },
            futuresMirror = { settingsSnapshot.value.wsMirror },
        )
    }

    private val marketApi: BinanceMarketApi by lazy { BinanceMarketApi(restClient, restHosts) }

    private val webSocket: MarketWebSocket by lazy { MarketWebSocket(wsClient, wsHosts) }

    val marketRepository: MarketRepositoryImpl by lazy {
        MarketRepositoryImpl(
            api = marketApi,
            socket = webSocket,
            tickerDao = database.tickerDao(),
            instrumentDao = database.instrumentDao(),
            klineDao = database.klineDao(),
            scope = appScope,
            initialMarket = settingsSnapshot.value.defaultMarket,
        ).also { it.start() }
    }

    val watchlistRepository: WatchlistRepository by lazy {
        WatchlistRepositoryImpl(database.watchlistDao(), database)
    }

    val alertRepository: AlertRepository by lazy { AlertRepositoryImpl(database.alertDao()) }

    /** Webhook 端点含密钥，加密存储的初始化涉及主线程禁做的磁盘 IO，首次访问时构建。 */
    val webhookRepository: WebhookRepository by lazy { EncryptedWebhookStore.create(context) }

    val updateRepository: UpdateRepository by lazy {
        UpdateRepositoryImpl(
            api = GithubReleaseApi(downloadClient, BuildConfig.UPDATE_OWNER, BuildConfig.UPDATE_REPO),
            abiPreferences = Build.SUPPORTED_ABIS.toList(),
        )
    }

    val installer: ApkInstaller by lazy {
        ApkInstaller(context, "${context.packageName}.fileprovider")
    }
}
