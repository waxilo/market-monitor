package com.waxilo.marketmonitor.domain.repository

import kotlinx.coroutines.flow.StateFlow

/**
 * 币安私有接口凭据（查仓位/现货余额等账户数据用）。
 * key 是标识符、secret 是签名密钥，两者都按敏感数据处理、加密落盘。
 */
data class ApiCredentials(
    val key: String,
    val secret: String,
) {
    val isConfigured: Boolean get() = key.isNotBlank() && secret.isNotBlank()
}

interface BinanceCredentialRepository {
    /** 未配置时为 null；冷启动前即可订阅，读取失败降级为未配置而不是崩溃。 */
    val credentials: StateFlow<ApiCredentials?>

    suspend fun save(credentials: ApiCredentials)

    suspend fun clear()
}
