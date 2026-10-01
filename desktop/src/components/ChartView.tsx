import { useEffect, useMemo, useState } from 'react';
import {
  fetchKlines,
  nativeMinutes,
  type Bar,
  type Instrument,
  type Ticker24h,
} from '../lib/api';
import { MA_CHOICES, SUB_PANE_KINDS, SUB_PANE_LABEL, type SubPaneKind } from '../lib/chartSeries';
import {
  INDICATORS_KEY,
  parseIndicatorPrefs,
  serializeIndicatorPrefs,
  type IndicatorPrefs,
} from '../lib/indicatorPrefs';
import {
  DEFAULT_INTERVALS,
  FALLBACK_INTERVAL,
  firstSupported,
  INTERVALS_KEY,
  intervalLabel,
  isKnownInterval,
  parseIntervals,
  serializeIntervals,
  synthesisOf,
} from '../lib/intervals';
import { useSourceKey, endpointOf } from '../lib/sources';
import { changeClass, displaySymbol, formatChange, formatCompact, formatPrice } from '../lib/format';
import { Sparkline } from '../hooks/useSparks';
import { useDrawings } from '../hooks/useDrawings';
import { watchKey, type WatchItem } from '../hooks/useWatchlist';
import { IntervalPanel } from './IntervalPanel';
import { ChartMenu } from './ChartMenu';
import { KlineCanvas, type ChartMenuRequest, type TrendSeed } from './KlineCanvas';

const RT_POLL_MS = 2000;
const HISTORY_BARS = 300;

interface Props {
  item: WatchItem | null;
  ticker: Ticker24h | null;
  instrument?: Instrument;
  spark: number[] | undefined;
  theme: string;
  /** 当前标的在不在自选里（详情页那枚 ☆/★ 的状态）。 */
  watched: boolean;
  /** 切换自选：★ = 加进左侧列表、☆ = 从左侧列表删掉（行内 × 是同一件事的另一个入口）。 */
  onToggleWatch: (item: WatchItem) => void;
}

