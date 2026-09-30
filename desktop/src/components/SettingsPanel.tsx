import { useEffect, useState } from 'react';
import {
  chordOfEvent,
  chordRejection,
  formatChord,
  isDefaultChord,
  readChord,
  resetChord,
  SHORTCUTS,
  writeChord,
  type Chord,
  type ShortcutId,
} from '../lib/shortcuts';
import {
  applyGlobalShortcut,
  clearGlobalShortcut,
  readGlobalShortcutStatus,
  type GlobalKeyStatus,
} from '../lib/globalKey';
import { UpdateSection } from './UpdateSection';
import type { UpdateController } from '../hooks/useUpdate';

type Theme = 'dark' | 'light';

interface Props {
  theme: Theme;
  onTheme: (next: Theme) => void;
  update: UpdateController;
  onClose: () => void;
}

/**
 * 设置：外观、快捷键、更新。
 *
 * 这三件事的共同点是**偶尔改一次**，所以从顶栏收进来，顶栏只留一个「设置」按钮
 * （它同时是更新的报信口：圆点表示正在下 / 下好了，见 App 的 `.up-badge`）。
 *
 * 悬浮窗显示哪几条**不在这里配** —— 它改的是「这会儿盯着看什么」，是日常操作不是设置，
 * 入口在顶栏，弹窗见 `MiniListPanel`。
 *
 * 快捷键的录制规则：点「修改」先把系统级那条摘掉，然后**捕获阶段**的 keydown 把后续
 * 一切按键吃掉 —— 不摘的话这一下按键被系统吃掉、网页根本收不到（见 lib/globalKey）。
 * 录制期间按 Esc 是取消而不是关面板，面板自己的 Esc 处理与外面那条 `useShortcut`
 * 都不会抢先（录制那头 `preventDefault` 过）。
 * 只按修饰键不算数（`chordOfEvent` 返 null），按到不合规的组合会就地提示、
 * 但**留在录制态**让用户接着按，不用重新点一次「修改」。
 * 退出录制（成功/取消/面板被关）一律按 localStorage 里现在那把重新挂回去。
 */
export function SettingsPanel({ theme, onTheme, update, onClose }: Props) {
  /** 正在录制的快捷键（null = 没在录）。 */
  const [recording, setRecording] = useState<ShortcutId | null>(null);
  const [error, setError] = useState<string | null>(null);
  /** 系统级快捷键现在的状态（面板打开时读一次，之后每次摘/挂都跟着更新）。 */
  const [global, setGlobal] = useState<GlobalKeyStatus | null>(null);
  const [chords, setChords] = useState<Record<string, string>>(() =>
    Object.fromEntries(SHORTCUTS.map((def) => [def.id, readChord(def.id)])),
  );

  useEffect(() => {
    void readGlobalShortcutStatus().then(setGlobal);
  }, []);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      // 录制中按 Esc 是「取消录制」（由下面那个捕获阶段监听处理），别顺手把面板关了
      if (e.key === 'Escape' && !recording) onClose();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose, recording]);

  useEffect(() => {
    if (!recording) return;
    const id = recording;
    void clearGlobalShortcut().then(setGlobal);
    function onKeyDown(e: KeyboardEvent) {
      // 捕获阶段 + 全部吃掉：录制期间这一下按键不能顺便触发搜索/切窗口/关面板
      e.preventDefault();
      e.stopPropagation();
      if (e.key === 'Escape') {
        setRecording(null);
        setError(null);
        return;
      }
      const chord: Chord | null = chordOfEvent(e);
      if (!chord) return; // 只按了修饰键，接着等主键
      const bad = chordRejection(chord);
      if (bad) {
        setError(bad);
        return;
      }
      writeChord(id, chord);
      setChords((prev) => ({ ...prev, [id]: readChord(id) }));
      setRecording(null);
      setError(null);
    }
    window.addEventListener('keydown', onKeyDown, true);
    return () => {
      window.removeEventListener('keydown', onKeyDown, true);
      // 不管录成功还是取消，都按 localStorage 里现在那把重新挂回去
      // —— 成功那条 `writeChord` 已经先写进去了，所以这一句同时是「换成新键」
      void applyGlobalShortcut(id).then(setGlobal);
    };
  }, [recording]);

  return (
    <div className="up-backdrop" onMouseDown={onClose}>
      <section className="up-panel up-wide" onMouseDown={(e) => e.stopPropagation()}>
        <header className="up-head">
          <span className="overline">设置</span>
          <span className="grow" />
          <button className="panel-close" title="关闭" onClick={onClose}>
            ×
          </button>
        </header>

        <div className="up-body">
          <div className="set-block">
            <div className="up-label">外观</div>
            <div className="set-row">
              <div className="set-name">
                主题
                <span className="set-sub">深色适合盯盘，浅色适合截图/投屏</span>
              </div>
              <div className="set-seg" role="radiogroup" aria-label="主题">
                <button
                  type="button"
                  role="radio"
                  aria-checked={theme === 'dark'}
                  className={theme === 'dark' ? 'on' : ''}
                  onClick={() => onTheme('dark')}
                >
                  深色
                </button>
                <button
                  type="button"
                  role="radio"
                  aria-checked={theme === 'light'}
                  className={theme === 'light' ? 'on' : ''}
                  onClick={() => onTheme('light')}
                >
                  浅色
                </button>
              </div>
            </div>
          </div>

          <div className="set-block">
            <div className="up-label">快捷键</div>
            {SHORTCUTS.map((def) => {
              const live = recording === def.id;
              const state = globalHint(global);
              return (
                <div className="set-row" key={def.id}>
                  <div className="set-name">
                    {def.label}
                    <span className="set-sub">{live ? '按下新的组合键（Esc 取消）' : def.hint}</span>
                    {!live && state && (
                      <span className={`set-sub${global?.error ? ' bad' : ''}`}>{state}</span>
                    )}
                  </div>
                  <kbd className={`set-key${live ? ' on' : ''}`}>
                    {live ? '按键…' : formatChord(chords[def.id])}
                  </kbd>
                  <button
                    className="up-btn"
                    onClick={() => {
                      setError(null);
                      setRecording(live ? null : def.id);
                    }}
                  >
                    {live ? '取消' : '修改'}
                  </button>
                  <button
                    className="up-btn"
                    disabled={isDefaultChord(def.id, chords[def.id] ?? '')}
                    title="恢复成默认组合键"
                    onClick={() => {
                      resetChord(def.id);
                      setChords((prev) => ({ ...prev, [def.id]: readChord(def.id) }));
                      void applyGlobalShortcut(def.id).then(setGlobal);
                    }}
                  >
                    恢复默认
                  </button>
                </div>
              );
            })}
            {error && <p className="up-error">{error}</p>}
          </div>

          <div className="set-block">
            <div className="up-label">更新</div>
            <UpdateSection controller={update} />
          </div>
        </div>
      </section>
    </div>
  );
}

/**
 * 系统级那把键现在的状态（一句话，没得说就空串）。
 *
 * 值得说：注册是会失败的（那把键被别的程序占了），而失败之后快捷键并不会没反应 ——
 * 它退回只在窗口里生效。这两种情况看得出区别，用户才不会以为功能坏了。
 */
function globalHint(status: GlobalKeyStatus | null): string {
  if (!status) return '';
  if (status.registered) return `系统级已生效：${status.accelerator}`;
  if (status.error) return `系统级注册失败：${status.error}（现在只在窗口里生效）`;
  return '现在只在窗口里生效';
}
