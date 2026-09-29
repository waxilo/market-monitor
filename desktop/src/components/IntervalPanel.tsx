import {
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type PointerEvent as ReactPointerEvent,
} from 'react';
import { createPortal } from 'react-dom';
import {
  addInterval,
  composeInterval,
  DEFAULT_INTERVALS,
  INTERVAL_UNITS,
  intervalInfo,
  minutesLabel,
  moveInterval,
  moveIntervalTo,
  removeInterval,
  synthesisOf,
  type IntervalInfo,
  type IntervalSynthesis,
} from '../lib/intervals';

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

/** 位移超过这个距离才算「拖」，否则只当点击 —— 不然点一下行就把顺序抖一下。 */
const DRAG_THRESHOLD_PX = 4;

/** 挤动动效时长（与 `--base` 一致）。 */
const SHIFT_MS = 200;

/** 我们自己的位移动画才允许被取消；别误伤行上的 CSS 过渡。 */
const SHIFT_ANIM_ID = 'iv-shift';

/** 动效曲线，与 `--ease-standard` 同一个 —— Web Animations 那边读不到 CSS 变量。 */
const EASE = 'cubic-bezier(0.2, 0, 0, 1)';

interface DragState {
  id: string;
  pointerId: number;
  /** 按下时的指针位置（client 坐标），只用来量「有没有越过阈值」。 */
  originX: number;
  originY: number;
  /** 按下点相对行左上角的偏移 —— 幽灵靠它把「抓住的那一点」钉在指针下。 */
  grabX: number;
  grabY: number;
  /** 最近一次指针位置。 */
  lastX: number;
  lastY: number;
  /** 是否已越过阈值（越过之前不重排、也不显示拖动外观）。 */
  moved: boolean;
}

