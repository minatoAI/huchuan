#!/usr/bin/env bash
# Huchuan Windows 壳一键构建。
#
#   bash scripts/build-windows.sh                 准备 SDK → 编译 → 启动 exe 逐页截图
#   bash scripts/build-windows.sh --no-evidence   只编译，不截图
#   bash scripts/build-windows.sh --clean         先清掉 windows/build
#
# 产物：windows/build/Huchuan.exe（绿色版：旁边不需要 dll、不写注册表）
# 证据：artifacts/windows/ui/（冷启动 + P0 三态 + P1–P7 + 深色，共 16 张）、index.json、build-report.md
#
# 页面资源在 windows/resources/web/，由 CMake 扫成资源表编进 exe；
# 改完页面必须重新跑本脚本（或先用 scripts/dev-web.sh 在浏览器里看）。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WIN="$ROOT/windows"
TOOLS="$WIN/.tools"
BUILD="$WIN/build"
ART="$ROOT/artifacts/windows"
EXE="$BUILD/Huchuan.exe"

# WebView2 SDK 版本，与本机 WebView2 Runtime 156.0.4314.58 对齐。
WEBVIEW2_VERSION="${WEBVIEW2_VERSION:-1.0.4314.35}"

EVIDENCE=1
CLEAN=0
for arg in "$@"; do
  case "$arg" in
    --no-evidence) EVIDENCE=0 ;;
    --clean) CLEAN=1 ;;
    -h|--help) sed -n '2,9p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "[x] 未知参数：$arg（可用：--no-evidence / --clean）" >&2; exit 2 ;;
  esac
done

step() { printf '\n[>] %s\n' "$*"; }
ok()   { printf '[ok] %s\n' "$*"; }
die()  { printf '\n[x] 失败：%s\n' "$*" >&2; exit 1; }

if command -v cygpath >/dev/null 2>&1; then
  winpath() { cygpath -w "$1"; }
else
  die "需要在 MSYS/Git Bash 里运行（找不到 cygpath）。"
fi

now_ms() { local v; v=$(date +%s%3N 2>/dev/null || echo 0); case "$v" in *N*|"") v=$(($(date +%s) * 1000)) ;; esac; printf '%s' "$v"; }
START_MS=$(now_ms)

command -v cmake >/dev/null 2>&1 || die "找不到 cmake。装上 CMake（>=3.25）并加进 PATH 后重试。"
command -v ninja >/dev/null 2>&1 || die "找不到 ninja。装上 ninja 并加进 PATH 后重试。"

resolve_exe() {
  if [ -f "$1.exe" ]; then winpath "$1.exe"; else winpath "$1"; fi
}
CMAKE_WIN="$(resolve_exe "$(command -v cmake)")"
NINJA_WIN="$(resolve_exe "$(command -v ninja)")"

# ------------------------------------------------------------------ 1. vcvars
find_vcvars() {
  local vswhere="/c/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe"
  local candidates=()
  if [ -x "$vswhere" ]; then
    local root
    root="$("$vswhere" -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath 2>/dev/null | tr -d '\r' | head -n1)"
    if [ -n "$root" ]; then
      candidates+=("$(cygpath -u "$root")/VC/Auxiliary/Build/vcvars64.bat")
    fi
  fi
  candidates+=("/c/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/VC/Auxiliary/Build/vcvars64.bat")
  candidates+=("/c/Program Files/Microsoft Visual Studio/2022/BuildTools/VC/Auxiliary/Build/vcvars64.bat")
  local c
  for c in "${candidates[@]}"; do
    if [ -f "$c" ]; then printf '%s' "$c"; return 0; fi
  done
  return 1
}

step "检查 MSVC 工具链"
VCVARS="$(find_vcvars)" || die "找不到 vcvars64.bat。请安装 Visual Studio 2022 BuildTools 的 C++ 桌面开发组件。"
ok "vcvars64: $VCVARS"

