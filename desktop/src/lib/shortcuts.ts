/**
 * 键盘快捷键：一份「和弦（chord）」模型 + 落盘 + 注册表。
 *
 * **为什么要有这个文件**：快捷键的判定散在事件回调里就会长成三份互不相同的实现
 * （录制时怎么认、按下时怎么比、面板上怎么显示），而它们必须**严格同源** ——
 * 录制出来的东西按下时不生效，是最难查的一类问题。这里只有一处定义：
 * `Chord`（四个修饰键 + 一个归一化的主键），其余全是围绕它的纯函数。
 *
 * 落盘在 localStorage（`mm.shortcut.<id>`，值是 `serializeChord` 的产物，如 `alt+m`）。
 * 主窗与悬浮窗是**两个 JS 实例**，所以谁都不缓存它：按下时现读（`readChord`），
 * 在设置面板里改完，另一个窗口下一次按键就生效，不需要任何同步。
 *
 * 键名用 `KeyboardEvent.code` 归一化（`KeyM` → `m`、`Digit1` → `1`、`F5` → `f5`），
 * 不用 `key`：`key` 会随修饰键变（Alt+M 在部分布局上给出别的字符），
 * 而 `code` 是物理位置，按「M 键」就是 M 键。
 */

export type ShortcutId = 'toggleWindow';

export interface Chord {
  ctrl: boolean;
  alt: boolean;
  shift: boolean;
  meta: boolean;
  /** 归一化后的主键：单字母/数字小写（`m`、`1`）、`f5`、`space`、`up`… */
  key: string;
}

export interface ShortcutDef {
  id: ShortcutId;
  /** 面板上的名字。 */
  label: string;
  /** 面板上的说明（一行）。 */
  hint: string;
  /** 默认和弦（`serializeChord` 的产物）。 */
  defaultChord: string;
}

/**
 * 注册表。**目前只有一条** —— 但面板是按这张表渲染的，加第二条不用碰组件。
 * 默认 `Alt+M`：`Alt` 组合不会被浏览器/输入框吃掉，`M` 又不与任何既有键冲突
 * （`Ctrl+K` 是搜索）。
 */
export const SHORTCUTS: readonly ShortcutDef[] = [
  {
    id: 'toggleWindow',
    label: '主窗 ⇄ 悬浮窗',
    hint: '在哪个窗口里按都行，按一下就换成另一个窗口',
    defaultChord: 'alt+m',
  },
];

const STORAGE_PREFIX = 'mm.shortcut.';

const MODIFIER_KEYS = ['ctrl', 'alt', 'shift', 'meta'] as const;
const MODIFIER_LABEL: Record<(typeof MODIFIER_KEYS)[number], string> = {
  ctrl: 'Ctrl',
  alt: 'Alt',
  shift: 'Shift',
  meta: 'Win',
};

/** 主键的显示名：能给符号给符号，其余大写。 */
const KEY_LABEL: Record<string, string> = {
  up: '↑',
  down: '↓',
  left: '←',
  right: '→',
  space: 'Space',
  enter: 'Enter',
  tab: 'Tab',
  escape: 'Esc',
  backspace: 'Backspace',
  delete: 'Delete',
  home: 'Home',
  end: 'End',
  pageup: 'PageUp',
  pagedown: 'PageDown',
};

/** `code` 里不能当主键的那些（纯修饰键 / 保留键）。 */
const IGNORED_CODES = new Set([
  'ControlLeft',
  'ControlRight',
  'AltLeft',
  'AltRight',
  'ShiftLeft',
  'ShiftRight',
  'MetaLeft',
  'MetaRight',
  'CapsLock',
  'ContextMenu',
  'Escape', // Esc 是「取消录制」，不当主键
]);

const NAMED_CODES: Record<string, string> = {
  Space: 'space',
  Enter: 'enter',
  NumpadEnter: 'enter',
  Tab: 'tab',
  Backspace: 'backspace',
  Delete: 'delete',
  Home: 'home',
  End: 'end',
  PageUp: 'pageup',
  PageDown: 'pagedown',
  ArrowUp: 'up',
  ArrowDown: 'down',
  ArrowLeft: 'left',
  ArrowRight: 'right',
};

export function defOf(id: ShortcutId): ShortcutDef {
  const def = SHORTCUTS.find((s) => s.id === id);
  if (!def) throw new Error(`未知快捷键 ${id}`);
  return def;
}

/* ── 和弦 ⇄ 字符串 ────────────────────────────────────────── */

export function serializeChord(c: Chord): string {
  const parts: string[] = MODIFIER_KEYS.filter((m) => c[m]).map((m) => (m === 'meta' ? 'win' : m));
  parts.push(c.key);
  return parts.join('+');
}

/**
 * 解析落盘值。**任何看不懂的值都当没有**（返回 null，由调用方退回默认）——
 * 老版本留下的、手改坏的、或哪天换了表示法，都不该让界面崩掉或让快捷键静默失效。
 */
