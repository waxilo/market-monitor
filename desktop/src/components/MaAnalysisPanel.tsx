import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { fetchKlines, type Bar, type MarketType } from '../lib/api';
import {
  analysisWindow,
  EMPTY_SCAN,
  findLevelLine,
  isSystemLine,
  levelCurve,
  levelNote,
  mergeScan,
  originKindOf,
  splitLevels,
  type Level,
  type LevelCurve,
  type LevelKind,
  type LevelRow,
  type LevelScan,
  type ScanFailure,
  type ScanMissing,
} from '../lib/maAnalysis';
import type { PriceLine } from '../lib/priceLines';
import { maSlotColors, type MaColors } from '../lib/chartSeries';
import { intervalLabel, minutesOf } from '../lib/intervals';
import { changeClass, displaySymbol, formatChange, formatPrice } from '../lib/format';
import { useSourceKey } from '../lib/sources';

/** 一个周期取回来了 / 没取回来（失败原因原样留着，压进提示里）。 */
type Outcome = { interval: string; bars: Bar[] } | { interval: string; reason: string };

/** 面板开着时的自动刷新间隔：1 分钟（K 线最少也是 1 分钟一根，再密没东西可变）。 */
const AUTO_REFRESH_MS = 60_000;

/**
 * 上一次扫描的结果，按「族 + 标的 + 数据源 + 那两份配置」存一份。
 *
 * 为什么放在模块级而不是 state：面板一关就整个卸载，state 全丢；下次点开又得从头取一遍
 * 11 个周期的 K 线，那几百毫秒里表是空的 —— 而这份表的用法恰恰是「点开看一眼就走」。
 * 有了这层，点开就能立刻看到上次的数值，同时后台静默换成新的。
 *
 * 表与缩略曲线存同一份：右栏的形态图只认 `curves`，如果只缓存表，重开时左栏有数、
 * 右栏却要等新一轮取数才画得出来 —— 那正是「已经加载过一次，还得再等刷新」。
 *
 * 只留最近 6 份（切标的 / 换源会各占一格）：这是个「上次看到的」缓存，不是数据仓库，
 * 存太多既没用又会让内存里挂着一堆过期的价位。
 */
interface CachedScan {
  scan: LevelScan;
  /** 每一档的尾段曲线，键见 `curveKeyOf`。与 `scan` 出自同一轮扫描。 */
  curves: Record<string, LevelCurve>;
}

const scanCache = new Map<string, CachedScan>();
const SCAN_CACHE_MAX = 6;

function rememberScan(key: string, scan: LevelScan, curves: Record<string, LevelCurve>): void {
  // 先删再塞：Map 的插入顺序才是淘汰顺序，覆盖写不会把它推到队尾
  scanCache.delete(key);
  scanCache.set(key, { scan, curves });
  while (scanCache.size > SCAN_CACHE_MAX) {
    const oldest = scanCache.keys().next();
    if (oldest.done) break;
    scanCache.delete(oldest.value);
  }
}

/** 曲线键：一档均线 = (周期, 档位)。 */
function curveKeyOf(interval: string, period: number): string {
  return `${interval}|${period}`;
}

/** 中间缩略图的默认 / 最小可见根数（滚轮在这两档之间缩放，上限是曲线全长）。 */
const SHAPE_VIEW_BARS = 120;
const SHAPE_MIN_BARS = 16;
/** 滚轮一格 ≈ 10% 的缩放步长（deltaY 一格通常是 ±100）。 */
const SHAPE_ZOOM_RATE = 0.0012;

/**
 * 中间那一栏的缩略形态图：收盘价（淡灰）+ 这一档均线（色槽同色），**共用一根纵轴**。
 *
 * 共用才是重点 —— 分开归一化会把「均线贴在价格上方」画成两条互不相干的曲线，
 * 而用户要看的正是「均线相对价格是抬还是压、拐点在哪」。
 *
 * 用真实像素尺寸画（ResizeObserver 量容器）：`preserveAspectRatio="none"` 拉满会把
 * 线宽和末点圆一起压扁。滚轮缩放改**可见根数**，右端始终贴着最新一根 ——
 * 这是一张「看形态」的图，不需要横向平移。
 */