# ------------------------------------------------------------ 2. WebView2 SDK
WV2_ROOT="$TOOLS/packages/Microsoft.Web.WebView2/build/native"
if [ ! -f "$WV2_ROOT/include/WebView2.h" ]; then
  step "拉取 WebView2 SDK $WEBVIEW2_VERSION（NuGet → windows/.tools，不装系统）"
  mkdir -p "$TOOLS/packages"
  if [ ! -f "$TOOLS/nuget.exe" ]; then
    command -v curl.exe >/dev/null 2>&1 || die "需要 curl 或 PowerShell 下载 nuget.exe。"
    curl.exe -fsSL --retry 2 -m 240 -o "$TOOLS/nuget.exe.part" \
      "https://dist.nuget.org/win-x86-commandline/latest/nuget.exe" \
      || { rm -f "$TOOLS/nuget.exe.part"; die "下载 nuget.exe 失败（网络不通？）。可手动放到 windows/.tools/nuget.exe 后重试。"; }
    mv "$TOOLS/nuget.exe.part" "$TOOLS/nuget.exe" || die "放置 nuget.exe 失败"
  fi
  ok "nuget.exe: $TOOLS/nuget.exe"
  if ! MSYS_NO_PATHCONV=1 "$TOOLS/nuget.exe" install Microsoft.Web.WebView2 \
        -Version "$WEBVIEW2_VERSION" -OutputDirectory "$(winpath "$TOOLS/packages")" \
        -NonInteractive -ExcludeVersion -Source "https://api.nuget.org/v3/index.json" >"$TOOLS/nuget.log" 2>&1; then
    tail -n 15 "$TOOLS/nuget.log" >&2
    die "拉取 WebView2 SDK 失败（详情见 windows/.tools/nuget.log）。检查网络，或版本号 $WEBVIEW2_VERSION 是否存在。"
  fi
  [ -f "$WV2_ROOT/include/WebView2.h" ] || die "NuGet 安装完成但找不到 WebView2.h，包结构可能变了：$WV2_ROOT"
fi
ok "WebView2 SDK: $WV2_ROOT（版本 $WEBVIEW2_VERSION）"

# ------------------------------------------------------------ 3. 编译
step "编译（CMake + Ninja + MSVC）"
if [ "$CLEAN" = "1" ]; then rm -rf "$BUILD"; fi
mkdir -p "$BUILD" "$TOOLS" "$ART"

BUILD_CMD="$TOOLS/build.cmd"
cat > "$BUILD_CMD" <<CMDBAT
@echo off
setlocal
call "$(winpath "$VCVARS")" >nul
if errorlevel 1 exit /b 10
cmake -S "$(winpath "$WIN")" -B "$(winpath "$BUILD")" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_MAKE_PROGRAM="$NINJA_WIN" -DWEBVIEW2_ROOT="$(winpath "$WV2_ROOT")"
if errorlevel 1 exit /b 11
cmake --build "$(winpath "$BUILD")" --parallel
if errorlevel 1 exit /b 12
dumpbin /dependents "$(winpath "$EXE")" > "$(winpath "$BUILD")/dependents.txt" 2>&1
exit /b 0
CMDBAT

BUILD_START=$(now_ms)
cmd.exe //c "$(winpath "$BUILD_CMD")" >"$TOOLS/build.log" 2>&1
BUILD_CODE=$?
BUILD_MS=$(( $(now_ms) - BUILD_START ))
if [ "$BUILD_CODE" != "0" ]; then
  echo "---- 构建日志尾部（完整：windows/.tools/build.log）----" >&2
  tail -n 40 "$TOOLS/build.log" >&2
  case "$BUILD_CODE" in
    10) die "vcvars64 环境初始化失败（MSVC 组件可能没装全）。" ;;
    11) die "CMake 配置失败，看上面日志（常见原因：WebView2 SDK 路径不对、cmake 版本太低）。" ;;
    12) die "编译/链接失败，看上面日志（常见原因：源码语法错误、WebView2 头文件缺失）。" ;;
    *)  die "构建失败（退出码 $BUILD_CODE），看上面日志。" ;;
  esac
fi
[ -f "$EXE" ] || die "构建没有报错但产物不存在：$EXE"
ok "Huchuan.exe 生成：$EXE"

# ------------------------------------------------------------ 4. 绿色版自检
step "绿色版自检"
DUPS=$(find "$BUILD" -maxdepth 1 -name '*.dll' -print -quit 2>/dev/null)
if [ -n "$DUPS" ]; then
  die "exe 旁边出现了 dll（$DUPS），不满足绿色版单文件要求。"
