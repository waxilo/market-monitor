import { useEffect, useRef, useState } from 'react';
import { LINE_NOTE_MAX, normalizeLineNote } from '../lib/lineNote';

interface Props {
  /** 抬头那行字（哪条线在改备注），由调用方拼好。 */
  title: string;
  /** 现有备注（没有就给空串）—— 弹窗打开即可直接改。 */
  value: string;
  /** 保存：空串 = 删掉备注（与 `setLineNote` 同口径，不在这里再判断一次）。 */
  onSave: (note: string) => void;
  onClose: () => void;
}

/**
 * 画线备注的输入框。
 *
 * 为什么不用 `window.prompt`：Tauri 的 WebView2 里 prompt 会被当成脚本弹窗拦掉
 * （截图/自动化里更是完全拿不到），而且它没有字数、没有「删掉备注」，一个都补不进去。
 *
 * 为什么是**一行**语义（Enter 保存、Shift+Enter 也不换行）：备注画在线上是 `fillText`
 * 一行短句，`normalizeLineNote` 会把所有空白压成一个空格 —— 允许换行只会让用户以为
 * 「换行能保住」，存进去却是空格。要写多段文字，那是笔记工具的事，不是线的备注。
 */
export function LineNoteDialog({ title, value, onSave, onClose }: Props) {
  const [text, setText] = useState(value);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  /** 归一化之后的样子 —— 保存的就是这串字，字数也按它算（贴一堆空格进来不该显示 60/60）。 */
  const cleaned = normalizeLineNote(text);
  const hasOld = normalizeLineNote(value) !== '';

  useEffect(() => {
    const el = inputRef.current;
    if (!el) return;
    el.focus();
    el.select();
  }, []);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key !== 'Escape') return;
      // 画布的 Esc（退出「画了一半的直线」）挂在 document 上；备注框开着时那一下
      // 应该是关弹窗，别顺着冒到画布那边去。
      e.stopPropagation();
      onClose();
    }
    // capture 阶段拦：比画布的 document 监听更早拿到
    document.addEventListener('keydown', onKey, true);
    return () => document.removeEventListener('keydown', onKey, true);
  }, [onClose]);

  function submit() {
    onSave(cleaned);
    onClose();
  }

  return (
    <div className="up-backdrop" onMouseDown={onClose}>
      <section
        className="up-panel note-panel"
        onMouseDown={(e) => e.stopPropagation()}
        role="dialog"
        aria-label="画线备注"
      >
        <header className="up-head">
          <span className="overline">备注 · {title}</span>
          <span className="grow" />
          <button className="panel-close" title="关闭（Esc）" onClick={onClose}>
            ×
          </button>
        </header>

        <div className="up-body">
          <p className="up-hint">
            这条线的用处，几个字就够（例：缺口上沿 / 前高 / 跌破这里就废）。会画在这条线旁边，
            清空后保存就是删掉备注。
          </p>
          <textarea
            ref={inputRef}
            className="note-input"
            rows={2}
            value={text}
            maxLength={LINE_NOTE_MAX}
            spellCheck={false}
            placeholder="给这条线写个备注…"
            aria-label="备注内容"
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault(); // 备用：一行语义，Enter 就是保存
                submit();
              }
            }}
          />
          <div className="note-foot">
            <span className={`note-count${cleaned.length >= LINE_NOTE_MAX ? ' full' : ''}`}>
              {cleaned.length} / {LINE_NOTE_MAX}
            </span>
            {hasOld && (
              <button
                className="up-btn"
                title="把这条备注删掉（线还在）"
                onClick={() => {
                  onSave('');
                  onClose();
                }}
              >
                删掉备注
              </button>
            )}
            <button className="up-btn" onClick={onClose}>
              取消
            </button>
            <button className="up-btn primary" onClick={submit}>
              保存
            </button>
          </div>
        </div>
      </section>
    </div>
  );
}
