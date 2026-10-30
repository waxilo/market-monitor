/**
 * 搜索匹配优先级，逐条移植 App 的 SearchRanking.kt：
 * 交易对名 = baseAsset + quoteAsset，只按「包含」排会让 `1INCHBTC` 压在 `BTCUSDT`
 * 上面——用户翻半天找不到比特币。所以先按**匹配位置**分层，再按计价币、成交额。
 */

import type { Instrument, MarketType } from './api';
import { displaySymbol } from './format';

/** 空关键词（刚进搜索）时所有标的同分：不过滤，排序交给计价币。 */
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

/** 混搜的一条命中：标的 + 它是哪个市场的（主窗要按它开标签页，悬浮窗要连同市场一起带）。 */
export interface MarketHit {
  market: MarketType;
  inst: Instrument;
}

/**
 * 同一市场内的排序键：匹配分层（见 `rankSymbol`）→ 计价币主流度 → 交易对名字母序。
 * 主窗下拉的两栏与悬浮窗的混搜共用这一把尺子，两边不会排出两种偏好。
 */
function compareRelevance(
  a: { inst: Instrument; rank: number },
  b: { inst: Instrument; rank: number },
): number {
  return (
    a.rank - b.rank ||
    quotePriority(a.inst.quoteAsset) - quotePriority(b.inst.quoteAsset) ||
    a.inst.symbol.localeCompare(b.inst.symbol)
  );
}

/**
 * 单市场搜索：主窗下拉的「现货 / 永续」两栏各用一次。
 *
 * 不做「按成交额排」：那要常开一份全量行情快照，而两家盘口各拉一次全量
 * 正是限频治理要避开的。搜索是偶发动作，匹配质量 + 计价币已经够用。
 */
export function rankMarket(
  instruments: readonly Instrument[],
  keyword: string,
  limit: number = MAX_RESULTS,
): Instrument[] {
  if (!keyword) return [];
  return instruments
    .map((inst) => ({ inst, rank: rankSymbol(inst.symbol, inst.baseAsset, keyword) }))
    .filter((r): r is { inst: Instrument; rank: number } => r.rank != null)
    .sort(compareRelevance)
    .slice(0, limit)
    .map((r) => r.inst);
}

/**
 * 把多个市场的清单**混在一起搜**（设置 → 悬浮窗的搜索框：那份列表本来就现货/合约混排）。
 *
 * 排序链与 `rankMarket` 同一套，末尾再补一级「市场顺序」（`lists` 的给定顺序）：
 * 同名标的（`BTC_USDT`）两边各有一条，字母序打平后由它兜底 —— 排序结果必须**确定**，
 * 不然同名两条会在两次输入之间互换位置。
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
        compareRelevance(a, b) ||
        (marketOrder.get(a.market) ?? 0) - (marketOrder.get(b.market) ?? 0),
    )
    .slice(0, limit)
    .map(({ market, inst }) => ({ market, inst }));
}
