package com.waxilo.marketmonitor.data.remote

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 现货域名链的「健康记忆」。
 *
 * 旧实现每次都从固定顺序的第一个域名开始试：在**移动网络**下（没有 Wi-Fi 代理），
 * 币安的前几个域经常被黑洞，于是每次请求都先白等一个连接超时再换下一个 ——
 * 一次加载几十秒，看起来就是「App 在蜂窝网络下很慢」。这里记住最近成功的主机并置顶，
 * 失败的主机进入冷却，本轮不再反复撞它。
 *
 * 所有主机都失败时再给整条链一个短暂的「停试窗」（[allFailedCooldownMs]）：
 * 断网 / 被墙期间请求应当**快速失败**并让界面显示离线，而不是每一拍都重走一遍
 * 几十秒的域名链。窗口过后自动重试，恢复网络无需重启 App。
 *
 * 冷却与首选都是纯内存状态，且**只对产生它们的那条网络路径有意义**：
 * 开 / 关 VPN、换 Wi-Fi 与蜂窝之后由 [reset] 整份作废，下一次请求从链首重来。
 */
internal class SpotHostHealth(
    private val now: () -> Long = System::currentTimeMillis,
    /** 单个主机失败后的冷却：期间排在链尾，只有其它主机全都不可用时才重试。 */
    private val hostCooldownMs: Long = 3 * 60_000L,
    /** 整条链全失败后的停试窗：窗口内直接抛上次的错，不再逐个撞超时。 */
    private val allFailedCooldownMs: Long = 15_000L,
) {

    @Volatile
    private var preferred: String? = null

    private val failedAt = ConcurrentHashMap<String, Long>()

    @Volatile
    private var allFailedSource: String? = null

    @Volatile
    private var allFailedUntil = 0L

    @Volatile
    var lastFailure: IOException? = null
        private set

    /** 按「首选 → 其它健康主机 → 冷却中的主机（更早失败的先试）」排序。 */
    fun order(hosts: List<String>): List<String> {
        val at = now()
        val cooling = hosts.filter { isCooling(it, at) }
        val healthy = hosts.filterNot { isCooling(it, at) }
        val preferredFirst = healthy.sortedBy { if (it == preferred) 0 else 1 }
        return preferredFirst + cooling.sortedBy { failedAt[it] ?: 0L }
    }

    fun noteOk(host: String) {
        preferred = host
        failedAt.remove(host)
        allFailedSource = null
        allFailedUntil = 0L
    }

    fun noteFailed(host: String) {
        failedAt[host] = now()
    }

    /** 整条链刚刚全部不可用：记下错误，并让接下来的一小段时间直接失败。 */
    fun noteAllFailed(sourceKey: String, error: IOException) {
        lastFailure = error
        allFailedSource = sourceKey
        allFailedUntil = now() + allFailedCooldownMs
    }

    /** 当前源是否处于全失败停试窗内。 */
    fun unavailable(sourceKey: String): Boolean =
        now() < allFailedUntil && allFailedSource == sourceKey

    /**
     * 网络路径变了（开 / 关 VPN、换网、系统代理变更）：整份记忆作废。
     *
     * 旧路径上的成败对新路径一点参考价值都没有 —— 开着 VPN 时首选的主机，关掉 VPN 后
     * 可能只剩超时；而刚被记成「冷却中」的主机，恰恰可能是新路径上唯一能直连的那个。
     * 不清掉的话，链路要带着旧路径的偏见再撞满一两分钟。
     */
    fun reset() {
        preferred = null
        failedAt.clear()
        allFailedSource = null
        allFailedUntil = 0L
        lastFailure = null
    }

    private fun isCooling(host: String, at: Long): Boolean {
        val failed = failedAt[host] ?: return false
        return at - failed < hostCooldownMs
    }
}
