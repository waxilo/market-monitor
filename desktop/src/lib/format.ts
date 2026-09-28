/** 与 App PriceFormatter.kt 对齐的展示规则 */

export const NO_DATA = '--';

/**
 * 标的的展示名：Gate 写作 `BTC_USDT`，App（Aster/币安）写作 `BTCUSDT`。
 * 只在展示与搜索匹配时用，请求仍走带下划线的原名。
 */
export function displaySymbol(symbol: string): string {
  return symbol.replace(/_/g, '');
}

/** tickSize 的小数位数；≤2 位一律显示 2 位，细于 0.01 放开到 tickSize 位数，上限 12。 */
export function decimalsFor(tickSize?: string | null): number {
  if (!tickSize) return 2;
  const t = Number(tickSize);
  if (!Number.isFinite(t) || t <= 0) return 2;
  const s = t.toString();
  const dot = s.indexOf('.');
  const scale = dot === -1 ? 0 : s.length - dot - 1;
  return scale <= 2 ? 2 : Math.min(scale, 12);
}

export function formatPrice(value?: number | null, tickSize?: string | null): string {
  if (value == null || Number.isNaN(value)) return NO_DATA;
  return value.toFixed(decimalsFor(tickSize));
}

/** 涨跌幅：固定 2 位，正数带 +，负数自带 -，尾随 %。 */
export function formatChange(changePercent?: number | null): string {
  if (changePercent == null || Number.isNaN(changePercent)) return NO_DATA;
  const rounded = Number(changePercent.toFixed(2));
  const sign = rounded > 0 ? '+' : '';
  return `${sign}${rounded.toFixed(2)}%`;
}

export function changeClass(changePercent?: number | null): 'up' | 'down' | 'flat' {
  if (changePercent == null || Number.isNaN(changePercent) || changePercent === 0) return 'flat';
  return changePercent > 0 ? 'up' : 'down';
}

/** 大额缩写 K/M/B/T，固定 2 位，避免列宽跳动。 */
export function formatCompact(value?: number | null): string {
  if (value == null || Number.isNaN(value)) return NO_DATA;
  const abs = Math.abs(value);
  const units: [number, string][] = [
    [1e12, 'T'],
    [1e9, 'B'],
    [1e6, 'M'],
    [1e3, 'K'],
  ];
  for (const [u, s] of units) {
    if (abs >= u) return (value / u).toFixed(2) + s;
  }
  return value.toFixed(2);
}
