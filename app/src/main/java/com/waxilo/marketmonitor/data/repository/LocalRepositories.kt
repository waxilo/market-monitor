package com.waxilo.marketmonitor.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.waxilo.marketmonitor.data.local.room.AlertDao
import com.waxilo.marketmonitor.data.local.room.WatchlistDao
import com.waxilo.marketmonitor.data.local.room.WatchlistEntity
import com.waxilo.marketmonitor.data.local.room.toDomain
import com.waxilo.marketmonitor.data.local.room.toEntity
import com.waxilo.marketmonitor.data.local.room.toSymbolId
import com.waxilo.marketmonitor.domain.alert.AlertRule
import com.waxilo.marketmonitor.domain.alert.AlertState
import com.waxilo.marketmonitor.domain.alert.IndicatorLine
import com.waxilo.marketmonitor.domain.alert.LineAlertMode
import com.waxilo.marketmonitor.domain.model.MarketType
import com.waxilo.marketmonitor.domain.model.SymbolId
import com.waxilo.marketmonitor.domain.repository.AlertMessage
import com.waxilo.marketmonitor.domain.repository.AlertRepository
import com.waxilo.marketmonitor.domain.repository.WebhookDelivery
import com.waxilo.marketmonitor.domain.repository.WatchlistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class WatchlistRepositoryImpl(
    private val dao: WatchlistDao,
    private val db: RoomDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : WatchlistRepository {

    override fun watchlist(market: MarketType): Flow<List<SymbolId>> =
        dao.observeMarket(market.key).map { rows -> rows.map { it.toSymbolId() } }

    override suspend fun contains(id: SymbolId): Boolean = dao.contains(id.market.key, id.symbol)

    override suspend fun add(id: SymbolId) {
        db.withTransaction {
            val items = dao.findAll(id.market.key)
            if (items.any { it.symbol == id.symbol }) return@withTransaction
            dao.upsertAll(
                items + WatchlistEntity(
                    market = id.market.key,
                    symbol = id.symbol,
                    position = items.size,
                    addedAt = clock(),
                ),
            )
        }
    }

    override suspend fun remove(id: SymbolId) {
        dao.remove(id.market.key, id.symbol)
    }

    override suspend fun move(id: SymbolId, toIndex: Int) {
        db.withTransaction {
            val items = dao.findAll(id.market.key).toMutableList()
            val from = items.indexOfFirst { it.symbol == id.symbol }
            if (from < 0) return@withTransaction
            val moved = items.removeAt(from)
            items.add(toIndex.coerceIn(0, items.size), moved)
            dao.upsertAll(items.mapIndexed { index, entity -> entity.copy(position = index) })
        }
    }
}

/** 预警规则、判定状态与触发记录（PRD 4.3 / 4.5）。 */
class AlertRepositoryImpl(private val dao: AlertDao) : AlertRepository {

    override fun rules(): Flow<List<AlertRule>> = dao.observeRules().map { rows -> rows.map { it.toDomain() } }

    override suspend fun rule(id: Long): AlertRule? = dao.findRule(id)?.toDomain()

    override suspend fun enabledRules(): List<AlertRule> = dao.enabledRules().map { it.toDomain() }

    override suspend fun saveRule(rule: AlertRule): Long {
        val entity = rule.toEntity()
        // id 为 0 表示新建：autoGenerate 会补真实 id，需要回传给调用方做后续关联
        return if (rule.id == 0L) dao.insertRule(entity.copy(id = 0L)) else {
            dao.insertRule(entity)
            rule.id
        }
    }

    override suspend fun deleteRule(id: Long) {
        dao.deleteRule(id)
        dao.deleteState(id)
    }

    override suspend fun setRuleEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, if (enabled) 1 else 0)

    override suspend fun state(ruleId: Long): AlertState = dao.findState(ruleId)?.toDomain() ?: AlertState()

    override suspend fun saveState(ruleId: Long, state: AlertState) = dao.upsertStateIfRuleExists(state.toEntity(ruleId))

    override fun messages(limit: Int): Flow<List<AlertMessage>> =
        dao.observeLogs(limit).map { rows -> rows.map { it.toDomain() } }

    override fun unreadCount(): Flow<Int> = dao.unreadCount()

    override suspend fun record(message: AlertMessage): Long = dao.insertLog(message.toEntity())

    override suspend fun acknowledge(id: Long) = dao.acknowledge(id)

    override suspend fun acknowledgeAll() = dao.acknowledgeAll()

    override suspend fun clearMessages() = dao.deleteAllLogs()

    override suspend fun messagesWithDelivery(delivery: WebhookDelivery, limit: Int): List<AlertMessage> =
        dao.logsByWebhookStatus(delivery.code, limit).map { it.toDomain() }

    override suspend fun setDelivery(id: Long, delivery: WebhookDelivery) = dao.setWebhookStatus(id, delivery.code)

    override suspend fun pruneMessages(olderThan: Long) = dao.deleteLogsOlderThan(olderThan)

    override fun indicatorLines(): Flow<List<IndicatorLine>> =
        dao.observeIndicatorLines().map { rows -> rows.map { it.toDomain() } }

    override suspend fun saveIndicatorLine(line: IndicatorLine): Long {
        val entity = line.toEntity()
        return if (line.id == 0L) dao.upsertIndicatorLine(entity.copy(id = 0L)) else {
            dao.upsertIndicatorLine(entity)
            line.id
        }
    }

    override suspend fun deleteIndicatorLine(id: Long) = dao.deleteIndicatorLine(id)

    override suspend fun setIndicatorLineAlertMode(id: Long, mode: LineAlertMode) =
        dao.setIndicatorLineAlertMode(id, mode.key)
}
