package com.waxilo.marketmonitor.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.io.IOException

/**
 * 探针竞速：**谁先成功谁算数**，不等落后的成员，并把它们取消掉。
 *
 * 为什么需要它 —— 一个现货源是一串主机（币安 6 个），其中打不通的那几个每条都要跑满
 * 5 秒超时。旧口径是 `attempts.map { async { … } }.awaitAll()` 再取 min：结果「显示的延迟」
 * （最快那条）与「这一行什么时候回填」（最慢那条）变成两个量 —— 币安行恒定在 ~5.1 秒才亮，
 * 而它写着 1.6 秒，用户看到的就是「Gate 每次先测完，币安最后测完延迟却不高」。
 *
 * 语义：
 *  - 任一成功 → 立刻返回它的值，其余 attempt 被取消（`supervisorScope` 退出时取消子协程，
 *    取消会经 `awaitText` 的 `invokeOnCancellation` 传进 OkHttp 的 call）；
 *  - **全挂** → 抛**第 0 个**那条的异常：要回答的是「首选域为什么不通」，不是随机一个镜像；
 *  - 列表为空 → 抛 [IllegalArgumentException]（调用方的 hosts 不该为空，属编程错误）。
 *
 * `attempts` 里的下标即优先级：全挂时的结论只认 0 号，与「谁先失败」无关。
 */
internal suspend fun <R> firstSuccess(attempts: List<suspend () -> R>): R = supervisorScope {
    require(attempts.isNotEmpty()) { "探测列表不能为空" }
    val outcomes = arrayOfNulls<Result<R>>(attempts.size)
    // 容量 = 成员数：每个 attempt 只发一条，收发不会互相阻塞
    val replies = Channel<Pair<Int, Result<R>>>(attempts.size)
    val jobs = attempts.mapIndexed { index, attempt ->
        launch {
            val result =
                try {
                    Result.success(attempt())
                } catch (e: CancellationException) {
                    // 取消不是失败：被取消时静默退出，别把它记成一条「探测失败」
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            replies.send(index to result)
        }
    }
    try {
        repeat(attempts.size) {
            val (index, result) = replies.receive()
            outcomes[index] = result
            result.getOrNull()?.let { return@supervisorScope it }
        }
        throw outcomes[0]?.exceptionOrNull() ?: IOException("探测失败")
    } finally {
        jobs.forEach { it.cancel() }
        replies.close()
    }
}
