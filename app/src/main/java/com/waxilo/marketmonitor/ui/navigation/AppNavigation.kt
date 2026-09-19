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
import com.waxilo.marketmonitor.ui.common.HintRow
import com.waxilo.marketmonitor.ui.detail.DetailScreen
import com.waxilo.marketmonitor.ui.market.MarketScreen
import com.waxilo.marketmonitor.ui.search.SearchScreen

/** 路由：详情用两段路径参数携带 (market, symbol)，避免复合键里出现斜杠转义问题。 */
object Routes {
    const val MARKET = "market"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val DETAIL_TEMPLATE = "detail/{market}/{symbol}"

    fun detail(id: SymbolId): String = "detail/${id.market.key}/${id.symbol}"
}

@Composable
fun AppNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val openDetail: (SymbolId) -> Unit = { navController.navigate(Routes.detail(it)) }

    NavHost(
        navController = navController,
        startDestination = Routes.MARKET,
        modifier = modifier,
    ) {
        composable(Routes.MARKET) {
            MarketScreen(
                onOpenDetail = openDetail,
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(onOpenDetail = openDetail)
        }
        composable(Routes.SETTINGS) {
            HintRow(title = "设置页正在开发", subtitle = "计价币、刷新间隔、预警与更新将在此提供")
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
            DetailScreen(symbolId = id, onBack = { navController.popBackStack() })
        }
    }
}
