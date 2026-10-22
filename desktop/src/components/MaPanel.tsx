import { Fragment, useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  MA_KINDS,
  MA_KIND_LABEL,
  MA_KIND_NAME,
  MA_LIMIT,
  MA_MAX,
  MA_MIN,
  MA_SLOT_COUNT,
  maSlotColors,
  type MaKind,
} from '../lib/chartSeries';
import {
  addMaPeriod,
  defaultCatalog,
  drawnPeriods,
  lineLabel,
  parseMaPeriod,
  removeMaPeriod,
  toggleMuted,
  type MaFamilies,
} from '../lib/maPeriods';
import { EyeIcon } from './EyeIcon';
import { useDragSort } from '../hooks/useDragSort';

interface Props {
  /** 两族的目录、配色、整族显隐与逐条小眼睛（这里只改后两者之外的配置 + 小眼睛）。 */
  families: MaFamilies;
  /** 改某族的目录（增删 / 排序）。 */
  onCatalogChange: (kind: MaKind, next: number[]) => void;
  /** 改某条均线的线色；槽位被同族别的均线占着时由上层互换。 */
  onColorChange: (kind: MaKind, period: number, slot: number) => void;
  /** 改某族「眼睛闭着」的期数集合（逐条显隐）。 */
  onMutedChange: (kind: MaKind, next: number[]) => void;
  /** 一次改完这一族的所有小眼睛（`hide = true` 全闭）。 */
  onAllMutedChange: (kind: MaKind, hide: boolean) => void;
  /** 某一族恢复出厂档位（配色一起回到出厂那套，小眼睛全睁开）。 */
  onReset: (kind: MaKind) => void;
  onClose: () => void;
}

/** 行内容：网格里的行与拖动幽灵共用同一套 DOM，免得两处样式分叉。 */
function RowInner({
  label,
  color,
  muted,
  pickerOpen,
  onTogglePicker,
  onToggleMuted,
  onRemove,
}: {
  label: string;
  color?: string;
  /** 这条均线的眼睛闭着（不画，但仍在目录里）。 */
  muted?: boolean;
  pickerOpen?: boolean;
  onTogglePicker?: () => void;
  onToggleMuted?: () => void;
  onRemove?: () => void;
}) {
  return (
    <>
      <span className="iv-name">{label}</span>
      <span className="iv-note">{muted ? '图上不画' : ''}</span>
      {/* 小眼睛在色点左边：先「画不画」，再「长什么样」，最后才是破坏性的 × */}
      <button
        type="button"
        className={`ma-eye${muted ? ' off' : ''}`}
        title={muted ? `${label} 现在不画 · 点一下显示` : `${label} 正在画 · 点一下只藏这一条`}
        aria-label={`${label} 显隐`}
        aria-pressed={!muted}
        onClick={onToggleMuted}
      >
        <EyeIcon off={muted === true} size={12} />
      </button>
      <button
        type="button"
        className={`ma-dot${pickerOpen ? ' on' : ''}`}
        style={{ background: color }}
        title={`${label} 线色 · 点一下改`}
        aria-label={`${label} 线色`}
        aria-expanded={pickerOpen}
        onClick={onTogglePicker}
      />
      <button className="iv-act" title="从配置里移除" onClick={onRemove}>
        ×
      </button>
    </>
  );
}

/**
 * 主图均线的配置面板：**MA / EMA 两族各一页**（整行拖动排序 + `useDragSort` 的挤动动效，
 * DOM 与 `iv-*` / `up-*` 样式类照搬 `IntervalPanel`），页内就是这一族「画哪几档、什么线色」。
 *
 * 显隐分两级，这里只管**第二级**：每行行尾一只小眼睛 ——
 * 族开着的时候这条画不画。第一级是指标栏上那枚族 chip（整族显隐），不在这里重复。
 * 两级互不覆盖：chip 关掉再打开，谁睁眼谁闭眼原样保留。
 *
 * 线色**绑在期数上**（`colors`）：拖动只改 chip 的先后，颜色跟着均线走；
 * 新增一条时由上层归一分到第一个空槽，点行内色点可改（占用中的色槽互换）。
 */
