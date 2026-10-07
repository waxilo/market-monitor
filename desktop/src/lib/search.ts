/**
 * 搜索匹配优先级，逐条移植 App 的 SearchRanking.kt：
 * 交易对名 = baseAsset + quoteAsset，只按「包含」排会让 `1INCHBTC` 压在 `BTCUSDT`
 * 上面——用户翻半天找不到比特币。所以先按**匹配位置**分层，再按计价币、成交额。
 */

import type { Instrument, MarketType, Ticker24h } from './api';
import { displaySymbol } from './format';

/** 空关键词（刚进搜索）时所有标的同分：不过滤，排序交给自选置顶/计价币/成交额。 */
export const EMPTY_QUERY_RANK = 4;

/**
 * 搜索框只收英文：顶栏全市场搜索与设置 → 悬浮窗的搜索框都是「敲交易对名」用的
 * （`BTC_USDT`、`1000PEPE_USDT`），中文、全角、表情既匹配不到任何标的，也不该留在框里。
 * 保留的字符集就是交易对名自己的字母表：英文字母 + 数字 + 下划线。
 *
 * 两个框都是自绘的 `EnglishField`（非编辑控件，输入法压根不启动 —— 没有组合中间态这回事），
 * 它用它滤字符、滤粘贴进来的整段文本；这里只管字符集这一条。
 */
export function englishOnly(raw: string): string {
  return raw.replace(/[^A-Za-z0-9_]/g, '');
}

/**
 * 匹配质量分层，数字越小越靠前；`null` = 不匹配。
 * - 0 币种完全等于关键词：`btc` → BTC
 * - 1 币种以关键词开头：`btc` → BTCDOM
 * - 2 交易对以关键词开头：`btc` → BTCUSDT
 * - 3 币种包含关键词：`eth` → WETH
 * - 4 交易对包含关键词：`btc` → 1INCHBTC（BTC 只在计价侧）
 *
 * 比对的交易对名用**展示形**（去掉 Gate 的下划线）：App 的符号就是 `BTCUSDT`，
 * 不去掉的话用户照着界面敲 `btcusdt` 反而搜不到。
 */
export function rankSymbol(symbol: string, baseAsset: string, keyword: string): number | null {
  if (!keyword) return EMPTY_QUERY_RANK;
  const k = keyword.toUpperCase();
  const base = baseAsset.toUpperCase();
  const sym = displaySymbol(symbol).toUpperCase();
  if (base === k) return 0;
  if (base.startsWith(k)) return 1;
  if (sym.startsWith(k)) return 2;
  if (base.includes(k)) return 3;
  if (sym.includes(k)) return 4;
  return null;
}

/**
 * 计价币优先级，数字越小越靠前；不在主流行列的统一排后。
 * 这条**与行情无关**：冷启动时成交额全是 0，按成交额排等于没排，
 * 字母序会把 `BTC_USDT` 推到几十名开外（前面全是 BTCAEUR/BTCARS 这类法币对）。
 */
export function quotePriority(quoteAsset: string): number {
  switch (quoteAsset.toUpperCase()) {
    case 'USDT':
      return 0;
    case 'USDC':
      return 1;
    case 'FDUSD':
      return 2;
    case 'TUSD':
      return 3;
    case 'BNB':
      return 4;
    case 'BTC':
      return 5;
    case 'ETH':
      return 6;
    case 'EUR':
      return 7;
    case 'TRY':
      return 8;
    default:
      return 9;
  }
}

/** 搜索结果上限，与 App SearchViewModel.MAX_RESULTS 一致。 */
export const MAX_RESULTS = 80;

/**
 * 排序权重（越小越靠前），**自选恒在最前**：`0` 已自选 / `1` 未自选。
 *
 * 自选置顶与「匹配质量」是两把尺子，分开算再比 —— 否则搜 `btc` 时
 * 一个恰好叫 BTC 的未自选标的（rank 0）会压住用户自己加的 BTCUSDT（rank 2）。
 */
const WATCHED_WEIGHT = 0;
const UNWATCHED_WEIGHT = 1;

export interface RankContext {
  market: MarketType;
  /** 当前市场的自选条目键（`market:symbol`）。 */
  watched: ReadonlySet<string>;
  /** 全量行情快照：只有成交额这一档排序用它，缺了按 0 处理。 */
  snapshot: Readonly<Record<string, Ticker24h>>;
}

