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
import { useDrawings } from '../hooks/useDrawings';
import { watchKey, type WatchItem } from '../hooks/useWatchlist';
import { IntervalPanel } from './IntervalPanel';
import { ChartMenu } from './ChartMenu';
import { KlineCanvas, type ChartMenuRequest, type DrawTool } from './KlineCanvas';

const MA_CHOICES = [5, 10, 20, 30, 60];

/** 画线工具（**单选，再点一次取消**）：不选就是纯看图，左键动不了线。 */
const DRAW_TOOLS: readonly { id: DrawTool; label: string; hint: string }[] = [
  { id: 'hline', label: '水平线', hint: '在图上点一下就在那个价上落一条水平线；按住拖仍是平移。再点一次退出画图' },
  { id: 'trend', label: '直线', hint: '两点定一条贯穿全图的直线 —— 按住从起点拖出来，或点两下。再点一次退出画图' },
];
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
  /** 周期增删排序的弹窗开关（弹窗本身挂在末尾那枚「＋」上）。 */
  const [intervalPanelOpen, setIntervalPanelOpen] = useState(false);
  const [maPeriods, setMaPeriods] = useState<number[]>(DEFAULT_MA);
  /** 布林带默认关，与 AppSettings 的 bollEnabled 默认值一致。 */
  const [showBoll, setShowBoll] = useState(false);
  const [subPanes, setSubPanes] = useState<SubPaneKind[]>(DEFAULT_SUB_PANES);
  const [candles, setCandles] = useState<Bar[]>([]);
  const market = item?.market;
  const symbol = item?.symbol;
  /**
   * 画线（水平线 + 两点直线）。按标的存（与自选同键）—— 线画在「这段行情」上，
   * 换周期不该丢，换标的必须分开。
   */
  const drawings = useDrawings(item ? watchKey(item) : null);
  /**
   * 选中的画线工具；`null` = 现在不画图，左键在画布上动不了线（线照旧显示、也照旧拖不动）。
   * 落盘不必要：它是一次会话内的操作意图，默认给「不画」—— 谁都不希望一开机左键就在改图。
   */
  const [drawTool, setDrawTool] = useState<DrawTool | null>(null);
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

      {/* 工具条两行：第一行只有周期，第二行把指标 / 副图 / 画图三组一次铺开。
          以前四格单选、每次只展开选中那一组，改任何一个开关都要先多点一下切格子；
          现在周期（天天换）独占一行，另外三组（偶尔调）常驻第二行，不用再声明「我要改哪一组」。
          宽度不够靠 .interval-bar 的 wrap 兜底；1280 宽的桌面两行都是单行。 */}
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
                  {iv}
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
            onClick={() => setShowBoll((v) => !v)}
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

          <div className="sep" />

          <span className="bar-label grp">画图</span>
          <div className="bar-tabs" role="group" aria-label="画线工具">
            {DRAW_TOOLS.map((t) => (
              <button
                key={t.id}
                type="button"
                aria-pressed={drawTool === t.id}
                className={`chip${drawTool === t.id ? ' selected' : ''}`}
                title={`${t.hint}`}
                // 再点一次退回「不画图」：这组 chip 是开关，不是必须选一个的单选钮
                onClick={() => setDrawTool((cur) => (cur === t.id ? null : t.id))}
              >
                {t.label}
              </button>
            ))}
          </div>
          <span className="bar-label">
            {drawTool === null
              ? '右键图上任意处：设告警 / 删线'
              : drawTool === 'hline'
                ? '左键单击落线 · 拖动移线 · 右键弹菜单'
                : '按住拖出直线 · 或点两下 · 拖动移线 · 右键弹菜单'}
          </span>
          <button
            type="button"
            className="chip"
            disabled={drawingCount === 0}
            onClick={drawings.clear}
            title="删掉当前标的的全部画线（水平线 + 直线，含告警线）"
          >
            清空 {drawingCount}
          </button>
          {alertCount > 0 && (
            <span className="bar-label alert" title="价格穿越这些金色点线时弹系统通知（主窗收进托盘也照弹）">
              告警 {alertCount}
            </span>
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
        drawTool={drawTool}
        onAddLine={drawings.addPriceLine}
        onMoveLine={drawings.movePriceLine}
        onAddTrend={drawings.addTrendLine}
        onMoveTrendBy={drawings.moveTrendLineBy}
        onMoveTrendAnchor={drawings.moveTrendAnchor}
        onMenu={setMenu}
        resetPriceSignal={resetPriceSignal}
      />

      {menu && (
        <ChartMenu
          x={menu.x}
          y={menu.y}
          hit={menu.hit}
          priceText={menuPriceText}
          adjusted={menu.adjusted}
          onAddLine={drawings.addPriceLine}
          onRemoveLine={drawings.removePriceLine}
          onSetAlert={drawings.setPriceLineAlert}
          onRemoveTrend={drawings.removeTrendLine}
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