/** 行内容：网格里的行与拖动幽灵共用同一套 DOM，免得两处样式分叉。 */
function RowInner({
  id,
  note,
  origin,
  onRemove,
}: {
  id: string;
  note: string;
  origin: IntervalSynthesis | null;
  onRemove?: () => void;
}) {
  return (
    <>
      <span className="iv-name">{id}</span>
      <span className="iv-note">{note}</span>
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
  /** 正在被拖的那一行（网格里表现为一个虚线空格）。 */
  const [dragId, setDragId] = useState<string | null>(null);
  /** 跟着指针飞的幽灵；只在拖起来之后才存在。 */
  const [ghost, setGhost] = useState<{ id: string; w: number; h: number } | null>(null);

  /** 拖拽的实时状态 —— 放 ref 里，指针每动一像素都 setState 会把面板拖卡。 */
  const dragRef = useRef<DragState | null>(null);
  const ghostRef = useRef<HTMLDivElement | null>(null);
  /** 行的 DOM，按 id 索引。 */
  const rowsRef = useRef(new Map<string, HTMLDivElement>());
  /** 重排前拍下的「视觉位置」，交给下面的 useLayoutEffect 做 FLIP。 */
  const pendingRef = useRef<Map<string, DOMRect> | null>(null);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const parsed = useMemo(() => (num.trim() === '' ? null : composeInterval(num, unit)), [num, unit]);
  const aggregated = list.filter((id) => synthesisOf(id, nativeMinutes) !== null);

  // ————————————————————————— 重排 + 挤动动效 —————————————————————————

  /** 按现在的**视觉**位置拍一张快照（含正在跑的动画）—— FLIP 的 First。 */
  function snapshotVisual(): Map<string, DOMRect> {
    const snap = new Map<string, DOMRect>();
    for (const [id, el] of rowsRef.current) snap.set(id, el.getBoundingClientRect());
    return snap;
  }

  /** 任何一次重排都先拍快照再改列表，动效统一由下面的 layout effect 播放。 */
  function reorder(next: string[]) {
    pendingRef.current = snapshotVisual();
    onChange(next);
  }

  /**
   * FLIP：先撤销自己上一轮还没播完的位移（读到的正是**当前视觉位置**，
   * 于是新一轮动画从半路接着走而不是跳回去），再让每一行从旧位置滑到新位置。
   *
   * 被拖的那一行也一起动画 —— 它是那个「松手会落在这里」的虚线空格，
   * 空格不跟着滑的话，别人都在动、只有它在瞬移。
   */
  useLayoutEffect(() => {
    const first = pendingRef.current;
    pendingRef.current = null;
    if (!first) return;
    for (const [id, el] of rowsRef.current) {
      const before = first.get(id);
      if (!before) continue;
      el.getAnimations()
        .filter((a) => a.id === SHIFT_ANIM_ID)
        .forEach((a) => a.cancel());
      const now = el.getBoundingClientRect();
      const dx = before.left - now.left;
      const dy = before.top - now.top;
      if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5) continue;
      const anim = el.animate(
        [{ transform: `translate(${dx}px, ${dy}px)` }, { transform: 'translate(0px, 0px)' }],
        { duration: SHIFT_MS, easing: EASE },
      );
      anim.id = SHIFT_ANIM_ID;
    }
  }, [list]);

  /** 输入框下面那行反馈：把「解析成了什么、从哪来、会不会重复」一次说清。 */
  const feedback = useMemo(() => {
    if (!parsed) {
      return { kind: 'idle', text: '填数字再选单位，例：45 分 / 4 时 / 1 天 · 7 天 = 1 周、30 天 = 1 月 · 上限 1 年' };
    }
    if (!parsed.ok) return { kind: 'bad', text: parsed.reason };
    const shown = `${parsed.id} = ${minutesLabel(parsed.minutes)}`;
    if (list.includes(parsed.id)) return { kind: 'idle', text: `${shown} · 已经在周期条上` };
    const origin = synthesisOf(parsed.id, nativeMinutes);
    return { kind: 'ok', text: `${shown} · ${origin ? origin.title : `${sourceLabel} 原生支持`}` };
  }, [parsed, list, nativeMinutes, sourceLabel]);

  function submit() {
    if (!parsed?.ok) return;
    onChange(addInterval(list, parsed.id));
    setNum('');
  }

  // ————————————————————————— 整行拖拽 —————————————————————————

  /**
   * 幽灵是 `position: fixed` + portal 到 body 的。
   *
   * 为什么非要 portal：行和格子一样宽，而抓点常常落在行的左半边，
   * 于是「贴在指针下面」必然让它探出面板右边缘一大截。`.up-body` 是滚动容器
   * （`overflow-y: auto` ⇒ 横轴也被算成 auto），留在里面就会被裁掉三分之一、
   * 还多出一条横向滚动条。portal 出去就完全不受这些剪裁影响。
   *
   * 原地留一个虚线空格（列表里那一行本身，见 `.iv-row.slot`）：
   * 网格不会塌、落点判定与 FLIP 都不用为「少了一行」特判。
   */
  function placeGhost(d: DragState) {
    const g = ghostRef.current;
    if (g) g.style.transform = `translate(${d.lastX - d.grabX}px, ${d.lastY - d.grabY}px)`;
  }

  // 幽灵挂上去的第一帧就摆好，否则会先在左上角闪一下
  useLayoutEffect(() => {
    if (ghost && dragRef.current) placeGhost(dragRef.current);
  }, [ghost]);

  function onRowDown(e: ReactPointerEvent<HTMLDivElement>, id: string) {
    if (e.button !== 0) return;
    // 行上有按钮（移除）时别把点击吃成拖拽
    if ((e.target as HTMLElement).closest('button')) return;
    const el = e.currentTarget;
    const r = el.getBoundingClientRect();
    dragRef.current = {
      id,
      pointerId: e.pointerId,
      originX: e.clientX,
      originY: e.clientY,
      grabX: e.clientX - r.left,
      grabY: e.clientY - r.top,
      lastX: e.clientX,
      lastY: e.clientY,
      moved: false,
    };
    el.setPointerCapture(e.pointerId);
  }

  function onRowMove(e: ReactPointerEvent<HTMLDivElement>) {
    const d = dragRef.current;
    if (!d || d.pointerId !== e.pointerId) return;
    const el = rowsRef.current.get(d.id);
    if (!el) return;
    d.lastX = e.clientX;
    d.lastY = e.clientY;

    if (!d.moved) {
      if (Math.hypot(e.clientX - d.originX, e.clientY - d.originY) < DRAG_THRESHOLD_PX) return;
      d.moved = true;
      setDragId(d.id);
      const r = el.getBoundingClientRect();
      setGhost({ id: d.id, w: r.width, h: r.height });
    }
    placeGhost(d);

    /*
     * 落点按**阅读顺序**判定，基准是每格的**中心**：
     *   纵向中心差 < 半格高 ⇒ 同一视觉行，比横坐标；否则比纵坐标。
     * 两侧都用「包含」，所以判据其实是一条**中点边界**：指针停在某格的右/下半
     * ⇒ 落在它后面，停在左/上半 ⇒ 落在它前面。
     *
     * ⚠️ 一律用 `offset*`（布局位置）判，不用 `getBoundingClientRect`：
     * 其它行正在播挤动动效，rect 是**动画中途**的值，用它判会来回抖。
     */
    const parent = el.offsetParent as HTMLElement | null;
    const pr = parent?.getBoundingClientRect();
    const px = e.clientX - (pr?.left ?? 0) - (parent?.clientLeft ?? 0);
    const py = e.clientY - (pr?.top ?? 0) - (parent?.clientTop ?? 0);

    let insertAt = 0;
    for (const other of list) {
      if (other === d.id) continue;
      const oe = rowsRef.current.get(other);
      if (!oe) continue;
      const cx = oe.offsetLeft + oe.offsetWidth / 2;
      const cy = oe.offsetTop + oe.offsetHeight / 2;
      const sameRow = Math.abs(cy - py) < oe.offsetHeight / 2;
      const before = sameRow ? cx <= px : cy < py;
      if (before) insertAt += 1;
    }
    const from = list.indexOf(d.id);
    if (insertAt !== from) reorder(moveIntervalTo(list, from, insertAt));
  }

  function endDrag(e: ReactPointerEvent<HTMLDivElement>) {
    const d = dragRef.current;
    if (!d || d.pointerId !== e.pointerId) return;
    dragRef.current = null;
    const el = rowsRef.current.get(d.id);
    if (el?.hasPointerCapture(e.pointerId)) el.releasePointerCapture(e.pointerId);

    const g = ghostRef.current;
    if (!d.moved || !g || !el) {
      setDragId(null);
      setGhost(null);
      return;
    }
    // 松手时让幽灵**飞回**它落下的格子，而不是凭空消失
    const r = el.getBoundingClientRect();
    g.style.transition = `transform ${SHIFT_MS}ms ${EASE}`;
    g.style.transform = `translate(${r.left}px, ${r.top}px)`;
    const node = g;
    setTimeout(() => {
      // 组件已卸载 / 又开了一次拖拽 ⇒ ghostRef 不再指着它，就别清了
      if (ghostRef.current !== node) return;
      setDragId(null);
      setGhost(null);
    }, SHIFT_MS);
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
              const info: IntervalInfo | null = intervalInfo(id);
              const origin = synthesisOf(id, nativeMinutes);
              return (
                <div
                  className={`iv-row${dragId === id ? ' slot' : ''}`}
                  key={id}
                  ref={(el) => {
                    if (el) rowsRef.current.set(id, el);
                    else rowsRef.current.delete(id);
                  }}
                  tabIndex={0}
                  role="listitem"
                  title="按住这一行任意处拖动排序（也可用 ↑ ↓）"
                  onPointerDown={(e) => onRowDown(e, id)}
                  onPointerMove={onRowMove}
                  onPointerUp={endDrag}
                  onPointerCancel={endDrag}
                  onKeyDown={(e) => {
                    if (e.key === 'ArrowUp') {
                      e.preventDefault();
                      reorder(moveInterval(list, id, -1));
                    }
                    if (e.key === 'ArrowDown') {
                      e.preventDefault();
                      reorder(moveInterval(list, id, 1));
                    }
                  }}
                >
                  <RowInner
                    id={id}
                    note={info?.note ?? ''}
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
              恢复默认（{DEFAULT_INTERVALS.join(' / ')}）
            </button>
          </div>
        </div>
      </section>

      {ghost &&
        createPortal(
          <div
            className="iv-row dragging iv-ghost"
            ref={(el) => {
              ghostRef.current = el;
            }}
            style={{ width: ghost.w, height: ghost.h }}
          >
            <RowInner
              id={ghost.id}
              note={intervalInfo(ghost.id)?.note ?? ''}
              origin={synthesisOf(ghost.id, nativeMinutes)}
            />
          </div>,
          document.body,
        )}
    </div>
  );
}
