import { useCallback, useEffect, useRef, useState } from 'react';
import { listen } from '@tauri-apps/api/event';
import { IS_TAURI } from '../lib/tauri';
import {
  CHECK_INTERVAL_MS,
  checkUpdate,
  fetchAppVersion,
  fetchDownloadState,
  installUpdate,
  markChecked,
  openUrl,
  readLastCheck,
  readMirror,
  startDownload,
  writeMirror,
  type DownloadState,
  type UpdateInfo,
} from '../lib/update';

/**
 * 更新中心的前端控制器。
 *
 * 状态放在 App 级而不是更新弹窗里：弹窗关掉再打开（甚至主窗收进托盘）都得看到
 * 同一份「检查结果 / 下载进度」；下载与校验本身在 Rust 侧跑，与界面无生命周期耦合。
 */
export function useUpdate() {
  const [version, setVersion] = useState('');
  const [mirror, setMirror] = useState(readMirror);
  const [info, setInfo] = useState<UpdateInfo | null>(null);
  const [checking, setChecking] = useState(false);
  const [checked, setChecked] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [download, setDownload] = useState<DownloadState>({ state: 'idle' });

  useEffect(() => {
    if (IS_TAURI) void fetchAppVersion().then(setVersion);
  }, []);

  useEffect(() => {
    if (!IS_TAURI) return;
    void fetchDownloadState().then(setDownload);
    const unlisten = listen<DownloadState>('update-download', (event) => setDownload(event.payload));
    return () => {
      void unlisten.then((off) => off());
    };
  }, []);

  const check = useCallback(
    async (silent: boolean) => {
      if (!IS_TAURI) {
        if (!silent) setError('浏览器预览不检查更新');
        return;
      }
      if (!silent) {
        setError(null);
        setInfo(null);
        setChecked(false);
        setDownload({ state: 'idle' });
      }
      setChecking(true);
      try {
        setInfo(await checkUpdate(mirror));
        setChecked(true);
        setError(null);
        markChecked();
      } catch (e) {
        // 静默检查不打扰用户，但失败不当成「已是最新」——check 结果保持未知
        if (!silent) setError(String(e));
      } finally {
        setChecking(false);
      }
    },
    [mirror],
  );

  const startupChecked = useRef(false);
  useEffect(() => {
    if (!IS_TAURI || startupChecked.current) return;
    startupChecked.current = true;
    if (Date.now() - readLastCheck() < CHECK_INTERVAL_MS) return;
    void check(true);
  }, [check]);

  const beginDownload = useCallback(async () => {
    setError(null);
    try {
      await startDownload(mirror);
    } catch (e) {
      setError(String(e));
    }
  }, [mirror]);

  const install = useCallback(async () => {
    setError(null);
    try {
      await installUpdate();
    } catch (e) {
      setError(String(e));
    }
  }, []);

  const openPage = useCallback(async () => {
    if (!info) return;
    try {
      await openUrl(info.pageUrl);
    } catch (e) {
      setError(String(e));
    }
  }, [info]);

  const changeMirror = useCallback((key: string) => {
    setMirror(key);
    writeMirror(key);
    // 换了源，上一次的检查结果就不作数：下载地址与校验值都可能不同
    setInfo(null);
    setChecked(false);
    setError(null);
    setDownload((prev) => (prev.state === 'running' ? prev : { state: 'idle' }));
  }, []);

  return {
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
  };
}

export type UpdateController = ReturnType<typeof useUpdate>;
