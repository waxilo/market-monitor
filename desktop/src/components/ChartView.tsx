import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import {
  fetchKlines,
  fetchKlinesBefore,
  nativeLadder,
  nativeMinutes,
  supportsOlderKlines,
  supportsOpenInterest,
  type Bar,
  type Instrument,
  type OpenInterestSeries,
  type Ticker24h,
} from '../lib/api';
import {
  MA_KINDS,
  MA_KIND_LABEL,
  MA_KIND_NAME,
  SUB_PANE_KINDS,
  SUB_PANE_LABEL,
  type MaFamilySpec,
  type MaKind,
  type SubPaneKind,
} from '../lib/chartSeries';
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
  minutesOf,
} from '../lib/intervals';
import type { DrawTool, LineSelection } from '../lib/lineSelection';
import { lineBadgeVisible } from '../lib/priceLines';
import { useSourceKey, endpointOf, spotEndpointOf, useSpotSource } from '../lib/sources';
import { openBinanceSpotStream, openBinanceStream, streamSymbolOf } from '../lib/stream';
import {
  assignMaColor,
  defaultCatalog,
  drawnPeriods,
  lineLabel,
  normalizeMaColors,
  normalizeMuted,
  setAllMuted,
} from '../lib/maPeriods';
import { changeClass, displaySymbol, formatChange, formatCompact, formatPrice } from '../lib/format';
import {
  alignLevelLines,
  analysisWindow,
  applyLevelLine,
  isSystemLine,
} from '../lib/maAnalysis';
import {
  isKlineFresh,
  KLINE_FRESH_MS,
  klineCacheKey,
  klineFetchedAt,
  mergeRefreshedKlines,
  recallKlines,
  rememberKlines,
  subscribeKlines,
} from '../lib/klineCache';
import { loadKlines, loadOpenInterest } from '../lib/klineStore';
import { planWarm, WARM_GAP_MS, type WarmSlot } from '../lib/klineWarm';
import { Sparkline } from '../hooks/useSparks';
import { useDirectConnection } from '../hooks/useDirectConnection';
import { useDrawings } from '../hooks/useDrawings';
import { watchKey, type WatchItem } from '../hooks/useWatchlist';
import { IntervalPanel } from './IntervalPanel';
import { MaPanel } from './MaPanel';
import { MaAnalysisPanel } from './MaAnalysisPanel';
import { EyeIcon } from './EyeIcon';
import { HLineIcon, NoteIcon, TrashIcon, TrendIcon } from './icons';
import { LineNoteDialog } from './LineNoteDialog';
import { KlineCanvas } from './KlineCanvas';

const RT_POLL_MS = 2000;
/**
 * 往更早翻一页、持仓量整段重拉的根数。首屏**不用它**：首屏根数交给 `analysisWindow`
 * （与 EMA 面板同一口径），否则图上长档 EMA 的右端会与面板里的值对不上。
 */
const PAGE_BARS = 300;

/** 指标栏小眼睛的图形见 `./EyeIcon`（均线面板里每条均线也用它，两处必须一致）。 */
/**
 * 翻旧页失败后的退避：拖到左墙时画布**逐帧**上报「还要更早的」，不设冷却就会每秒
 * 打出一串失败请求。冷却过后由 2s 的实时轮询（数据一变就重新上报）自动重试。
 */
const OLDER_RETRY_MS = 5_000;
/**
 * 持仓量的轮询节奏：最细的原生持仓量周期也是 5m（Gate 的 1m 是例外），一小时也就十几个点，
 * 跟 K 线那样 2s 一跳纯属浪费 —— 整段重拉（不搞增量），10s 已远快于数据本身的更新频率。
 */
const OI_POLL_MS = 10_000;

/**
 * 取数失败时的**可读原因**（压成一行，全文挂在提示的 `title` 上）。
 *
 * 为什么值得占这一行：方言层抛出来的原文常常直接点名病灶（`Bitget 400171：Parameter
 * verification failed k-line time range should be [...]`），而笼统的「网络或数据源不通」
 * 会把这层线索吞掉 —— 2026-10-22 的 Bitget 周期码大小写问题就因此只能靠抓包才看得出来。
 */
function errorText(error: unknown): string {
  const text = error instanceof Error ? error.message : String(error ?? '');
  return text.replace(/\s+/g, ' ').trim();
}

/** 一行放得下的长度；短消息原样返回。 */
function clipReason(text: string): string {
  return text.length > 64 ? `${text.slice(0, 64)}…` : text;
}

interface Props {
  item: WatchItem | null;
  ticker: Ticker24h | null;
  instrument?: Instrument;
  spark: number[] | undefined;
  theme: string;
}

