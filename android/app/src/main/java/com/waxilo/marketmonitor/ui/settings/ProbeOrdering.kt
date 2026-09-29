package com.waxilo.marketmonitor.ui.settings

import com.waxilo.marketmonitor.domain.model.FuturesEndpoint

/**
 * 行情接口候选的排序规则（从 `FuturesSourcePickerDialog` 里抽出来的纯函数）。
 *
 * 抽出来的理由不是「看着整齐」，而是这条规则**有三个非平凡的性质**，而它们只靠肉眼看界面
 * 是验不出来的（顺序错了照样能点、能选中）：
 *
 * 1. **通的在前、连不上的在中、未检测的在最后** —— 探测是逐条回填的，早期「未检测」
 *    必须暂时沉底，不能因为它们还没结果就把已经确定的结论挤下去；
 * 2. **通的内部按延迟升序** —— 这就是这个弹窗存在的意义（挑最快的）。延迟相同时
 *    保持 `ALL` 里的原始顺序（`sortedWith` 稳定），不能让界面每次重排都换一个样子；
 * 3. **连不上的也要有稳定顺序** —— 它们全部拿 `Long.MAX_VALUE` 当键，靠稳定排序
 *    退回原始顺序；否则端口扫描失败的那几条会随 hash 顺序乱跳。
 */
object ProbeOrdering {

    /** 按探测结论排序：通的（延迟升序）→ 连不上的 → 未检测的。 */
    fun of(
        all: List<FuturesEndpoint>,
        results: Map<String, ProbeOutcome>,
    ): List<FuturesEndpoint> = all.sortedWith(
        compareBy(
            { results[it.baseUrl].sortRank() },
            { results[it.baseUrl].latencyOrMax() },
        )
    )
}

/** 排序名次：通的 0、连不上的 1、未检测的 2。 */
private fun ProbeOutcome?.sortRank(): Int = when (this) {
    is ProbeOutcome.Reachable -> 0
    is ProbeOutcome.Failed -> 1
    null -> 2
}

/** 同类排序键：只有通的有真实延迟，其余给最大值沉到本档末尾。 */
private fun ProbeOutcome?.latencyOrMax(): Long =
    (this as? ProbeOutcome.Reachable)?.latencyMs ?: Long.MAX_VALUE
