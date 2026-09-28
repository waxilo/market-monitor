/** 技术指标纯函数：与 App domain/indicator/Indicators.kt 同一套公式（本地计算）。
 *  约定：输出与输入等长，窗口不足处为 NaN，绘图时跳过。 */

export function sma(input: number[], period: number): number[] {
  const out = new Array<number>(input.length).fill(NaN);
  if (input.length < period || period <= 0) return out;
  let sum = 0;
  for (let i = 0; i < input.length; i++) {
    sum += input[i];
    if (i >= period) sum -= input[i - period];
    if (i >= period - 1) out[i] = sum / period;
  }
  return out;
}

/** 以第 period 根的 SMA 作为种子，之后按 alpha 递推。 */
export function ema(input: number[], period: number): number[] {
  const out = new Array<number>(input.length).fill(NaN);
  if (input.length < period || period <= 0) return out;
  let seed = 0;
  for (let i = 0; i < period; i++) seed += input[i];
  let prev = seed / period;
  out[period - 1] = prev;
  const alpha = 2 / (period + 1);
  for (let i = period; i < input.length; i++) {
    prev = alpha * input[i] + (1 - alpha) * prev;
    out[i] = prev;
  }
  return out;
}

export interface Macd {
  dif: number[];
  signal: number[];
  histogram: number[];
}

export function macd(close: number[], fast = 12, slow = 26, signalPeriod = 9): Macd {
  const emaFast = ema(close, fast);
  const emaSlow = ema(close, slow);
  const dif = close.map((_, i) =>
    Number.isNaN(emaFast[i]) || Number.isNaN(emaSlow[i]) ? NaN : emaFast[i] - emaSlow[i],
  );
  const signal = new Array<number>(close.length).fill(NaN);
  const histogram = new Array<number>(close.length).fill(NaN);

  const firstValid = dif.findIndex((v) => !Number.isNaN(v));
  if (firstValid >= 0) {
    const smoothed = ema(dif.slice(firstValid), signalPeriod);
    for (let i = 0; i < smoothed.length; i++) {
      if (Number.isNaN(smoothed[i])) continue;
      signal[firstValid + i] = smoothed[i];
      histogram[firstValid + i] = dif[firstValid + i] - smoothed[i];
    }
  }
  return { dif, signal, histogram };
}

/** Wilder 平滑（等价于 alpha = 1/period）。 */
export function rsi(close: number[], period = 14): number[] {
  const out = new Array<number>(close.length).fill(NaN);
  if (close.length <= period || period <= 0) return out;

  let gainSum = 0;
  let lossSum = 0;
  for (let i = 1; i <= period; i++) {
    const change = close[i] - close[i - 1];
    if (change >= 0) gainSum += change;
    else lossSum -= change;
  }
  let avgGain = gainSum / period;
  let avgLoss = lossSum / period;
  out[period] = rsiOf(avgGain, avgLoss);

  for (let i = period + 1; i < close.length; i++) {
    const change = close[i] - close[i - 1];
    const gain = change > 0 ? change : 0;
    const loss = change < 0 ? -change : 0;
    avgGain = (avgGain * (period - 1) + gain) / period;
    avgLoss = (avgLoss * (period - 1) + loss) / period;
    out[i] = rsiOf(avgGain, avgLoss);
  }
  return out;
}

function rsiOf(avgGain: number, avgLoss: number): number {
  if (avgLoss === 0 && avgGain === 0) return 50;
  if (avgLoss === 0) return 100;
  return 100 - 100 / (1 + avgGain / avgLoss);
}

export interface Bollinger {
  middle: number[];
  upper: number[];
  lower: number[];
}

/**
 * 布林带：中轨是 SMA，上下轨为中轨 ± multiplier × 总体标准差。
 * 除数是 period 而非 period-1（与 App Indicators.boll 同式，也与主流行情软件一致）。
 */
export function boll(close: number[], period = 20, multiplier = 2): Bollinger {
  const middle = sma(close, period);
  const upper = new Array<number>(close.length).fill(NaN);
  const lower = new Array<number>(close.length).fill(NaN);
  if (close.length < period || period <= 0) return { middle, upper, lower };

  for (let i = period - 1; i < close.length; i++) {
    const mean = middle[i];
    let variance = 0;
    for (let j = i - period + 1; j <= i; j++) {
      const diff = close[j] - mean;
      variance += diff * diff;
    }
    const deviation = Math.sqrt(variance / period);
    upper[i] = mean + multiplier * deviation;
    lower[i] = mean - multiplier * deviation;
  }
  return { middle, upper, lower };
}

export interface Kdj {
  k: number[];
  d: number[];
  j: number[];
}

/** 经典 KDJ：RSV 以 2/3 前值 + 1/3 当期平滑，初值 50。 */
export function kdj(
  high: number[],
  low: number[],
  close: number[],
  period = 9,
  kSmooth = 3,
  dSmooth = 3,
): Kdj {
  const size = Math.min(high.length, low.length, close.length);
  const k = new Array<number>(size).fill(NaN);
  const d = new Array<number>(size).fill(NaN);
  const j = new Array<number>(size).fill(NaN);
  if (size < period || kSmooth <= 0 || dSmooth <= 0) return { k, d, j };

  let prevK = 50;
  let prevD = 50;
  for (let i = period - 1; i < size; i++) {
    let highest = high[i];
    let lowest = low[i];
    for (let m = i - period + 1; m <= i; m++) {
      highest = Math.max(highest, high[m]);
      lowest = Math.min(lowest, low[m]);
    }
    const rsv = highest === lowest ? 50 : ((close[i] - lowest) / (highest - lowest)) * 100;
    const curK = ((kSmooth - 1) * prevK + rsv) / kSmooth;
    const curD = ((dSmooth - 1) * prevD + curK) / dSmooth;
    prevK = curK;
    prevD = curD;
    k[i] = curK;
    d[i] = curD;
    j[i] = 3 * curK - 2 * curD;
  }
  return { k, d, j };
}