function LevelShapeChart({
  kind,
  curve,
  color,
}: {
  kind: LevelKind;
  curve: LevelCurve;
  color: string;
}) {
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const [size, setSize] = useState({ w: 320, h: 200 });
  const [bars, setBars] = useState(() => Math.min(SHAPE_VIEW_BARS, curve.values.length));

  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    const measure = () => {
      const rect = el.getBoundingClientRect();
      setSize({ w: Math.max(60, Math.round(rect.width)), h: Math.max(40, Math.round(rect.height)) });
    };
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  // 滚轮缩放：React 的 onWheel 在根节点上是 passive 的，preventDefault 会被忽略，所以挂原生。
  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const max = curve.values.length;
      const min = Math.min(SHAPE_MIN_BARS, max);
      setBars((prev) =>
        Math.round(Math.max(min, Math.min(max, prev * Math.exp(e.deltaY * SHAPE_ZOOM_RATE)))),
      );
    };
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, [curve]);

  const total = curve.values.length;
  const n = Math.min(bars, total);
  const closes = curve.closes.slice(total - n);
  const values = curve.values.slice(total - n);
  const { w, h } = size;

  let body: ReactNode = null;
  if (n >= 2) {
    const padX = 6;
    const padY = 8;
    const min = Math.min(...closes, ...values);
    const max = Math.max(...closes, ...values);
    const span = max - min || 1;
    const x = (i: number) => padX + (i / (n - 1)) * (w - padX * 2);
    const y = (v: number) => h - padY - ((v - min) / span) * (h - padY * 2);
    const points = (valuesOfLine: number[]) =>
      valuesOfLine.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
    body = (
      <svg width={w} height={h} role="img" aria-label={`${kind} 最近 ${n} 根形态`}>
        <polyline className="ema-shape-close" points={points(closes)} />
        <polyline
          points={points(values)}
          fill="none"
          stroke={color}
          strokeWidth="1.5"
          strokeLinejoin="round"
          strokeLinecap="round"
        />
        <circle cx={x(n - 1)} cy={y(values[n - 1])} r="2.5" fill={color} />
      </svg>
    );
  }

  return (
    <div
      ref={wrapRef}
      className="ema-shape-chart"
      onDoubleClick={() => setBars(Math.min(SHAPE_VIEW_BARS, total))}
    >
      {body}
      <span className="ema-shape-bars num">
        {n} / {total} 根
      </span>
    </div>
  );
}

