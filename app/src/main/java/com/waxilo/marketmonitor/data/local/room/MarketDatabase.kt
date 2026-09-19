package com.waxilo.marketmonitor.data.local.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 本地缓存库（PRD 3.2 缓存策略）。版本 1；结构变化时按 PRD 允许丢弃缓存
 * （行情缓存可重建，用户数据——自选/预警/消息——由迁移脚本负责，不用破坏性迁移兜底）。
 */
@Database(
    entities = [
        TickerEntity::class,
        InstrumentEntity::class,
        KlineEntity::class,
        WatchlistEntity::class,
        AlertRuleEntity::class,
        AlertStateEntity::class,
        AlertLogEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class MarketDatabase : RoomDatabase() {

    abstract fun tickerDao(): TickerDao
    abstract fun instrumentDao(): InstrumentDao
    abstract fun klineDao(): KlineDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun alertDao(): AlertDao

    companion object {
        const val NAME = "market-monitor.db"

        fun create(context: Context): MarketDatabase =
            Room.databaseBuilder(context.applicationContext, MarketDatabase::class.java, NAME)
                .build()
    }
}
