import { useEffect, useMemo, useState } from 'react';
import {
  fetchKlines,
  nativeMinutes,
  type Bar,
  type Instrument,
  type Ticker24h,
} from '../lib/api';
import { SUB_PANE_KINDS, SUB_PANE_LABEL, type SubPaneKind } from '../lib/chartSeries';
import {
  DEFAULT_INTERVALS,
  FALLBACK_INTERVAL,
  firstSupported,
  INTERVALS_KEY,
  isKnownInterval,
  parseIntervals,
  serializeIntervals,
  synthesisOf,
} from '../lib/intervals';
import { useSourceKey, endpointOf } from '../lib/sources';
import { changeClass, displaySymbol, formatChange, formatCompact, formatPrice } from '../lib/format';
import { Sparkline } from '../hooks/useSparks';
import { usePriceLines } from '../hooks/usePriceLines';
import { watchKey, type WatchItem } from '../hooks/useWatchlist';
import { IntervalPanel } from './IntervalPanel';
import { KlineCanvas } from './KlineCanvas';

const MA_CHOICES = [5, 10, 20, 30, 60];
/**
 * 第一次进来是**裸 K 线**：均线 / 布林带 / 副图全关。
 * （App 端默认开 5/10/30 + VOL，桌面端按需求改成从零开始 —— 指标是用户自己叠上去的，
 * 默认塞三条均线一块成交量反而看不清「现在这根 K 线长什么样」。）
 */
const DEFAULT_MA: number[] = [];
const DEFAULT_SUB_PANES: SubPaneKind[] = [];
const RT_POLL_MS = 2000;
const HISTORY_BARS = 300;

interface Props {
  item: WatchItem | null;
  ticker: Ticker24h | null;
  instrument?: Instrument;
  spark: number[] | undefined;
  theme: string;
}

