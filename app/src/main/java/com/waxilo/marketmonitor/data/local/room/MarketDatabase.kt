package com.waxilo.marketmonitor.data.local.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 本地缓存库（PRD 3.2 缓存策略）。结构变化时必须写迁移脚本：
 * 行情缓存可重建，但用户数据（自选/预警/消息）不能靠破坏性迁移兜底。
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
        IndicatorLineEntity::class,
    ],
    version = 7,
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

        /** v1→v2：给规则打上来源标记（后升级为指标告警的 INDICATOR 线）。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alert_rule ADD COLUMN source TEXT NOT NULL DEFAULT 'manual'")
                db.execSQL("ALTER TABLE alert_rule ADD COLUMN maPeriod INTEGER")
            }
        }

        /**
         * v2→v3：指标告警（均线 + BOLL 两种条目）升级为预警模块的正式条目（独立 tab 管理），
         * 规则回指归属条目。旧「详情页开关」注入的 source='ma' 线没有归属、语义也已换 key，
         * 直接清掉（该版本从未发布，只可能是开发期数据），连带其判定状态行。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `indicator_alert` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `market` TEXT NOT NULL,
                        `symbol` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `maPeriods` TEXT NOT NULL,
                        `intervalKeys` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """
                )
                db.execSQL("ALTER TABLE alert_rule ADD COLUMN indicatorAlertId INTEGER")
                db.execSQL("DELETE FROM alert_state WHERE ruleId IN (SELECT id FROM alert_rule WHERE source = 'ma')")
                db.execSQL("DELETE FROM alert_rule WHERE source = 'ma'")
            }
        }

        /**
         * v3→v4：「指标告警条目」（候选池夹逼）重构为详情页「指标划线」（逐条显式配置）。
         * 旧条目表与其挂出的 source='indicator' 规则整体作废（该版本从未发布，只可能是开发期数据），
         * 划线规则改挂 indicator_line 新表。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `indicator_line` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `market` TEXT NOT NULL,
                        `symbol` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `intervalKey` TEXT NOT NULL,
                        `maPeriod` INTEGER NOT NULL,
                        `band` TEXT NOT NULL,
                        `alertMode` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """
                )
                db.execSQL("ALTER TABLE alert_rule ADD COLUMN indicatorLineId INTEGER")
                db.execSQL("DELETE FROM alert_state WHERE ruleId IN (SELECT id FROM alert_rule WHERE source = 'indicator')")
                db.execSQL("DELETE FROM alert_rule WHERE source = 'indicator'")
                db.execSQL("DROP TABLE IF EXISTS `indicator_alert`")
            }
        }

        /**
         * v4→v5：划线新增「均线带」聚合条目——多选周期合成一个集合，
         * 引擎按现价取集合内上/下方最近均线各挂一条持续跟随的预警线。
         * indicator_line 加 members 列存成员组合；旧单线条目不受影响（空串）。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE indicator_line ADD COLUMN `members` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v5→v6：BOLL 划线整体下线（效果不好）。kind='boll' 的划线连同其规则与判定状态一并清除；
         * indicator_line 重建去掉 band 列（minSdk 26 无 DROP COLUMN，均线带数据原样保留）。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "DELETE FROM alert_state WHERE ruleId IN " +
                        "(SELECT id FROM alert_rule WHERE indicatorLineId IN " +
                        "(SELECT id FROM indicator_line WHERE kind = 'boll'))",
                )
                db.execSQL(
                    "DELETE FROM alert_rule WHERE indicatorLineId IN " +
                        "(SELECT id FROM indicator_line WHERE kind = 'boll')",
                )
                db.execSQL(
                    """
                    CREATE TABLE `indicator_line_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `market` TEXT NOT NULL,
                        `symbol` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `intervalKey` TEXT NOT NULL,
                        `maPeriod` INTEGER NOT NULL,
                        `alertMode` TEXT NOT NULL,
                        `members` TEXT NOT NULL DEFAULT '',
                        `createdAt` INTEGER NOT NULL
                    )
                    """
                )
                db.execSQL(
                    "INSERT INTO indicator_line_new " +
                        "(id, market, symbol, kind, intervalKey, maPeriod, alertMode, members, createdAt) " +
                        "SELECT id, market, symbol, kind, intervalKey, maPeriod, alertMode, members, createdAt " +
                        "FROM indicator_line WHERE kind != 'boll'",
                )
                db.execSQL("DROP TABLE `indicator_line`")
                db.execSQL("ALTER TABLE indicator_line_new RENAME TO indicator_line")
            }
        }

        /**
         * v6→v7：划线去列表化后新增启用开关。关闭 = 引擎与图上都当它不存在，
         * 但配置原样保留（取代列表弹层里的删除动作）。存量行默认启用。
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE indicator_line ADD COLUMN `enabled` INTEGER NOT NULL DEFAULT 1")
            }
        }

        fun create(context: Context): MarketDatabase =
            Room.databaseBuilder(context.applicationContext, MarketDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build()
    }
}
