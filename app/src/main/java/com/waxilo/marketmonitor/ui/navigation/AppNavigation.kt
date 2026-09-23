package com.waxilo.marketmonitor.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.ui.alerts.AlertEditorScreen
import com.waxilo.marketmonitor.ui.alerts.AlertsScreen
import com.waxilo.marketmonitor.ui.detail.DetailScreen
import com.waxilo.marketmonitor.ui.market.MarketScreen
import com.waxilo.marketmonitor.ui.search.SearchScreen
import com.waxilo.marketmonitor.ui.settings.SettingsScreen
import com.waxilo.marketmonitor.ui.theme.MarketTheme
import com.waxilo.marketmonitor.ui.webhook.WebhookScreen

/**
 * 路由：详情用两段路径参数携带 (market, symbol)，避免复合键里的冒号出现在 URL 上；
 * 新建预警的预填值走查询参数，因为「无预填」是合法输入。
 */
object Routes {
    const val MARKET = "market"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val ALERTS = "alerts"
    const val WEBHOOKS = "webhooks"
    const val DETAIL_TEMPLATE = "detail/{market}/{symbol}"
    const val ALERT_EDIT_TEMPLATE = "alert/edit/{ruleId}"
    const val ALERT_NEW_TEMPLATE = "alert/new?market={market}&symbol={symbol}"

    fun detail(id: SymbolId): String = "detail/${id.market.key}/${id.symbol}"

    fun alertEdit(ruleId: Long): String = "alert/edit/$ruleId"

    /** 预填值总是显式带上：路由里的查询参数缺省时依赖 defaultValue，跨版本行为不一致。 */
    fun alertNew(market: MarketType = MarketType.SPOT, symbol: String = ""): String =
        "alert/new?market=${market.key}&symbol=$symbol"
}

/** 底栏一级页：行情 / 预警 / 设置。二级页（详情/搜索/Webhook/预警编辑）不入底栏。 */
private data class BottomTab(val route: String, val label: String, val icon: ImageVector)

private val BottomTabs = listOf(
    BottomTab(Routes.MARKET, "行情", Icons.Default.Home),
    BottomTab(Routes.ALERTS, "预警", Icons.Default.Notifications),
    BottomTab(Routes.SETTINGS, "设置", Icons.Default.Settings),
)

