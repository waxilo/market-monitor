package com.waxilo.marketmonitor.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.waxilo.marketmonitor.domain.repository.ApiCredentials
import com.waxilo.marketmonitor.domain.repository.BinanceCredentialRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * 币安 API 凭据存储。secret 是能签名动账户的密钥，必须加密落盘，
 * 与 [EncryptedWebhookStore] 同一套 Keystore 主密钥方案；
 * 量小（一对字符串），EncryptedSharedPreferences 足够，不值得为它开 Room。
 */
class EncryptedBinanceCredentialStore private constructor(
    private val prefs: SharedPreferences,
) : BinanceCredentialRepository {

    private val cache = MutableStateFlow(read())

    override val credentials: StateFlow<ApiCredentials?> = cache

    override suspend fun save(credentials: ApiCredentials) {
        if (!credentials.isConfigured) {
            clear()
            return
        }
        prefs.edit()
            .putString(KEY_API_KEY, credentials.key.trim())
            .putString(KEY_API_SECRET, credentials.secret.trim())
            .apply()
        cache.value = credentials.copy(key = credentials.key.trim(), secret = credentials.secret.trim())
    }

    override suspend fun clear() {
        prefs.edit().remove(KEY_API_KEY).remove(KEY_API_SECRET).apply()
        cache.value = null
    }

    private fun read(): ApiCredentials? {
        val key = prefs.getString(KEY_API_KEY, null)
        val secret = prefs.getString(KEY_API_SECRET, null)
        return if (key.isNullOrBlank() || secret.isNullOrBlank()) null
        else ApiCredentials(key, secret)
    }

    companion object {
        private const val KEY_API_KEY = "api_key"
        private const val KEY_API_SECRET = "api_secret"
        const val FILE_NAME = "binance.enc"

        fun create(context: Context): EncryptedBinanceCredentialStore {
            val keySpec = try {
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            } catch (e: GeneralSecurityException) {
                throw IllegalStateException("无法创建本地密钥，币安凭据不可用", e)
            } catch (e: IOException) {
                throw IllegalStateException("无法创建本地密钥，币安凭据不可用", e)
            }
            val prefs = EncryptedSharedPreferences.create(
                FILE_NAME,
                keySpec,
                context.applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return EncryptedBinanceCredentialStore(prefs)
        }
    }
}
