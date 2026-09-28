import { useEffect, useMemo, useRef, useState } from 'react';
import {
  FUTURES_ENDPOINTS,
  probeEndpoint,
  setFuturesSource,
  useFuturesSourceUrl,
  type FuturesEndpoint,
  type ProbeOutcome,
} from '../lib/sources';

interface RowState {
  probing: boolean;
  outcome: ProbeOutcome | null;
}

/**
 * 合约数据源弹窗：打开即**并行**检测全部候选（结果只对本机当前网络有效），点一行即选定。
 * 交互与文案照 App 的 FuturesSourcePickerDialog：每行独立回填，哪个先回来哪个先亮。
 */
export function SourcePanel({ onClose }: { onClose: () => void }) {
  const selectedUrl = useFuturesSourceUrl();
  const [states, setStates] = useState<Record<string, RowState>>({});
  /** 每行独立的一代计数：重测没回来前又点一次，旧响应不许覆盖新结果。 */
  const genRef = useRef<Record<string, number>>({});

  async function probeOne(endpoint: FuturesEndpoint) {
    const gen = (genRef.current[endpoint.baseUrl] ?? 0) + 1;
    genRef.current[endpoint.baseUrl] = gen;
    setStates((prev) => ({
      ...prev,
      [endpoint.baseUrl]: { probing: true, outcome: prev[endpoint.baseUrl]?.outcome ?? null },
    }));
    const outcome = await probeEndpoint(endpoint);
    if (genRef.current[endpoint.baseUrl] !== gen) return;
    setStates((prev) => ({ ...prev, [endpoint.baseUrl]: { probing: false, outcome } }));
  }

  // 弹窗打开就先拉一轮：用户不必先点「全部重新检测」才知道哪个能用
  useEffect(() => {
    for (const endpoint of FUTURES_ENDPOINTS) void probeOne(endpoint);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const probing = Object.values(states).some((s) => s.probing);
  const fastest = useMemo(() => {
    const latencies = Object.values(states)
      .map((s) => s.outcome)
      .filter((o): o is ProbeOutcome => o != null && o.ok)
      .map((o) => o.latencyMs ?? Number.POSITIVE_INFINITY);
    return latencies.length > 0 ? Math.min(...latencies) : null;
  }, [states]);

  // 整体按检测结论排序：能连的按延迟升序排最上面，连不上的沉底，未检测其次。
  // 结果逐条回填，快的域自然先浮上来；重测中的行沿用上一轮结论，位置不跳。
  const ordered = useMemo(() => {
    const rank = (endpoint: FuturesEndpoint) => {
      const outcome = states[endpoint.baseUrl]?.outcome;
      if (!outcome) return 2;
      return outcome.ok ? 0 : 1;
    };
    const latency = (endpoint: FuturesEndpoint) =>
      states[endpoint.baseUrl]?.outcome?.latencyMs ?? Number.POSITIVE_INFINITY;
    return [...FUTURES_ENDPOINTS].sort((a, b) => rank(a) - rank(b) || latency(a) - latency(b));
  }, [states]);

  const renderRow = (endpoint: FuturesEndpoint) => {
    const state = states[endpoint.baseUrl];
    const outcome = state?.outcome ?? null;
    const isSelected = endpoint.baseUrl === selectedUrl;
    const pill = state?.probing
      ? { cls: 'flat', text: '检测中…' }
      : outcome?.ok
        ? { cls: 'up', text: `${outcome.latencyMs === fastest ? '最快 ' : ''}${outcome.latencyMs}ms` }
        : outcome
          ? { cls: 'down', text: outcome.reason ?? '失败' }
          : { cls: 'flat', text: '未检测' };

    return (
      <div
        key={endpoint.baseUrl}
        className={`src-row${isSelected ? ' on' : ''}`}
        title={isSelected ? '使用中' : `切换到 ${endpoint.label}`}
        onClick={() => {
          setFuturesSource(endpoint.baseUrl);
          onClose();
        }}
      >
        <span className="src-main">
          <span className="src-label">{endpoint.label}</span>
          <span className="src-sub num">
            {endpoint.baseUrl.replace('https://', '')} · {endpoint.note}
          </span>
        </span>
        <span className={`pill ${pill.cls} src-pill`}>{pill.text}</span>
        {!state?.probing && (
          <button
            className="src-retry"
            onClick={(e) => {
              e.stopPropagation();
              void probeOne(endpoint);
            }}
          >
            重测
          </button>
        )}
        <span className="src-check">{isSelected ? '✓' : ''}</span>
      </div>
    );
  };

  return (
    <div className="up-backdrop" onMouseDown={onClose}>
      <section className="up-panel" onMouseDown={(e) => e.stopPropagation()}>
        <header className="up-head">
          <span className="overline">合约数据源</span>
          <span className="grow" />
          <button className="icon-btn source-retry-all" disabled={probing} onClick={() => {
            for (const endpoint of FUTURES_ENDPOINTS) void probeOne(endpoint);
          }}>
            {probing ? '检测中…' : '全部重新检测'}
          </button>
          <button className="mini-x" title="关闭" onClick={onClose}>
            ×
          </button>
        </header>

        <div className="up-body">
          <p className="up-hint">
            检测结果只对当前网络有效，换个网络环境（如开关代理）请重新检测。
          </p>
          <p className="up-hint">
            只影响永续合约：K 线与 24h 行情都取自选定的这一家，现货固定走 Gate。
            切换后清空合约缓存并重新同步交易对。
          </p>

          {ordered.map(renderRow)}
        </div>
      </section>
    </div>
  );
}
