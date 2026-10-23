import { useEffect, useMemo, useState } from 'react';
import { fetchKlines, type Bar, type MarketType } from '../lib/api';
import {
  emaAlertNote,
  emaWindow,
  EMPTY_EMA_SCAN,
  hasEmaAlert,
  lastEma,
  mergeEmaScan,
  splitLevels,
  type EmaFailure,
  type EmaLevel,
  type EmaLevelRow,
  type EmaMissing,
  type EmaScan,
} from '../lib/emaAnalysis';
import type { PriceLine } from '../lib/priceLines';
import { maSlotColors, type MaColors } from '../lib/chartSeries';
import { intervalLabel, minutesOf } from '../lib/intervals';
import { displaySymbol, formatChange, formatPrice } from '../lib/format';
import { useSourceKey } from '../lib/sources';

/** 一个周期取回来了 / 没取回来（失败原因原样留着，压进提示里）。 */
type Outcome = { interval: string; bars: Bar[] } | { interval: string; reason: string };

/** 面板开着时的自动刷新间隔：1 分钟（K 线最少也是 1 分钟一根，再密没东西可变）。 */
const AUTO_REFRESH_MS = 60_000;

/**
 * 上一次扫描的结果，按「标的 + 数据源 + 那两份配置」存一份。
 *
 * 为什么放在模块级而不是 state：面板一关就整个卸载，state 全丢；下次点开又得从头取一遍
 * 11 个周期的 K 线，那几百毫秒里表是空的 —— 而这份表的用法恰恰是「点开看一眼就走」。
 * 有了这层，点开就能立刻看到上次的数值，同时后台静默换成新的。
 *
 * 只留最近 6 份（切标的 / 换源会各占一格）：这是个「上次看到的」缓存，不是数据仓库，
 * 存太多既没用又会让内存里挂着一堆过期的价位。
 */
const scanCache = new Map<string, EmaScan>();
const SCAN_CACHE_MAX = 6;

function rememberScan(key: string, scan: EmaScan): void {
  // 先删再塞：Map 的插入顺序才是淘汰顺序，覆盖写不会把它推到队尾
  scanCache.delete(key);
  scanCache.set(key, scan);
  while (scanCache.size > SCAN_CACHE_MAX) {
    const oldest = scanCache.keys().next();
    if (oldest.done) break;
    scanCache.delete(oldest.value);
  }
}

interface Props {
  market: MarketType;
  symbol: string;
  /** 实时最新价（行情没到 / 断线时给 null，改用最后一根收盘价）。 */
  price: number | null;
  tickSize?: string;
  /** 周期条上配置的全部 K 线周期（分析范围）。 */
  intervals: string[];
  /** 均线面板里配置的全部 EMA 档位（分析范围，含小眼睛藏起来的那些）。 */
  periods: number[];
  /** EMA 期数 → 色槽（表里的色点与图上那条线同色）。 */
  colors: MaColors;
  /** 当前数据源名，写进抬头（换源后数值会变，得说清是跟谁取的）。 */
  sourceLabel: string;
  /** 当前标的上已有的水平线 —— 用来判断某一档的价位是不是已经画过告警线。 */
  priceLines: readonly PriceLine[];
  /** 点一行 = 在那个价位加 / 取消一条告警线（同一行再点一下即取消）；`note` 是自动备注文案。 */
  onToggleAlert: (level: EmaLevelRow, note: string) => void;
  /** 点行内的周期小方框 = 切到那个 K 线周期看图（顺手关掉面板）。 */
  onPickInterval: (interval: string) => void;
  onClose: () => void;
}

/** 失败原因压成一行（与 ChartView 的 errorText 同口径：方言层的原文常常直接点名病灶）。 */
function reasonOf(error: unknown): string {
  const text = error instanceof Error ? error.message : String(error ?? '');
  return text.replace(/\s+/g, ' ').trim() || '取数失败';
}

/** 时间戳 → `HH:MM:SS`。只进按钮的 title，不占版面（版面多一个字都会挤掉价位）。 */
function clockText(at: number): string {
  return new Date(at).toLocaleTimeString('zh-CN', { hour12: false });
}