export function parseChord(raw: string | null | undefined): Chord | null {
  if (!raw) return null;
  const parts = raw.toLowerCase().split('+').filter(Boolean);
  if (parts.length === 0) return null;
  const chord: Chord = { ctrl: false, alt: false, shift: false, meta: false, key: '' };
  for (const part of parts) {
    if (part === 'ctrl' || part === 'control') chord.ctrl = true;
    else if (part === 'alt' || part === 'option') chord.alt = true;
    else if (part === 'shift') chord.shift = true;
    else if (part === 'win' || part === 'meta' || part === 'cmd' || part === 'super') chord.meta = true;
    else if (!chord.key) chord.key = part;
    else return null; // 两个主键 —— 不是我们写出来的东西
  }
  if (!chord.key) return null;
  return usableChord(chord) ? chord : null;
}

/**
 * 可用性：必须带一个**非 Shift** 的修饰键。
 *
 * 光按 `M` 就能切窗口是不行的（输入框里打不了字）；`Shift+M` 同理（打不出大写）。
 * 这条规则同时被**录制**与**读取**使用 —— 于是「手工写进 localStorage 的非法值」
 * 与「录制时按错」走同一条路：退回默认。
 */
export function usableChord(c: Chord): boolean {
  return (c.ctrl || c.alt || c.meta) && c.key !== '';
}

/** 录制时的拒绝理由（能用则 null）。 */
export function chordRejection(c: Chord): string | null {
  if (c.ctrl || c.alt || c.meta) return null;
  return c.shift ? '要带上 Ctrl / Alt / Win 其中之一（只有 Shift 会打不出大写）' : '要带上 Ctrl / Alt / Win 其中之一';
}

export function formatChord(raw: string | null | undefined): string {
  const c = parseChord(raw);
  if (!c) return '未设置';
  const parts = MODIFIER_KEYS.filter((m) => c[m]).map((m) => MODIFIER_LABEL[m]);
  parts.push(KEY_LABEL[c.key] ?? (c.key.length === 1 ? c.key.toUpperCase() : c.key));
  return parts.join(' + ');
}

/* ── 事件 ⇄ 和弦 ─────────────────────────────────────────── */

/** 事件的主键名（归一化）；纯修饰键返回 null。 */
export function keyNameOf(e: KeyboardEvent): string | null {
  const code = e.code || '';
  if (code && !IGNORED_CODES.has(code)) {
    const letter = /^Key([A-Z])$/.exec(code);
    if (letter) return letter[1]!.toLowerCase();
    const digit = /^(?:Digit|Numpad)([0-9])$/.exec(code);
    if (digit) return digit[1]!;
    const fn = /^F([0-9]{1,2})$/.exec(code);
    if (fn) return `f${fn[1]}`;
    if (NAMED_CODES[code]) return NAMED_CODES[code];
    // 其余（符号键、国际布局上的 extra 键）直接用 code，至少是稳定且可比的
    if (/^[A-Za-z0-9]+$/.test(code)) return code.toLowerCase();
  }
  // 兜底：合成事件（测试脚本）可能不带 code，只能看 key
  const key = (e.key || '').toLowerCase();
  if (!key || key === 'unidentified') return null;
  if (['shift', 'control', 'alt', 'meta', 'capslock', 'os', 'escape'].includes(key)) return null;
  if (key === ' ') return 'space';
  return key;
}

export function chordOfEvent(e: KeyboardEvent): Chord | null {
  const key = keyNameOf(e);
  if (!key) return null;
  return { ctrl: e.ctrlKey, alt: e.altKey, shift: e.shiftKey, meta: e.metaKey, key };
}

/**
 * 事件是否命中某个和弦。修饰键要求**完全一致** —— 否则 `Alt+M` 会在
 * `Ctrl+Alt+M`（或 `Alt+Shift+M`）时也触发，而那几个组合通常是别的东西在用。
 */
export function matchesChord(e: KeyboardEvent, raw: string | null | undefined): boolean {
  const want = parseChord(raw);
  if (!want) return false;
  const got = chordOfEvent(e);
  if (!got) return false;
  return (
    got.key === want.key &&
    got.ctrl === want.ctrl &&
    got.alt === want.alt &&
    got.shift === want.shift &&
    got.meta === want.meta
  );
}

/* ── 落盘 ───────────────────────────────────────────────── */

export function readChord(id: ShortcutId): string {
  // 认不出 ⇒ 用默认（不写回：坏值留着不动，下次仍走这条兜底，也没有副作用）。
  // 认得出的走一遍 `serializeChord` 规范化 —— 手工写成 `Ctrl+M` / `ALT+M` 也照样生效。
  const c = parseChord(safeGet(STORAGE_PREFIX + id));
  return c ? serializeChord(c) : defOf(id).defaultChord;
}

export function writeChord(id: ShortcutId, chord: Chord): void {
  safeSet(STORAGE_PREFIX + id, serializeChord(chord));
}

/** 恢复默认（直接删掉落盘值，`readChord` 自然给默认）。 */
export function resetChord(id: ShortcutId): void {
  safeRemove(STORAGE_PREFIX + id);
}

export function isDefaultChord(id: ShortcutId, raw: string): boolean {
  return raw.toLowerCase() === defOf(id).defaultChord;
}

function safeGet(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function safeSet(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* 存不进去也不能让快捷键用不了 */
  }
}

function safeRemove(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    /* 同上 */
  }
}