export function ChartView({ item, ticker, instrument, spark, theme }: Props) {
  /** 周期条上放哪些周期、按什么顺序 —— 用户在「周期」弹窗里改，存 localStorage。 */
  const [intervals, setIntervals] = useState<string[]>(() =>
    parseIntervals(localStorage.getItem(INTERVALS_KEY)),
  );
  /**
   * 当前周期仍从 15m 起（一直以来的默认视图；1m 太吵、不适合当首屏）。
   * 它不一定在用户的列表里 —— 那种情况交给下面的回落规则处理。
   */
  const [interval, setInterval_] = useState(FALLBACK_INTERVAL);
  const [intervalPanelOpen, setIntervalPanelOpen] = useState(false);
  const [maPeriods, setMaPeriods] = useState<number[]>(DEFAULT_MA);
  /** 布林带默认关，与 AppSettings 的 bollEnabled 默认值一致。 */
  const [showBoll, setShowBoll] = useState(false);
  const [subPanes, setSubPanes] = useState<SubPaneKind[]>(DEFAULT_SUB_PANES);
  const [candles, setCandles] = useState<Bar[]>([]);
  const market = item?.market;
  const symbol = item?.symbol;
  /**
   * 水平线（画线）+ 它的锁。按标的存（与自选同键）—— 线画在「这个价」上，
   * 换周期不该丢，换标的必须分开。
   */
  const priceLines = usePriceLines(item ? watchKey(item) : null);
  // 换数据源 = 换盘口：整段 K 线重拉（历史与增量都吃这个依赖）
  const source = useSourceKey(market);
  /** 当前市场 + 数据源**原生**支持哪些周期（分钟数）；换源后重算（依赖 source）。 */
  const natives = useMemo(() => nativeMinutes(market ?? 'FUTURES'), [market, source]);
  const sourceLabel = market === 'FUTURES' ? endpointOf(source).label : 'Gate 现货';

  useEffect(() => {
    localStorage.setItem(INTERVALS_KEY, serializeIntervals(intervals));
  }, [intervals]);

  // 当前周期必须在列表里，否则回落到列表里第一个。
  // 现在不存在「当前源不支持」这回事了 —— 表外周期由更细的原生周期聚合出来，
  // 所以回落只剩一个真实触发点：用户在弹窗里把当前周期移出了周期条
  // （不回落的后果是没有任何 chip 高亮，看不出图在看哪个周期）。
  useEffect(() => {
    if (intervals.includes(interval) && isKnownInterval(interval)) return;
    setInterval_(firstSupported(intervals));
  }, [interval, intervals]);

  // 切换标的/周期：全量拉 300 根
  useEffect(() => {
    if (!market || !symbol) {
      setCandles([]);
      return;
    }
    let alive = true;
    setCandles([]);
    fetchKlines(market, symbol, interval, HISTORY_BARS)
      .then((bars) => {
        if (alive) setCandles(bars);
      })
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, [market, symbol, interval, source]);

  // 增量轮询：只取末根更新实时数据（App 的 K 线增量节奏是 2s）
  useEffect(() => {
    if (!market || !symbol) return;
    let alive = true;
    const timer = setInterval(() => {
      fetchKlines(market, symbol, interval, 1)
        .then((bars) => {
          if (!alive || bars.length === 0) return;
          const update = bars[0];
          setCandles((prev) => {
            if (prev.length === 0) return prev;
            const last = prev[prev.length - 1];
            if (update.timestamp === last.timestamp) return [...prev.slice(0, -1), update];
            if (update.timestamp > last.timestamp) return [...prev, update];
            return prev;
          });
        })
        .catch(() => {});
    }, RT_POLL_MS);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, [market, symbol, interval, source]);

  function toggleMa(p: number) {
    setMaPeriods((prev) => (prev.includes(p) ? prev.filter((x) => x !== p) : [...prev, p].sort((a, b) => a - b)));
  }

  /** 副图可多选，空集 = 不显示（与 App 的 toggleSubPane 同一语义）。 */
  function toggleSubPane(kind: SubPaneKind) {
    setSubPanes((prev) =>
      prev.includes(kind)
        ? prev.filter((k) => k !== kind)
        : [...prev, kind].sort((a, b) => SUB_PANE_KINDS.indexOf(a) - SUB_PANE_KINDS.indexOf(b)),
    );
  }

  const cls = changeClass(ticker?.priceChangePercent);

  if (!item) {
    return (
      <section className="panel">
        <div className="chart-empty">
          <span className="overline">K 线</span>
          <span className="chart-empty-hint">从顶部搜索现货或永续合约全市场，加入自选后看图</span>
        </div>
      </section>
    );
  }

  return (
    <section className="panel">
      <div className="chart-head">
        <div className="chart-title">
          <div className="base">
            {instrument?.baseAsset ?? item.symbol.split('_')[0]}
            <span className="sym">
              {displaySymbol(item.symbol)} · {item.market === 'FUTURES' ? 'PERP' : 'SPOT'}
            </span>
          </div>
        </div>
        <div className="hero num" key={ticker?.lastPrice}>
          <span className={cls}>{formatPrice(ticker?.lastPrice, instrument?.tickSize)}</span>
        </div>
        <span className={`pill ${cls}`}>{formatChange(ticker?.priceChangePercent)}</span>
        <div className="chart-metrics">
          <div className="metric-block">
            <div className="overline">24H High</div>
            <div className="metric num">{formatPrice(ticker?.highPrice, instrument?.tickSize)}</div>
          </div>
          <div className="metric-block">
            <div className="overline">24H Low</div>
            <div className="metric num">{formatPrice(ticker?.lowPrice, instrument?.tickSize)}</div>
          </div>
          <div className="metric-block">
            <div className="overline">24H Vol</div>
            <div className="metric num">{formatCompact(ticker?.quoteVolume)}</div>
          </div>
          <div className="metric-block">
            <div className="overline">24H Trend</div>
            <div style={{ padding: '2px 0' }}>
              <Sparkline closes={spark} />
            </div>
          </div>
        </div>
      </div>

      <div className="interval-bar">
        {intervals.map((iv) => {
          // 表外周期由更细的原生周期**聚合**出来，所以不再有「点了会得到空白图」的周期 ——
          // 一律可点；只在 title 里说明它是原生直取还是拼出来的。
          const synth = synthesisOf(iv, natives);
          return (
            <button
              key={iv}
              className={`chip${iv === interval ? ' selected' : ''}`}
              title={synth ? synth.title : undefined}
              onClick={() => setInterval_(iv)}
            >
              {iv}
            </button>
          );
        })}
        <button
          className="icon-btn"
          title="添加 / 移除 / 排序 K 线周期"
          onClick={() => setIntervalPanelOpen(true)}
        >
          周期
        </button>
        <div className="sep" />
        <span className="bar-label">叠加</span>
        {MA_CHOICES.map((p) => (
          <button
            key={p}
            className={`chip${maPeriods.includes(p) ? ' on' : ''}`}
            onClick={() => toggleMa(p)}
          >
            MA{p}
          </button>
        ))}
        <button className={`chip${showBoll ? ' on' : ''}`} onClick={() => setShowBoll((v) => !v)}>
          BOLL
        </button>
        <div className="sep" />
        <span className="bar-label">副图 · 可多选</span>
        {SUB_PANE_KINDS.map((kind) => (
          <button
            key={kind}
            className={`chip${subPanes.includes(kind) ? ' on' : ''}`}
            onClick={() => toggleSubPane(kind)}
          >
            {SUB_PANE_LABEL[kind]}
          </button>
        ))}
        <div className="sep" />
        {/* 画线：操作全在画布上（右键加减、拖动改价），这里只给锁与清理。
            标签刻意短 —— 指标条是靠 .interval-bar 的 wrap 兜底的，
            多写几个字就会在 1280 宽的窗口下折成两行、白吃掉 20 多 px 图高；
            手势提示放进 title。 */}
        <span
          className="bar-label"
          title="右键主图添加水平线；右键已有的线删掉；拖动线能改价（锁住后三个都不生效）"
        >
          画线
        </span>
        <button
          className={`chip${priceLines.locked ? ' on' : ''}`}
          onClick={priceLines.toggleLock}
          title={
            priceLines.locked
              ? '已锁住：水平线不能再增删、也不能拖动。点一下解锁'
              : '锁住水平线：右键与拖动一概不生效（防手滑改图）'
          }
        >
          {priceLines.locked ? '已锁' : '锁住'}
        </button>
        <button
          className="chip"
          disabled={priceLines.locked || priceLines.lines.length === 0}
          onClick={priceLines.clear}
          title={priceLines.locked ? '先解锁才能清空' : '删掉当前标的的全部水平线'}
        >
          清空 {priceLines.lines.length}
        </button>
      </div>

      <KlineCanvas
        candles={candles}
        maPeriods={maPeriods}
        showBoll={showBoll}
        subPanes={subPanes}
        interval={interval}
        tickSize={instrument?.tickSize}
        theme={theme}
        resetKey={`${item.market}|${item.symbol}|${interval}`}
        priceLines={priceLines.lines}
        linesLocked={priceLines.locked}
        onAddLine={priceLines.add}
        onRemoveLine={priceLines.removeAt}
        onMoveLine={priceLines.moveAt}
      />

      {intervalPanelOpen && (
        <IntervalPanel
          list={intervals}
          nativeMinutes={natives}
          sourceLabel={sourceLabel}
          onChange={setIntervals}
          onReset={() => setIntervals([...DEFAULT_INTERVALS])}
          onClose={() => setIntervalPanelOpen(false)}
        />
      )}
    </section>
  );
}
