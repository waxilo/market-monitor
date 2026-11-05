import { useEffect, useState } from 'react';
import { DIRECT_CONNECTION_EVENT, readDirectConnection } from '../lib/netPrefs';

/**
 * 当前是否强制直连。设置页切换后同窗口立刻生效；悬浮窗靠 storage 事件跟上
 * （两个 JS 实例各自的 localStorage 缓存不会自动同步）。
 */
export function useDirectConnection(): boolean {
  const [direct, setDirect] = useState(readDirectConnection);

  useEffect(() => {
    const sync = () => setDirect(readDirectConnection());
    window.addEventListener(DIRECT_CONNECTION_EVENT, sync);
    window.addEventListener('storage', sync);
    return () => {
      window.removeEventListener(DIRECT_CONNECTION_EVENT, sync);
      window.removeEventListener('storage', sync);
    };
  }, []);

  return direct;
}
