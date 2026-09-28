import { useEffect, useState } from 'react';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { IS_TAURI } from '../lib/tauri';

/** 与 `Window.startResizeDragging` 的入参一致（该类型未从 @tauri-apps/api 导出）。 */
type ResizeDirection =
  | 'East'
  | 'North'
  | 'NorthEast'
  | 'NorthWest'
  | 'South'
  | 'SouthEast'
  | 'SouthWest'
  | 'West';

const DIRECTIONS: ResizeDirection[] = [
  'North',
  'South',
  'East',
  'West',
  'NorthEast',
  'NorthWest',
  'SouthEast',
  'SouthWest',
];

/**
 * 自绘窗口控件：主窗去掉系统边框（tauri.conf.json 的 `decorations: false`），
 * 最小化 / 最大化 / 关闭都要自己实现。关闭仍走 Rust 的 CloseRequested → 收进托盘。
 *
 * 顶栏拖拽与双击最大化在 `lib/windowDrag`（按落点判定，不依赖注入脚本），
 * 这里只负责按钮与拉伸热区；拉伸热区必须标 `data-no-drag`，否则按住它会被当成搬窗口。
 */
export function WindowChrome() {
  const [maximized, setMaximized] = useState(false);

  useEffect(() => {
    if (!IS_TAURI) return;
    const win = getCurrentWindow();
    const sync = () => void win.isMaximized().then(setMaximized);
    sync();
    const unlisten = win.onResized(sync);
    return () => {
      void unlisten.then((off) => off());
    };
  }, []);

  function run(action: (win: ReturnType<typeof getCurrentWindow>) => void) {
    if (!IS_TAURI) return;
    action(getCurrentWindow());
  }

  return (
    <>
      <div className="winops">
        <button
          className="winbtn"
          title="最小化"
          onClick={() => run((win) => void win.minimize())}
        >
          –
        </button>
        <button
          className="winbtn"
          title={maximized ? '还原' : '最大化'}
          onClick={() => run((win) => void win.toggleMaximize())}
        >
          {maximized ? '❐' : '□'}
        </button>
        <button
          className="winbtn close"
          title="关闭（收进托盘，行情继续刷新）"
          onClick={() => run((win) => void win.close())}
        >
          ×
        </button>
      </div>

      {!maximized &&
        DIRECTIONS.map((direction) => (
          <div
            key={direction}
            className={`rz rz-${direction.toLowerCase()}`}
            data-no-drag
            onMouseDown={(e) => {
              if (e.button !== 0) return;
              e.preventDefault();
              run((win) => void win.startResizeDragging(direction));
            }}
          />
        ))}
    </>
  );
}
