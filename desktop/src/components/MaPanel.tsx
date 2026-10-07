import { useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { MA_LIMIT, MA_MAX, MA_MIN } from '../lib/chartSeries';
import {
  addMaPeriod,
  DEFAULT_MA_CATALOG,
  maLabel,
  parseMaPeriod,
  removeMaPeriod,
} from '../lib/maPeriods';
import { useDragSort } from '../hooks/useDragSort';

interface Props {
  /** 指标栏上的均线目录（有顺序）。 */
  list: number[];
  onChange: (next: number[]) => void;
  onReset: () => void;
  onClose: () => void;
}

/** 行内容：网格里的行与拖动幽灵共用同一套 DOM，免得两处样式分叉。 */
function RowInner({ period, onRemove }: { period: number; onRemove?: () => void }) {
  return (
    <>
      <span className="iv-name">{maLabel(period)}</span>
      <span className="iv-note" />
      <button className="iv-act" title="从指标栏移除" onClick={onRemove}>
        ×
      </button>
    </>
  );
}

/**
 * 主图均线的「有哪些 chip + 什么顺序 + 自定义」—— 与 `IntervalPanel` 同一套骨架
 * （整行拖动排序 + `useDragSort` 的挤动动效，DOM 与 `iv-*` / `up-*` 样式类照搬），
 * 只是把「数值 + 单位」换成单个「期数」输入。
 *
 * ⚠️ 这里排的是 **chip 的先后**；主图线色按**期数升序**分配（`chartSeries.maRoleAt`），
 * 与这个顺序无关 —— 面板提示里写清楚，免得以为拖动会换色。
 */
export function MaPanel({ list, onChange, onReset, onClose }: Props) {
  const [num, setNum] = useState('');
  /** 拖拽排序只认 string[] 的 id 顺序，这里把期数转成字符串喂给它，回来再转回数字。 */
  const ids = useMemo(() => list.map(String), [list]);
  const { dragId, ghost, ghostRef, rowProps, nudge } = useDragSort({
    list: ids,
    onChange: (next) => onChange(next.map(Number)),
    flow: 'column',
  });

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const parsed = useMemo(() => (num.trim() === '' ? null : parseMaPeriod(num)), [num]);

  /** 输入框下面那行反馈：解析结果 + 会不会重复 / 超上限，一次说清。 */
  const feedback = useMemo(() => {
    if (!parsed) {
      return {
        kind: 'idle',
        text: `填期数，例：7 / 99 / 200 · 取值 ${MA_MIN}~${MA_MAX} · 最多 ${MA_LIMIT} 条`,
      };
    }
    if (!parsed.ok) return { kind: 'bad', text: parsed.reason };
    const label = maLabel(parsed.period);
    if (list.includes(parsed.period)) return { kind: 'idle', text: `${label} · 已经在指标栏上` };
    if (list.length >= MA_LIMIT) return { kind: 'bad', text: `最多 ${MA_LIMIT} 条均线，先删一条再加` };
    return { kind: 'ok', text: `${label} · 可以添加` };
  }, [parsed, list]);

  function submit() {
    if (!parsed?.ok) return;
    onChange(addMaPeriod(list, parsed.period));
    setNum('');
  }

  return (
    <div className="up-backdrop" onMouseDown={onClose}>
      <section className="up-panel up-wide" onMouseDown={(e) => e.stopPropagation()}>
        <header className="up-head">
          <span className="overline">均线 MA</span>
          <span className="grow" />
          <button className="panel-close" title="关闭" onClick={onClose}>
            ×
          </button>
        </header>

        <div className="up-body">
          <p className="up-hint">
            指标栏上放哪些均线、按什么顺序都在这里定；配色按期数升序分配，与这里的顺序无关。
          </p>

          <div className="up-label">已选 · 直接拖动整行排序（也可用 ↑ ↓）</div>
          <div className="iv-group">
            {list.map((period) => {
              const id = String(period);
              return (
                <div
                  className={`iv-row drag-row${dragId === id ? ' slot' : ''}`}
                  key={id}
                  tabIndex={0}
                  role="listitem"
                  title="按住这一行任意处拖动排序（也可用 ↑ ↓）"
                  {...rowProps(id)}
                  onKeyDown={(e) => {
                    if (e.key === 'ArrowUp') {
                      e.preventDefault();
                      nudge(id, -1);
                    }
                    if (e.key === 'ArrowDown') {
                      e.preventDefault();
                      nudge(id, 1);
                    }
                  }}
                >
                  <RowInner period={period} onRemove={() => onChange(removeMaPeriod(list, period))} />
                </div>
              );
            })}
          </div>

          <div className="up-label">添加均线</div>
          <div className={`iv-custom${feedback.kind === 'idle' ? '' : ` ${feedback.kind}`}`}>
            <div className="iv-compose">
              <input
                className="iv-num"
                value={num}
                inputMode="numeric"
                spellCheck={false}
                aria-label="自定义均线期数"
                placeholder="7"
                onChange={(e) => setNum(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') submit();
                }}
              />
              <button
                className="iv-act iv-add"
                disabled={feedback.kind !== 'ok'}
                onClick={submit}
              >
                添加
              </button>
            </div>
            <span className="iv-msg" title={feedback.text}>
              {feedback.text}
            </span>
          </div>

          <div className="up-actions">
            <button className="up-btn" onClick={onReset}>
              恢复默认（{DEFAULT_MA_CATALOG.map(maLabel).join(' / ')}）
            </button>
          </div>
        </div>
      </section>

      {ghost &&
        createPortal(
          <div
            className="iv-row drag-row dragging drag-ghost"
            ref={ghostRef}
            style={{ width: ghost.w, height: ghost.h }}
          >
            <RowInner period={Number(ghost.id)} />
          </div>,
          document.body,
        )}
    </div>
  );
}