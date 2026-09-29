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
  readReadyDismissed,
  startDownload,
  writeMirror,
  writeReadyDismissed,
  type DownloadState,
  type UpdateInfo,
} from '../lib/update';

/**
 * 更新中心的前端控制器。
 *
 * 状态放在 App 级而不是更新弹窗里：弹窗关掉再打开（甚至主窗收进托盘）都得看到
 * 同一份「检查结果 / 下载进度」；下载与校验本身在 Rust 侧跑，与界面无生命周期耦合。
 *
 * **自动下载**：一查到新版本就立刻在后台开下，不用用户点「下载更新」——
 * 下载与验签都在 Rust 侧，主窗收进托盘也照样跑完。代价是下载这件事变得**没有声音**，
 * 所以完成时必须有人提示（`ready` → 主窗右下角那条提示条），否则包默默躺在内存里，
 * 用户永远停在旧版本上。
 */
export function useUpdate() {
  const [version, setVersion] = useState('');
  const [mirror, setMirror] = useState(readMirror);
  const [info, setInfo] = useState<UpdateInfo | null>(null);
  const [checking, setChecking] = useState(false);
  const [checked, setChecked] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [download, setDownload] = useState<DownloadState>({ state: 'idle' });
  /** 本轮下载是不是自动发起的 —— 只影响文案，不影响行为。 */
  const [auto, setAuto] = useState(false);
  const [dismissed, setDismissed] = useState(readReadyDismissed);

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
      }
      setChecking(true);
      try {
        setInfo(await checkUpdate(mirror));
        setChecked(true);
        setError(null);
        markChecked();
        // ⚠️ 下载状态是**回读**的，不是猜的：同一个包重复检查时 Rust 会保留已下好的字节
        // （见 `update.rs::check_effect`）。前端要是自己重置成 idle，按钮就退回「下载更新」，
        // 点下去 Rust 只回一个 no-op —— 界面从此卡在「没下过」的错误认知里。
        setDownload(await fetchDownloadState());
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

  /** 已经为哪个版本自动开过一次下载（同一版本不重复触发）。 */
  const autoStarted = useRef<string | null>(null);
  useEffect(() => {
    if (!IS_TAURI || !info) return;
    // 只在「还没开始下」时才自动开：正在下 / 已下好 / 上次失败等用户重试，都不该被覆盖。
    if (download.state !== 'idle') return;
    if (autoStarted.current === info.latestVersion) return;
    autoStarted.current = info.latestVersion;
    setAuto(true);
    void startDownload(mirror).catch((e) => {
      // 自动下载失败**不出声**：面板里有失败态和「重试下载」，启动时被一个红字打断看盘
      // 比晚装一版烦人得多。这里只把状态落成 failed，让按钮变成「重试下载」。
      setDownload({ state: 'failed', message: String(e) });
    });
  }, [info, mirror, download.state]);

  const beginDownload = useCallback(async () => {
    setError(null);
    setAuto(false); // 用户自己点的，文案就别再说是自动的
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
    // 换了源，上一次的检查结果就不作数（manifest 都可能不是同一份），
    // 但**已下好的包不动**：加速站只决定「从哪下」，不改变包的身份
    // —— Rust 侧按 版本 + 下载地址 + 签名 认包，换站不会被认成另一个包。
    setInfo(null);
    setChecked(false);
    setError(null);
  }, []);

  /** 已下载并通过签名校验、可以装的那个版本号（没有就是空串）。 */
  const readyVersion = download.state === 'done' && info ? info.latestVersion : '';
  const ready = readyVersion !== '' && readyVersion !== dismissed;

  const dismissReady = useCallback(() => {
    if (!readyVersion) return;
    writeReadyDismissed(readyVersion);
    setDismissed(readyVersion);
  }, [readyVersion]);

  return {
    version,
    mirror,
    changeMirror,
    info,
    checking,
    checked,
    error,
    download,
    auto,
    readyVersion,
    ready,
    dismissReady,
    check,
    beginDownload,
    install,
    openPage,
  };
}

export type UpdateController = ReturnType<typeof useUpdate>;
