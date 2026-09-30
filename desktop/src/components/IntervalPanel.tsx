import { useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  addInterval,
  composeInterval,
  DEFAULT_INTERVALS,
  INTERVAL_UNITS,
  intervalLabel,
  intervalNote,
  removeInterval,
  synthesisOf,
  type IntervalSynthesis,
} from '../lib/intervals';
import { useDragSort } from '../hooks/useDragSort';

interface Props {
  /** 当前的周期列表（有顺序）。 */
  list: string[];
  /** 当前数据源**原生**支持的周期分钟数 —— 不在里面的靠聚合，行上标出来。 */
  nativeMinutes: ReadonlySet<number>;
  /** 当前数据源的展示名（用来解释「谁不支持、谁要聚合」）。 */
  sourceLabel: string;
  onChange: (next: string[]) => void;
  onReset: () => void;
  onClose: () => void;
}

/** 行内容：网格里的行与拖动幽灵共用同一套 DOM，免得两处样式分叉。 */
function RowInner({
  id,
  origin,
  onRemove,
}: {
  id: string;
  origin: IntervalSynthesis | null;
  onRemove?: () => void;
}) {
  return (
    <>
      <span className="iv-name">{intervalLabel(id)}</span>
      <span className="iv-note">{intervalNote(id)}</span>
      {origin && (
        <span className="iv-tag" title={origin.title}>
          {origin.tag}
        </span>
      )}
      <button className="iv-act" title="从周期条里移除" onClick={onRemove}>
        ×
      </button>
    </>
  );
}

/**
 * K 线周期的「选哪些 + 什么顺序 + 自定义」。
 *
 * · **已选**：**整行都能拖**（没有把手，也就没有「找不到拖的地方」这回事）——
 *   按住行的任意处拖动排序；键盘 ↑↓ 也能换位。
 *   拖动时原地留一个虚线空格、另有一个「幽灵」跟着指针飞（见下），其它行带挤动动效让位。
 * · **添加**：只有「数值 + 单位（分/时/天/月/年）」这一条路。**没有「未选」清单** ——
 *   目录里 15 个周期全部能用数值造出来（`1 时`→`1h`、`7 天`→`1w`、`30 天`→`1M`），
 *   所以那张清单只是同一件事的第二个入口，删掉之后面板也短了三分之一。
 * · **聚合**：各家的周期是一张固定白名单，表外的值会被接口拒掉，所以自造周期由
 *   **能整除它的原生周期**聚合出来（见 `lib/aggregate.ts`），行上标出来。
 *
 * 顺序与选择存在 localStorage（`mm.intervals`），下次进来就是这个顺序。
 */
export function IntervalPanel({ list, nativeMinutes, sourceLabel, onChange, onReset, onClose }: Props) {
  const [num, setNum] = useState('');
  const [unit, setUnit] = useState(INTERVAL_UNITS[0]!.suffix);
  /** 整行拖动排序 + 挤动动效的机制都在这个 hook 里（与侧栏自选、悬浮窗配置共用）。 */
  const { dragId, ghost, ghostRef, rowProps, nudge } = useDragSort({ list, onChange });

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const parsed = useMemo(() => (num.trim() === '' ? null : composeInterval(num, unit)), [num, unit]);
  const aggregated = list.filter((id) => synthesisOf(id, nativeMinutes) !== null);

  /** 输入框下面那行反馈：把「解析成了什么、从哪来、会不会重复」一次说清。 */
  const feedback = useMemo(() => {
    if (!parsed) {
      return { kind: 'idle', text: '填数字再选单位，例：45 分 / 4 时 / 1 天 · 7 天 = 1 周、30 天 = 1 月 · 上限 1 年' };
    }
    if (!parsed.ok) return { kind: 'bad', text: parsed.reason };
    const note = intervalNote(parsed.id);
    const shown = note ? `${intervalLabel(parsed.id)} = ${note}` : intervalLabel(parsed.id);
    if (list.includes(parsed.id)) return { kind: 'idle', text: `${shown} · 已经在周期条上` };
    const origin = synthesisOf(parsed.id, nativeMinutes);
    return { kind: 'ok', text: `${shown} · ${origin ? origin.title : `${sourceLabel} 原生支持`}` };
  }, [parsed, list, nativeMinutes, sourceLabel]);

  function submit() {
    if (!parsed?.ok) return;
    onChange(addInterval(list, parsed.id));
    setNum('');
  }

  return (
    <div className="up-backdrop" onMouseDown={onClose}>
      <section className="up-panel up-wide" onMouseDown={(e) => e.stopPropagation()}>
        <header className="up-head">
          <span className="overline">K 线周期</span>
          <span className="grow" />
          <button className="panel-close" title="关闭" onClick={onClose}>
            ×
          </button>
        </header>

        <div className="up-body">
          {/* 提示刻意收成一行：多一行就会把这块顶到弹窗上限去（见 theme.css 的 .iv-group） */}
          <p className="up-hint">
            周期条上放哪些周期、按什么顺序都在这里定；目录里的 15 个周期都能用「数值 + 单位」造出来。
          </p>

          <div className="up-label">
            已选 · 直接拖动整行排序（也可用 ↑ ↓）
            {aggregated.length > 0 && `（其中 ${aggregated.length} 个靠聚合）`}
          </div>
          <div className="iv-group">
            {list.map((id) => {
              const origin = synthesisOf(id, nativeMinutes);
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
                  <RowInner
                    id={id}
                    origin={origin}
                    onRemove={() => onChange(removeInterval(list, id))}
                  />
                </div>
              );
            })}
          </div>

          <div className="up-label">添加周期</div>
          <div className={`iv-custom${feedback.kind === 'idle' ? '' : ` ${feedback.kind}`}`}>
            <div className="iv-compose">
              <input
                className="iv-num"
                value={num}
                inputMode="decimal"
                spellCheck={false}
                aria-label="自定义周期数值"
                placeholder="45"
                onChange={(e) => setNum(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') submit();
                }}
              />
              <div className="iv-units" role="radiogroup" aria-label="周期单位">
                {INTERVAL_UNITS.map((u) => (
                  <button
                    key={u.suffix}
                    type="button"
                    role="radio"
                    aria-checked={unit === u.suffix}
                    className={`iv-unit${unit === u.suffix ? ' on' : ''}`}
                    title={u.hint}
                    onClick={() => setUnit(u.suffix)}
                  >
                    {u.label}
                  </button>
                ))}
              </div>
              <button className="iv-act iv-add" disabled={!parsed?.ok} onClick={submit}>
                添加
              </button>
            </div>
            <span className="iv-msg" title={feedback.text}>
              {feedback.text}
            </span>
          </div>

          <div className="up-actions">
            <button className="up-btn" onClick={onReset}>
              恢复默认（{DEFAULT_INTERVALS.map(intervalLabel).join(' / ')}）
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
            <RowInner id={ghost.id} origin={synthesisOf(ghost.id, nativeMinutes)} />
          </div>,
          document.body,
        )}
    </div>
  );
}
