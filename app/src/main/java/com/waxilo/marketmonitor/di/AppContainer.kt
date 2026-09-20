package com.waxilo.marketmonitor.di

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import com.waxilo.marketmonitor.BuildConfig
import com.waxilo.marketmonitor.data.alert.AlertEngine
import com.waxilo.marketmonitor.data.alert.AlertNotifier
import com.waxilo.marketmonitor.data.local.EncryptedWebhookStore
import com.waxilo.marketmonitor.data.local.SettingsDataStore
import com.waxilo.marketmonitor.data.remote.BinanceMarketApi
import com.waxilo.marketmonitor.data.remote.DefaultRestHosts
import com.waxilo.marketmonitor.data.remote.GithubReleaseApi
import com.waxilo.marketmonitor.data.remote.WebhookSender
import com.waxilo.marketmonitor.data.remote.ws.DefaultWsHosts
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

private const val TAG = "AppContainer"

/**
 * 手工依赖容器（PRD 7 已定：不用 Hilt）。
 * 全部懒加载，主线程不做磁盘与网络初始化；需要后台常驻的对象由 [appScope] 持有。
 */
class AppContainer(private val context: Context) {

    /** 供 UI 层查询系统状态（通知权限等），不用于创建新依赖。 */
    val appContext: Context get() = context

    /** 常驻协程作用域。必须挂 CoroutineExceptionHandler：后台协程的未捕获异常会直接杀死进程。 */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            Log.e(TAG, "后台协程未捕获异常", error)
        },
    )

    /** 共享连接池：REST 与下载复用同一客户端（PRD 7 网络选型）。 */
    private val restClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .applySystemProxy()
            .build()
    }

    /** WS 客户端需要长连接 + 心跳，超时策略与 REST 不同，因此单独一个实例。 */
    private val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .applySystemProxy()
            .build()
    }

    /**
     * OkHttp 在 Android 上默认不读取系统（Wi-Fi）代理，导致全国性网络治理下走代理才能访问的
     * 交易所域名请求不到数据。这里把系统代理套用到客户端：系统未配置代理时保持直连不变。
     */
    private fun OkHttpClient.Builder.applySystemProxy(): OkHttpClient.Builder {
        systemProxy()?.let(::proxy)
        return this
    }

    private fun systemProxy(): Proxy? {
        // 1) 显式系统属性（http.proxyHost），少数环境通过命令行/debug 注入
        System.getProperty("http.proxyHost")?.takeIf { it.isNotBlank() }?.let { host ->
            val port = System.getProperty("http.proxyPort")?.toIntOrNull() ?: 80
            return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
        }
        // 2) 系统网卡配置的代理（Wi-Fi 高级设置，API 23+；minSdk 26 可安全直达）
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val info = cm?.defaultProxy ?: return null
        val host = info.host
        return if (host.isNullOrBlank() || info.port <= 0) null
        else Proxy(Proxy.Type.HTTP, InetSocketAddress(host, info.port))
    }

    /** 更新包下载可能持续数十秒，只放宽整体 callTimeout。 */
    private val downloadClient: OkHttpClient by lazy {
        restClient.newBuilder().callTimeout(5, TimeUnit.MINUTES).build()
    }

    /** Webhook 端点 URL 含 secret，禁止重定向以免把它递给另一个主机。 */
    private val webhookClient: OkHttpClient by lazy {
        restClient.newBuilder().followRedirects(false).callTimeout(20, TimeUnit.SECONDS).build()
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
            watchlist = watchlistRepository,
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

    val alertNotifier: AlertNotifier by lazy { AlertNotifier(context) }

    /** 端点配置页的「测试发送」也走同一个客户端，保证行为与真实推送一致。 */
    val webhookSender: WebhookSender by lazy { WebhookSender(webhookClient) }

    /**
     * 预警检测中枢。懒构建：没有启用规则时不该为它付协程与数据库的代价，
     * 但一旦构建就一直活在进程里（[appScope]），因为提醒必须跨页面存活。
     */
    val alertEngine: AlertEngine by lazy {
        AlertEngine(
            context = context,
            alerts = alertRepository,
            webhooks = webhookRepository,
            market = marketRepository,
            settings = settings,
            notifier = alertNotifier,
            sender = webhookSender,
            scope = appScope,
        )
    }

    val updateRepository: UpdateRepository by lazy {
        UpdateRepositoryImpl(
            api = GithubReleaseApi(downloadClient, BuildConfig.UPDATE_OWNER, BuildConfig.UPDATE_REPO),
            abiPreferences = Build.SUPPORTED_ABIS.toList(),
        )
    }

    /**
     * 更新包下载目录。
     *
     * 必须是 `cacheDir/updates/`：`res/xml/file_paths.xml` 里声明的就是
     * `cache-path name="updates" path="updates/"`，换别的目录 `FileProvider.getUriForFile`
     * 会直接抛 IllegalArgumentException（表现为「点安装没反应」）。
     * 走 cacheDir 而非 filesDir：更新包用完即弃，系统空间紧张时可以自行回收。
     */
    val updateDir: File get() = File(context.cacheDir, "updates")

    /** 供 ViewModel 拉起「允许安装未知应用」授权页 —— 容器之外不该拿到 Context。 */
    fun startActivity(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    val installer: ApkInstaller by lazy {
        ApkInstaller(context, "${context.packageName}.fileprovider")
    }
}
