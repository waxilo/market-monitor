import { Fragment, useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  MA_LIMIT,
  MA_MAX,
  MA_MIN,
  MA_SLOT_COLORS,
  MA_SLOT_COUNT,
  type MaColors,
} from '../lib/chartSeries';
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
  /** 期数 → 颜色槽位（0~`MA_SLOT_COUNT-1`）。 */
  colors: MaColors;
  onChange: (next: number[]) => void;
  /** 改线色；槽位被别的均线占着时由上层互换。 */
  onColorChange: (period: number, slot: number) => void;
  onReset: () => void;
  onClose: () => void;
}

/** 行内容：网格里的行与拖动幽灵共用同一套 DOM，免得两处样式分叉。 */
function RowInner({
  period,
  color,
  pickerOpen,
  onTogglePicker,
  onRemove,
}: {
  period: number;
  color?: string;
  pickerOpen?: boolean;
  onTogglePicker?: () => void;
  onRemove?: () => void;
}) {
  return (
    <>
      <span className="iv-name">{maLabel(period)}</span>
      <span className="iv-note" />
      <button
        type="button"
        className={`ma-dot${pickerOpen ? ' on' : ''}`}
        style={{ background: color }}
        title={`${maLabel(period)} 线色 · 点一下改`}
        aria-label={`${maLabel(period)} 线色`}
        aria-expanded={pickerOpen}
        onClick={onTogglePicker}
      />
      <button className="iv-act" title="从指标栏移除" onClick={onRemove}>
        ×
      </button>
    </>
  );
}

/**
 * 主图均线的「有哪些 chip + 什么顺序 + 自定义 + 线色」—— 与 `IntervalPanel` 同一套骨架
 * （整行拖动排序 + `useDragSort` 的挤动动效，DOM 与 `iv-*` / `up-*` 样式类照搬），
 * 只是把「数值 + 单位」换成单个「期数」输入。
 *
 * 线色**绑在期数上**（`colors`）：拖动只改 chip 的先后，颜色跟着均线走；
 * 新增一条时由上层归一分到第一个空槽，点行内色点可改（占用中的色槽互换）。
 */
export function MaPanel({ list, colors, onChange, onColorChange, onReset, onClose }: Props) {
  const [num, setNum] = useState('');
  /** 正在选色的那一行（展开的色板只出一个，免得整页都是色块）。 */
  const [pickerFor, setPickerFor] = useState<number | null>(null);
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
            指标栏上放哪些均线、按什么顺序都在这里定。线色跟着均线走：新增会自动挑一个
            还没用的颜色，点行内色点可改（拖动排序不会换色）。
          </p>

          <div className="up-label">已选 · 直接拖动整行排序（也可用 ↑ ↓）</div>
          <div className="iv-group">
            {list.map((period) => {
              const id = String(period);
              const slot = colors[period] ?? 0;
              return (
                <Fragment key={id}>
                  <div
                    className={`iv-row drag-row${dragId === id ? ' slot' : ''}`}
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
                      period={period}
                      color={MA_SLOT_COLORS[slot]}
                      pickerOpen={pickerFor === period}
                      onTogglePicker={() => setPickerFor(pickerFor === period ? null : period)}
                      onRemove={() => {
                        onChange(removeMaPeriod(list, period));
                        if (pickerFor === period) setPickerFor(null);
                      }}
                    />
                  </div>
                  {pickerFor === period && (
                    /* 色板横跨两列：8 个色点在一列行宽里摆不下 */
                    <div className="ma-picker" role="group" aria-label={`${maLabel(period)} 线色`}>
                      <span className="ma-picker-label">线色</span>
                      {Array.from({ length: MA_SLOT_COUNT }, (_, slotIndex) => {
                        const owner = list.find((p) => p !== period && colors[p] === slotIndex);
                        const current = colors[period] === slotIndex;
                        return (
                          <button
                            key={slotIndex}
                            type="button"
                            className={`ma-swatch${current ? ' on' : ''}${owner != null ? ' used' : ''}`}
                            style={{ background: MA_SLOT_COLORS[slotIndex] }}
                            title={
                              current
                                ? '当前颜色'
                                : owner != null
                                  ? `与 ${maLabel(owner)} 互换颜色`
                                  : `把 ${maLabel(period)} 改成这个颜色`
                            }
                            aria-label={`${maLabel(period)} 用第 ${slotIndex + 1} 种颜色`}
                            onClick={() => {
                              onColorChange(period, slotIndex);
                              setPickerFor(null);
                            }}
                          />
                        );
                      })}
                    </div>
                  )}
                </Fragment>
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
            <RowInner
              period={Number(ghost.id)}
              color={MA_SLOT_COLORS[colors[Number(ghost.id)] ?? 0]}
            />
          </div>,
          document.body,
        )}
    </div>
  );
}