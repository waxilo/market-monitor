import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 行情源 Gate（https://api.gateio.ws）由 webview 直连：该域名对任意 Origin 回 ACAO:*，
// Tauri 里不需要代理，浏览器里跑 `npm run dev` 时同样直连。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
  },
});
