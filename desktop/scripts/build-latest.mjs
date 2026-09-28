#!/usr/bin/env node
// 生成 Tauri v2 updater 所需的 latest.json（v2 的 tauri build 只产出 .sig，不再自动生成 manifest）。
//
// 用法：node desktop/scripts/build-latest.mjs <version> <releaseTag> <dir> <repo>
//   例：node desktop/scripts/build-latest.mjs 0.1.1 desktop-latest desktop/dist waxilo/market-monitor
//
// 扫描 <dir> 下各平台的 `.sig` 文件，按文件名推断平台并填进 `platforms`，
// 产物 URL 指向 <repo> 固定 tag Release 上的同名文件，输出 <dir>/latest.json。

import fs from "node:fs";
import path from "node:path";

const [version, tag, dir, repo] = process.argv.slice(2);
if (!version || !tag || !dir || !repo) {
  console.error("用法: node desktop/scripts/build-latest.mjs <version> <releaseTag> <dir> <repo>");
  process.exit(1);
}

// 每种 .sig 对应的平台 key 与产物：本仓库只发 macOS arm64（aarch64）与 Windows x64，
// 所以每个签名只映射一个平台 key，不做 ai-assistant 那种「universal 包映射两个 key」。
const RULES = [
  { sig: ".app.tar.gz.sig", keys: ["darwin-aarch64"] },
  { sig: ".exe.sig", keys: ["windows-x86_64"] },
];

const platforms = {};
for (const f of fs.readdirSync(dir)) {
  const full = path.join(dir, f);
  if (!fs.statSync(full).isFile()) continue;

  const rule = RULES.find((r) => f.endsWith(r.sig));
  if (!rule) continue;

  const artifact = f.slice(0, -".sig".length);
  const signature = fs.readFileSync(full, "utf8").trim();
  if (!signature) {
    console.error(`签名文件为空: ${f}`);
    process.exit(1);
  }
  const url = `https://github.com/${repo}/releases/download/${tag}/${encodeURIComponent(artifact)}`;
  for (const key of rule.keys) {
    if (platforms[key]) {
      console.error(`平台 ${key} 出现多个候选签名（${platforms[key].file} 与 ${f}），产物名有歧义`);
      process.exit(1);
    }
    platforms[key] = { file: f, signature, url };
  }
}

const keys = Object.keys(platforms);
// 两个平台都必须在：漏了一个平台时，那个平台的应用会在 check 时报 TargetsNotFound，
// 而不是「已是最新」—— 宁可发版这一步就红，也别让用户端看到莫名其妙的报错。
if (keys.length !== RULES.length) {
  console.error(`只找到 ${keys.length}/${RULES.length} 个平台的签名（${keys.join(", ") || "无"}），不完整的 manifest 会让缺失平台的应用检查更新失败`);
  process.exit(1);
}

for (const key of keys) delete platforms[key].file;

const manifest = {
  version,
  notes: "",
  pub_date: new Date().toISOString(),
  platforms,
};

const out = path.join(dir, "latest.json");
fs.writeFileSync(out, JSON.stringify(manifest, null, 2) + "\n");
console.log(`已生成 ${out}，包含平台: ${keys.join(", ")}`);
