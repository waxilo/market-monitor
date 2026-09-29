import type { UpdateController } from '../hooks/useUpdate';

/**
 * 「安装包已就绪」提示条。
 *
 * 自动下载把「下载」这一步从用户手里拿走了，代价是**下载完成必须有人告诉他** ——
 * 否则包默默躺在内存里，用户永远停在旧版本上。所以这条提示是自动下载的配套件，
 * 不是可选装饰：它一消失，「自动下载」就等于没做。
 *
 * 位置钉在主窗右下角：不挡图表区，也不打断任何操作；只有「安装并重启」是动作，
 * 「稍后」按版本记在 localStorage（下一版必须重新提示）。
 */
export function UpdateReady({ controller }: { controller: UpdateController }) {
  const { readyVersion, install, dismissReady, error } = controller;

  return (
    <aside className="up-ready" role="status">
      <div className="up-ready-head">
        <span className="up-ready-dot" />
        <span className="up-ready-title">新版本已就绪</span>
      </div>
      <p className="up-ready-text">
        v{readyVersion} 已下载并通过签名校验
        <br />
        安装后会自动重启，用上新版本。
      </p>
      {error && <p className="up-error">{error}</p>}
      <div className="up-ready-actions">
        <button className="up-btn primary" onClick={() => void install()}>
          安装并重启
        </button>
        <button className="up-btn" onClick={dismissReady}>
          稍后
        </button>
      </div>
    </aside>
  );
}