/**
 * EMA 指标分析：一次点击，把**所有已配置的 EMA 档位 × 所有已配置的 K 线周期**的当前 EMA 值
 * 全部取回来，按最新价分成「上方压力 / 下方支撑」两块。
 *
 * 几条设计取舍：
 *
 * - **静默刷新**：打开时先用上次的结果把表铺出来（`scanCache`），同时在后台重取，
 *   拿到新值再整表替换 —— 不出现「先清空、再转圈、再重排」那段闪烁。刷新中只有右上角
 *   的按钮说话（文案变「刷新中」、禁用），表格照旧可读可点。
 *   面板一直开着时**每 1 分钟自动刷新一次**（`AUTO_REFRESH_MS`），所以它是一张会自己变新的表。
 *   现价则跟着 `price` 属性实时更新 —— 价格穿过某道水位时，那一行会当场换个筐。
 * - **刷新失败不清表**：这一轮哪个周期没取到，就沿用上一轮该周期的各档（见 `mergeEmaScan`），
 *   并把这些周期标成「上次的值」。断网时一道压力线凭空消失，比显示略旧的数危险得多。
 * - **失败与「算不出」分开报**：前者是网络/数据源不通（该重试），后者是这个周期取回的根数
 *   不够算这一档 EMA（粗周期上的长档就是这样）。混成一句话会让用户以为取数又坏了。
 * - **点一行画告警线**：表里每一行就是一个候选价位，点一下直接在图上落一条红色告警线
 *   （穿越时弹系统通知），再点一下取消 —— 面板**不关**，所以可以连着点几道压力位一起画。
 *   想看某一道在哪根 K 线上，点行内那个周期小方框（切过去，面板随即关掉，不挡着图）。
 *   两种动作分开：整行是「盯住这个价」，周期方框是「去看这个周期」，
 *   把切周期放在整行上会让人点一下就离开表，没法连续画线。
 */
