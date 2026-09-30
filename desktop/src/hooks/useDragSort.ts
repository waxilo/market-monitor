import {
  useLayoutEffect,
  useRef,
  useState,
  type PointerEvent as ReactPointerEvent,
} from 'react';

/** 位移超过这个距离才算「拖」，否则只当点击 —— 不然点一下行就把顺序抖一下。 */
const DRAG_THRESHOLD_PX = 4;

/** 挤动动效时长（与 `--base` 一致）。 */
const SHIFT_MS = 200;

/** 我们自己的位移动画才允许被取消；别误伤行上的 CSS 过渡。 */
const SHIFT_ANIM_ID = 'drag-shift';

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

export interface DragSortRowProps {
  ref: (el: HTMLDivElement | null) => void;
  onPointerDown: (e: ReactPointerEvent<HTMLDivElement>) => void;
  onPointerMove: (e: ReactPointerEvent<HTMLDivElement>) => void;
  onPointerUp: (e: ReactPointerEvent<HTMLDivElement>) => void;
  onPointerCancel: (e: ReactPointerEvent<HTMLDivElement>) => void;
}

/**
 * 「整行拖动排序」的公用机制：阈值判定、跟着指针飞的幽灵、其它行的 FLIP 挤动动效。
 *
 * 从 `IntervalPanel` 抽出来的（那套手感是调过的，判据都写在这里的注释里），
 * 现在三处共用它：K 线周期条、悬浮窗配置弹窗、侧栏自选列表。
 *
 * 用法：`list` 是唯一 id 的**顺序**数组，改动一律通过 `onChange` 交回调用方
 * （谁存这份顺序谁负责持久化）。渲染侧要做的三件事：
 * 1. 每行 `<div {...rowProps(id)} className={dragId === id ? 'slot' : ''}>`；
 * 2. 想支持键盘 ↑↓ 就在 `onKeyDown` 里调 `nudge(id, ±1)`；
 * 3. `ghost` 非空时把它 portal 到 body、用 `ghostRef` 挂那个浮起来的路基行
 *    （见 `styles/theme.css` 的 `.drag-ghost`）。
 *
 * 一行只摆一格的列表（配置弹窗、侧栏）要把 `flow` 传 `'column'`。
 */
