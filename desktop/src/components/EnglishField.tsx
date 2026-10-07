import {
  forwardRef,
  useEffect,
  useImperativeHandle,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type ClipboardEvent as ReactClipboardEvent,
  type MouseEvent as ReactMouseEvent,
} from 'react';
import { englishOnly } from '../lib/search';

/**
 * 自绘英文输入框（顶栏搜索 / 设置→悬浮窗搜索共用）。
 *
 * 为什么不用 `<input>`：候选词是**输入法在系统层弹的**，只要焦点落在可编辑元素上就无
 * 法从 Web 层关掉（没有这个标准接口）。换成不可编辑的元素后，Chromium / WebKit 都不会
 * 给它建输入法上下文 —— 选着中文输入法敲字母，也是**不出候选、直接进框**，Windows 与
 * macOS 一套机制（游戏里用键盘的 canvas 就是这么做的）。
 *
 * 代价是输入行为全部自绘：光标、选区、删改、方向键、Ctrl+A/C/X/V（粘贴借一个藏起来的
 * sink input 接一次原生粘贴）、双击全选。字符集仍走 `englishOnly`（字母 / 数字 / 下划线）。
 * 场景键（顶栏的 Esc、悬浮窗的 ↑↓/Enter）由 `onKeyDown` 先过一手：它 preventDefault 了
 * 就到此为止，不再当输入处理。
 */
export interface EnglishFieldHandle {
  focus: () => void;
  /** 全选：Ctrl+K 之后直接打字 = 替换旧词。 */
  select: () => void;
}

interface EnglishFieldProps {
  value: string;
  onChange: (next: string) => void;
  placeholder: string;
  /** 皮肤类（`.search-field .ef-box` / `.ms-input` 各自给造型，行为都在 `.ef-box` 里）。 */
  className?: string;
  /** 先过这一手（React 合成事件）：preventDefault 过就轮到场景自己的键了。 */
  onKeyDown?: (e: ReactKeyboardEvent<HTMLDivElement>) => void;
  onFocus?: () => void;
}

/** 在 `[lo, hi)` 处插入文本 → 新值与新光标位。 */
function splice(v: string, lo: number, hi: number, text: string) {
  return { v: v.slice(0, lo) + text + v.slice(hi), caret: lo + text.length };
}

