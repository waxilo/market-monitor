package com.waxilo.marketmonitor.data.local.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.waxilo.marketmonitor.domain.indicator.MaAnalysis

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
        ChartLineEntity::class,
    ],
    version = 13,
    exportSchema = false,
)
abstract class MarketDatabase : RoomDatabase() {

    abstract fun tickerDao(): TickerDao
    abstract fun instrumentDao(): InstrumentDao
    abstract fun klineDao(): KlineDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun alertDao(): AlertDao
    abstract fun chartLineDao(): ChartLineDao

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

        /**
         * v7→v8：告警冷却整体下线 —— 判定不再有冷却压制，每次穿越都推送；
         * 依赖冷却才有意义的「重复提醒」模式一并不用。
         *
         * minSdk 26 的 SQLite 无 DROP COLUMN，alert_rule 只能整表重建来去掉 cooldownMinutes。
         * 重建的同时把存量 repeatMode 归一：已下线的 repeat 一律 every_cross；
         * 划线自动挂的 indicator 规则原本的 once（旧的「单次划线」）也一并归一 ——
         * 划线告警现在是「每次穿越」。手动规则的 once（用户显式选的单次）原样保留。
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE `alert_rule_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `market` TEXT NOT NULL,
                        `symbol` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `condition` TEXT NOT NULL,
                        `threshold` TEXT,
                        `rangeLower` TEXT,
                        `rangeUpper` TEXT,
                        `changePercent` TEXT,
                        `repeatMode` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `playSound` INTEGER NOT NULL,
                        `vibrate` INTEGER NOT NULL,
                        `webhookIds` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `source` TEXT NOT NULL DEFAULT 'manual',
                        `maPeriod` INTEGER,
                        `indicatorAlertId` INTEGER,
                        `indicatorLineId` INTEGER
                    )
                    """
                )
                db.execSQL(
                    """
                    INSERT INTO `alert_rule_new`
                        (id, market, symbol, name, condition, threshold, rangeLower, rangeUpper,
                         changePercent, repeatMode, enabled, playSound, vibrate, webhookIds,
                         createdAt, source, maPeriod, indicatorAlertId, indicatorLineId)
                    SELECT id, market, symbol, name, condition, threshold, rangeLower, rangeUpper,
                           changePercent,
                           CASE
                               WHEN repeatMode = 'repeat' THEN 'every_cross'
                               WHEN repeatMode = 'once' AND source = 'indicator' THEN 'every_cross'
                               ELSE repeatMode
                           END,
                           enabled, playSound, vibrate, webhookIds, createdAt, source,
                           maPeriod, indicatorAlertId, indicatorLineId
                    FROM `alert_rule`
                    """
                )
                db.execSQL("DROP TABLE `alert_rule`")
                db.execSQL("ALTER TABLE `alert_rule_new` RENAME TO `alert_rule`")
                // 旧的「单次划线」告警随 ONCE 下线一并归一（当时划线告警一律「每次穿越」）
                db.execSQL("UPDATE `indicator_line` SET `alertMode` = 'every_cross' WHERE `alertMode` = 'once'")
            }
        }

        /**
         * v8→v9：全屏划线工具的纯视觉画线（水平线/直线）落库，竖屏也显示。
         * 直线存两个 (时间, 价位) 锚点，与桌面端趋势线同构。
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chart_line` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `market` TEXT NOT NULL,
                        `symbol` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `price` TEXT,
                        `t1` INTEGER,
                        `p1` TEXT,
                        `t2` INTEGER,
                        `p2` TEXT,
                        `createdAt` INTEGER NOT NULL
                    )
                    """
                )
            }
        }

        /**
         * v9→v10：**指标划线整体下线**。
         *
         * 划线表丢弃；它挂出的 source='indicator' 规则没有任何入口还能维护 ——
         * 阈值本由引擎跟随指标实时移动，留着就是一批既不响也不更新的僵尸规则，
         * 连同判定状态一起清掉。
         *
         * alert_rule 顺带重建，去掉划线留下的一串死列（source / indicatorLineId /
         * maPeriod / indicatorAlertId）：minSdk 26 的 SQLite 无 DROP COLUMN，只能整表重建。
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "DELETE FROM alert_state WHERE ruleId IN " +
                        "(SELECT id FROM alert_rule WHERE source = 'indicator')",
                )
                db.execSQL("DELETE FROM alert_rule WHERE source = 'indicator'")
                db.execSQL("DROP TABLE IF EXISTS `indicator_line`")
                db.execSQL(
                    """
                    CREATE TABLE `alert_rule_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `market` TEXT NOT NULL,
                        `symbol` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `condition` TEXT NOT NULL,
                        `threshold` TEXT,
                        `rangeLower` TEXT,
                        `rangeUpper` TEXT,
                        `changePercent` TEXT,
                        `repeatMode` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `playSound` INTEGER NOT NULL,
                        `vibrate` INTEGER NOT NULL,
                        `webhookIds` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """
                )
                db.execSQL(
                    """
                    INSERT INTO `alert_rule_new`
                        (id, market, symbol, name, condition, threshold, rangeLower, rangeUpper,
                         changePercent, repeatMode, enabled, playSound, vibrate, webhookIds, createdAt)
                    SELECT id, market, symbol, name, condition, threshold, rangeLower, rangeUpper,
                           changePercent, repeatMode, enabled, playSound, vibrate, webhookIds, createdAt
                    FROM `alert_rule`
                    """
                )
                db.execSQL("DROP TABLE `alert_rule`")
                db.execSQL("ALTER TABLE `alert_rule_new` RENAME TO `alert_rule`")
            }
        }

        /**
         * v11：划线备注（2026-10-24）。
         *
         * 全屏点一下线 → 右侧竖栏变编辑栏 → 「备注」写一句。两张线表各加一列，都可空：
         * 老行读出来是 null = 没备注，与「删掉备注」同义（清空即写 null，不写空串）。
         *
         * **为什么 alert_rule 不拿 `name` 当备注**：自动画的线名字形如 `8时EMA50`，
         * 它是 EMA 压力支撑面板按名字认领那条线的键（`EmaAnalysis.lineOf`）——
         * 用户一改备注，面板里那行就再也对不上号（变成孤儿线）。所以备注单开一列，
         * name 留给「这条线是谁画的」。
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `alert_rule` ADD COLUMN `note` TEXT")
                db.execSQL("ALTER TABLE `chart_line` ADD COLUMN `note` TEXT")
            }
        }

        /**
         * v12：均线分析的线搬进 `chart_line`（2026-11-26）。
         *
         * 老形态是「一条线 = 一条 alert_rule」，于是画一条参考线必然等于开一条预警；
         * 现在线是线（本表）、告警才是告警（[ChartLineEntity.alertRuleId] 指过去的规则）。
         *
         * 迁移分两步，顺序不能反 —— 先加列，再搬数据：
         *
         *  1. **老线搬过来**：名字能解回 (族, 周期, 档位) 的规则一律插一条带身份的 chart_line，
         *     并把这把原规则挂上去（`alertRuleId`）。**规则本身保留** —— 它可能正开着、
         *     正在响，静默关掉用户的预警比多一条线糟得多；`showBadge` 写 0（新默认）：
         *     这批线正是用户嫌价签太挤的那一批。
         *  2. 名字解不出的（用户改过名 / 手建的预警）**原样不动**，仍在图上当告警线。
         *
         * 不搬的话，升级后面板面对一堆「认不出身份」的告警线会显示成没画过，
         * 用户只能一条条删了自己重画。
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `chart_line` ADD COLUMN `originKind` TEXT")
                db.execSQL("ALTER TABLE `chart_line` ADD COLUMN `originInterval` TEXT")
                db.execSQL("ALTER TABLE `chart_line` ADD COLUMN `originPeriod` INTEGER")
                db.execSQL("ALTER TABLE `chart_line` ADD COLUMN `showBadge` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `chart_line` ADD COLUMN `alertRuleId` INTEGER")

                db.query(
                    "SELECT `id`,`market`,`symbol`,`name`,`threshold`,`note`,`createdAt` " +
                        "FROM `alert_rule` WHERE `threshold` IS NOT NULL",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val origin = MaAnalysis.parseAutoNote(cursor.getString(3)) ?: continue
                        db.execSQL(
                            "INSERT INTO `chart_line` " +
                                "(`market`,`symbol`,`kind`,`price`,`note`,`originKind`," +
                                "`originInterval`,`originPeriod`,`showBadge`,`alertRuleId`,`createdAt`) " +
                                "VALUES (?,?,'h',?,?,?,?,?,0,?,?)",
                            arrayOf(
                                cursor.getString(1),
                                cursor.getString(2),
                                cursor.getString(4),
                                cursor.getString(5),
                                origin.family.name,
                                origin.interval.storageKey,
                                origin.period,
                                cursor.getLong(0),
                                cursor.getLong(6),
                            ),
                        )
                    }
                }
            }
        }

        /**
         * v12 → v13：给**两条存储路径**各加一列线型（实线 / 虚线）。
         *
         * 为什么两张表都要加：图上的线有两个来源 —— 用户手画 + 均线分析的线落在 `chart_line`，
         * 「预警线」工具画的仍是一条 `alert_rule`。竖栏里那枚「线型」按钮对**选中的任何一条线**
         * 都要有效，缺一张表就会出现「同样是线，有的能切虚线、有的不能」。
         *
         * 纯样式列，`NOT NULL DEFAULT 0`（0 = 实线）⇒ 升级后所有老线保持原来的样子，
         * 不搬数据、不需要回填。
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `chart_line` ADD COLUMN `dashed` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `alert_rule` ADD COLUMN `dashed` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): MarketDatabase =
            Room.databaseBuilder(context.applicationContext, MarketDatabase::class.java, NAME)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                )
                .build()
    }
}
