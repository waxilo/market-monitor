import { useEffect } from 'react';
import { MIRRORS } from '../lib/update';
import type { UpdateController } from '../hooks/useUpdate';

function formatSize(bytes: number): string {
  if (!bytes) return '大小未知';
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function formatDate(iso: string | null): string {
  return iso ? iso.slice(0, 10) : '';
}

/** 更新弹窗：加速站选择、检查、下载进度、安装与 Release 页兜底。 */
export function UpdatePanel({
  controller,
  onClose,
}: {
  controller: UpdateController;
  onClose: () => void;
}) {
  const {
    version,
    mirror,
    changeMirror,
    info,
    checking,
    checked,
    error,
    download,
    check,
    beginDownload,
    install,
    openPage,
  } = controller;

  const downloading = download.state === 'running';
  const percent = download.state === 'running' && download.progress >= 0
    ? Math.round(download.progress * 100)
    : null;

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div className="up-backdrop" onMouseDown={onClose}>
      <section className="up-panel" onMouseDown={(e) => e.stopPropagation()}>
        <header className="up-head">
          <span className="overline">应用更新</span>
          <span className="grow" />
          <button className="mini-x" title="关闭" onClick={onClose}>
            ×
          </button>
        </header>

        <div className="up-body">
          <div className="up-meta-row">
            <span className="up-label">当前版本</span>
            <span className="num">v{version || '—'}</span>
          </div>

          <div className="up-meta-row">
            <span className="up-label">加速站</span>
            <select
              className="up-select"
              value={mirror}
              disabled={downloading}
              onChange={(e) => changeMirror(e.target.value)}
            >
              {MIRRORS.map((item) => (
                <option key={item.key} value={item.key}>
                  {item.label}
                </option>
              ))}
            </select>
          </div>

          <p className="up-hint">
            加速站同时用于检查更新与下载；某一站不通时会自动回退到直连和其他站。
            开 VPN 时 GitHub 会按出口 IP 拒绝，检查失败就换一个加速站。
          </p>

          <div className="up-actions">
            <button
              className="up-btn"
              onClick={() => void check(false)}
              disabled={checking || downloading}
            >
              {checking ? '检查中…' : '检查更新'}
            </button>
            {info && download.state !== 'done' && (
              <button
                className="up-btn primary"
                onClick={() => void beginDownload()}
                disabled={downloading}
              >
                {download.state === 'failed' ? '重试下载' : '下载更新'}
              </button>
            )}
            {download.state === 'done' && (
              <button className="up-btn primary" onClick={() => void install()}>
                安装并重启
              </button>
            )}
          </div>

          {error && <p className="up-error">{error}</p>}
          {checked && !info && !error && <p className="up-ok">已是最新版本</p>}

          {download.state === 'running' && (
            <div className="up-progress">
              <div className="up-bar">
                <div
                  className="up-bar-fill"
                  style={{ width: percent == null ? '100%' : `${percent}%` }}
                  data-indeterminate={percent == null ? 'true' : 'false'}
                />
              </div>
              <span className="num up-progress-text">
                {percent == null
                  ? `下载中 ${formatSize(download.downloaded)}`
                  : `${percent}% · ${formatSize(download.downloaded)} / ${formatSize(download.total)}`}
              </span>
            </div>
          )}

          {download.state === 'failed' && <p className="up-error">{download.message}</p>}
          {download.state === 'done' && (
            <p className="up-ok">安装包已下载并通过签名校验，点「安装并重启」完成升级。</p>
          )}

          {info && (
            <div className="up-release">
              <div className="up-release-head">
                <span className="up-version">v{info.latestVersion}</span>
                <span className="up-release-meta">{formatDate(info.publishedAt)}</span>
              </div>
              <pre className="up-notes">{info.notes || '（该版本没有填写更新说明）'}</pre>
              <button className="up-link" onClick={() => void openPage()}>
                在浏览器中打开 Release 页面
              </button>
            </div>
          )}
        </div>
      </section>
    </div>
  );
}
