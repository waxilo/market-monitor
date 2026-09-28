package com.waxilo.marketmonitor.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.waxilo.marketmonitor.data.remote.MarketJson
import com.waxilo.marketmonitor.domain.repository.WebhookRepository
import com.waxilo.marketmonitor.domain.webhook.WebhookEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Webhook 端点存储（PRD 4.4：URL 含密钥，必须加密落盘）。
 * 端点数量很小（个位数），整份 JSON 存进 EncryptedSharedPreferences 比给 Room 接一层
 * 加密更简单，也不会因为查询解密而出现半明文状态。
 */
@Serializable
private data class WebhookFile(val items: List<WebhookEndpoint> = emptyList())

class EncryptedWebhookStore private constructor(
    private val prefs: SharedPreferences,
) : WebhookRepository {

    private val cache = MutableStateFlow(read())

    override fun endpoints(): StateFlow<List<WebhookEndpoint>> = cache.asStateFlow()

    override suspend fun all(): List<WebhookEndpoint> = cache.value

    override suspend fun find(id: Long): WebhookEndpoint? = cache.value.firstOrNull { it.id == id }

    override suspend fun save(endpoint: WebhookEndpoint): Long {
        val items = cache.value.toMutableList()
        val saved = if (endpoint.id == 0L) {
            val id = (items.maxOfOrNull { it.id } ?: 0L) + 1L
            endpoint.copy(id = id, createdAt = if (endpoint.createdAt == 0L) System.currentTimeMillis() else endpoint.createdAt)
        } else {
            endpoint
        }
        val index = items.indexOfFirst { it.id == saved.id }
        if (index >= 0) items[index] = saved else items.add(saved)
        write(items)
        return saved.id
    }

    override suspend fun delete(id: Long) {
        write(cache.value.filterNot { it.id == id })
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) {
        write(cache.value.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    private fun read(): List<WebhookEndpoint> {
        val raw = prefs.getString(KEY, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            MarketJson.DEFAULT.decodeFromString(WebhookFile.serializer(), raw).items
        }.getOrElse { emptyList() }
    }

    private fun write(items: List<WebhookEndpoint>) {
        val raw = MarketJson.DEFAULT.encodeToString(WebhookFile.serializer(), WebhookFile(items))
        prefs.edit().putString(KEY, raw).apply()
        cache.value = items
    }

    companion object {
        private const val KEY = "endpoints"
        const val FILE_NAME = "webhooks.enc"

        fun create(context: Context): EncryptedWebhookStore {
            val keySpec = try {
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            } catch (e: GeneralSecurityException) {
                throw IllegalStateException("无法创建本地密钥，Webhook 配置不可用", e)
            } catch (e: IOException) {
                throw IllegalStateException("无法创建本地密钥，Webhook 配置不可用", e)
            }
            val prefs = EncryptedSharedPreferences.create(
                FILE_NAME,
                keySpec,
                context.applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return EncryptedWebhookStore(prefs)
        }
    }
}
