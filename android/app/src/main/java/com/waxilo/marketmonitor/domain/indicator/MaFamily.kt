package com.waxilo.marketmonitor.domain.indicator

/**
 * 主图均线族：MA（简单均线）/ EMA（指数均线）。
 *
 * 两族**结构完全对称** —— 各有自己的目录（取哪几档）、八色槽与整族显隐开关，
 * 指标条上各只占一枚 chip。所以凡是「一族」的代码都按它泛化，不写两遍。
 *
 * 与桌面端 `lib/chartSeries.ts` 的 `MaKind` 同口径（连同默认值一起，见 [DEFAULT_MA_CATALOG]
 * 与 [DEFAULT_EMA_CATALOG]）。
 */
enum class MaFamily(
    /** 界面短名，也是读数带与配置列表里的前缀：`MA` / `EMA`。 */
    val label: String,
    /** 面板里的全称（说明这两枚 chip 分别是什么）。 */
    val displayName: String,
) {
    MA("MA", "简单均线"),
    EMA("EMA", "指数均线"),
    ;

    /** 一条线的名字：`MA5` / `EMA12`。 */
    fun lineLabel(period: Int): String = "$label$period"
}

/** MA 一族的出厂目录：就是用户可编辑之前那固定五档。 */
val DEFAULT_MA_CATALOG: List<Int> = listOf(5, 10, 20, 30, 60)

/** EMA 一族的出厂目录：最常用的三条。 */
val DEFAULT_EMA_CATALOG: List<Int> = listOf(12, 26, 50)

/** 族 → 出厂目录（「恢复默认」一键回到这里）。 */
fun defaultCatalog(family: MaFamily): List<Int> = when (family) {
    MaFamily.MA -> DEFAULT_MA_CATALOG
    MaFamily.EMA -> DEFAULT_EMA_CATALOG
}
