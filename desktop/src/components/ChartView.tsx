import { useEffect, useState } from 'react';
import { fetchKlines, type Bar, type Instrument, type Ticker24h } from '../lib/api';
import { SUB_PANE_KINDS, SUB_PANE_LABEL, type SubPaneKind } from '../lib/chartSeries';
import { changeClass, displaySymbol, formatChange, formatCompact, formatPrice } from '../lib/format';
import { Sparkline } from '../hooks/useSparks';
import type { WatchItem } from '../hooks/useWatchlist';
import { KlineCanvas } from './KlineCanvas';

const INTERVALS = ['1m', '5m', '15m', '30m', '1h', '4h', '1d', '1w'];
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
  const [interval, setInterval_] = useState('15m');
  const [maPeriods, setMaPeriods] = useState<number[]>(DEFAULT_MA);
  /** 布林带默认关，与 AppSettings 的 bollEnabled 默认值一致。 */
  const [showBoll, setShowBoll] = useState(false);
  const [subPanes, setSubPanes] = useState<SubPaneKind[]>(DEFAULT_SUB_PANES);
  const [candles, setCandles] = useState<Bar[]>([]);
  const market = item?.market;
  const symbol = item?.symbol;

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
  }, [market, symbol, interval]);

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
  }, [market, symbol, interval]);

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
        {INTERVALS.map((iv) => (
          <button
            key={iv}
            className={`chip${iv === interval ? ' selected' : ''}`}
            onClick={() => setInterval_(iv)}
          >
            {iv}
          </button>
        ))}
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
      />
    </section>
  );
}