fi
DEPS="$BUILD/dependents.txt"
if [ -f "$DEPS" ]; then
  if grep -qiE 'VCRUNTIME|MSVCP[0-9]|WebView2Loader\.dll' "$DEPS"; then
    cat "$DEPS" >&2
    die "exe 依赖了 VC 运行库或 WebView2Loader.dll，拷走就跑不起来。"
  fi
  DEP_LIST=$(grep -oE '[A-Za-z0-9_.-]+\.dll' "$DEPS" | sort -u | tr '\n' ' ')
  ok "依赖只有系统 dll：$DEP_LIST"
else
  ok "exe 同级目录没有依赖 dll（dumpbin 输出缺失，跳过导入表检查）"
fi

# ------------------------------------------------------------ 5. 逐页截图证据
if [ "$EVIDENCE" = "1" ]; then
  # 页面读的是真 daemon（docs/daemon-api.md）。截图前先起一个带料的 daemon：
  # 一个群 + 一个文件 + 一条评论。没有它，P2 是「还没有群组」、P3/P4 直接退回首页，
  # 逐页断言就没有对象了。用户名留空，冷启动才会落在 P1。
  step "准备取证用的 daemon（真实数据）"
  DEMO_JAR="$ROOT/daemon/build/dist/daemon.jar"
  if [ ! -f "$DEMO_JAR" ]; then
    ( cd "$ROOT" && ./gradlew --console=plain -q :daemon:daemonJar ) || die "daemon.jar 构建失败"
  fi
  if netstat -ano 2>/dev/null | grep -qE "TCP +127\.0\.0\.1:47200 +.*LISTENING"; then
    die "端口 47200 已被占用：先停掉手动起的 daemon 再跑取证"
  fi
  DEMO_DIR="$ART/daemon"
  rm -rf "$DEMO_DIR"
  mkdir -p "$DEMO_DIR/data"
  # 中文一律走文件、不走命令行参数：MSYS 会把 argv 按 ANSI 代码页转给原生程序，中文会变乱码。
  printf '%s' '{"name":"老板的群"}' > "$DEMO_DIR/group.json"
  printf '%s' '{"body":"这段我下到 60% 了"}' > "$DEMO_DIR/comment.json"
  head -c 1048576 /dev/urandom > "$DEMO_DIR/travel-photos.zip"
  java -jar "$(winpath "$DEMO_JAR")" \
    --data-dir="$(winpath "$DEMO_DIR/data")" --port=47200 --msg-port=47500 --bt-port=51800 \
    --device-name=WIN11-0715 --log="$(winpath "$DEMO_DIR/daemon.log")" \
    > "$DEMO_DIR/daemon.out" 2>&1 &
  DEMO_PID=$!
  trap 'kill -9 "$DEMO_PID" 2>/dev/null' EXIT
  for _ in $(seq 1 60); do
    case "$(curl -s -m 2 http://127.0.0.1:47200/api/health 2>/dev/null || true)" in
      *'"status":"ok"'*) break ;;
    esac
    sleep 0.5
  done
  DEMO_GID=$(curl -s -X POST -H 'Content-Type: application/json' \
    --data-binary "@$(winpath "$DEMO_DIR/group.json")" http://127.0.0.1:47200/api/groups \
    | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
  DEMO_FID=$(curl -s -X POST -H 'Content-Type: application/json' \
    --data-binary "{\"path\":\"$(cygpath -m "$DEMO_DIR/travel-photos.zip")\"}" \
    "http://127.0.0.1:47200/api/groups/$DEMO_GID/files" \
    | sed -n 's/.*"id":"\([0-9a-f]*\)".*/\1/p')
  curl -s -X POST -H 'Content-Type: application/json' \
    --data-binary "@$(winpath "$DEMO_DIR/comment.json")" \
    "http://127.0.0.1:47200/api/groups/$DEMO_GID/files/$DEMO_FID/comments" > /dev/null
  [ -n "$DEMO_GID" ] && [ -n "$DEMO_FID" ] || die "取证 daemon 没建出群或文件"
  ok "取证 daemon 就绪：群 $DEMO_GID 文件 $DEMO_FID"

  step "启动 exe 逐页截图取证（冷启动 + P0 三态 + P1–P7 + 深色）"
  rm -rf "$ART/ui"                     # 先清旧的，避免失败时留着上次的截图冒充本次
  mkdir -p "$ART/ui"
  EVID_PS="$(winpath "$WIN/scripts/capture-ui.ps1")"
  JOBS_JSON="$(winpath "$WIN/scripts/ui-capture-jobs.json")"
  if ! powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$EVID_PS" \
        -ExePath "$(winpath "$EXE")" -OutDir "$(winpath "$ART/ui")" \
        -JobsPath "$JOBS_JSON" >"$ART/ui/capture.stdout.log" 2>&1; then
    tail -n 25 "$ART/ui/capture.stdout.log" >&2
    die "逐页截图失败（完整日志：artifacts/windows/ui/capture.stdout.log）"
  fi
  SHOTS=$(find "$ART/ui" -maxdepth 1 -name '*.png' | wc -l | tr -d ' ')
  FAILED=$(tr -d '\r' < "$ART/ui/index.json" | sed -n 's/.*"failed": *\([0-9]*\).*/\1/p' | head -n1)
  [ "$SHOTS" -ge 16 ] || die "截图数量不足：只产出 $SHOTS 张"
  [ "$FAILED" = "0" ] || die "有 ${FAILED:-?} 张截图没通过 DOM / 像素断言（见 artifacts/windows/ui/index.json）"
  cp "$ART/ui/p2-home.png" "$ART/huchuan-window.png"
  ok "截图 $SHOTS 张：artifacts/windows/ui（每张都断言了页名 + 页面文案 + 明暗底色）"
  ok "首页截图：artifacts/windows/huchuan-window.png"
fi

# ------------------------------------------------------------ 6. 构建报告
TOTAL_MS=$(( $(now_ms) - START_MS ))
EXE_BYTES=$(stat -c %s "$EXE")
EXE_SHA=$(sha256sum "$EXE" | cut -d' ' -f1)
CXX_VER=$(grep -h "CMAKE_CXX_COMPILER_VERSION" "$BUILD"/CMakeFiles/*/CMakeCXXCompiler.cmake 2>/dev/null | head -n1 | sed 's/.*"\(.*\)".*/\1/')

{
  echo "# Windows 壳构建报告"
  echo
  echo "- 生成时间：$(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "- 产物：windows/build/Huchuan.exe"
  echo "- 体积：${EXE_BYTES} 字节（$(awk -v b="$EXE_BYTES" 'BEGIN{printf "%.2f", b/1048576}') MiB）"
  echo "- SHA256：${EXE_SHA}"
  echo "- 构建耗时：$((BUILD_MS / 1000)).$(printf '%03d' $((BUILD_MS % 1000))) 秒；脚本总耗时 $((TOTAL_MS / 1000)).$(printf '%03d' $((TOTAL_MS % 1000))) 秒"
  echo "- 编译器：MSVC ${CXX_VER:-未知}"
  echo "- WebView2 SDK：Microsoft.Web.WebView2 ${WEBVIEW2_VERSION}（NuGet 解包到 windows/.tools）"
  echo "- 构建方式：CMake + Ninja + /MT 静态 CRT + 静态 WebView2Loader"
  echo "- 导入表（dumpbin /dependents）：${DEP_LIST:-未采集}"
  echo
  echo "## 运行取证"
  if [ "$EVIDENCE" = "1" ]; then
    echo
    echo "- 逐页截图：artifacts/windows/ui/（${SHOTS} 张，逐张断言页名 + 页面文案 + 明暗底色）"
    echo "- 断言索引：artifacts/windows/ui/index.json"
    echo "- 首页截图：artifacts/windows/huchuan-window.png"
    echo
    echo "截图清单："
    echo
    for shot in "$ART/ui"/*.png; do
      echo "    - $(basename "$shot")（$(stat -c %s "$shot") 字节）"
    done
    echo
    echo "DOM 探针摘录（artifacts/windows/ui/p3-group.log）："
    echo
    grep "DOM 探针" "$ART/ui/p3-group.log" 2>/dev/null | sed 's/^/    /'
  else
    echo
    echo "- 本次用 --no-evidence 跳过运行取证"
  fi
} > "$ART/build-report.md"
ok "构建报告：$ART/build-report.md"

printf '\n============================ 完成 ============================\n'
printf 'exe 绝对路径：%s\n' "$(winpath "$EXE")"
printf '体积：%s 字节 ｜ 构建耗时：%s ms\n' "$EXE_BYTES" "$BUILD_MS"
if [ "$EVIDENCE" = "1" ]; then printf '截图：%s\n' "$(winpath "$ART/huchuan-window.png")"; fi
