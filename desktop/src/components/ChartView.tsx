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
import { KlineCanvas, type DrawTool } from './KlineCanvas';

const MA_CHOICES = [5, 10, 20, 30, 60];

/** 工具条那四格（**单选**）：选中哪一格，右边就只展开哪一组的选项。 */
type BarTab = 'interval' | 'indicator' | 'subpane' | 'draw';

/**
 * 一格只管一件事，标签都得短（指标条是靠 `.interval-bar` 的 wrap 兜底的，
 * 多写几个字就在 1280 宽下折成两行、白吃掉 20 多 px 图高）。
 * 说明文字进 `hint`（title）：选中那一格之后要干什么，鼠标停上去就能看到。
 */
const BAR_TABS: readonly { id: BarTab; label: string; hint: string }[] = [
  { id: 'interval', label: '周期', hint: '点一个换一张图；末尾的「＋」是增删与排序' },
  { id: 'indicator', label: '指标', hint: '主图叠加：均线可多选，布林带单独开关' },
  { id: 'subpane', label: '副图', hint: '副图可多选，一个都不点亮 = 不显示副图' },
  {
    id: 'draw',
    label: '画图',
    hint: '只有选中这一格才能在图上动线：左键画、拖动移、右键删',
  },
];

/** 画线工具（单选）：水平线 = 单击落线；直线 = 拖出来或点两下。 */
const DRAW_TOOLS: readonly { id: DrawTool; label: string; hint: string }[] = [
  { id: 'hline', label: '水平线', hint: '在图上点一下就在那个价上落一条水平线；按住拖仍是平移' },
  { id: 'trend', label: '直线', hint: '两点定一条贯穿全图的直线 —— 按住从起点拖出来，或点两下' },
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
  /**
   * 工具条当前选中哪一格。**不落盘**：它只是「现在要看/要改哪一组」的临时焦点，
   * 重启后回到周期最直白（尤其是「画图」—— 谁都不希望一开机右键就在改图）。
   */
  const [tab, setTab] = useState<BarTab>('interval');
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
   * 「画图」格里选中的工具。落盘不必要：它是一次会话内的操作意图；默认水平线（点一下就完事，
   * 是最常画的那一种）。
   */
  const [drawTool, setDrawTool] = useState<DrawTool>('hline');
  const drawingCount = drawings.priceLines.length + drawings.trendLines.length;
  /** 选中「画图」那格 = 画布现在可以动线；另外三格都只是看图，手滑改不了图。 */
  const canDraw = tab === 'draw';

  /**
   * 换格子。离开「画图」时把工具退回水平线 —— 下次进来先给最不容易误操作的那一种，
   * 而不是停在「直线」上让人对着空白一顿拖。
   */
  function pickTab(next: BarTab) {
    setTab(next);
    if (next !== 'draw') setDrawTool('hline');
  }
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

      {/* 工具条：四格**单选**（周期 / 指标 / 副图 / 画图），选中哪一格右边就只展开哪一组。
          平铺那版在 1280 宽下只剩几十 px 富余（内容 ~1015 / 可用 ~1046），再加一组就得折行、
          白吃掉 20 多 px 图高；收成单选之后每行只出现当前那一组，行宽基本恒定。 */}
      <div className="interval-bar">
        <div className="bar-tabs" role="radiogroup" aria-label="图表工具条">
          {BAR_TABS.map((t) => (
            <button
              key={t.id}
              type="button"
              role="radio"
              aria-checked={tab === t.id}
              className={`chip${tab === t.id ? ' selected' : ''}`}
              title={t.hint}
              onClick={() => pickTab(t.id)}
            >
              {t.label}
            </button>
          ))}
        </div>
        <div className="sep" />

        {tab === 'interval' && (
          <>
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
            {/* 末尾这一枚「＋」就是原来的「周期」按钮：增删与排序都在弹窗里（IntervalPanel） */}
            <button
              className="chip"
              title="添加 / 移除 / 排序 K 线周期"
              onClick={() => setIntervalPanelOpen(true)}
            >
              ＋
            </button>
          </>
        )}

        {tab === 'indicator' && (
          <>
            {MA_CHOICES.map((p) => (
              <button
                key={p}
                className={`chip${maPeriods.includes(p) ? ' on' : ''}`}
                title={`叠加 MA${p}（可多选）`}
                onClick={() => toggleMa(p)}
              >
                MA{p}
              </button>
            ))}
            <button
              className={`chip${showBoll ? ' on' : ''}`}
              title="布林带：主图叠加开关（周期 20、倍数 2）"
              onClick={() => setShowBoll((v) => !v)}
            >
              BOLL
            </button>
          </>
        )}

        {tab === 'subpane' && (
          <>
            {SUB_PANE_KINDS.map((kind) => (
              <button
                key={kind}
                className={`chip${subPanes.includes(kind) ? ' on' : ''}`}
                title={`${SUB_PANE_LABEL[kind]} 副图（可多选，一个都不点亮 = 不显示副图）`}
                onClick={() => toggleSubPane(kind)}
              >
                {SUB_PANE_LABEL[kind]}
              </button>
            ))}
          </>
        )}

        {tab === 'draw' && (
          <>
            {/* 这一格是整个画线功能的总开关：选中才动得了线。里面再**单选**一个工具
                （水平线 / 直线），造型沿用左边那四格的分段选择器；手势说明直接写在条上，
                不用逐个 chip 去摸。 */}
            <div className="bar-tabs" role="radiogroup" aria-label="画线工具">
              {DRAW_TOOLS.map((t) => (
                <button
                  key={t.id}
                  type="button"
                  role="radio"
                  aria-checked={drawTool === t.id}
                  className={`chip${drawTool === t.id ? ' selected' : ''}`}
                  title={t.hint}
                  onClick={() => setDrawTool(t.id)}
                >
                  {t.label}
                </button>
              ))}
            </div>
            <span className="bar-label">
              {drawTool === 'hline'
                ? '左键单击落线 · 拖动移线 · 右键删线'
                : '按住拖出直线 · 或点两下 · 拖动移线 · 右键删线'}
            </span>
            <button
              className="chip"
              disabled={drawingCount === 0}
              onClick={drawings.clear}
              title="删掉当前标的的全部画线（水平线 + 直线）"
            >
              清空 {drawingCount}
            </button>
          </>
        )}
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
        drawTool={canDraw ? drawTool : null}
        onAddLine={drawings.addPriceLine}
        onRemoveLine={drawings.removePriceLine}
        onMoveLine={drawings.movePriceLine}
        onAddTrend={drawings.addTrendLine}
        onRemoveTrend={drawings.removeTrendLine}
        onMoveTrendBy={drawings.moveTrendLineBy}
        onMoveTrendAnchor={drawings.moveTrendAnchor}
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
