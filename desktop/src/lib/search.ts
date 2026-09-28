/**
 * 搜索匹配优先级，逐条移植 App 的 SearchRanking.kt：
 * 交易对名 = baseAsset + quoteAsset，只按「包含」排会让 `1INCHBTC` 压在 `BTCUSDT`
 * 上面——用户翻半天找不到比特币。所以先按**匹配位置**分层，再按计价币、成交额。
 */

import { displaySymbol } from './format';

/** 空关键词（刚进搜索）时所有标的同分：不过滤，排序交给自选置顶/计价币/成交额。 */
export const EMPTY_QUERY_RANK = 4;

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