export const EnglishField = forwardRef<EnglishFieldHandle, EnglishFieldProps>(
  function EnglishField({ value, onChange, placeholder, className, onKeyDown, onFocus }, ref) {
    const boxRef = useRef<HTMLDivElement>(null);
    const sinkRef = useRef<HTMLInputElement>(null);
    const caretRef = useRef<HTMLSpanElement>(null);
    /** 选区：`a` = 锚点，`c` = 活动端（光标画在这头）；相等 = 没选区。 */
    const [sel, setSel] = useState({ a: 0, c: 0 });
    /** 焦点在不在框里（光标只在焦点里画）。 */
    const [focused, setFocused] = useState(false);
    /** 鼠标拖选中：mousedown 起、mouseup 止。 */
    const dragging = useRef(false);
    /** 粘贴走位：Ctrl+V 后焦点暂借给 sink，等它接到原生粘贴再回来。 */
    const pastePending = useRef(false);
    const pasteTimer = useRef(0);

    // 事件路径（window 上的拖动 / sink 的异步回调）读它，避免闭包拿旧值。
    // indexAt 底下也读它 —— 挂在 window 上的监听只挂一次，不能依赖渲染闭包。
    const latest = useRef({ value, sel });
    latest.current = { value, sel };

    const length = value.length;
    const lo = Math.min(sel.a, sel.c);
    const hi = Math.max(sel.a, sel.c);

    useImperativeHandle(ref, () => ({
      focus: () => boxRef.current?.focus(),
      select: () => {
        boxRef.current?.focus();
        setSel({ a: 0, c: latest.current.value.length });
      },
    }));

    // 外面改了值（清空 / 换词）：选区跟着收进新长度
    useEffect(() => {
      setSel((s) => {
        const a = Math.min(s.a, value.length);
        const c = Math.min(s.c, value.length);
        return a === s.a && c === s.c ? s : { a, c };
      });
    }, [value]);

    /** 落一次编辑：值变了才回给父级；光标停在插入点后面。 */
    const commit = (v: string, caret: number) => {
      if (v !== value) onChange(v);
      setSel({ a: caret, c: caret });
    };
    const insert = (text: string) => {
      const r = splice(value, lo, hi, text);
      commit(r.v, r.caret);
    };
    const deleteSel = () => commit(value.slice(0, lo) + value.slice(hi), lo);

    /** 复制：先走异步剪贴板；被拒（权限 / WebView 限制）就退回「sink + execCommand」的老办法。 */
    const copyText = (text: string) => {
      const clip = navigator.clipboard;
      if (clip?.writeText) {
        clip.writeText(text).catch(() => legacyCopy(text));
        return;
      }
      legacyCopy(text);
    };
    const legacyCopy = (text: string) => {
      const sink = sinkRef.current;
      if (!sink) return;
      sink.value = text;
      sink.focus();
      sink.select();
      try {
        document.execCommand('copy');
      } catch {
        /* 剪贴板用不了就作罢 */
      }
      sink.value = '';
      boxRef.current?.focus();
    };

    /** 鼠标位置 → 字符下标（caretRangeFromPoint 给的是文字节点偏移，按 pre/sel/post 折算）。 */
    const indexAt = (x: number, y: number): number => {
      const box = boxRef.current;
      const v = latest.current.value;
      if (!box) return 0;
      if (v === '') return 0;
      const probe = (
        document as Document & { caretRangeFromPoint?: (x: number, y: number) => Range | null }
      ).caretRangeFromPoint;
      const r = probe ? probe.call(document, x, y) : null;
      if (r) {
        const node = r.startContainer;
        const nodeEl = node.nodeType === Node.TEXT_NODE ? node.parentElement : (node as Element);
        const part = nodeEl?.closest('[data-part]') as HTMLElement | null;
        if (part && box.contains(part)) {
          let base = 0;
          for (const p of ['pre', 'sel', 'post']) {
            if (p === part.dataset.part) {
              return Math.max(0, Math.min(base + r.startOffset, v.length));
            }
            base += (box.querySelector(`[data-part="${p}"]`)?.textContent ?? '').length;
          }
        }
      }
      // 点到文字之外（左右留白 / 垂直偏出去）：按左右半区兜底
      const rect = box.getBoundingClientRect();
      return x < rect.left + rect.width / 2 ? 0 : v.length;
    };

    const handleKeyDown = (e: ReactKeyboardEvent<HTMLDivElement>) => {
      onKeyDown?.(e); // 场景键先走（Escape / ↑↓ / Enter）
      if (e.defaultPrevented) return;
      // 真被输入法组合拿去了（理论上不会 —— 这框不可编辑）：这轮别掺和
      if (e.nativeEvent.isComposing || e.key === 'Process') return;

      const mod = e.ctrlKey || e.metaKey;
      const k = e.key;

      if (mod && k.toLowerCase() === 'a') {
        e.preventDefault();
        setSel({ a: 0, c: length });
        return;
      }
      if (mod && k.toLowerCase() === 'c') {
        const t = value.slice(lo, hi);
        if (t) {
          e.preventDefault();
          copyText(t);
        }
        return;
      }
      if (mod && k.toLowerCase() === 'x') {
        const t = value.slice(lo, hi);
        if (t) {
          e.preventDefault();
          copyText(t);
          deleteSel();
        }
        return;
      }
      if (mod && k.toLowerCase() === 'v') {
        // 这里**不能** preventDefault：粘贴这下的默认行为要留给浏览器，
        // 我们只把焦点先让给 sink —— 原生粘贴落在「当前焦点」上，正好是它。
        pastePending.current = true;
        const sink = sinkRef.current;
        if (sink) {
          sink.value = '';
          sink.focus();
        }
        window.clearTimeout(pasteTimer.current);
        pasteTimer.current = window.setTimeout(() => {
          // 剪贴板里没文本 / 没贴进来：把焦点收回来，别把框晾着
          if (pastePending.current) {
            pastePending.current = false;
            boxRef.current?.focus();
          }
        }, 400);
        return;
      }
      if (mod) return; // 其余组合键（Ctrl+K 等）不认，任其上行

      switch (k) {
        case 'ArrowLeft':
          e.preventDefault();
          move(-1, e.shiftKey);
          return;
        case 'ArrowRight':
          e.preventDefault();
          move(1, e.shiftKey);
          return;
        case 'ArrowUp':
        case 'Home':
          e.preventDefault();
          jump(0, e.shiftKey);
          return;
        case 'ArrowDown':
        case 'End':
          e.preventDefault();
          jump(length, e.shiftKey);
          return;
        case 'Backspace':
          e.preventDefault();
          if (lo !== hi) deleteSel();
          else if (sel.c > 0) commit(value.slice(0, sel.c - 1) + value.slice(sel.c), sel.c - 1);
          return;
        case 'Delete':
          e.preventDefault();
          if (lo !== hi) deleteSel();
          else if (sel.c < length) commit(value.slice(0, sel.c) + value.slice(sel.c + 1), sel.c);
          return;
        default:
          break;
      }

      // 可打印字符：与滤词同一把尺子 —— 中文、全角、标点、空格到这里被拒
      if (k.length === 1 && !e.altKey) {
        e.preventDefault();
        const ch = englishOnly(k);
        if (ch) insert(ch);
      }
    };

    /** 方向键左右移：带 shift = 拉选区（锚点不动）；无 shift 且有选区 = 收拢到对应一头。 */
    const move = (delta: number, shift: boolean) => {
      if (shift) {
        setSel((s) => ({ a: s.a, c: Math.max(0, Math.min(s.c + delta, length)) }));
        return;
      }
      if (lo !== hi) setSel({ a: delta < 0 ? lo : hi, c: delta < 0 ? lo : hi });
      else setSel({ a: sel.c + delta, c: sel.c + delta });
    };
    /** Home/End（单行框里 ↑↓ 也是它俩）：带 shift = 拉到这头。 */
    const jump = (to: number, shift: boolean) => {
      if (shift) setSel((s) => ({ a: s.a, c: to }));
      else setSel({ a: to, c: to });
    };

    const onMouseDown = (e: ReactMouseEvent<HTMLDivElement>) => {
      if (e.button !== 0) return;
      e.preventDefault(); // 原生选择关掉（user-select: none 兜底）；焦点手动给
      boxRef.current?.focus();
      const i = indexAt(e.clientX, e.clientY);
      if (e.shiftKey) setSel((s) => ({ a: s.a, c: i }));
      else setSel({ a: i, c: i });
      dragging.current = true;
    };

    useEffect(() => {
      const onMove = (e: MouseEvent) => {
        if (dragging.current) setSel((s) => ({ a: s.a, c: indexAt(e.clientX, e.clientY) }));
      };
      const onUp = () => {
        dragging.current = false;
      };
      window.addEventListener('mousemove', onMove);
      window.addEventListener('mouseup', onUp);
      return () => {
        window.removeEventListener('mousemove', onMove);
        window.removeEventListener('mouseup', onUp);
      };
      // 只挂一次：indexAt 只碰 ref（boxRef / latest），首轮闭包就够
    }, []);

    // 光标跟到视野里：文字比框长时把框的横向滚动带过去（照 input 的老样子）
    useLayoutEffect(() => {
      const box = boxRef.current;
      const caret = caretRef.current;
      if (!box || !caret || !focused) return;
      const c = caret.offsetLeft;
      if (c + caret.offsetWidth > box.scrollLeft + box.clientWidth) {
        box.scrollLeft = c + caret.offsetWidth - box.clientWidth + 2;
      } else if (c < box.scrollLeft) {
        box.scrollLeft = Math.max(0, c - 2);
      }
    }, [value, sel, focused]);

    // 窗口失焦时元素仍留着 :focus（Chromium），光标得手动收
    useEffect(() => {
      const onBlur = () => setFocused(false);
      const onFocus = () => setFocused(document.activeElement === boxRef.current);
      window.addEventListener('blur', onBlur);
      window.addEventListener('focus', onFocus);
      return () => {
        window.removeEventListener('blur', onBlur);
        window.removeEventListener('focus', onFocus);
      };
    }, []);

    /** 粘贴落盘：滤一遍、插到选区处、焦点还给框（paste / input 两条路共用）。 */
    const finishPaste = (raw: string) => {
      if (!pastePending.current) return;
      pastePending.current = false;
      window.clearTimeout(pasteTimer.current);
      const sink = sinkRef.current;
      if (sink) sink.value = '';
      const cleaned = englishOnly(raw);
      if (cleaned) {
        const { value: v, sel: s } = latest.current;
        const l = Math.min(s.a, s.c);
        const h = Math.max(s.a, s.c);
        const r = splice(v, l, h, cleaned);
        onChange(r.v);
        setSel({ a: r.caret, c: r.caret });
      }
      boxRef.current?.focus();
    };

    /** sink 接到原生粘贴：剪贴板正文直接从事件里拿（不需要读剪贴板权限）。 */
    const onSinkPaste = (e: ReactClipboardEvent<HTMLInputElement>) => {
      if (!pastePending.current) return;
      e.preventDefault(); // sink 不留字：正文只进我们的模型
      finishPaste(e.clipboardData.getData('text/plain') || e.clipboardData.getData('text'));
    };

    /** 没走 paste 事件（某些引擎只回灌值）时兜底：从 sink 的值里拿。 */
    const onSinkInput = () => {
      if (!pastePending.current) return;
      finishPaste(sinkRef.current?.value ?? '');
    };

    const caretAfterSel = sel.c >= hi;

    return (
      <div
        ref={boxRef}
        className={`ef-box${className ? ` ${className}` : ''}`}
        role="textbox"
        aria-multiline="false"
        aria-label={placeholder}
        tabIndex={0}
        data-focus={focused ? '1' : '0'}
        onKeyDown={handleKeyDown}
        onFocus={() => {
          setFocused(true);
          onFocus?.();
        }}
        onBlur={() => setFocused(false)}
        onMouseDown={onMouseDown}
        onDoubleClick={(e) => {
          e.preventDefault();
          setSel({ a: 0, c: length }); // 双击 = 整词（交易对名就是一个词）
        }}
        onContextMenu={(e) => e.preventDefault()} // 不在可编辑框上，原生菜单没有意义
      >
        <span data-part="pre">{value.slice(0, lo)}</span>
        {!caretAfterSel && <span key={`c${sel.c}`} ref={caretRef} className="ef-caret" />}
        <span data-part="sel" className={hi > lo ? 'ef-sel' : undefined}>
          {value.slice(lo, hi)}
        </span>
        {caretAfterSel && <span key={`c${sel.c}`} ref={caretRef} className="ef-caret" />}
        <span data-part="post">{value.slice(hi)}</span>
        {value === '' && <span className="ef-ph">{placeholder}</span>}
        {/* 粘贴走位用的 sink：不露面、不进 Tab 序，只借它接一次原生粘贴 */}
        <input
          ref={sinkRef}
          className="ef-sink"
          tabIndex={-1}
          aria-hidden="true"
          autoComplete="off"
          spellCheck={false}
          onPaste={onSinkPaste}
          onChange={onSinkInput}
        />
      </div>
    );
  },
);