export function ChartView({ item, ticker, instrument, spark, theme, watched, onToggleWatch }: Props) {
  /** 周期条上放哪些周期、按什么顺序 —— 用户在「周期」弹窗里改，存 localStorage。 */
  const [intervals, setIntervals] = useState<string[]>(() =>
    parseIntervals(localStorage.getItem(INTERVALS_KEY)),
  );
  /**
   * 当前周期仍从 15m 起（一直以来的默认视图；1m 太吵、不适合当首屏）。
   * 它不一定在用户的列表里 —— 那种情况交给下面的回落规则处理。
   */
  const [interval, setInterval_] = useState(FALLBACK_INTERVAL);
  /** 周期增删排序的弹窗开关（弹窗本身挂在末尾那枚「＋」上）。 */
  const [intervalPanelOpen, setIntervalPanelOpen] = useState(false);
  /**
   * 指标的选择（均线 / 布林带 / 副图）—— 存 localStorage，重启回来照旧。
   *
   * 出厂是**裸 K 线**：一个都不叠（App 端默认开 5/10/30 + 成交量，桌面端按需求从零开始 ——
   * 指标是用户自己叠上去的，默认塞三条均线一块成交量反而看不清「现在这根 K 线长什么样」）。
   * 它是「怎么看图」，与看哪个标的无关，所以全局存一份（与周期条的 `mm.intervals` 同口径）。
   */
  const [prefs, setPrefs] = useState<IndicatorPrefs>(() =>
    parseIndicatorPrefs(localStorage.getItem(INDICATORS_KEY)),
  );
  const { ma: maPeriods, boll: showBoll, panes: subPanes } = prefs;
  const [candles, setCandles] = useState<Bar[]>([]);
  const market = item?.market;
  const symbol = item?.symbol;
  /**
   * 画线（水平线 + 两点直线）。按标的存（与自选同键）—— 线画在「这段行情」上，
   * 换周期不该丢，换标的必须分开。
   */
  const drawings = useDrawings(item ? watchKey(item) : null);
  /**
   * 菜单里那条「从这里画直线」落下的一次性信号（第一点 = 右键落点，第二下左键成线）。
   * 画布侧的动作（预览、成线、Esc 撤销）都住在 KlineCanvas，这一层只递信号。
   */
  const [trendSeed, setTrendSeed] = useState<TrendSeed | null>(null);
  const drawingCount = drawings.priceLines.length + drawings.trendLines.length;
  const alertCount = drawings.priceLines.filter((l) => l.alert).length;
  /** 右键菜单：画布只报「落在哪儿、压着哪条线」，菜单长什么样、点完干什么都在这一层。 */
  const [menu, setMenu] = useState<ChartMenuRequest | null>(null);
  /** 递增一次 = 让画布把纵向刻度退回自动量程（见 KlineCanvas 的 `resetPriceSignal`）。 */
  const [resetPriceSignal, setResetPriceSignal] = useState(0);
  // 换数据源 = 换盘口：整段 K 线重拉（历史与增量都吃这个依赖）
  const source = useSourceKey(market);
  /** 当前市场 + 数据源**原生**支持哪些周期（分钟数）；换源后重算（依赖 source）。 */
  const natives = useMemo(() => nativeMinutes(market ?? 'FUTURES'), [market, source]);
  const sourceLabel = market === 'FUTURES' ? endpointOf(source).label : 'Gate 现货';

  useEffect(() => {
    localStorage.setItem(INTERVALS_KEY, serializeIntervals(intervals));
  }, [intervals]);

  useEffect(() => {
    localStorage.setItem(INDICATORS_KEY, serializeIndicatorPrefs(prefs));
  }, [prefs]);

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
    setPrefs((prev) => ({
      ...prev,
      ma: prev.ma.includes(p) ? prev.ma.filter((x) => x !== p) : [...prev.ma, p].sort((a, b) => a - b),
    }));
  }

  /** 副图可多选，空集 = 不显示（与 App 的 toggleSubPane 同一语义）。 */
  function toggleSubPane(kind: SubPaneKind) {
    setPrefs((prev) => ({
      ...prev,
      panes: prev.panes.includes(kind)
        ? prev.panes.filter((k) => k !== kind)
        : SUB_PANE_KINDS.filter((k) => prev.panes.includes(k) || k === kind),
    }));
  }

  const cls = changeClass(ticker?.priceChangePercent);

  /**
   * 菜单右侧那列价位：空白落点用反算出来的价，命中水平线用那条线自己的价；
   * 直线没有「一个价位」可报，留空。精度跟着标的的 tickSize，和轴上的标号同一套。
   */
  const menuPriceText =
    menu == null || menu.hit.kind === 'trend' ? '' : formatPrice(menu.hit.price, instrument?.tickSize);

  if (!item) {
    return (
      <section className="panel">
        <div className="chart-empty">
          <span className="overline">K 线</span>
          <span className="chart-empty-hint">从顶部搜索现货或永续合约全市场，搜到就能看图（入不入自选都行）</span>
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
              {displaySymbol(item.symbol)} · {item.market === 'FUTURES' ? '永续' : '现货'}
            </span>
            {/* 详情页的「自选」标记：★ = 在左侧列表里、☆ = 只是搜来看看。
                点一下就是加/删 —— 与行内 × 、搜索结果行内的 ＋ 同一件事。 */}
            <button
              type="button"
              className={`star-btn${watched ? ' on' : ''}`}
              aria-pressed={watched}
              title={watched ? `从自选移除 ${displaySymbol(item.symbol)}` : `把 ${displaySymbol(item.symbol)} 加入自选`}
              onClick={() => onToggleWatch(item)}
            >
              {watched ? '★' : '☆'}
            </button>
          </div>
        </div>
        <div className="hero num" key={ticker?.lastPrice}>
          <span className={cls}>{formatPrice(ticker?.lastPrice, instrument?.tickSize)}</span>
        </div>
        <span className={`pill ${cls}`}>{formatChange(ticker?.priceChangePercent)}</span>
        <div className="chart-metrics">
          <div className="metric-block">
            <div className="overline">24小时最高</div>
            <div className="metric num">{formatPrice(ticker?.highPrice, instrument?.tickSize)}</div>
          </div>
          <div className="metric-block">
            <div className="overline">24小时最低</div>
            <div className="metric num">{formatPrice(ticker?.lowPrice, instrument?.tickSize)}</div>
          </div>
          <div className="metric-block">
            <div className="overline">24小时成交额</div>
            <div className="metric num">{formatCompact(ticker?.quoteVolume)}</div>
          </div>
          <div className="metric-block">
            <div className="overline">24小时走势</div>
            <div style={{ padding: '2px 0' }}>
              <Sparkline closes={spark} />
            </div>
          </div>
        </div>
      </div>

      {/* 工具条两行：第一行只有周期，第二行是指标 / 副图两组。
          「画图」那组（水平线/直线选择、手势提示、清空）已整组拿掉 —— 画线一律走右键菜单，
          工具条上只留「告警 N」这个纯状态读数。宽度不够靠 .interval-bar 的 wrap 兜底。 */}
      <div className="bar-stack">
        <div className="interval-bar">
          <span className="bar-label grp">周期</span>
          <div className="bar-tabs" role="radiogroup" aria-label="K 线周期">
            {intervals.map((iv) => {
              // 表外周期由更细的原生周期**聚合**出来，所以不再有「点了会得到空白图」的周期 ——
              // 一律可点；只在 title 里说明它是原生直取还是拼出来的。
              const synth = synthesisOf(iv, natives);
              return (
                <button
                  key={iv}
                  type="button"
                  role="radio"
                  aria-checked={iv === interval}
                  className={`chip${iv === interval ? ' selected' : ''}`}
                  title={synth ? synth.title : undefined}
                  onClick={() => setInterval_(iv)}
                >
                  {intervalLabel(iv)}
                </button>
              );
            })}
          </div>
          {/* 末尾这一枚「＋」是周期条自己的编辑器：增删与排序都在弹窗里（IntervalPanel） */}
          <button
            type="button"
            className="chip"
            title="添加 / 移除 / 排序 K 线周期"
            onClick={() => setIntervalPanelOpen(true)}
          >
            ＋
          </button>
        </div>

        <div className="interval-bar">
          <span className="bar-label grp">指标</span>
          {MA_CHOICES.map((p) => (
            <button
              key={p}
              type="button"
              className={`chip${maPeriods.includes(p) ? ' on' : ''}`}
              title={`叠加 MA${p}（可多选）`}
              onClick={() => toggleMa(p)}
            >
              MA{p}
            </button>
          ))}
          <button
            type="button"
            className={`chip${showBoll ? ' on' : ''}`}
            title="布林带：主图叠加开关（周期 20、倍数 2）"
            onClick={() => setPrefs((prev) => ({ ...prev, boll: !prev.boll }))}
          >
            BOLL
          </button>

          <div className="sep" />

          <span className="bar-label grp">副图</span>
          {SUB_PANE_KINDS.map((kind) => (
            <button
              key={kind}
              type="button"
              className={`chip${subPanes.includes(kind) ? ' on' : ''}`}
              title={`${SUB_PANE_LABEL[kind]} 副图（可多选，一个都不点亮 = 不显示副图）`}
              onClick={() => toggleSubPane(kind)}
            >
              {SUB_PANE_LABEL[kind]}
            </button>
          ))}

          {alertCount > 0 && (
            <>
              <div className="sep" />
              <span className="bar-label alert" title="价格穿越这些红色点线时弹系统通知（主窗收进托盘也照弹）">
                告警 {alertCount}
              </span>
            </>
          )}
        </div>
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
        priceLines={drawings.priceLines}
        trendLines={drawings.trendLines}
        trendSeed={trendSeed}
        onAddTrend={drawings.addTrendLine}
        onMenu={setMenu}
        resetPriceSignal={resetPriceSignal}
      />

      {menu && (
        <ChartMenu
          x={menu.x}
          y={menu.y}
          hit={menu.hit}
          priceText={menuPriceText}
          drawingCount={drawingCount}
          adjusted={menu.adjusted}
          onAddLine={drawings.addPriceLine}
          onRemoveLine={drawings.removePriceLine}
          onSetAlert={drawings.setPriceLineAlert}
          onRemoveTrend={drawings.removeTrendLine}
          onStartTrend={(anchor) => setTrendSeed((prev) => ({ anchor, seq: (prev?.seq ?? 0) + 1 }))}
          onClear={drawings.clear}
          onResetPrice={() => setResetPriceSignal((n) => n + 1)}
          onClose={() => setMenu(null)}
        />
      )}

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