export function ChartView({ item, ticker, instrument, spark, theme }: Props) {
  // 参与实时通道 effect 的依赖：强制直连时 WS 不开，需要重排成 REST 兜底
  const direct = useDirectConnection();
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
  const { families, boll: showBoll, panes: subPanes } = prefs;
  /** 均线增删排序的弹窗开关（弹窗挂在指标栏末尾那枚「＋」上）。 */
  const [maPanelOpen, setMaPanelOpen] = useState(false);
  /**
   * 均线分析面板的显隐。
   * 入口在**周期条右端**（2026-10-30 从指标组挪过去）；面板本体也在本组件里渲染，
   * 它要读图表的周期 / 均线 / 划线状态，状态放在这里最省事。MA / EMA 两族并进了
   * 同一个面板（`MaAnalysisPanel`），看哪一族由面板抬头里的切换自己管。
   */
  const [analysisOpen, setAnalysisOpen] = useState(false);
  const [candles, setCandles] = useState<Bar[]>([]);
  /**
   * 画布上这份蜡烛属于哪条缓存（见 `lib/klineCache`）。实时帧、翻旧页都在改写 `candles`，
   * 写回缓存时必须认准**它自己**那条 —— 切过去的瞬间 `candles` 还是上一段的，
   * 按「当前选中的周期」写就会把上一段的蜡烛塞进新周期。
   */
  const [candlesKey, setCandlesKey] = useState<string | null>(null);
  /**
   * EMA 预热放弃（历史不够长 / 补齐失败）：那时就用手上的根数直接画，不再等。
   * 换标的 / 换周期时归零（见下面的取数 effect）。
   */
  const [warmGaveUp, setWarmGaveUp] = useState(false);
  /**
   * 首屏根数：EMA 一族要「最长档位 × 4」根才收敛（见 `maAnalysis.WARMUP_FACTOR`），
   * 这一段是**后台补齐**的目标，不是首屏要等的量。
   */
  const initialBars = analysisWindow(families.EMA.catalog);
  /**
   * 首屏一次取多少根：**一屏多一点**（默认视窗 60 根 + 左缘提前量 120），
   * 再保证最长 MA 算得出来（+30 缓冲）；BOLL 只要 20 根。
   * 长档 EMA 的预热历史由下面的补齐 effect 慢慢往上堆 —— 首屏不陪它等。
   */
  const fastBars = Math.max(200, ...families.MA.catalog.map((period) => period + 30));
  /**
   * EMA 到底画不画（族开着、至少一条眼睛睁着）。不画就**不需要**预热 —— 裸 K / 只开 MA 时
   * 不该为了一条看不见的线多打一次补齐请求。
   */
  const emaDrawn = !families.EMA.hidden && drawnPeriods(families.EMA).length > 0;
  /**
   * EMA 能不能画：根数够了（或补齐已放弃 / 本来就不画）。补齐前**不画 EMA 线与读数** ——
   * 用短窗口算出来的值会与 EMA 分析面板对不上，先闪一个错数再自己变，比晚一秒更糟。
   */
  const emaReady = !emaDrawn || candles.length >= initialBars || warmGaveUp;
  /**
   * 交给画布的均线族：把「藏起来」的族折算成空目录，**族内再减去眼睛闭上的那几条** ——
   * 画布只画给它的东西，不知道显隐开关这回事（与画线小眼睛同口径）。
   * EMA 在预热补齐前也折算成空目录（见 `emaReady`）。
   */
  const visibleFamilies = useMemo<MaFamilySpec[]>(
    () =>
      MA_KINDS.map((kind) => ({
        kind,
        periods:
          families[kind].hidden || (kind === 'EMA' && !emaReady)
            ? []
            : drawnPeriods(families[kind]),
        colors: families[kind].colors,
      })),
    [families, emaReady],
  );
  /**
   * 取数状态。换标的 / 换周期 / 换数据源时先立 `loading`（画布出骨架 + 遮罩），
   * 数据到了才落下 —— 不立的话那一段只能看到一块全白的画布，用户分不清是在取数还是坏了。
   *
   * 初值给真（与 App 的 `loadingCandles = true` 同）：首帧还没跑取数 effect，不能先闪一下
   * 「没有取到 K 线」。`failed` 与「取回来是空的」分开记 —— 一个是网络/数据源不通（该重试），
   * 一个是这个周期本来就没数据，提示语不同。
   */
  const [load, setLoad] = useState({ loading: true, failed: false, reason: '' });
  /** 「重新加载」递一次 = 让取数 effect 重跑（空数据时那块提示里的按钮）。 */
  const [reloadSeq, setReloadSeq] = useState(0);
  const market = item?.market;
  const symbol = item?.symbol;
  /**
   * 画线（水平线 + 两点直线）。按标的存（与标签页同键）—— 线画在「这段行情」上，
   * 换周期不该丢，换标的必须分开。
   */
  const drawings = useDrawings(item ? watchKey(item) : null);
  const drawingCount = drawings.priceLines.length + drawings.trendLines.length;
  const alertCount = drawings.priceLines.filter((l) => l.alert).length;
  /**
   * 划线区的两个状态（口径见 `lib/lineSelection`）：
   * · `tool` —— 刚点了哪种线型，接下来在图上落点就是画线；
   * · `selection` —— 图上哪条线被选中，划线区随之整块变成对它的操作按钮（备注 / 告警 / 删除 / ✕）。
   * 两者互斥（工具优先），且都只在解锁态成立。
   */
  const [tool, setTool] = useState<DrawTool>('none');
  const [selection, setSelection] = useState<LineSelection | null>(null);
  /**
   * 正在改备注的那条线（`null` = 备注框没开）。
   *
   * 与选中分开存：选中是「图上那条线被点亮」，备注框是一个**等待输入**的弹窗 ——
   * 关掉弹窗后线仍然是选中的（用户可能接着点删除），两者的生命周期不一样。
   */
  const [noteEdit, setNoteEdit] = useState<{
    target: LineSelection;
    /** 打开时线已有的备注（预填，改起来不用重打）。 */
    value: string;
    title: string;
  } | null>(null);
  // 换数据源 = 换盘口：整段 K 线重拉（历史与增量都吃这个依赖）
  const source = useSourceKey(market);
  /**
   * 补旧页的去重 / 状态机。放在 ref 里而不是 state：画布上报是**逐帧**的
   * （拖动每一帧都可能在报「到墙了」），state 落后一帧就会重复发请求。
   * `epoch` 每次换标的/周期/源递增一次：在飞的那页回来时对不上号就直接作废。
   */
  const olderRef = useRef({
    inFlight: false,
    exhausted: false,
    requestedOldest: 0,
    failedAt: 0,
    epoch: 0,
  });
  /** 当前市场 + 数据源**原生**支持哪些周期（分钟数）；换源后重算（依赖 source）。 */
  const natives = useMemo(() => nativeMinutes(market ?? 'FUTURES'), [market, source]);
  /** 现货源是可选的（默认币安）：标签必须跟着选择走，不能写死某一家。 */
  const spotSource = useSpotSource();
  const sourceLabel = market === 'FUTURES' ? endpointOf(source).label : spotEndpointOf(spotSource).label;
  /** 当前这组「市场|标的|数据源|周期」的缓存键；没选标的时为 null。 */
  const klineKey = item ? klineCacheKey(item.market, item.symbol, source, interval) : null;
  /** 当前数据源有没有历史持仓量（OI 副图那枚 chip 的可用性）；换源后重算（依赖 source）。 */
  const oiSupported = useMemo(
    () => (market === 'FUTURES' ? supportsOpenInterest(market) : false),
    [market, source],
  );
  /** 持仓量序列（原始点，对齐在画布层做）；换标的 / 换源 / 关副图都会重取或清空。 */
  const [oi, setOi] = useState<OpenInterestSeries | null>(null);
  const oiSelected = subPanes.includes('OI');

  // 换标的（画线的落盘键变了）/ 眼睛把线全藏起来：选中与工具一律作废。
  // 它们指向的是**上一张图**（或者图上已经看不见）的线，留着只会让划线区对着一条
  // 不存在的线发指令。
  useEffect(() => {
    setTool('none');
    setSelection(null);
  }, [item?.market, item?.symbol, drawings.linesHidden]);

  // 线被删掉了（自己删、清空、EMA 面板收回）：选中跟着失效 —— 否则划线区会继续显示
  // 那条已经不在的线，点一下删除还会按旧下标删到别人。
  useEffect(() => {
    if (!selection) return;
    const count = selection.kind === 'price' ? drawings.priceLines.length : drawings.trendLines.length;
    if (selection.index >= count) setSelection(null);
  }, [selection, drawings.priceLines.length, drawings.trendLines.length]);

  /** 删掉当前选中的那条线（划线区的垃圾桶与键盘 Delete / Backspace 共用一个动作）。 */
  const removeSelected = useCallback(() => {
    if (!selection) return;
    if (selection.kind === 'price') drawings.removePriceLine(selection.index);
    else drawings.removeTrendLine(selection.index);
    setSelection(null);
  }, [selection, drawings.removePriceLine, drawings.removeTrendLine]);

  // 选中一条线后按 Delete / Backspace 直接删掉它（Mac 键盘上那颗删除键发的是 Backspace）。
  // 三处让路，删除键在这些时候不归画线：备注弹窗开着；焦点在输入类控件里；键已被别的组件
  // 吃掉（EnglishField 这类自绘框靠 preventDefault 把 ←→ / 退格收给自己，不能再删线）。
  useEffect(() => {
    if (!selection) return;
    function onKeyDown(e: KeyboardEvent) {
      if (e.defaultPrevented || noteEdit) return;
      if (e.key !== 'Delete' && e.key !== 'Backspace') return;
      const target = e.target instanceof HTMLElement ? e.target : null;
      if (target?.closest('input, textarea, select, [contenteditable="true"]')) return;
      e.preventDefault();
      removeSelected();
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [selection, noteEdit, removeSelected]);

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

  // 切换标的/周期：**双段式上屏** —— 先把旧图撤掉、盖上骨架 + 「加载中」遮罩，
  // 最新这段回来后才整图上屏。只有一种情况跳过第一段：`KLINE_FRESH_MS`（5s）内刚取过，
  // 那条路径根本不发请求、手上就是最新的（来回对比两个周期时不闪）。
  //
  // 为什么不再「先铺缓存、再静默替换」：缓存常常是几十秒前的那段，末几根与此刻的最新价
  // 对不上；先画上去再自己变一次，看着像图在抽风。多等一次往返反而是明确的。
  // 缓存从此只当**退路**：请求失败 / 回来是空的，拿它兜底（见下），总比空图或一直转圈强。
  // `reloadSeq` 只为「重新加载」按钮服务。
  //
  // 用 layout effect 而不是 effect：换周期必须**在同一帧里**把蜡烛换掉。否则画布会先用
  // 新周期的坐标画上一段的蜡烛（一帧错轴、时间轴标签也错）。
  useLayoutEffect(() => {
    if (!market || !symbol || klineKey == null) {
      setCandlesKey(null);
      setCandles([]);
      setLoad({ loading: false, failed: false, reason: '' });
      return;
    }
    let alive = true;
    const cached = recallKlines(klineKey);
    // 5s 内刚取过就不用打接口：末几根由 2s 的实时链路追平，图上没差别。
    // 超了就走加载态 —— 宁可多等一次往返，也不拿过期的蜡烛顶在前面。
    // 还要求**根数够一屏**：缓存可能是面板写进来的、也可能短得连 MA 都算不出来。
    const fresh = cached != null && cached.length >= fastBars && isKlineFresh(klineKey);
    setCandlesKey(klineKey);
    setCandles(fresh && cached ? cached : []);
    setLoad({ loading: !fresh, failed: false, reason: '' });
    setWarmGaveUp(false);
    // 换目标 = 上一段的历史翻页全部作废；旧页在飞也让它自生自灭（epoch 对不上不落地）
    const older = olderRef.current;
    older.inFlight = false;
    older.exhausted = false;
    older.requestedOldest = 0;
    older.failedAt = 0;
    older.epoch += 1;
    // 刚取过就不再打接口（见上 `fresh`）
    if (fresh) return;
    // 走统一入口（`lib/klineStore`）：命中「刚被面板 / 预热刷新过」的缓存就不再发请求，
    // 与面板扫描撞上时共用同一个 in-flight 请求。
    loadKlines({ market, symbol, source, interval, want: fastBars, freshMs: KLINE_FRESH_MS })
      .then((bars) => {
        if (!alive) return;
        // 核对回来是空的（数据源抽风）：退回缓存那份，总比空图好；
        // 没有缓存就走下面的空态（「这个周期没有取到 K 线」）
        if (bars.length === 0 && cached != null) {
          setCandles(cached);
          setLoad({ loading: false, failed: false, reason: '' });
          return;
        }
        // 只换掉重叠段，缓存里更老的历史（翻旧页补回来的）原样保留
        setCandles(mergeRefreshedKlines(bars, recallKlines(klineKey)));
        setLoad({ loading: false, failed: false, reason: '' });
      })
      .catch((error: unknown) => {
        if (!alive) return;
        // 失败：有缓存就退回它（过期几根也比一块空图 + 「重新加载」更接近真相），
        // 没有才给失败提示 —— 那时遮罩必须撤掉，否则一直是「加载中」，看着像卡死了。
        // reason 一路带出来：各家业务码（如 Bitget 400171 参数非法）与「网络不通」是两种病，
        // 提示里只说「网络或数据源不通」会把能自查的线索吞掉（2026-10-22 的 Bitget 周期码 bug 即如此）
        if (cached != null) {
          setCandles(cached);
          setLoad({ loading: false, failed: false, reason: '' });
          return;
        }
        setLoad({ loading: false, failed: true, reason: errorText(error) });
      });
    return () => {
      alive = false;
    };
  }, [market, symbol, klineKey, interval, source, fastBars, reloadSeq]);

  /**
   * EMA 预热补齐：首屏只取 `fastBars`，长档 EMA 要「最长档 × 4」根才收敛，
   * 这段历史在**后台往前补** —— 一次 effect 只补一页，candles 变长后自己重跑，
   * 天然串行，也不会和用户「拖到左墙」的补页抢（共用 `olderRef.inFlight`）。
   *
   * 取到历史头（短页 / 空页）、来源不支持时间窗、或这一页失败：就用手上的根数收尾
   * （`setWarmGaveUp`）—— 新上市的标的本来就没有那么多历史，EMA 不该一直不出现。
   */
  useEffect(() => {
    if (!market || !symbol || klineKey == null || candles.length === 0 || !emaDrawn) return;
    if (candles.length >= initialBars || warmGaveUp) return;
    const older = olderRef.current;
    if (older.exhausted) {
      setWarmGaveUp(true);
      return;
    }
    if (older.inFlight) return;
    if (!supportsOlderKlines(market)) {
      older.exhausted = true;
      setWarmGaveUp(true);
      return;
    }
    const oldest = candles[0]?.timestamp;
    if (oldest == null) return;
    // 这一端已经为这根要过一页、却没往前推进（服务端无视时间窗 / 整页都被去重掉）：
    // 再要只会原地打转，收手用手上的根数画。
    if (oldest === older.requestedOldest) {
      setWarmGaveUp(true);
      return;
    }
    const limit = Math.min(PAGE_BARS, initialBars - candles.length);
    const epoch = older.epoch;
    older.inFlight = true;
    older.requestedOldest = oldest;
    fetchKlinesBefore(market, symbol, interval, oldest, limit)
      .then((bars) => {
        if (olderRef.current.epoch !== epoch) return;
        if (bars.length < limit) olderRef.current.exhausted = true;
        if (bars.length > 0) {
          setCandles((prev) => {
            // 以 prev 的老端为准再滤一次（与 loadOlder 同一口径）
            if (prev.length === 0 || prev[0].timestamp !== oldest) return prev;
            const fresh = bars.filter((b) => b.timestamp < prev[0].timestamp);
            return fresh.length === 0 ? prev : [...fresh, ...prev];
          });
        }
        if (bars.length < limit) setWarmGaveUp(true);
      })
      .catch(() => {
        if (olderRef.current.epoch !== epoch) return;
        // 补不回来就算了：EMA 用手上的根数先画，别让图一直缺一族
        olderRef.current.failedAt = Date.now();
        setWarmGaveUp(true);
      })
      .finally(() => {
        if (olderRef.current.epoch === epoch) olderRef.current.inFlight = false;
      });
  }, [market, symbol, klineKey, interval, source, candles, initialBars, warmGaveUp, emaDrawn]);

  // 实时帧、翻旧页都在改写 candles：同步写回**它自己**那条缓存，
  // 切回来时连补过的历史一起恢复（否则只记得首屏那几百根）。
  useEffect(() => {
    if (candlesKey == null || candlesKey !== klineKey || candles.length === 0) return;
    rememberKlines(candlesKey, candles);
  }, [candlesKey, klineKey, candles]);

  /**
   * 均线分析面板（或预热）刷新了这个周期 → **静默合并**进来（2026-11-21 用户口径）。
   *
   * 不盖遮罩、不动翻页历史，只把重叠段换成刚取回的那份：面板一轮扫描本来就会把
   * 整条周期条各取一遍，它手里的当前周期比 2s 链路更权威（整段重取，连事后修正的成交量都带回来）。
   * 用户看不到任何「重新加载」，而图上最后一根跟着准了。
   *
   * 只在**手上已有图**时合并：加载态里插队会让首屏那一段的落点变得不可预测
   * （图会先被面板这份填上，随后自己的请求回来又整体换掉，反而闪一下）。
   */
  useEffect(() => {
    if (klineKey == null) return;
    return subscribeKlines((key, bars) => {
      if (key !== klineKey) return;
      setCandles((prev) => {
        if (prev.length === 0) return prev;
        const merged = mergeRefreshedKlines(bars, prev);
        // 一模一样（同一段、同一首尾）就别造新数组：画布每次重绘都是白花的
        if (
          merged.length === prev.length &&
          merged[0]?.timestamp === prev[0]?.timestamp &&
          merged[merged.length - 1]?.timestamp === prev[prev.length - 1]?.timestamp
        ) {
          return prev;
        }
        return merged;
      });
    });
  }, [klineKey]);

  /**
   * 周期条上**其它**周期的后台预热（调度规则见 `lib/klineWarm`）。
   *
   * 为什么要有这一层：切周期慢的根子不是网络，是「别的周期根本没有数据」——
   * 缓存只记看过的那几个（默认周期条就有 8 个 chip），冷启动的切换必然要等一次完整往返。
   * 这里按 60s 一轮把每个周期各自取一次写进缓存：切换那一发若失败/回来是空的，
   * 手上有整段可以退守（见取数 effect），5s 内来回对比也能直接上屏；
   * 当前周期跳过（它自己有 2s 末根链路），同一时刻只发一个请求、相邻两次隔 `WARM_GAP_MS`，
   * 冷启时约十几秒把整条铺完，之后按各自的到期时间错开。
   *
   * 窗口不可见时**照跑**（用户口径：宁可多打几个请求，也别让切换撞上接口抖动时无货可退）；
   * 换标的 / 换源 / 改周期列表都会重建队列，在飞的旧请求靠 `alive` 丢弃。
   */
  useEffect(() => {
    if (!market || !symbol || intervals.length === 0) return;
    let alive = true;
    let timer = 0;
    /** 本帧内失败过的周期：缓存只记「成功取到的时刻」，失败得自己记（退避用）。 */
    const failed = new Map<string, number>();

    const slots = (): WarmSlot[] =>
      intervals.map((iv) => ({
        interval: iv,
        fetchedAt: klineFetchedAt(klineCacheKey(market, symbol, source, iv)) ?? 0,
        failedAt: failed.get(iv) ?? 0,
      }));

    const schedule = (waitMs: number) => {
      window.clearTimeout(timer);
      timer = window.setTimeout(tick, Math.max(0, waitMs));
    };

    const tick = () => {
      if (!alive) return;
      const plan = planWarm(slots(), Date.now(), interval);
      if (plan == null) return;
      const wait = plan.dueAt - Date.now();
      if (wait > 0) {
        schedule(wait);
        return;
      }
      const target = plan.interval;
      // 统一入口：与面板扫描撞上时搭同一趟车（面板正在飞这个周期就不重复打）。
      // `freshMs: 0` —— 该不该取已经由 `planWarm` 判过，这里只管取。
      loadKlines({ market, symbol, source, interval: target, want: fastBars, freshMs: 0 })
        .then((bars) => {
          // 取回来是空的（这个周期本来没数据 / 源抽风）也算失败：否则它会一直「最饿」，
          // 每 1.5s 被选中一次 —— 变成打接口的死循环
          if (bars.length === 0) {
            if (alive) failed.set(target, Date.now());
            return;
          }
          failed.delete(target);
          // 人切走了（effect 已卸载）也照样落了缓存（在 store 里）：这一份数据本身是有效的，
          // 丢掉等于下次再打一遍同一个请求。合并老段也在 store 里做，这里不再手动写。
        })
        .catch(() => {
          if (alive) failed.set(target, Date.now());
        })
        .finally(() => {
          if (alive) schedule(WARM_GAP_MS);
        });
    };

    // 首拍让给当前周期的首屏请求：它没回来之前别抢带宽
    schedule(WARM_GAP_MS);
    return () => {
      alive = false;
      window.clearTimeout(timer);
    };
  }, [market, symbol, source, interval, intervals, fastBars]);

  /**
   * 「时间轴快够到已加载的最老一根了」→ 往前补一页（画布逐帧上报，这里同步去重）。
   *
   * 补法是**前插**：视窗意图（rightOffset）不动，prepend k 根后老数据整体右移 k 个下标、
   * plotStart 也右移 k —— 同一根蜡烛的落点像素级不变，**不跳**（这就是「流畅」从哪来）。
   * 不设「最多存多少根」的上限：桌面端全在内存里（3 万根 ≈ 1MB 出头），
   * 而截断反而是个坑 —— 深视窗下刚补回来的段被截掉、老端时间戳没变，补页会无限重复同一页。
   */
  const loadOlder = useCallback(() => {
    const older = olderRef.current;
    if (!market || !symbol || older.inFlight || older.exhausted) return;
    // 来源不支持按时间窗翻页（HTX）：标记耗尽，不再问（见 api.supportsOlderKlines）
    if (!supportsOlderKlines(market)) {
      older.exhausted = true;
      return;
    }
    const oldest = candles[0]?.timestamp;
    if (oldest == null) return;
    // 这一端已经为这个时间戳要过一页了（拖动逐帧上报、补页在飞都会走到这）
    if (oldest === older.requestedOldest) return;
    if (Date.now() - older.failedAt < OLDER_RETRY_MS) return;
    older.inFlight = true;
    older.requestedOldest = oldest;
    const epoch = older.epoch;
    fetchKlinesBefore(market, symbol, interval, oldest, PAGE_BARS)
      .then((bars) => {
        if (olderRef.current.epoch !== epoch) return;
        // 取回不足一页 = 这一端真的到头了（各家的 time-window 取数都是「尽量填满」，
        // 有空洞也会往前补数，所以短页只可能发生在历史起点）
        if (bars.length < PAGE_BARS) older.exhausted = true;
        if (bars.length === 0) return;
        setCandles((prev) => {
          // 以 prev 的老端为准再滤一次：请求在飞时实时轮询换过数组也不打紧
          if (prev.length === 0 || prev[0].timestamp !== oldest) return prev;
          const fresh = bars.filter((b) => b.timestamp < prev[0].timestamp);
          return fresh.length === 0 ? prev : [...fresh, ...prev];
        });
      })
      .catch(() => {
        if (olderRef.current.epoch === epoch) older.failedAt = Date.now();
      })
      .finally(() => {
        if (olderRef.current.epoch === epoch) older.inFlight = false;
      });
  }, [market, symbol, interval, candles]);

  /**
   * 实时的那一根：币安系（合约 / Aster / 币安现货）走 WS 推 `@kline_<周期>`，
   * 省掉 2s 一次的 REST 权重。WS 不是免封金牌 —— 受限 IP 上币安会接受握手但不推任何数据，
   * 所以只有收到**首帧**才停 REST 兜底；其余来源或 WS 没数据时仍按 2s 取末根
   * （App 的增量节奏也是 2s）。聚合周期没有原生流（如 45m），只能继续走 REST ——
   * 那边本来就是拼出来的。
   */
  useEffect(() => {
    if (!market || !symbol) return;
    let alive = true;

    const applyLive = (update: Bar) => {
      setCandles((prev) => {
        if (prev.length === 0) return prev;
        const last = prev[prev.length - 1];
        if (update.timestamp === last.timestamp) return [...prev.slice(0, -1), update];
        if (update.timestamp > last.timestamp) return [...prev, update];
        return prev;
      });
    };

    const minutes = minutesOf(interval);
    const code = minutes != null && natives.has(minutes) ? nativeLadder(market)[minutes] : undefined;
    const futuresStream = market === 'FUTURES' && endpointOf(source).dialect === 'BINANCE';
    const spotStream = market === 'SPOT' && spotSource === 'BINANCE';
    let wsLive = false;
    let stopStream = () => {};
    if (code != null && (futuresStream || spotStream)) {
      const streams = [`${streamSymbolOf(symbol)}@kline_${code}`];
      const handlers = {
        onClose: () => {
          wsLive = false;
        },
        onKline: (streamSymbol: string, bar: Bar) => {
          if (!alive || streamSymbol !== symbol) return;
          // 收到第一帧才停 REST 兜底：受限 IP 上币安会接受握手但不推任何数据
          wsLive = true;
          applyLive(bar);
        },
      };
      stopStream = futuresStream
        ? openBinanceStream(source, streams, handlers)
        : openBinanceSpotStream(streams, handlers);
    }

    const timer = setInterval(() => {
      if (wsLive) return;
      // 这一发**故意不走 `loadKlines`**：它只要「最新一根」用来追末根，
      // 走统一入口会命中缓存、拿回整段（`bars[0]` 是最老的那根，直接画错），
      // 而且每 2s 写一次缓存并触发订阅，白白让画布重绘。
      fetchKlines(market, symbol, interval, 1)
        .then((bars) => {
          if (!alive || bars.length === 0) return;
          applyLive(bars[0]);
        })
        .catch(() => {});
    }, RT_POLL_MS);
    return () => {
      alive = false;
      clearInterval(timer);
      stopStream();
    };
  }, [market, symbol, interval, source, natives, direct]);

  // 持仓量：选了 OI 副图且当前源支持才取；10s 整段重拉（见 OI_POLL_MS 的注释）
  useEffect(() => {
    if (!oiSelected || !market || !symbol || !oiSupported) {
      setOi(null);
      return;
    }
    let alive = true;
    // 换标的 / 换周期时先清掉旧序列：上一段的持仓量画在新 K 线上就是一坨错线
    setOi(null);
    const load = () => {
      // 副图取数也走统一层（`lib/klineStore`）：OI 是独立接口、有自己的缓存，
      // 10s 内切回同一个周期直接命中；同一时刻的并发（切周期瞬间的两次轮询）只打一次。
      loadOpenInterest({ market, symbol, source, interval, want: PAGE_BARS })
        .then((series) => {
          if (alive) setOi(series);
        })
        .catch(() => {});
    };
    load();
    const timer = setInterval(load, OI_POLL_MS);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, [oiSelected, oiSupported, market, symbol, interval, source]);

  /**
   * 更新某一族的**目录**（面板里增删/排序）：同一族的配色跟着归一 ——
   * 保留已有均线的槽位，新加的挑第一个空槽，删掉的让出颜色。
   *
   * 目录只是「配了哪些」，画不画由两级开关决定（族 chip + 每条的小眼睛）；
   * 删掉一条就从图上消失、加一条就先睁着眼出现 —— 新加的均线不该继承任何隐藏态。
   */
  function setCatalog(kind: MaKind, next: number[]) {
    setPrefs((prev) => {
      const family = prev.families[kind];
      return {
        ...prev,
        families: {
          ...prev.families,
          [kind]: {
            ...family,
            catalog: next,
            colors: normalizeMaColors(next, family.colors),
            // 删掉的档位必须一并从「眼睛闭着」里消失：否则删了再加回来会莫名不显示
            muted: normalizeMuted(next, family.muted),
          },
        },
      };
    });
  }

  /**
   * 改某一族的**逐条小眼睛**（面板里每条行尾那只）。
   *
   * 与 `toggleFamily` 正交：那只总闸只改 `hidden`，这里只改 `muted` ——
   * 族关掉再打开，谁睁眼谁闭眼一动不动（用户不必重新点一遍）。
   */
  function setMuted(kind: MaKind, next: number[]) {
    setPrefs((prev) => {
      const family = prev.families[kind];
      return {
        ...prev,
        families: {
          ...prev.families,
          [kind]: { ...family, muted: normalizeMuted(family.catalog, next) },
        },
      };
    });
  }

  /** 「全部显示 / 全部隐藏」：一次改完这一族的所有小眼睛（面板标题行那个小按钮）。 */
  function setAllPeriodHidden(kind: MaKind, hide: boolean) {
    setPrefs((prev) => {
      const family = prev.families[kind];
      return {
        ...prev,
        families: {
          ...prev.families,
          [kind]: { ...family, muted: setAllMuted(family.catalog, hide) },
        },
      };
    });
  }

  /** 改某条均线的线色；目标色被同族别的均线占着就互换（一色一线）。 */
  function setMaColor(kind: MaKind, period: number, slot: number) {
    setPrefs((prev) => {
      const family = prev.families[kind];
      return {
        ...prev,
        families: {
          ...prev.families,
          [kind]: { ...family, colors: assignMaColor(family.colors, family.catalog, period, slot) },
        },
      };
    });
  }

  /** 某一族恢复默认：目录回到该族出厂档位，配色也回到出厂那套（0 号槽起步），小眼睛全部睁开。 */
  function resetCatalog(kind: MaKind) {
    const next = defaultCatalog(kind);
    setPrefs((prev) => ({
      ...prev,
      families: {
        ...prev.families,
        [kind]: {
          ...prev.families[kind],
          catalog: next,
          colors: normalizeMaColors(next, null),
          muted: [],
        },
      },
    }));
  }

  /** 指标栏上那枚 chip：整族显隐（目录与配色原样保留，再点一下回来）。 */
  function toggleFamily(kind: MaKind) {
    setPrefs((prev) => ({
      ...prev,
      families: {
        ...prev.families,
        [kind]: { ...prev.families[kind], hidden: !prev.families[kind].hidden },
      },
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
   * 划线区在选中态下要显示的那串身份字：
   * 水平线报价位（那是它唯一的身份），直线只有序号可报。精度跟着 tickSize，与轴上的标号同一套。
   */
  const selectedLine =
    selection?.kind === 'price' ? drawings.priceLines[selection.index] : undefined;
  const selectedLineLabel = !selection
    ? ''
    : selection.kind === 'price'
      ? formatPrice(selectedLine?.price ?? NaN, instrument?.tickSize)
      : `直线 ${selection.index + 1}`;

  /** 打开备注框：预填已有备注，抬头要能认出是哪条线（水平线报价位、直线报序号）。 */
  function openNote(target: LineSelection) {
    if (target.kind === 'price') {
      const line = drawings.priceLines[target.index];
      setNoteEdit({
        target,
        value: line?.note ?? '',
        title: `水平线 ${formatPrice(line?.price ?? NaN, instrument?.tickSize)}`,
      });
    } else {
      const line = drawings.trendLines[target.index];
      setNoteEdit({ target, value: line?.note ?? '', title: `直线 ${target.index + 1}` });
    }
  }

  if (!item) {
    return (
      <section className="panel panel-chart">
        <div className="chart-empty">
          <span className="overline">K 线</span>
          <span className="chart-empty-hint">
            没有打开的图表 —— 顶部搜索框搜现货或永续全市场，点一条结果就开一个标签页
          </span>
        </div>
      </section>
    );
  }

  return (
    <section className="panel panel-chart">
      <div className="chart-head">
        <div className="chart-title">
          <div className="base">
            {instrument?.baseAsset ?? item.symbol.split('_')[0]}
            <span className="sym">
              {displaySymbol(item.symbol)} · {item.market === 'FUTURES' ? '永续' : '现货'}
            </span>
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

      {/* 工具条两行：第一行只有周期，第二行是指标 / 副图 / 划线三组。
          「划线区」在最末尾（组名「划线」到行尾）：选线型、清空、锁定、眼睛、告警数都在那里，
          选中图上某条线之后整块变成对那条线的操作按钮 —— 详见下面那一段注释。
          均线不占额外的眼睛：MA / EMA 两枚 chip 自己就是整族显隐开关。
          宽度不够靠 .interval-bar 的 wrap 兜底。 */}
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
          {/* 均线分析钉在周期条**右端**（2026-10-30 用户指定）：它是「换个视角看这段行情」的
              **动作**，不是又一个指标开关 —— 放在指标组里会被读成 BOLL 的邻居。入口只有
              一枚（2026-11 用户要求把 MA / EMA 两枚并成一个）：族的选择收进面板抬头，
              一次点开就是把当前那一族所有已配置档位 × 所有已配置周期的当前值汇总成
              压力 / 支撑两栏。auto 外边距把它推到行尾。 */}
          <button
            type="button"
            className={`chip analysis-entry${analysisOpen ? ' on' : ''}`}
            aria-pressed={analysisOpen}
            title="均线分析：把 MA / EMA 所有已配置档位在所有已配置周期上的当前值汇总，按最新价分成上方压力 / 下方支撑"
            onClick={() => setAnalysisOpen((prev) => !prev)}
          >
            均线分析
          </button>
        </div>

        <div className="interval-bar">
          <div className="bar-group">
            <span className="bar-label grp">指标</span>
            {/* 均线**收敛成两枚 chip**：MA 一枚、EMA 一枚，点了就是整族显隐。
                具体画哪几档、各是什么线色，全在末尾「＋」的配置面板里定 ——
                指标栏不再按档位铺开（MA5/MA10/… 一排下去，档位一多就挤成一条长龙）。 */}
            {MA_KINDS.map((kind) => {
              const family = families[kind];
              // 族开着也只是「参与绘图」：真正落笔的是没被小眼睛藏起来的那几条
              const drawn = drawnPeriods(family).map((period) => lineLabel(kind, period));
              const mutedCount = family.catalog.length - drawn.length;
              return (
                <button
                  key={kind}
                  type="button"
                  className={`chip${family.hidden ? '' : ' on'}`}
                  aria-pressed={!family.hidden}
                  title={
                    family.catalog.length === 0
                      ? `${MA_KIND_NAME[kind]}：还没配置档位，点「＋」去加`
                      : drawn.length === 0
                        ? `${MA_KIND_NAME[kind]}：${family.catalog.length} 条都被小眼睛藏起来了（点「＋」去开）`
                        : `${MA_KIND_NAME[kind]}：${drawn.join(' / ')}` +
                          `${mutedCount > 0 ? ` · 另 ${mutedCount} 条被小眼睛藏起` : ''}` +
                          '（点一下整族显示 / 隐藏）'
                  }
                  onClick={() => toggleFamily(kind)}
                >
                  {MA_KIND_LABEL[kind]}
                </button>
              );
            })}
            <button
              type="button"
              className={`chip${showBoll ? ' on' : ''}`}
              title="布林带：主图叠加开关（周期 20、倍数 2）"
              onClick={() => setPrefs((prev) => ({ ...prev, boll: !prev.boll }))}
            >
              BOLL
            </button>
            {/* 指标组的最后一枚「＋」是均线配置面板：MA / EMA 各一页，增删、排序、线色都在里面。
                放在组末 —— 它是「配置」，不是又一个指标开关。 */}
            <button
              type="button"
              className="chip"
              title="自定义 MA / EMA 均线（每族有哪些档位、什么线色）"
              onClick={() => setMaPanelOpen(true)}
            >
              ＋
            </button>
          </div>

          <div className="sep" />

          <div className="bar-group">
            <span className="bar-label grp">副图</span>
            {SUB_PANE_KINDS.map((kind) => {
              // 持仓量是永续专属、且只有四家数据源提供；不满足就置灰（原因写在 title 里）
              const unsupported = kind === 'OI' && !oiSupported;
              return (
                <button
                  key={kind}
                  type="button"
                  className={`chip${subPanes.includes(kind) ? ' on' : ''}`}
                  disabled={unsupported}
                  title={
                    unsupported
                      ? market === 'SPOT'
                        ? '现货没有持仓量（永续专属）'
                        : `${sourceLabel} 没有持仓量历史（Gate / 币安系 / OKX / Bybit 有）`
                      : `${SUB_PANE_LABEL[kind]} 副图（可多选，一个都不点亮 = 不显示副图）`
                  }
                  onClick={() => toggleSubPane(kind)}
                >
                  {SUB_PANE_LABEL[kind]}
                </button>
              );
            })}
          </div>

          {/* ── 划线区 ────────────────────────────────────────────────
              从「划线」这个组名到本行末尾，整块就是**划线区**（2026-10-24 定名）：
              画什么线、改哪条线都只在这里做。右键菜单已整档移除 —— 画线不再依赖
              「光标刚好落在哪儿」这种隐式状态，靠的是划线区里显式的当前状态。

              两种面孔（互斥）：
              · 平时 = 选线型（水平线 / 直线，点一次进入、再点一次退出）+ 清空 + 锁定 + 眼睛 + 告警数；
              · 选中图上某条线之后 = 只留对**这一条**的按钮（备注 / 告警 / 价签 / 删除）。
                整块换掉而不是并排，是因为这两组动作天生冲突：一边在画新线、一边在改旧线，
                并排放只会让人在同一行里同时看到两件不相干的事。

              ❗**没有「✕ 取消选中」按钮**（2026-11-21 用户：选中态尾巴上那枚 ✕ 多余）：
              退出选中只有两条路 —— **在图上空白处点一下**（含副图 / 时间轴，见 KlineCanvas 的
              pointerdown）、或按 **Esc**。选中本来就是「临时看一眼」，不该再占一个按钮位。 */}
          <div className="sep" />

          <div className="bar-group">
            <span className="bar-label grp">划线</span>

            {selection ? (
              <>
                {/* 先报清是哪条：水平线读价位（那是它唯一的身份），直线报序号。
                    这里也是「怎么退出选中」的唯一提示 —— 点空白 / Esc，界面上没有按钮。 */}
                <span
                  className="bar-label draw-target"
                  title="当前选中的线（在图上单击另一条可换；点空白处或按 Esc 取消）"
                >
                  {selectedLineLabel}
                </span>
                <button
                  type="button"
                  className="chip icon-only"
                  title={selectedLine?.note ? `改备注：${selectedLine.note}` : '给这条线加备注'}
                  aria-label="备注"
                  onClick={() => openNote(selection)}
                >
                  <NoteIcon />
                </button>
                {selection.kind === 'price' && (
                  <button
                    type="button"
                    className={`chip${selectedLine?.alert ? ' on' : ''}`}
                    aria-pressed={selectedLine?.alert === true}
                    title={
                      selectedLine?.alert
                        ? '取消这条线的价格告警（不再弹通知）'
                        : '设成告警线：价格穿越时弹系统通知'
                    }
                    onClick={() => drawings.setPriceLineAlert(selection.index, !selectedLine?.alert)}
                  >
                    {selectedLine?.alert ? '取消告警' : '告警'}
                  </button>
                )}
                {selection.kind === 'price' && selectedLine && (
                  <button
                    type="button"
                    className={`chip${lineBadgeVisible(selectedLine) ? ' on' : ''}`}
                    aria-pressed={lineBadgeVisible(selectedLine)}
                    title={
                      lineBadgeVisible(selectedLine)
                        ? '隐藏这条线的价位牌（图表右侧那枚悬浮的价格签；只藏价签，线本身还在）'
                        : '显示这条线的价位牌（图表右侧那枚悬浮的价格签）'
                    }
                    onClick={() =>
                      drawings.setPriceLineBadge(selection.index, !lineBadgeVisible(selectedLine))
                    }
                  >
                    价签
                  </button>
                )}
                <button
                  type="button"
                  className="chip icon-only danger"
                  title="删除这条线（Delete / Backspace）"
                  aria-label="删除"
                  onClick={removeSelected}
                >
                  <TrashIcon />
                </button>
              </>
            ) : (
              <>
                {/* 线型：点一次进入（图上左键 = 落点，光标保持那枚空心圆环），再点一次退出。
                    画成一条自动收回，见 KlineCanvas 的 addPointAt。锁定时不给画。 */}
                <button
                  type="button"
                  className={`chip with-icon${tool === 'hline' ? ' on' : ''}`}
                  aria-pressed={tool === 'hline'}
                  disabled={drawings.linesLocked}
                  title={
                    drawings.linesLocked
                      ? '画线已锁定：先解锁才能画（点右边「锁定」）'
                      : tool === 'hline'
                        ? '正在画水平线：在图上点一下就是它的价位；再点这里退出'
                        : '画水平线：点这里，然后在图上点一下落点'
                  }
                  onClick={() => setTool(tool === 'hline' ? 'none' : 'hline')}
                >
                  <HLineIcon />
                  水平线
                </button>
                <button
                  type="button"
                  className={`chip with-icon${tool === 'trend' ? ' on' : ''}`}
                  aria-pressed={tool === 'trend'}
                  disabled={drawings.linesLocked}
                  title={
                    drawings.linesLocked
                      ? '画线已锁定：先解锁才能画（点右边「锁定」）'
                      : tool === 'trend'
                        ? '正在画直线：点两下成线（第二点离第一点太近不算）；Esc 撤掉起点'
                        : '画两点直线：点这里，然后在图上点两下成线'
                  }
                  onClick={() => setTool(tool === 'trend' ? 'none' : 'trend')}
                >
                  <TrendIcon />
                  直线
                </button>
                {/* 清空是破坏性动作，与画新线同门：锁定时一并收掉（锁 = 线一根都不许动） */}
                <button
                  type="button"
                  className="chip icon-only danger"
                  disabled={drawingCount === 0 || drawings.linesLocked}
                  title={
                    drawingCount === 0
                      ? '当前标的还没有画线'
                      : `清空当前标的的全部画线（${drawingCount} 条，画线会一起没）`
                  }
                  aria-label="清空全部画线"
                  onClick={() => {
                    drawings.clear();
                    setSelection(null);
                  }}
                >
                  <TrashIcon />
                </button>
                {/* 锁定管「线能不能动 / 能不能画」，眼睛管「图上看不看得到」 */}
                <button
                  type="button"
                  className={`chip${drawings.linesLocked ? ' on' : ''}`}
                  aria-pressed={drawings.linesLocked}
                  title={
                    drawings.linesLocked
                      ? '画线已锁定：不能选线、不能拖、不能画新线；点一下解锁'
                      : '锁定全部画线：不能选线、不能拖、不能画新线'
                  }
                  onClick={() => {
                    const next = !drawings.linesLocked;
                    drawings.setLinesLocked(next);
                    // 锁上的一瞬间把选中与工具都收掉：锁定的语义就是「一根都不许动」，
                    // 留着选中态等于留着一个随时会动的入口。
                    if (next) {
                      setSelection(null);
                      setTool('none');
                    }
                  }}
                >
                  锁定
                </button>
                <button
                  type="button"
                  className={`chip icon-only${drawings.linesHidden ? ' on' : ''}`}
                  aria-pressed={drawings.linesHidden}
                  title={
                    drawings.linesHidden
                      ? '显示全部画线（隐藏期间线没删，告警照常）'
                      : '隐藏全部画线（只藏不删，告警照常）'
                  }
                  onClick={() => drawings.setLinesHidden(!drawings.linesHidden)}
                >
                  <EyeIcon off={drawings.linesHidden} />
                </button>
                {alertCount > 0 && (
                  <span className="bar-label alert" title="价格穿越这些红色告警线时弹系统通知（主窗收进托盘也照弹）">
                    告警 {alertCount}
                  </span>
                )}
              </>
            )}
          </div>
        </div>
      </div>

      {/* 取完了但没有数据：不留一块空白，说清是「没取回来」还是「这个周期本来就没有」，
          并给一个重来的入口。**加载中不在这里** —— 那一段由 KlineCanvas 画骨架 + 盖遮罩。 */}
      {!load.loading && candles.length === 0 ? (
        <div className="chart-empty">
          <span className="overline">K 线</span>
          <span className="chart-empty-hint" title={load.reason || undefined}>
            {load.failed
              ? `K 线没取回来 · ${clipReason(load.reason) || '网络或数据源不通'}`
              : '这个周期没有取到 K 线'}
          </span>
          <button type="button" className="chip" onClick={() => setReloadSeq((n) => n + 1)}>
            重新加载
          </button>
        </div>
      ) : (
        <KlineCanvas
          candles={candles}
          // 各族「藏着」时折算成空目录：叠线与读数行自然全关，画布不用知道显隐这回事
          families={visibleFamilies}
          showBoll={showBoll}
          subPanes={subPanes}
          openInterest={oi}
          interval={interval}
          tickSize={instrument?.tickSize}
          theme={theme}
          loading={load.loading}
          symbolKey={`${item.market}|${item.symbol}`}
          // 眼睛隐藏 = 直接喂空数组：画、价格标、命中判定（选中/拖抓）自然全关，画布不用知道「隐藏」这回事
          priceLines={drawings.linesHidden ? [] : drawings.priceLines}
          trendLines={drawings.linesHidden ? [] : drawings.trendLines}
          tool={drawings.linesHidden ? 'none' : tool}
          selection={drawings.linesHidden ? null : selection}
          onSelect={setSelection}
          onAddTrend={drawings.addTrendLine}
          onAddPriceLine={(price) => {
            drawings.addPriceLine(price, false);
            // 藏线时双击落下的这根会被眼睛一并藏掉，看着像「双击没反应」，也让人忘了图上
            // 还有别的线 —— 落线即睁眼（与 EMA 面板落线同一口径，见下面 onToggleLine）。
            if (drawings.linesHidden) drawings.setLinesHidden(false);
          }}
          onDrawn={() => setTool('none')}
          onEditNote={openNote}
          linesLocked={drawings.linesLocked}
          onMovePriceLine={drawings.movePriceLine}
          onMoveTrendBy={drawings.moveTrendBy}
          onMoveTrendAnchor={drawings.moveTrendAnchor}
          onNeedOlder={loadOlder}
        />
      )}

      {/* 备注输入框：划线区的「备注」按钮打开，这一层自己等着用户敲字（保存即落盘，清空即删备注） */}
      {noteEdit && (
        <LineNoteDialog
          title={noteEdit.title}
          value={noteEdit.value}
          onSave={(note) => {
            if (noteEdit.target.kind === 'price') {
              drawings.setPriceLineNote(noteEdit.target.index, note);
            } else {
              drawings.setTrendLineNote(noteEdit.target.index, note);
            }
          }}
          onClose={() => setNoteEdit(null)}
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

      {maPanelOpen && (
        <MaPanel
          families={families}
          onCatalogChange={setCatalog}
          onColorChange={setMaColor}
          onMutedChange={setMuted}
          onAllMutedChange={setAllPeriodHidden}
          onReset={resetCatalog}
          onClose={() => setMaPanelOpen(false)}
        />
      )}

      {/* 均线分析（MA / EMA 共用一个入口）：范围取「面板里选中的那一族的目录 × 周期条的列表」
          （都算配置，不管图上画没画）。点右栏按钮 = 把这一档落成图上一条**系统划线**（自动备注
          「1时MA50」/「1时EMA50」，带 origin 身份落盘，面板不关，可连点几道）：没画就画、
          漂了拉回当前值、已对齐就删掉 —— 一次点击一笔改动。
          带着身份落盘，面板重开时那几行才能回显（整行选中底色）。
          每一轮取数回来（手动刷新 + 每分钟自动）都只把**本族**的系统划线拉到新值：均线是活的，
          线得跟着走；手画的线与另一族的线一概不动。抬头「清空划线」也只收本族这批线。 */}
      {analysisOpen && (
        <MaAnalysisPanel
          market={item.market}
          symbol={item.symbol}
          price={ticker?.lastPrice ?? null}
          tickSize={instrument?.tickSize}
          intervals={intervals}
          catalogs={{ MA: families.MA.catalog, EMA: families.EMA.catalog }}
          colorSlots={{ MA: families.MA.colors, EMA: families.EMA.colors }}
          sourceLabel={sourceLabel}
          priceLines={drawings.priceLines}
          onToggleLine={(kind, row, note) => {
            const plan = applyLevelLine(drawings.priceLines, kind, row, note);
            if (plan.did === 'none') return;
            drawings.setPriceLines(plan.lines);
            // 画 / 对齐之后图上多了条看得见的东西：眼睛闭着就得自动睁开，否则这一下
            // 就是「点了没反应」。取消时不碰 —— 该保留用户自己那个开关。
            if (plan.did !== 'remove' && drawings.linesHidden) drawings.setLinesHidden(false);
          }}
          onLevels={(kind, levels) => {
            // 先用函数式写法读**提交那一刻**的线表：这一轮取数飞了一两秒，期间用户很可能
            // 刚拖过某条线，拿闭包里的旧表去覆盖会把那一拖连本带利抹掉。
            drawings.editPriceLines((current) => {
              const plan = alignLevelLines(current, kind, levels);
              return plan.moved > 0 ? plan.lines : current;
            });
          }}
          onClearLines={(kind) => {
            // 只收本族身份的线；手画的水平线 / 直线原样留着。
            // 原表返回 = 没有可清的（按钮此时本来也不显示），别造新数组触发重渲染。
            drawings.editPriceLines((current) => {
              const next = current.filter((line) => !isSystemLine(line, kind));
              return next.length === current.length ? current : next;
            });
          }}
          onClose={() => setAnalysisOpen(false)}
        />
      )}
    </section>
  );
}
