package com.waxilo.marketmonitor.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.ui.alerts.AlertEditorScreen
import com.waxilo.marketmonitor.ui.alerts.AlertsScreen
import com.waxilo.marketmonitor.ui.detail.DetailScreen
import com.waxilo.marketmonitor.ui.market.MarketScreen
import com.waxilo.marketmonitor.ui.common.HintRow
import com.waxilo.marketmonitor.ui.search.SearchScreen
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

@Composable
fun AppNavHost(openAlerts: Boolean = false, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val openDetail: (SymbolId) -> Unit = { navController.navigate(Routes.detail(it)) }

    NavHost(
        navController = navController,
        // 从通知栏进入时首帧就落在预警页，避免先闪一下行情列表
        startDestination = if (openAlerts) Routes.ALERTS else Routes.MARKET,
        modifier = modifier,
    ) {
        composable(Routes.MARKET) {
            MarketScreen(
                onOpenDetail = openDetail,
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenAlerts = { navController.navigate(Routes.ALERTS) },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(onOpenDetail = openDetail)
        }
        composable(Routes.SETTINGS) {
            // 设置页与在线更新在下一阶段实现（任务 #8），此处先占位
            HintRow(title = "设置页正在开发", subtitle = "计价币、刷新间隔、预警与更新将在此提供")
        }
        composable(Routes.ALERTS) {
            AlertsScreen(
                onBack = { navController.popBackStack() },
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
                presetMarket = MarketType.fromKey(entry.arguments?.getString("market") ?: MarketType.SPOT.key),
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
                presetMarket = MarketType.SPOT,
                presetSymbol = "",
                onBack = { navController.popBackStack() },
            )
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
                onBack = { navController.popBackStack() },
                onCreateAlert = { navController.navigate(Routes.alertNew(id.market, id.symbol)) },
            )
        }
    }
}
