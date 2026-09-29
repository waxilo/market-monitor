/**
 * 从母版 SVG 生成桌面端全平台图标。
 *
 *   node scripts/gen-icons.mjs
 *
 * 为什么要包一层：`tauri icon` 会**顺带**在 icons/ 下生成 android/ 与 ios/ 两份产物，
 * 而本仓库的 Android 端是独立 Gradle 工程（android/），图标是矢量重绘的
 * （android/app/src/main/res/drawable/ic_launcher_foreground.xml），
 * 这两份 PNG 永远不会被用到 —— 留着只会让人以为它们才是生效的那份。
 * 所以生成完就地清掉。
 */
import { execFileSync } from 'node:child_process';
import { existsSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const SOURCE = 'app-icon.svg';
const STALE = ['src-tauri/icons/android', 'src-tauri/icons/ios'];

execFileSync('npx', ['tauri', 'icon', SOURCE], { cwd: root, stdio: 'inherit', shell: true });

for (const dir of STALE) {
  const path = join(root, dir);
  if (existsSync(path)) {
    rmSync(path, { recursive: true, force: true });
    console.log(`清理未使用的产物：${dir}`);
  }
}

console.log(`✅ 图标已从 ${SOURCE} 重新生成（含 ico / icns / 各尺寸 png / Windows Store 方块图）`);