export function MaPanel({
  families,
  onCatalogChange,
  onColorChange,
  onMutedChange,
  onAllMutedChange,
  onReset,
  onClose,
}: Props) {
  const [kind, setKind] = useState<MaKind>('MA');
  const [num, setNum] = useState('');
  /** 正在选色的那一行（展开的色板只出一个，免得整页都是色块）。 */
  const [pickerFor, setPickerFor] = useState<number | null>(null);
  const family = families[kind];
  const list = family.catalog;
  /** 这一族此刻真正落笔的那几条（目录 − 眼睛闭上的）。 */
  const drawn = useMemo(() => drawnPeriods(family), [family]);
  const hiddenCount = list.length - drawn.length;
  const palette = useMemo(() => maSlotColors(kind), [kind]);
  /** 拖拽排序只认 string[] 的 id 顺序，这里把期数转成字符串喂给它，回来再转回数字。 */
  const ids = useMemo(() => list.map(String), [list]);
  const { dragId, ghost, ghostRef, rowProps, nudge } = useDragSort({
    list: ids,
    onChange: (next) => onCatalogChange(kind, next.map(Number)),
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
        text: `填期数，例：7 / 99 / 200 · 取值 ${MA_MIN}~${MA_MAX} · 每族最多 ${MA_LIMIT} 条`,
      };
    }
    if (!parsed.ok) return { kind: 'bad', text: parsed.reason };
    const label = lineLabel(kind, parsed.period);
    if (list.includes(parsed.period)) return { kind: 'idle', text: `${label} · 已经在配置里` };
    if (list.length >= MA_LIMIT) return { kind: 'bad', text: `每族最多 ${MA_LIMIT} 条，先删一条再加` };
    return { kind: 'ok', text: `${label} · 可以添加` };
  }, [parsed, list, kind]);

  function submit() {
    if (!parsed?.ok) return;
    onCatalogChange(kind, addMaPeriod(list, parsed.period));
    setNum('');
  }

  function switchKind(next: MaKind) {
    setKind(next);
    setPickerFor(null);
    setNum('');
  }

  return (
    <div className="up-backdrop" onMouseDown={onClose}>
      <section className="up-panel up-wide" onMouseDown={(e) => e.stopPropagation()}>
        <header className="up-head">
          <span className="overline">均线 · MA / EMA</span>
          <span className="grow" />
          <button className="panel-close" title="关闭" onClick={onClose}>
            ×
          </button>
        </header>

        <div className="up-body">
          <p className="up-hint">
            指标栏上 MA / EMA 各一枚 chip：点一下是整族显示 / 隐藏。这里定这一族画哪几档、
            用什么线色，每行右边那只**小眼睛**管「这条自己画不画」——
            藏起来的条目仍在配置里，随时点回来（族 chip 关掉再打开，也还是照原样）。
          </p>

          {/* 两族各一页：同一套列表骨架，只换数据源，避免两份几乎相同的 DOM 分叉维护 */}
          <div className="ma-tabs" role="tablist" aria-label="均线族">
            {MA_KINDS.map((k) => {
              const total = families[k].catalog.length;
              const live = drawnPeriods(families[k]).length;
              return (
                <button
                  key={k}
                  type="button"
                  role="tab"
                  aria-selected={kind === k}
                  className={`ma-tab${kind === k ? ' on' : ''}`}
                  title={`${MA_KIND_NAME[k]}：配置 ${MA_KIND_LABEL[k]} 画哪几档`}
                  onClick={() => switchKind(k)}
                >
                  {MA_KIND_LABEL[k]}
                  <span className="ma-tab-note">
                    {total === 0 ? '未配置' : `${live} / ${total} 条`}
                    {families[k].hidden ? ' · 整族已隐藏' : ''}
                  </span>
                </button>
              );
            })}
          </div>

          <div className="up-label ma-list-head">
            <span>已配置 · 直接拖动整行排序（也可用 ↑ ↓）</span>
            {hiddenCount > 0 ? (
              <button
                type="button"
                className="ma-batch"
                title={`把 ${MA_KIND_LABEL[kind]} 的 ${hiddenCount} 条全部显示`}
                onClick={() => onAllMutedChange(kind, false)}
              >
                全部显示
              </button>
            ) : (
              list.length > 0 && (
                <button
                  type="button"
                  className="ma-batch"
                  title={`把 ${MA_KIND_LABEL[kind]} 的这一组全部藏起来（与族 chip 等价，但不用关整族）`}
                  onClick={() => onAllMutedChange(kind, true)}
                >
                  全部隐藏
                </button>
              )
            )}
          </div>
          <div className="iv-group">
            {list.map((period) => {
              const id = String(period);
              const slot = family.colors[period] ?? 0;
              const label = lineLabel(kind, period);
              const muted = family.muted.includes(period);
              return (
                <Fragment key={id}>
                  <div
                    className={`iv-row drag-row${dragId === id ? ' slot' : ''}${muted ? ' muted' : ''}`}
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
                      label={label}
                      color={palette[slot]}
                      muted={muted}
                      pickerOpen={pickerFor === period}
                      onTogglePicker={() => setPickerFor(pickerFor === period ? null : period)}
                      onToggleMuted={() => onMutedChange(kind, toggleMuted(family, period))}
                      onRemove={() => {
                        onCatalogChange(kind, removeMaPeriod(list, period));
                        if (pickerFor === period) setPickerFor(null);
                      }}
                    />
                  </div>
                  {pickerFor === period && (
                    /* 色板横跨两列：8 个色点在一列行宽里摆不下 */
                    <div className="ma-picker" role="group" aria-label={`${label} 线色`}>
                      <span className="ma-picker-label">线色</span>
                      {Array.from({ length: MA_SLOT_COUNT }, (_, slotIndex) => {
                        const owner = list.find((p) => p !== period && family.colors[p] === slotIndex);
                        const current = family.colors[period] === slotIndex;
                        return (
                          <button
                            key={slotIndex}
                            type="button"
                            className={`ma-swatch${current ? ' on' : ''}${owner != null ? ' used' : ''}`}
                            style={{ background: palette[slotIndex] }}
                            title={
                              current
                                ? '当前颜色'
                                : owner != null
                                  ? `与 ${lineLabel(kind, owner)} 互换颜色`
                                  : `把 ${label} 改成这个颜色`
                            }
                            aria-label={`${label} 用第 ${slotIndex + 1} 种颜色`}
                            onClick={() => {
                              onColorChange(kind, period, slotIndex);
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
            {list.length === 0 && (
              <p className="up-hint">
                这一族还没配任何档位 —— 指标栏上的 {MA_KIND_LABEL[kind]} chip 点亮了也不会画线。
                在下面加几条吧。
              </p>
            )}
          </div>

          <div className="up-label">添加 {MA_KIND_LABEL[kind]} 均线</div>
          <div className={`iv-custom${feedback.kind === 'idle' ? '' : ` ${feedback.kind}`}`}>
            <div className="iv-compose">
              <input
                className="iv-num"
                value={num}
                inputMode="numeric"
                spellCheck={false}
                aria-label={`自定义 ${MA_KIND_LABEL[kind]} 均线期数`}
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
            <button className="up-btn" onClick={() => onReset(kind)}>
              恢复默认（{defaultCatalog(kind).map((p) => lineLabel(kind, p)).join(' / ')}）
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
              label={lineLabel(kind, Number(ghost.id))}
              color={palette[family.colors[Number(ghost.id)] ?? 0]}
              muted={family.muted.includes(Number(ghost.id))}
            />
          </div>,
          document.body,
        )}
    </div>
  );
}