export function useDragSort(opts: {
  list: readonly string[];
  onChange: (next: string[]) => void;
  /**
   * 列表的排布方式，决定「落在谁前面」怎么判：
   * - `grid`（默认）：一行摆好几格（周期条是两列），要先认「同一视觉行」再比横坐标。
   * - `column`：一行一条（悬浮窗配置、侧栏自选）。这时**横坐标完全没用** ——
   *   每格都和容器一样宽，指针几乎总在它水平中心的左边，按 `grid` 那套判的话
   *   「停在最后一格的下半」永远会被判成落在它前面，最后一格就成了落不进去的死角。
   */
  flow?: 'grid' | 'column';
}) {
  const { list, onChange, flow = 'grid' } = opts;
  /** 正在被拖的那一行（列表里表现为一个虚线空格）。 */
  const [dragId, setDragId] = useState<string | null>(null);
  /** 跟着指针飞的幽灵；只在拖起来之后才存在。 */
  const [ghost, setGhost] = useState<{ id: string; w: number; h: number } | null>(null);

  /** 拖拽的实时状态 —— 放 ref 里，指针每动一像素都 setState 会把面板拖卡。 */
  const dragRef = useRef<DragState | null>(null);
  const ghostRef = useRef<HTMLDivElement | null>(null);
  /** 行的 DOM，按 id 索引。 */
  const rowsRef = useRef(new Map<string, HTMLDivElement>());
  /** 重排前拍下的「视觉位置」，交给下面的 layout effect 做 FLIP。 */
  const pendingRef = useRef<Map<string, DOMRect> | null>(null);

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

  /** 把第 `from` 项搬到第 `to` 位（越界就地钳位；与 `intervals.ts` 的搬法同一语义）。 */
  function moveTo(arr: readonly string[], from: number, to: number): string[] {
    if (from < 0 || from >= arr.length) return arr as string[];
    const target = Math.max(0, Math.min(arr.length - 1, to));
    if (from === target) return arr as string[];
    const next = [...arr];
    const [moved] = next.splice(from, 1);
    next.splice(target, 0, moved as string);
    return next;
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

  /*
   * 幽灵是 `position: fixed` + portal 到 body 的。
   *
   * 为什么非要 portal：行和容器一样宽，而抓点常常落在行的左半边，
   * 于是「贴在指针下面」必然让它探出面板右边缘一大截。`.up-body` 是滚动容器
   * （`overflow-y: auto` ⇒ 横轴也被算成 auto），留在里面就会被裁掉三分之一、
   * 还多出一条横向滚动条。portal 出去就完全不受这些剪裁影响。
   *
   * 原地留一个虚线空格（列表里那一行本身，见 `.slot`）：
   * 列表不会塌、落点判定与 FLIP 都不用为「少了一行」特判。
   */
  function placeGhost(d: DragState) {
    const g = ghostRef.current;
    if (g) g.style.transform = `translate(${d.lastX - d.grabX}px, ${d.lastY - d.grabY}px)`;
  }

  // 幽灵挂上去的第一帧就摆好，否则会先在左上角闪一下
  useLayoutEffect(() => {
    if (ghost && dragRef.current) placeGhost(dragRef.current);
  }, [ghost]);

  /**
   * 「刚拖完」的旗标：拖完浏览器还会补一下 click，能点行的列表（侧栏）要靠它认出那一下。
   *
   * ⚠️ **读一次就清**：只在 pointerdown 复位的话，「没有 pointerdown 的 click」
   * （脚本合成的点击、以后可能加的键盘激活）会被一直当成拖后的那一下而吞掉。
   * 语义本来就是「吃掉紧接着的那一下 click」，用完即弃才对得上。
   */
  const movedRef = useRef(false);
  const justDragged = () => {
    const moved = movedRef.current;
    movedRef.current = false;
    return moved;
  };

  function onRowDown(e: ReactPointerEvent<HTMLDivElement>, id: string) {
    if (e.button !== 0) return;
    // 行上有按钮（移除 / 加入）时别把点击吃成拖拽
    if ((e.target as HTMLElement).closest('button')) return;
    movedRef.current = false;
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
     * 落点判据（`flow` 决定用哪一条，理由写在那里）：
     *   column：谁的**纵向中心**在指针之上 ⇒ 落在它后面。
     *   grid：  阅读顺序 —— 纵向中心差 < 半格高 ⇒ 同一视觉行，比横坐标；否则比纵坐标。
     * 两侧都用「包含」，所以其实是一条**中点边界**：指针停在某格的下/右半
     * ⇒ 落在它后面，停在上/左半 ⇒ 落在它前面。
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
      const cy = oe.offsetTop + oe.offsetHeight / 2;
      let before: boolean;
      if (flow === 'column') {
        before = cy < py;
      } else {
        const cx = oe.offsetLeft + oe.offsetWidth / 2;
        const sameRow = Math.abs(cy - py) < oe.offsetHeight / 2;
        before = sameRow ? cx <= px : cy < py;
      }
      if (before) insertAt += 1;
    }
    const from = list.indexOf(d.id);
    if (insertAt !== from) reorder(moveTo(list, from, insertAt));
  }

  function endDrag(e: ReactPointerEvent<HTMLDivElement>) {
    const d = dragRef.current;
    if (!d || d.pointerId !== e.pointerId) return;
    dragRef.current = null;
    // 拖起来之后浏览器还会补一下 click：侧栏那种「点行 = 选标的」的行要靠它识别并吃掉
    movedRef.current = d.moved;
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

  /** 键盘换位（↑↓）：与拖动走同一条「先拍快照再改」的路，动效一致。 */
  function nudge(id: string, delta: number) {
    reorder(moveTo(list, list.indexOf(id), list.indexOf(id) + delta));
  }

  /** 每行摊到的一组属性（`ref` 用来认行、量位置，四个指针事件是一套拖拽）。 */
  function rowProps(id: string): DragSortRowProps {
    return {
      ref: (el: HTMLDivElement | null) => {
        if (el) rowsRef.current.set(id, el);
        else rowsRef.current.delete(id);
      },
      onPointerDown: (e) => onRowDown(e, id),
      onPointerMove: onRowMove,
      onPointerUp: endDrag,
      onPointerCancel: endDrag,
    };
  }

  return { dragId, ghost, ghostRef, rowProps, nudge, justDragged };
}