/**
 * 搜索一个关键词命中的标的，按 App 的排序口径排好序返回。
 *
 * 排序链（逐级兜底，前一级相同才看后一级）：
 * 自选置顶 → 匹配分层（见 `rankSymbol`）→ 计价币主流度 → 成交额降序 → 交易对名字母序。
 *
 * 最后那两级的顺序不能反：成交额是给「同一匹配档、同一计价币」内部排的，
 * 冷启动时快照还没到、全是 0，此时字母序是唯一的确定性来源 —— 反过来写会让
 * 冷启动的结果顺序随机（依赖 `Array.prototype.sort` 对等值元素的处理）。
 *
 * **纯函数**：不请求、不读 storage，只把「命中哪些、怎么排」这条策略收在一处，
 * 供搜索下拉框调用，也便于用核对脚本直接断言。
 */
export function rankInstruments(
  instruments: readonly Instrument[],
  keyword: string,
  ctx: RankContext,
  limit: number = MAX_RESULTS,
): Instrument[] {
  if (!keyword) return [];
  return instruments
    .map((inst) => ({ inst, rank: rankSymbol(inst.symbol, inst.baseAsset, keyword) }))
    .filter((r): r is { inst: Instrument; rank: number } => r.rank != null)
    .sort((a, b) => {
      const aw = ctx.watched.has(`${ctx.market}:${a.inst.symbol}`) ? WATCHED_WEIGHT : UNWATCHED_WEIGHT;
      const bw = ctx.watched.has(`${ctx.market}:${b.inst.symbol}`) ? WATCHED_WEIGHT : UNWATCHED_WEIGHT;
      return (
        aw - bw ||
        a.rank - b.rank ||
        quotePriority(a.inst.quoteAsset) - quotePriority(b.inst.quoteAsset) ||
        (ctx.snapshot[b.inst.symbol]?.quoteVolume ?? 0) -
          (ctx.snapshot[a.inst.symbol]?.quoteVolume ?? 0) ||
        a.inst.symbol.localeCompare(b.inst.symbol)
      );
    })
    .slice(0, limit)
    .map((r) => r.inst);
}

/** 混搜的一条命中：标的 + 它是哪个市场的（加进悬浮窗要连同市场一起带）。 */
export interface MarketHit {
  market: MarketType;
  inst: Instrument;
}

/**
 * 把多个市场的清单**混在一起搜**（设置 → 悬浮窗的搜索框；悬浮窗列表本来就现货/合约混排）。
 *
 * 排序链与 `rankInstruments` 同一套，只是去掉了「自选置顶」（那边是给侧栏搜索用的：
 * 悬浮窗列表独立于自选，没有「已自选」这个概念）和成交额档（这里是偶发的一次搜索，
 * 不值得为它常开一份全量快照）：
 * 匹配分层 → 计价币主流度 → 交易对名字母序 → 市场（`lists` 的给定顺序，合约在前）。
 *
 * 同名的 `BTC_USDT` 在现货与合约各有一条，字母序打平后由市场顺序兜底 ——
 * 排序结果必须**确定**，不然同名两条会在两次输入之间互换位置。
 */
export function rankAcrossMarkets(
  lists: readonly { market: MarketType; instruments: readonly Instrument[] }[],
  keyword: string,
  limit: number = MAX_RESULTS,
): MarketHit[] {
  if (!keyword) return [];
  const marketOrder = new Map(lists.map((l, i) => [l.market, i]));
  return lists
    .flatMap((l) =>
      l.instruments.map((inst) => ({
        market: l.market,
        inst,
        rank: rankSymbol(inst.symbol, inst.baseAsset, keyword),
      })),
    )
    .filter((r): r is { market: MarketType; inst: Instrument; rank: number } => r.rank != null)
    .sort(
      (a, b) =>
        a.rank - b.rank ||
        quotePriority(a.inst.quoteAsset) - quotePriority(b.inst.quoteAsset) ||
        a.inst.symbol.localeCompare(b.inst.symbol) ||
        (marketOrder.get(a.market) ?? 0) - (marketOrder.get(b.market) ?? 0),
    )
    .slice(0, limit)
    .map(({ market, inst }) => ({ market, inst }));
}
