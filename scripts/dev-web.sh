#!/usr/bin/env bash
# 互传 Windows 壳 · 开发用静态服务器（发布时页面仍然内嵌进 exe，不读这里）。
#
#   bash scripts/dev-web.sh              默认 http://127.0.0.1:8731/
#   bash scripts/dev-web.sh --port 9000  换端口
#   PORT=9000 bash scripts/dev-web.sh    用环境变量换端口
#
# 服务的是 windows/resources/web 下的原始文件：改 css / js / html，浏览器刷新即可见，
# 不需要重新编译 exe。要验证内嵌效果，再跑 bash scripts/build-windows.sh。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WEB_ROOT="$ROOT/windows/resources/web"

die() { printf '\n[x] 失败：%s\n' "$*" >&2; exit 1; }

command -v node >/dev/null 2>&1 || die "找不到 node。装上 Node.js（>=18）后重试。"
[ -f "$WEB_ROOT/index.html" ] || die "找不到页面目录：$WEB_ROOT"

exec node "$ROOT/windows/scripts/dev-server.mjs" --root "$WEB_ROOT" "$@"
