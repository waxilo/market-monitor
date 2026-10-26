import { createRoot } from 'react-dom/client';
import App from './App';
import { MiniApp } from './MiniApp';
import { installAltGuard } from './lib/altGuard';
import './styles/theme.css';

// 不用 StrictMode：图表手动管理 canvas，dev 双挂载会反复 init/dispose
// 同一个前端承载两种窗口：主窗走 App，悬浮窗（src-tauri 用 #/mini 开）走 MiniApp

// Alt 的默认动作两个窗口都不要（按下去输入会被宿主截走，见 lib/altGuard）
installAltGuard();

const isMini = window.location.hash.startsWith('#/mini');
createRoot(document.getElementById('root')!).render(isMini ? <MiniApp /> : <App />);