@Composable
fun AppNavHost(
    openAlerts: Boolean = false,
    /** 点预警通知进入时要直达的标的；null = 普通启动或落预警页。 */
    startDetail: SymbolId? = null,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val openDetail: (SymbolId) -> Unit = { navController.navigate(Routes.detail(it)) }

    val startRoute = when {
        startDetail != null -> Routes.detail(startDetail)
        openAlerts -> Routes.ALERTS
        else -> Routes.MARKET
    }

    /**
     * 离开预警页。
     *
     * 从通知栏进入时预警页就是**起始页**，栈底就是它 —— `popBackStack()` 返回 false，
     * 左上角箭头点了没反应，系统返回键还会直接退出应用，两条路都回不到行情主页。
     * 所以退不动时改成「回家」：把预警页换成行情页，之后返回键才是正常的退出语义。
     *
     * 判断放在**调用时**而不是组合时：用户从预警页走进编辑页/Webhook 页时，
     * 返回处理器仍在注册状态，那时 `previousBackStackEntry` 非空，应当照常 pop 一层。
     */
    val leaveAlerts: () -> Unit = {
        if (navController.previousBackStackEntry == null) {
            navController.navigate(Routes.MARKET) {
                popUpTo(Routes.ALERTS) { inclusive = true }
            }
        } else {
            navController.popBackStack()
        }
    }

    /**
     * 详情页的返回路径。
     *
     * 点预警通知直达时详情页就是**起始页**，与从通知栏进预警页同一处境：退不动时
     * 「回家」换成行情页（见 [leaveAlerts]）。平时从行情页进来，正常 pop 一层。
     *
     * 起始页没法给模板路由填参（NavHost 无 startDestinationArgs），所以通知直达时
     * 额外注册一条**具体**路由（见下方 composable(Routes.detail(...))）。
     */
    val leaveDetail: () -> Unit = {
        val launchRoute = startDetail?.let { Routes.detail(it) }
        if (navController.previousBackStackEntry == null && launchRoute != null) {
            navController.navigate(Routes.MARKET) {
                popUpTo(launchRoute) { inclusive = true }
            }
        } else {
            navController.popBackStack()
        }
    }

    /**
     * 底栏切页：saveState/restoreState 让每个 tab 记住自己的滚动与状态；
     * 通知直达时起始页是详情/预警（非 tab），首次切页把它 inclusive 弹出——
     * 否则它会永远压在栈底，从任何 tab 按返回都会先跳回那个通知页。
     */
    val selectTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
                inclusive = navController.graph.findStartDestination().route !in BottomTabs.map { it.route }
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    // tab 页的返回箭头只在「不是栈底」时出现；栈底时底栏就是出口。
    val popBack: () -> Unit = { navController.popBackStack() }
    val backAsTab: (() -> Unit)? =
        popBack.takeIf { navController.previousBackStackEntry != null }

    // 系统返回键与左上角箭头走同一条路径。只在「预警页是起始页」时才需要接管，
    // 否则会抢掉 NavController 正常的 pop（如预警页 → 行情页）。
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val topLevel = currentRoute in BottomTabs.map { it.route }

    Scaffold(
        // 底栏不透明地占住一条：把 innerPadding 交给 NavHost，否则长列表的最后一行
        // 会被底栏永久盖住（滚到底也出不来）。底色同为 paper，接缝看不出来。
        bottomBar = {
            if (topLevel) {
                val hairline = MarketTheme.colors.hairline
                NavigationBar(
                    containerColor = MarketTheme.colors.paper,
                    contentColor = MarketTheme.colors.ink,
                    tonalElevation = 0.dp,
                    modifier = Modifier.drawBehind {
                        // 这版 M3 的 NavigationBar 没有 border 参数，自己画一条发丝线。
                        drawLine(
                            color = hairline,
                            start = Offset.Zero,
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx(),
                        )
                    },
                ) {
                    BottomTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { selectTab(tab.route) },
                            alwaysShowLabel = true,
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                // 「已选中」信号用强调黄，与主题注释一致；指示条用浅底。
                                selectedIconColor = MarketTheme.colors.accent,
                                unselectedIconColor = MarketTheme.colors.muted,
                                selectedTextColor = MarketTheme.colors.ink,
                                unselectedTextColor = MarketTheme.colors.muted,
                                indicatorColor = MarketTheme.colors.wash,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            // 从通知栏进入时首帧就落在目标页，避免先闪一下行情列表。
            // 代价是栈底没有行情页，返回要额外兜底 —— 见 [leaveAlerts] 与 [leaveDetail]。
            startDestination = startRoute,
            modifier = modifier.padding(innerPadding),
            // 克制转场：进入从右滑入 + 淡入，退出轻淡出；返回时反向滑回。
            enterTransition = { slideInHorizontally(tween(220)) { it / 3 } + fadeIn(tween(220)) },
            exitTransition = { fadeOut(tween(160)) },
            popEnterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(220)) { -it / 3 } },
            popExitTransition = { slideOutHorizontally(tween(220)) { it / 3 } + fadeOut(tween(220)) },
        ) {
            composable(Routes.MARKET) {
                MarketScreen(
                    onOpenDetail = openDetail,
                    onOpenSearch = { navController.navigate(Routes.SEARCH) },
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(
                    onOpenDetail = openDetail,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                // 返回箭头只在「不是栈底」时出现：从通知直达预警页再切过来的设置页
                // 没有可退的地方，底栏就是它的出口。
                SettingsScreen(
                    onBack = backAsTab,
                )
            }
            composable(Routes.ALERTS) {
                BackHandler(enabled = openAlerts) { leaveAlerts() }
                AlertsScreen(
                    onBack = backAsTab,
                    onOpenDetail = openDetail,
                    onNewRule = { navController.navigate(Routes.alertNew()) },
                    onEditRule = { navController.navigate(Routes.alertEdit(it)) },
                    onOpenWebhooks = { navController.navigate(Routes.WEBHOOKS) },
                )
            }
            composable(Routes.WEBHOOKS) {
                WebhookScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.ALERT_NEW_TEMPLATE,
                arguments = listOf(
                    navArgument("market") { type = NavType.StringType; defaultValue = MarketType.SPOT.key },
                    navArgument("symbol") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                AlertEditorScreen(
                    ruleId = null,
                    presetSymbol = entry.arguments?.getString("symbol").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.ALERT_EDIT_TEMPLATE,
                arguments = listOf(navArgument("ruleId") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("ruleId") ?: 0L
                AlertEditorScreen(
                    ruleId = id,
                    presetSymbol = "",
                    onBack = { navController.popBackStack() },
                )
            }
            // 通知直达专用：具体路由（如 `detail/binance/BTCUSDT`）当起始页。
            // 它比模板路由更具体，navigate 到同一标的时也会命中这里，两条路语义一致。
            startDetail?.let { id ->
                composable(Routes.detail(id)) {
                    // 起始页在栈底，pop 不动：系统返回键与左上角箭头都走「回家」，
                    // 非栈底时（从行情页点进来）leaveDetail 会正常 pop。
                    BackHandler { leaveDetail() }
                    DetailScreen(
                        symbolId = id,
                        onBack = leaveDetail,
                        onCreateAlert = { navController.navigate(Routes.alertNew(id.market, id.symbol)) },
                    )
                }
            }
            composable(
                route = Routes.DETAIL_TEMPLATE,
                arguments = listOf(
                    navArgument("market") { type = NavType.StringType },
                    navArgument("symbol") { type = NavType.StringType },
                ),
            ) { entry ->
                val id = SymbolId(
                    market = MarketType.fromKey(entry.arguments?.getString("market") ?: MarketType.SPOT.key),
                    symbol = entry.arguments?.getString("symbol").orEmpty(),
                )
                DetailScreen(
                    symbolId = id,
                    onBack = leaveDetail,
                    onCreateAlert = { navController.navigate(Routes.alertNew(id.market, id.symbol)) },
                )
            }
        }
    }
}
