#!/usr/bin/env bash
#
# 把产物发布到**固定 tag 的通道 Release**：已经存在就只换里面的产物，不存在才建。
#
# 为什么不「删掉再建」：GitHub 的 Release 与 tag 绑定，`gh release delete` + `create`
# 每次都会产生一个新的 Release（id、创建时间全变），发布列表里于是堆成一串。
# 而这两个通道对用户永远是**同一个入口** —— 桌面端读它的 `latest.json`，
# Android 读它里面的 APK 产物。所以这里原地换产物，Release 本身不动：
#
#   1. 通道不存在 → 建（只在第一次发版时）
#   2. 清掉上一次的全部产物 —— 必须清：产物名里带版本号
#      （`market-monitor_0.1.1_windows.exe` → 下一版名字就变了），
#      `--clobber` 只覆盖同名文件，不清就会新旧并存，用户可能下到过期包
#   3. 传新产物、改写说明
#
# 用法: publish-channel.sh <tag> <title> <notesFile> <distDir> <repo>
#   例: bash publish-channel.sh desktop-latest "桌面端更新通道" /tmp/notes.md dist waxilo/market-monitor

set -euo pipefail

if [ "$#" -ne 5 ]; then
  echo "用法: $0 <tag> <title> <notesFile> <distDir> <repo>" >&2
  exit 2
fi

TAG="$1"
TITLE="$2"
NOTES="$3"
DIST="$4"
REPO="$5"

if [ ! -d "$DIST" ]; then
  echo "::error::产物目录不存在：$DIST" >&2
  exit 1
fi
if [ ! -f "$NOTES" ]; then
  echo "::error::说明文件不存在：$NOTES" >&2
  exit 1
fi
if [ -z "$(ls -A "$DIST")" ]; then
  echo "::error::产物目录是空的：$DIST" >&2
  exit 1
fi

if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  echo "通道 $TAG 已存在 —— 原地换产物，不新建 Release"
else
  echo "通道 $TAG 不存在 —— 首次创建"
  gh release create "$TAG" --repo "$REPO" --title "$TITLE" --notes-file "$NOTES"
fi

while read -r name; do
  [ -n "$name" ] || continue
  echo "  移除旧产物：$name"
  gh release delete-asset "$TAG" "$name" --repo "$REPO" --yes
done < <(gh release view "$TAG" --repo "$REPO" --json assets --jq '.assets[].name')

gh release upload "$TAG" "$DIST"/* --repo "$REPO" --clobber
gh release edit "$TAG" --repo "$REPO" --title "$TITLE" --notes-file "$NOTES"

echo "通道 $TAG 现在的产物："
gh release view "$TAG" --repo "$REPO" --json assets --jq '.assets[].name'