export function EmaAnalysisPanel({
  market,
  symbol,
  price,
  tickSize,
  intervals,
  periods,
  colors,
  sourceLabel,
  priceLines,
  onToggleAlert,
  onPickInterval,
  onClose,
}: Props) {
  /** 换源 = 换盘口：整表重取（与 ChartView 的 K 线口径一致）。 */
  const source = useSourceKey(market);
  /** 周期列表 / 档位列表都是新数组引用的情况很多，依赖用拼出来的字符串判定。 */
  const intervalKey = intervals.join(',');
  const periodKey = periods.join(',');
  /** 这张表的身份：标的 + 数据源 + 那两份配置，任一变化就是另一张表。缓存按它认。 */
  const scanKey = `${market}|${symbol}|${source}|${intervalKey}|${periodKey}`;

  /** 当前这张表 —— 初值取上次看过的（读不到才是空表，见 `scanCache`）。 */
  const [scan, setScan] = useState<EmaScan>(() => scanCache.get(scanKey) ?? EMPTY_EMA_SCAN);
  /** 有取数在飞。**只喂给按钮与首次加载文案**，不拿来遮表格（遮了就回到「清空刷新」）。 */
  const [refreshing, setRefreshing] = useState(true);
  /** 已回几个周期（只有首次取数时看得到，之后表已经在那儿了）。 */
  const [done, setDone] = useState(0);
  /** 递增一次 = 重取（「刷新」按钮与每分钟的自动刷新共用这一条路）。 */
  const [seq, setSeq] = useState(0);
  /** 上次拿到新值的时间（0 = 还没成功过），写进按钮的 title。 */
  const [updatedAt, setUpdatedAt] = useState(0);

  // 换标的 / 换源 / 改配置：立刻换成那一份的缓存（没有就空表）并重新取。
  // 不这么做的话，切币的瞬间会先显示上一个币的价位 —— 那比空表更容易被当真。
  useEffect(() => {
    setScan(scanCache.get(scanKey) ?? EMPTY_EMA_SCAN);
    setRefreshing(true);
  }, [scanKey]);

  useEffect(() => {
    if (periods.length === 0 || intervals.length === 0) {
      setRefreshing(false);
      setScan(EMPTY_EMA_SCAN);
      setDone(0);
      return;
    }
    // 不挡重复请求：自动刷新 60s 一轮、单轮 1~2s 就回来，撞不上；手动按钮刷新时本来就是禁用的。
    // 反而**不能**按「同一份配置就跳过」——面板关掉重开时，上一轮的请求还在飞，
    // 跳过会让新一轮一直停在「刷新中」等那个已经没人要的结果。
    let alive = true;
    let finished = 0;
    // 一次取多少根：至少与图上一段历史同长（见 lib/emaAnalysis 的头注）
    const barCount = emaWindow(periods);
    setRefreshing(true);
    setDone(0);

    const load = async (interval: string): Promise<Outcome> => {
      try {
        return { interval, bars: await fetchKlines(market, symbol, interval, barCount) };
      } catch (error) {
        return { interval, reason: reasonOf(error) };
      } finally {
        finished += 1;
        if (alive) setDone(finished);
      }
    };

    void Promise.all(intervals.map(load)).then((outcomes) => {
      // 已被新的一轮取代（换了配置 / 面板重开）：结果丢掉，否则会把旧配置的表盖上去
      if (!alive) return;

      const nextLevels: EmaLevel[] = [];
      const nextFailures: EmaFailure[] = [];
      const nextMissing: EmaMissing[] = [];
      // 兜底基准取**最细**那个周期（周期越细，最后一根离「此刻」越近）
      let finest = Infinity;
      let finestClose = NaN;

      for (const outcome of outcomes) {
        if (!('bars' in outcome)) {
          nextFailures.push({ interval: outcome.interval, reason: outcome.reason });
          continue;
        }
        const bars = outcome.bars;
        if (bars.length === 0) {
          nextFailures.push({ interval: outcome.interval, reason: '这个周期没有取到 K 线' });
          continue;
        }
        const minutes = minutesOf(outcome.interval) ?? Number.MAX_SAFE_INTEGER;
        const close = bars[bars.length - 1].close;
        if (minutes < finest && Number.isFinite(close) && close > 0) {
          finest = minutes;
          finestClose = close;
        }
        const closes = bars.map((bar) => bar.close);
        for (const period of periods) {
          const value = lastEma(closes, period);
          if (Number.isFinite(value) && value > 0) {
            nextLevels.push({ interval: outcome.interval, minutes, period, value });
          } else {
            nextMissing.push({ interval: outcome.interval, period, bars: bars.length });
          }
        }
      }

      // 与上一轮合并：这一轮没取到的周期沿用旧值，表不会因为一次断网被清空
      setScan((prev) => {
        const merged = mergeEmaScan(prev, {
          levels: nextLevels,
          failures: nextFailures,
          missing: nextMissing,
          stale: [],
          fallbackPrice: finestClose,
        });
        rememberScan(scanKey, merged);
        return merged;
      });
      setUpdatedAt(Date.now());
      setRefreshing(false);
    });

    return () => {
      alive = false;
    };
  }, [market, symbol, source, intervalKey, periodKey, seq]);

  // 面板开着就一直自己换新：每分钟一轮（K 线最短一档就是 1 分钟，再密没有新东西）
  useEffect(() => {
    const timer = window.setInterval(() => setSeq((n) => n + 1), AUTO_REFRESH_MS);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  /** 比较基准：实时价优先（行情一来就跟着动），没有才用末根收盘。 */
  const livePrice = price != null && Number.isFinite(price) && price > 0;
  const base = livePrice ? price : scan.fallbackPrice;
  const buckets = useMemo(() => splitLevels(scan.levels, base), [scan.levels, base]);
  const palette = useMemo(() => maSlotColors('EMA'), []);
  /** 表里已经有值吗 —— 有值就绝不拿加载文案盖住它（这就是「静默」的字面含义）。 */
  const hasTable = scan.levels.length > 0;

  const column = (rows: EmaLevelRow[], side: 'resistance' | 'support') => {
    const nearest = rows[0];
    return (
      <section className={`ema-col ${side}`}>
        <header className="ema-col-head">
          <span className="ema-col-name">{side === 'resistance' ? '上方压力' : '下方支撑'}</span>
          <span className="ema-col-count num">{rows.length}</span>
          {nearest && (
            <span className="ema-col-near num" title="离现价最近的一条">
              最近 {intervalLabel(nearest.interval)} · EMA{nearest.period}
            </span>
          )}
        </header>
        <div className="ema-rows">
          {rows.map((row) => {
            const label = intervalLabel(row.interval);
            const alerted = hasEmaAlert(priceLines, row.value);
            const note = emaAlertNote(label, row.period);
            const priceText = formatPrice(row.value, tickSize);
            const gapText = formatChange(row.gapPercent);
            /* 整行 = 「在这个价位画 / 取消告警线」；周期小方框单独可点 = 「切过去看这根 K 线」。
               没有用两层 button：`<button>` 里塞第二个可点元素属于交互元素嵌套（HTML 不允许），
               所以行本身是带 role=button 的 div（Enter / 空格等同于点击）。 */
            return (
              <div
                key={`${row.interval}-${row.period}`}
                className={`ema-row${alerted ? ' on' : ''}`}
                role="button"
                tabIndex={0}
                aria-pressed={alerted}
                title={
                  alerted
                    ? `${label} 周期 EMA${row.period} = ${priceText}（${gapText}）\n已画告警线「${note}」：再点一下取消（点周期方框切到该周期看图）`
                    : `${label} 周期 EMA${row.period} = ${priceText}（${gapText}）\n点一下在这个价位画告警线，自动备注「${note}」，价格穿越时弹通知（点周期方框切到该周期看图）`
                }
                onClick={() => onToggleAlert(row, note)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    onToggleAlert(row, note);
                  }
                }}
              >
                <span
                  className="ema-dot"
                  style={{ background: palette[colors[row.period] ?? 0] }}
                  aria-hidden
                />
                <span className="ema-name">EMA{row.period}</span>
                <span
                  className="ema-iv"
                  role="button"
                  title={`切到 ${label} 看图`}
                  onClick={(e) => {
                    // 不让这一下冒泡成「画告警线」
                    e.stopPropagation();
                    onPickInterval(row.interval);
                  }}
                >
                  {label}
                </span>
                <span className="ema-val num">{priceText}</span>
                <span className="ema-gap num">{gapText}</span>
              </div>
            );
          })}
          {rows.length === 0 && (
            <p className="ema-empty">
              {side === 'resistance' ? '现价上方还没有已配置的 EMA' : '现价下方还没有已配置的 EMA'}
            </p>
          )}
        </div>
      </section>
    );
  };

  return (
    <div className="up-backdrop" onMouseDown={onClose}>
      <section
        className="up-panel up-wide ema-panel"
        onMouseDown={(e) => e.stopPropagation()}
        role="dialog"
        aria-label="EMA 指标分析"
      >
        <header className="up-head">
          <span className="overline">EMA · 压力支撑分析</span>
          <span className="grow" />
          <button className="panel-close" title="关闭（Esc）" onClick={onClose}>
            ×
          </button>
        </header>

        <div className="up-body">
          <div className="ema-summary">
            <div className="ema-sym">
              <span className="ema-sym-name">{displaySymbol(symbol)}</span>
              <span className="iv-tag">
                {market === 'FUTURES' ? '永续' : '现货'} · {sourceLabel}
              </span>
            </div>
            <div className="ema-price">
              <span className="overline">最新价</span>
              <span className="ema-price-v num">{formatPrice(base, tickSize)}</span>
              {!livePrice && <span className="ema-src">末根收盘价</span>}
            </div>
            {/* 刷新中的唯一可见痕迹：按钮自己说话（文案 + 禁用），表格不动。
                宽度靠 min-width 撑住，免得「刷新」↔「刷新中」来回跳把右侧布局推歪。 */}
            <button
              type="button"
              className="up-btn ema-refresh"
              disabled={refreshing}
              aria-busy={refreshing}
              title={
                refreshing
                  ? `正在取 ${intervals.length} 个周期的 K 线… ${done} / ${intervals.length}（旧值暂时留在表里）`
                  : `按当前配置重新取一遍各周期 K 线（面板开着时每 1 分钟自动刷新${
                      updatedAt ? `；上次 ${clockText(updatedAt)} 更新` : ''
                    }）`
              }
              onClick={() => setSeq((n) => n + 1)}
            >
              {refreshing ? '刷新中' : '刷新'}
            </button>
          </div>

          {intervals.length === 0 ? (
            <p className="ema-empty">
              周期条上还没有配置 K 线周期 —— 先在「周期」那一行的「＋」里加一个。
            </p>
          ) : periods.length === 0 ? (
            <p className="ema-empty">EMA 一档都没配置 —— 先在指标栏「＋」里加几档再来分析。</p>
          ) : hasTable ? (
            <div className="ema-cols">
              {column(buckets.resistance, 'resistance')}
              {column(buckets.support, 'support')}
            </div>
          ) : refreshing ? (
            <p className="ema-empty num">
              正在取 {intervals.length} 个周期的 K 线… {done} / {intervals.length}
            </p>
          ) : (
            <p className="ema-empty">所有周期都没能算出 EMA（见下方原因）。</p>
          )}

          {/* 异常提示跟着整张扫描走：有旧值时不显示「正在取」那种过渡态，但异常照报 */}
          {scan.failures.length > 0 && (
            <p
              className="ema-note bad"
              title={scan.failures.map((f) => `${f.interval}：${f.reason}`).join('\n')}
            >
              {scan.failures.length} 个周期没取到：
              {scan.failures.map((f) => intervalLabel(f.interval)).join(' / ')}
              {scan.stale.length > 0
                ? `（${scan.stale.map((iv) => intervalLabel(iv)).join(' / ')} 显示的是上次的值）`
                : ''}
              （悬停看原因）
            </p>
          )}
          {scan.missing.length > 0 && (
            <p
              className="ema-note"
              title={scan.missing
                .map(
                  (m) =>
                    `${intervalLabel(m.interval)} 的 EMA${m.period}：只取到 ${m.bars} 根，至少要 ${m.period} 根`,
                )
                .join('\n')}
            >
              {scan.missing.length} 档根数不够算不出：
              {scan.missing
                .slice(0, 6)
                .map((m) => `${intervalLabel(m.interval)}·EMA${m.period}`)
                .join(' / ')}
              {scan.missing.length > 6 ? ' …' : ''}
              （悬停看明细）
            </p>
          )}
        </div>
      </section>
    </div>
  );
}
