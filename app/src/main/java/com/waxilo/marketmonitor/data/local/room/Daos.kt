package com.waxilo.marketmonitor.data.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TickerDao {

    @Upsert
    suspend fun upsertAll(items: List<TickerEntity>)

    @Query("SELECT * FROM ticker WHERE market = :market")
    fun observeMarket(market: String): Flow<List<TickerEntity>>

    @Query("SELECT * FROM ticker WHERE market = :market AND symbol = :symbol")
    fun observeOne(market: String, symbol: String): Flow<TickerEntity?>

    @Query("SELECT * FROM ticker WHERE market = :market AND symbol = :symbol")
    suspend fun findOne(market: String, symbol: String): TickerEntity?

    /** 缓存新鲜度判定（超过阈值即标记「离线数据」）。 */
    @Query("SELECT updatedAt FROM ticker WHERE market = :market ORDER BY updatedAt DESC LIMIT 1")
    suspend fun lastUpdatedAt(market: String): Long?

    @Query("DELETE FROM ticker WHERE market = :market")
    suspend fun clear(market: String)
}

@Dao
interface InstrumentDao {

    @Upsert
    suspend fun upsertAll(items: List<InstrumentEntity>)

    @Query("SELECT * FROM instrument WHERE market = :market ORDER BY symbol ASC")
    fun observeMarket(market: String): Flow<List<InstrumentEntity>>

    @Query("SELECT * FROM instrument WHERE market = :market AND symbol = :symbol")
    suspend fun findOne(market: String, symbol: String): InstrumentEntity?

    @Query("SELECT COUNT(*) FROM instrument WHERE market = :market")
    suspend fun count(market: String): Int

    /** 下架标的清理：本轮同步时间戳早于上次同步即视为已消失。 */
    @Query("DELETE FROM instrument WHERE market = :market AND syncedAt < :before")
    suspend fun deleteStale(market: String, before: Long)
}

@Dao
interface KlineDao {

    @Upsert
    suspend fun upsertAll(items: List<KlineEntity>)

    @Query(
        """
        SELECT * FROM kline
        WHERE market = :market AND symbol = :symbol AND intervalKey = :intervalKey
        ORDER BY openTime DESC LIMIT :limit
        """
    )
    suspend fun latest(market: String, symbol: String, intervalKey: String, limit: Int): List<KlineEntity>

    @Query(
        """
        SELECT * FROM kline
        WHERE market = :market AND symbol = :symbol AND intervalKey = :intervalKey AND openTime < :before
        ORDER BY openTime DESC LIMIT :limit
        """
    )
    suspend fun before(
        market: String,
        symbol: String,
        intervalKey: String,
        before: Long,
        limit: Int,
    ): List<KlineEntity>

    @Query("SELECT MIN(openTime) FROM kline WHERE market = :market AND symbol = :symbol AND intervalKey = :intervalKey")
    suspend fun oldestOpenTime(market: String, symbol: String, intervalKey: String): Long?

    @Query("DELETE FROM kline WHERE market = :market AND symbol = :symbol AND intervalKey = :intervalKey")
    suspend fun clearFor(market: String, symbol: String, intervalKey: String)

    /** 只保留每个标的最近 N 根，防止长期累积撑爆存储（PRD 6 存储上限）。 */
    @Query(
        """
        DELETE FROM kline
        WHERE market = :market AND symbol = :symbol AND intervalKey = :intervalKey
          AND openTime < (
            SELECT openTime FROM kline
            WHERE market = :market AND symbol = :symbol AND intervalKey = :intervalKey
            ORDER BY openTime DESC LIMIT 1 OFFSET :keep
          )
        """
    )
    suspend fun trim(market: String, symbol: String, intervalKey: String, keep: Int)
}

@Dao
interface WatchlistDao {

    @Query("SELECT * FROM watchlist WHERE market = :market ORDER BY position ASC")
    fun observeMarket(market: String): Flow<List<WatchlistEntity>>

    @Query("SELECT * FROM watchlist WHERE market = :market ORDER BY position ASC")
    suspend fun findAll(market: String): List<WatchlistEntity>

    @Upsert
    suspend fun upsertAll(items: List<WatchlistEntity>)

    @Query("DELETE FROM watchlist WHERE market = :market AND symbol = :symbol")
    suspend fun remove(market: String, symbol: String)

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE market = :market AND symbol = :symbol)")
    suspend fun contains(market: String, symbol: String): Boolean
}

@Dao
interface AlertDao {

    @Query("SELECT * FROM alert_rule ORDER BY createdAt DESC")
    fun observeRules(): Flow<List<AlertRuleEntity>>

    @Query("SELECT * FROM alert_rule WHERE id = :id")
    suspend fun findRule(id: Long): AlertRuleEntity?

    @Query("SELECT * FROM alert_rule WHERE enabled = 1")
    suspend fun enabledRules(): List<AlertRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: AlertRuleEntity): Long

    @Query("UPDATE alert_rule SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Int)

    @Query("DELETE FROM alert_rule WHERE id = :id")
    suspend fun deleteRule(id: Long)

    @Query("SELECT * FROM alert_state WHERE ruleId = :ruleId")
    suspend fun findState(ruleId: Long): AlertStateEntity?

    @Upsert
    suspend fun upsertState(state: AlertStateEntity)

    @Query("DELETE FROM alert_state WHERE ruleId = :ruleId")
    suspend fun deleteState(ruleId: Long)

    @Insert
    suspend fun insertLog(log: AlertLogEntity): Long

    @Query("SELECT * FROM alert_log ORDER BY triggeredAt DESC LIMIT :limit")
    fun observeLogs(limit: Int): Flow<List<AlertLogEntity>>

    @Query("SELECT COUNT(*) FROM alert_log WHERE acknowledged = 0")
    fun unreadCount(): Flow<Int>

    @Query("UPDATE alert_log SET acknowledged = 1 WHERE id = :id")
    suspend fun acknowledge(id: Long)

    @Query("UPDATE alert_log SET acknowledged = 1 WHERE acknowledged = 0")
    suspend fun acknowledgeAll()

    @Query("UPDATE alert_log SET webhookStatus = :status WHERE id = :id")
    suspend fun setWebhookStatus(id: Long, status: Int)

    @Query("SELECT * FROM alert_log WHERE webhookStatus = :status ORDER BY triggeredAt ASC LIMIT :limit")
    suspend fun logsByWebhookStatus(status: Int, limit: Int): List<AlertLogEntity>

    @Query("DELETE FROM alert_log WHERE triggeredAt < :before")
    suspend fun deleteLogsOlderThan(before: Long)
}