interface Props {
  market: MarketType;
  symbol: string;
  /** 实时最新价（行情没到 / 断线时给 null，改用最后一根收盘价）。 */
  price: number | null;
  tickSize?: string;
  /** 周期条上配置的全部 K 线周期（分析范围）。 */
  intervals: string[];
  /** 两族在均线面板里各自配置的全部档位（分析范围，含小眼睛藏起来的那些）。 */
  catalogs: Record<LevelKind, number[]>;
  /** 族 → (期数 → 色槽)（表里的色点与图上那条线同色）。 */
  colorSlots: Record<LevelKind, MaColors>;
  /** 当前数据源名，写进抬头（换源后数值会变，得说清是跟谁取的）。 */
  sourceLabel: string;
  /** 当前标的上已有的水平线 —— 回显「这一档画过没有」全靠它。 */
  priceLines: readonly PriceLine[];
  /**
   * 中间缩略图下方那枚动作按钮 = 把这一档落成图上的一条**系统划线**：
   * 没画就画、漂了拉回当前值、已对齐就删掉。`note` 是自动备注文案（新画时写上去）。
   * `kind` 是这一行所属的族 —— 面板里切族只换视图，落线仍按各族的身份走。
   */
  onToggleLine: (kind: LevelKind, level: LevelRow, note: string) => void;
  /**
   * 每一轮取数回来后回调一次（拿到的是**合并之后**的整份水位表）。
   *
   * 用途只有一个：让图上的系统划线跟着均线走（`lib/maAnalysis.alignLevelLines`）。
   * 挂在「刷新」而不是「面板打开」上 —— 线要跟的是数值，而数值只在刷新时变。
   */
  onLevels: (kind: LevelKind, levels: Level[]) => void;
  /**
   * 清空图上属于本族的全部系统划线（`PriceLine.origin.kind` 与本族一致）。
   * 手画的水平线 / 直线 / 另一族的线一概不碰 —— 这是「把连点画下的那几道墙一次收掉」，
   * 不是清空画布。
   */
  onClearLines: (kind: LevelKind) => void;
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
 * 均线指标分析（MA / EMA 同一个面板，抬头里切族）：一次点击，把**所有已配置的档位 ×
 * 所有已配置的 K 线周期**的当前值全部取回来，按最新价分成「上方压力 / 下方支撑」两块。
 *
 * 几条设计取舍：
 *
 * - **族的选择在面板里**：周期条上只有一枚「均线分析」（2026-11 用户要求把 MA / EMA 两枚
 *   并成一个入口）。两族的档位目录、线色、落线身份都各走各的，切族 = 换整张表
 *   （缓存键里带族，见 `scanKey`），不会把一族的水位算进另一族。
 * - **静默刷新**：打开时先用上次的结果把表铺出来（`scanCache`），同时在后台重取，
 *   拿到新值再整表替换 —— 不出现「先清空、再转圈、再重排」那段闪烁。刷新中只有右上角
 *   的按钮说话（文案变「刷新中」、禁用），表格照旧可读可点。
 *   面板一直开着时**每 1 分钟自动刷新一次**（`AUTO_REFRESH_MS`），所以它是一张会自己变新的表。
 *   现价则跟着 `price` 属性实时更新 —— 价格穿过某道水位时，那一行会当场换个筐。
 * - **刷新失败不清表**：这一轮哪个周期没取到，就沿用上一轮该周期的各档（见 `mergeScan`），
 *   并把这些周期标成「上次的值」。断网时一道压力线凭空消失，比显示略旧的数危险得多。
 * - **失败与「算不出」分开报**：前者是网络/数据源不通（该重试），后者是这个周期取回的根数
 *   不够算这一档均线（粗周期上的长档就是这样）。混成一句话会让用户以为取数又坏了。
 * - **左列表 / 右缩略图**：左栏是「上方压力 / 下方支撑」两个页签（同一时刻只看一侧），
 *   点一条 = **选中**它，右栏立刻画出这一档均线的形态（收盘价淡灰、均线用色槽色，
 *   共用一根纵轴），滚轮缩放可见根数、双击复位。悬浮不再弹原生文字提示 ——
 *   详细信息都在右栏里，tooltip 只会挡着下面几行。
 * - **画线是右栏的动作按钮**：选中一条后，右栏底部那枚按钮把它落成图上的**系统划线**
 *   （没画就画、漂了拉回、已对齐就删）。这条线带**身份**落盘（`PriceLine.origin` =
 *   周期 + 档位），打开面板时那一行带「已画」底色回显。均线每分钟都在动，所以每一轮取数
 *   回来都会把这些系统划线**自动拉到**各自的新值（`onLevels` → `alignLevelLines`）——
 *   线是用来看盘的一道墙，墙得长在均线现在的位置上，不能停在画它的那一刻。
 *   手画的线一概不动：那是用户自己的判断。
 *   行内的周期小方框只是标注，不可点 —— 点走会切周期并关掉面板。
 *   选中是另一层底色（`.sel`），与「已画」的红底（`.on`）分开：选中的那条不一定画过线。
 */
export function MaAnalysisPanel({
  market,
  symbol,
  price,
  tickSize,
  intervals,
  catalogs,
  colorSlots,
  sourceLabel,
  priceLines,
  onToggleLine,
  onLevels,
  onClearLines,
  onClose,
}: Props) {
  /**
   * 正在看哪一族（抬头那枚 MA / EMA 分段控件）。
   * 初值取「配了档位的那一族」：只配了 EMA 的人点开不该先看到一句「MA 一档都没配置」。
   */
  const [kind, setKind] = useState<LevelKind>(() =>
    catalogs.MA.length === 0 && catalogs.EMA.length > 0 ? 'EMA' : 'MA',
  );
  /** 当前这一族的档位目录与色槽 —— 分析范围与配色都跟着族走。 */
  const periods = catalogs[kind];
  const colors = colorSlots[kind];
  /** 换源 = 换盘口：整表重取（与 ChartView 的 K 线口径一致）。 */
  const source = useSourceKey(market);
  /** 周期列表 / 档位列表都是新数组引用的情况很多，依赖用拼出来的字符串判定。 */
  const intervalKey = intervals.join(',');
  const periodKey = periods.join(',');
  /**
   * 这张表的身份：族 + 标的 + 数据源 + 那两份配置，任一变化就是另一张表。缓存按它认。
   * **必须带族** —— MA 与 EMA 两张表并存，只按标的认会让后打开的那张读到另一族的价位。
   */
  const scanKey = `${kind}|${market}|${symbol}|${source}|${intervalKey}|${periodKey}`;

  /** 当前这张表 —— 初值取上次看过的（读不到才是空表，见 `scanCache`）。 */
  const [scan, setScan] = useState<LevelScan>(() => scanCache.get(scanKey)?.scan ?? EMPTY_SCAN);
  /**
   * 与 `scan` 同源的一份镜像，只用来「并到上一轮上」。
   *
   * 合并本来写在 `setScan((prev) => …)` 里，但拿到新值之后面板还要干一件事：让图上的
   * 系统划线跟着均线走（`onLevels`）。那是不折不扣的副作用，塞进 updater 会在
   * StrictMode 下被触发两遍（多跑一次对齐本身无害，但「副作用在 updater 里」这件事
   * 迟早会被下一个人当成可靠行为），所以改成「先算出结果，再一次性上交」。
   */
  const scanRef = useRef(scan);
  /** 最新的 `onLevels`：取数回调是异步的，直接读 props 会读进这一轮开始时的旧闭包。 */
  const onLevelsRef = useRef(onLevels);
  useEffect(() => {
    onLevelsRef.current = onLevels;
  });
  /** 有取数在飞。**只喂给按钮与首次加载文案**，不拿来遮表格（遮了就回到「清空刷新」）。 */
  const [refreshing, setRefreshing] = useState(true);
  /** 已回几个周期（只有首次取数时看得到，之后表已经在那儿了）。 */
  const [done, setDone] = useState(0);
  /** 递增一次 = 重取（「刷新」按钮与每分钟的自动刷新共用这一条路）。 */
  const [seq, setSeq] = useState(0);
  /** 上次拿到新值的时间（0 = 还没成功过），写进按钮的 title。 */
  const [updatedAt, setUpdatedAt] = useState(0);
  /**
   * 每一档的尾段曲线 —— 中间缩略图的数据源。
   *
   * 与 `scan.levels` 同源（同一次扫描算出来），但单独存一份：表只留末值，
   * 而缩略图要看整段形态。断网时与表同口径地**沿用上一轮**（见下面的合并）。
   * 初值同样取上次看过的：右栏的形态图不该比左栏的表晚一轮才出现。
   */
  const [curves, setCurves] = useState<Record<string, LevelCurve>>(
    () => scanCache.get(scanKey)?.curves ?? {},
  );
  /** `curves` 的镜像，用来在取数回调里「并到上一轮上」（理由与 `scanRef` 相同）。 */
  const curvesRef = useRef(curves);
  /** 左栏页签：一次只看一侧（压力 / 支撑），另一侧留在页签上的数字里。 */
  const [tab, setTab] = useState<'resistance' | 'support'>('resistance');
  /** 当前选中的那一档 —— 中间缩略图跟着它走。 */
  const [selected, setSelected] = useState<{ interval: string; period: number } | null>(null);
  /** 首次拿到数据时挑一次页签（哪边有货看哪边），之后不再自动改，免得和用户抢。 */
  const tabPicked = useRef(false);

  // 换族 / 换标的 / 换源 / 改配置：立刻换成那一份的缓存（没有就空表）并重新取。
  // 不这么做的话，切币的瞬间会先显示上一个币的价位 —— 那比空表更容易被当真。
  useEffect(() => {
    const cached = scanCache.get(scanKey);
    // 镜像必须跟着换：并到「上一个币的表」上会把别家的水位搬过来
    scanRef.current = cached?.scan ?? EMPTY_SCAN;
    setScan(scanRef.current);
    // 曲线和表必须同一份缓存：只恢复表的话，右栏形态图要等这一轮取数回来才画得出
    curvesRef.current = cached?.curves ?? {};
    setCurves(curvesRef.current);
    setSelected(null);
    tabPicked.current = false;
    setRefreshing(true);
  }, [scanKey]);

  useEffect(() => {
    if (periods.length === 0 || intervals.length === 0) {
      setRefreshing(false);
      scanRef.current = EMPTY_SCAN;
      setScan(EMPTY_SCAN);
      curvesRef.current = {};
      setCurves({});
      setSelected(null);
      setDone(0);
      return;
    }
    // 不挡重复请求：自动刷新 60s 一轮、单轮 1~2s 就回来，撞不上；手动按钮刷新时本来就是禁用的。
    // 反而**不能**按「同一份配置就跳过」——面板关掉重开时，上一轮的请求还在飞，
    // 跳过会让新一轮一直停在「刷新中」等那个已经没人要的结果。
    let alive = true;
    let finished = 0;
    // 一次取多少根：至少与图上一段历史同长（见 lib/maAnalysis 的头注）
    const barCount = analysisWindow(periods);
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

      const nextLevels: Level[] = [];
      const nextFailures: ScanFailure[] = [];
      const nextMissing: ScanMissing[] = [];
      const nextCurves: Record<string, LevelCurve> = {};
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
          // 曲线与末值出自同一次递推：表里的价与预览图右端不可能对不上
          const curve = levelCurve(kind, outcome.interval, closes, period);
          const value = curve ? curve.values[curve.values.length - 1] : NaN;
          if (curve && Number.isFinite(value) && value > 0) {
            nextLevels.push({ interval: outcome.interval, minutes, period, value });
            nextCurves[curveKeyOf(outcome.interval, period)] = curve;
          } else {
            nextMissing.push({ interval: outcome.interval, period, bars: bars.length });
          }
        }
      }

      // 与上一轮合并：这一轮没取到的周期沿用旧值，表不会因为一次断网被清空
      const merged = mergeScan(scanRef.current, {
        levels: nextLevels,
        failures: nextFailures,
        missing: nextMissing,
        stale: [],
        fallbackPrice: finestClose,
      });
      scanRef.current = merged;
      // 预览与表同口径：表里还留着这一档（整段没取回来 / 这轮根数差几根）就沿用上一轮的曲线，
      // 断网时悬浮仍看得出形态；表里已经没了的档位，曲线也一起丢掉
      const keptLevels = new Set(merged.levels.map((level) => curveKeyOf(level.interval, level.period)));
      const mergedCurves: Record<string, LevelCurve> = {};
      for (const [key, curve] of Object.entries(curvesRef.current)) {
        if (keptLevels.has(key)) mergedCurves[key] = curve;
      }
      Object.assign(mergedCurves, nextCurves);
      curvesRef.current = mergedCurves;
      setCurves(mergedCurves);
      rememberScan(scanKey, merged, mergedCurves);
      setScan(merged);
      setUpdatedAt(Date.now());
      setRefreshing(false);
      // 数值到手了：让图上的系统划线跟着走（手画的线一概不碰，见 `alignLevelLines`）。
      // 手动「刷新」与每分钟的自动那一轮都经过这里 —— 它俩本来就是同一条路。
      onLevelsRef.current(kind, merged.levels);
    });

    return () => {
      alive = false;
    };
    // `kind` 必须进依赖：切换族不一定换档位（两族常见同一套 MA20/50/100），
    // 只靠 periodKey 识别不出来，会让新族沿用上一族的取数结果。
  }, [market, symbol, source, kind, intervalKey, periodKey, seq]);

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
  const palette = useMemo(() => maSlotColors(kind), [kind]);
  /** 本族系统划线的身份前缀（`ma` / `ema`）。 */
  const originKind = originKindOf(kind);
  /**
   * 图上有、但**当前配置里已经没有对应行**的系统划线。
   *
   * 只在用户改过本族目录 / 周期条之后才会出现（删掉 MA50 档，那条线还留在图上）。
   * 行内回显按 (周期, 档位) 配对，这类线天然配不上任何一行 —— 不点一句出来，用户就只能
   * 在图上看见一条带「1时MA50」备注的红线、却在面板里怎么也找不到它。
   * 判据用**配置**而不是 `scan.levels`：某一档这次没取到数也只管显示，不代表它被删了。
   */
  const orphanLines = useMemo(() => {
    const configured = new Set<string>();
    for (const interval of intervals) {
      for (const period of periods) configured.add(`${interval}|${period}`);
    }
    return priceLines.filter(
      (line) =>
        line.origin?.kind === originKind &&
        !configured.has(`${line.origin.interval}|${line.origin.period}`),
    );
  }, [priceLines, intervals, periods, originKind]);
  /** 表里已经有值吗 —— 有值就绝不拿加载文案盖住它（这就是「静默」的字面含义）。 */
  const hasTable = scan.levels.length > 0;
  /**
   * 图上属于本面板的系统划线总数（含配置里已经删掉的孤儿线）。
   * 「清空划线」按它决定出不出：一条都没有时这枚按钮不该占着抬头。
   */
  const lineCount = useMemo(
    () => priceLines.filter((line) => isSystemLine(line, kind)).length,
    [priceLines, kind],
  );

  /** 一档的自动备注（= 老存档里那条线的识别键，见 `findLevelLine`）。 */
  const autoNoteOf = (row: LevelRow) => levelNote(kind, intervalLabel(row.interval), row.period);
  const rows = tab === 'resistance' ? buckets.resistance : buckets.support;

  // 首次拿到数据时挑一次页签：压力有货就先看压力，没有才落到支撑
  useEffect(() => {
    if (tabPicked.current || !hasTable) return;
    tabPicked.current = true;
    if (buckets.resistance.length === 0 && buckets.support.length > 0) setTab('support');
  }, [hasTable, buckets]);

  // 选中项跟着页签 / 数据走：还在当前页签里就保留；否则落到这一侧**离现价最近**的一条
  // （现价穿过一条均线时，它会当场换筐 —— 选中项不该因此跳到一个随机位置）
  useEffect(() => {
    if (
      selected &&
      rows.some(
        (row) => curveKeyOf(row.interval, row.period) === curveKeyOf(selected.interval, selected.period),
      )
    ) {
      return;
    }
    const first = rows[0];
    setSelected(first ? { interval: first.interval, period: first.period } : null);
  }, [rows, selected]);

  const selectedKey = selected ? curveKeyOf(selected.interval, selected.period) : null;
  const selectedRow = selectedKey
    ? (rows.find((row) => curveKeyOf(row.interval, row.period) === selectedKey) ??
       buckets.resistance.find((row) => curveKeyOf(row.interval, row.period) === selectedKey) ??
       buckets.support.find((row) => curveKeyOf(row.interval, row.period) === selectedKey) ??
       null)
    : null;
  const selectedCurve = selectedKey ? curves[selectedKey] ?? null : null;
  const selectedLine = selectedRow
    ? findLevelLine(priceLines, kind, selectedRow, autoNoteOf(selectedRow))
    : null;
  const marked = rows.filter(
    (row) => findLevelLine(priceLines, kind, row, autoNoteOf(row)) !== null,
  ).length;

  /**
   * 左栏：页签（上方压力 / 下方支撑）+ 这一侧的条目。
   *
   * 点一条 = **选中**它（中间缩略图跟着换），不再直接画线 —— 画线是中间那一栏的动作按钮。
   * 行上的「已画」仍然只靠底色 + 左缘色条表达（`.on`），选中是另一层底色（`.sel`）：
   * 两件事互不包含（选中的那条不一定画过线），混成一个颜色就分不清了。
   */
  const listPane = (
    <section className={`ema-col ${tab}`}>
      <header className="ema-col-head ema-tabs">
        <div className="ema-tab-switch" role="tablist" aria-label="上方压力 / 下方支撑">
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'resistance'}
            className={`ema-tab${tab === 'resistance' ? ' on' : ''}`}
            onClick={() => setTab('resistance')}
          >
            上方压力 <span className="num">{buckets.resistance.length}</span>
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'support'}
            className={`ema-tab${tab === 'support' ? ' on' : ''}`}
            onClick={() => setTab('support')}
          >
            下方支撑 <span className="num">{buckets.support.length}</span>
          </button>
        </div>
        {marked > 0 && <span className="ema-col-marked">已画 {marked}</span>}
      </header>
      <div className="ema-rows">
        {rows.map((row) => {
          const label = intervalLabel(row.interval);
          /* 回显：图上与这一档绑定的那条线（没有 = 还没画）。选中态交给另一层底色，
             行内不再写字 —— 均线一跳，「待对齐」这种瞬时状态就会自己变来变去。 */
          const line = findLevelLine(priceLines, kind, row, autoNoteOf(row));
          const isSelected = selectedKey === curveKeyOf(row.interval, row.period);
          return (
            <div
              key={`${row.interval}-${row.period}`}
              className={`ema-row${line ? ' on' : ''}${isSelected ? ' sel' : ''}`}
              role="button"
              tabIndex={0}
              aria-pressed={isSelected}
              onClick={() => setSelected({ interval: row.interval, period: row.period })}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  setSelected({ interval: row.interval, period: row.period });
                }
              }}
            >
              <span
                className="ema-dot"
                style={{ background: palette[colors[row.period] ?? 0] }}
                aria-hidden
              />
              <span className="ema-name">
                {kind}
                {row.period}
              </span>
              <span className="ema-iv">{label}</span>
              <span className="ema-val num">{formatPrice(row.value, tickSize)}</span>
              <span className="ema-gap num">{formatChange(row.gapPercent)}</span>
            </div>
          );
        })}
        {rows.length === 0 && (
          <p className="ema-empty">
            {tab === 'resistance'
              ? `现价上方还没有已配置的 ${kind}`
              : `现价下方还没有已配置的 ${kind}`}
          </p>
        )}
      </div>
    </section>
  );

  /** 右栏：选中那一档的缩略形态图 + 画线动作（滚轮缩放）。 */
  const shapePane = (
    <section className="ema-shape">
      {selectedRow && selectedCurve ? (
        <>
          <header className="ema-shape-head">
            <span
              className="ema-shape-dot"
              style={{ background: palette[colors[selectedRow.period] ?? 0] }}
              aria-hidden
            />
            <span className="ema-shape-name">
              {kind}
              {selectedRow.period}
            </span>
            <span className="ema-iv">{intervalLabel(selectedRow.interval)}</span>
            <span className={`ema-shape-gap num ${changeClass(selectedRow.gapPercent)}`}>
              {formatChange(selectedRow.gapPercent)}
            </span>
          </header>
          <div className="ema-shape-value">
            <b className="num">{formatPrice(selectedRow.value, tickSize)}</b>
            <span className="ema-shape-sub">
              {tab === 'resistance' ? '上方压力' : '下方支撑'} · 距现价
            </span>
          </div>
          <LevelShapeChart
            key={selectedKey ?? ''}
            kind={kind}
            curve={selectedCurve}
            color={palette[colors[selectedRow.period] ?? 0]}
          />
          <div className="ema-shape-foot">
            <span>
              <i className="ema-shape-swatch close" aria-hidden />
              收盘
            </span>
            <span>
              <i
                className="ema-shape-swatch"
                style={{ background: palette[colors[selectedRow.period] ?? 0] }}
                aria-hidden
              />
              {kind}
            </span>
            <span className="ema-shape-hint">滚轮缩放 · 双击复位</span>
          </div>
          <div className="ema-shape-actions">
            <span className={`ema-shape-line${selectedLine && !selectedLine.aligned ? ' drift' : ''}`}>
              {selectedLine
                ? `图上已画 ${formatPrice(selectedLine.price, tickSize)}${
                    selectedLine.aligned
                      ? ''
                      : `（偏离 ${formatChange(
                          ((selectedLine.price - selectedRow.value) / selectedRow.value) * 100,
                        )}）`
                  }`
                : '图上未画告警线'}
            </span>
            <button
              type="button"
              className="ema-shape-btn"
              onClick={() => onToggleLine(kind, selectedRow, autoNoteOf(selectedRow))}
            >
              {selectedLine
                ? selectedLine.aligned && selectedLine.alert
                  ? '删除告警线'
                  : '对齐到当前值'
                : '画告警线'}
            </button>
          </div>
        </>
      ) : (
        <p className="ema-shape-empty">
          {hasTable ? '点左侧的条目，在这里看它的均线形态' : '等这一轮取数回来…'}
        </p>
      )}
    </section>
  );

  return (
    <div className="up-backdrop" onMouseDown={onClose}>
      <section
        className="up-panel ema-panel"
        onMouseDown={(e) => e.stopPropagation()}
        role="dialog"
        aria-label="均线指标分析"
      >
        <header className="up-head ema-head">
          <div className="ema-head-id">
            <span className="ema-head-title">均线压力支撑</span>
            <span className="ema-head-kicker">指标分析</span>
          </div>
          {/* 族的选择：两族共用一张表，一次只看一族（档位目录、线色、落线身份各走各的） */}
          <div className="ema-kind-switch" role="tablist" aria-label="均线族">
            {(['MA', 'EMA'] as const).map((value) => (
              <button
                key={value}
                type="button"
                role="tab"
                aria-selected={kind === value}
                className={`ema-tab${kind === value ? ' on' : ''}`}
                onClick={() => setKind(value)}
              >
                {value}
              </button>
            ))}
          </div>
          <span className="grow" />
          {lineCount > 0 && (
            <button
              type="button"
              className="ema-clear"
              title={`清空图上全部 ${lineCount} 条 ${kind} 划线（手画的线与另一族的线不受影响）`}
              onClick={() => onClearLines(kind)}
            >
              清空划线
            </button>
          )}
          <button
            type="button"
            className="ema-refresh"
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
            {refreshing ? `刷新中 ${done}/${intervals.length}` : '刷新'}
          </button>
          <button className="panel-close" title="关闭（Esc）" onClick={onClose}>
            ×
          </button>
        </header>

        <div className="up-body ema-body">
          <section className="ema-hero">
            <div className="ema-hero-id">
              <div className="ema-sym-line">
                <span className="ema-sym-name">{displaySymbol(symbol)}</span>
                <span className="ema-tag">
                  {market === 'FUTURES' ? '永续' : '现货'} · {sourceLabel}
                </span>
              </div>
              <div className="ema-hero-meta">
                <span>
                  {intervals.length} 个周期 × {periods.length} 档 {kind}
                </span>
                <span className="ema-hero-sep" aria-hidden />
                <span>
                  {refreshing
                    ? `正在取数 ${done}/${intervals.length}`
                    : updatedAt
                      ? `${clockText(updatedAt)} 更新`
                      : '等待首轮取数'}
                </span>
              </div>
            </div>
            <div className="ema-price">
              <span className="ema-price-label">最新价</span>
              <span className="ema-price-v num">{formatPrice(base, tickSize)}</span>
              {!livePrice && <span className="ema-src">末根收盘价</span>}
            </div>
          </section>

          {intervals.length === 0 ? (
            <p className="ema-empty">
              周期条上还没有配置 K 线周期 —— 先在「周期」那一行的「＋」里加一个。
            </p>
          ) : periods.length === 0 ? (
            <p className="ema-empty">{kind} 一档都没配置 —— 先在指标栏「＋」里加几档再来分析。</p>
          ) : hasTable ? (
            <div className="ema-cols">
              {listPane}
              {shapePane}
            </div>
          ) : refreshing ? (
            <p className="ema-empty num">
              正在取 {intervals.length} 个周期的 K 线… {done} / {intervals.length}
            </p>
          ) : (
            <p className="ema-empty">所有周期都没能算出 {kind}（见下方原因）。</p>
          )}

          {/* 异常提示跟着整张扫描走：有旧值时不显示「正在取」那种过渡态，但异常照报 */}
          {(scan.failures.length > 0 ||
            scan.missing.length > 0 ||
            orphanLines.length > 0) && (
            <footer className="ema-notes">
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
                        `${intervalLabel(m.interval)} 的 ${kind}${m.period}：只取到 ${m.bars} 根，至少要 ${m.period} 根`,
                    )
                    .join('\n')}
                >
                  {scan.missing.length} 档根数不够算不出：
                  {scan.missing
                    .slice(0, 6)
                    .map((m) => `${intervalLabel(m.interval)}·${kind}${m.period}`)
                    .join(' / ')}
                  {scan.missing.length > 6 ? ' …' : ''}
                  （悬停看明细）
                </p>
              )}
              {orphanLines.length > 0 && (
                <p
                  className="ema-note"
                  title={orphanLines
                    .map((line) =>
                      line.origin?.kind === originKind
                        ? `${intervalLabel(line.origin.interval)} · ${kind}${line.origin.period} @ ${formatPrice(line.price, tickSize)}`
                        : '',
                    )
                    .filter((text) => text !== '')
                    .join('\n')}
                >
                  图上还有 {orphanLines.length} 条 {kind} 划线不在当前配置里（档位或周期被删掉了）：
                  {orphanLines
                    .slice(0, 4)
                    .map((line) =>
                      line.origin?.kind === originKind
                        ? `${intervalLabel(line.origin.interval)}·${kind}${line.origin.period}`
                        : '',
                    )
                    .join(' / ')}
                  {orphanLines.length > 4 ? ' …' : ''}
                  （改回配置即可回显，或点上方「清空划线」一次收掉；悬停看价位）
                </p>
              )}
            </footer>
          )}
        </div>

      </section>
    </div>
  );
}
