#!/usr/bin/env bash
#
# verify-tracker-wiring.sh — Android 宿主把内核 tracker 接到配置与 UI 的验收（M1.6 接线）
#
#   bash artifacts/tracker-wiring/verify-tracker-wiring.sh              # (a)(b)(c) 全跑，约 10-15 分钟
#   bash artifacts/tracker-wiring/verify-tracker-wiring.sh --phase a    # 只跑开启态
#   bash artifacts/tracker-wiring/verify-tracker-wiring.sh --phase b    # 只跑关闭态
#
# 验收项（对应 docs/reports/tracker-wiring.md §4）：
#   a 开启：P0 授权通过后才起内核，内核拿到 tracker 基址；logcat 有注册尝试（本机到 *.workers.dev
#            被 DNS 污染，失败也算通过）；局域网 mDNS 入群与传输照常；进程不崩。
#   b 关闭：P7 关掉「跨网发现」→ 冷启动后 kernel_started tracker=off + tracker_disabled，
#            整轮没有任何 tracker 网络事件（不注册、不 announce）。
#   c 两轮都不影响 P0 权限门与 mDNS 发现（off 态靠 mDNS 冷启动重连回桌面对端）。
#
# 前置：OPPO(3B1F5HEADK1TXSF0) 在线且与桌面同网段；本机 JDK 17 + Android SDK；libtorrent4j.dll 已构建。
# 证据：artifacts/tracker-wiring/（截图、logcat 流、桌面端日志、summary.txt）
# 最后一行打印 PASS / FAIL，退出码 0 / 1（环境错误 3）。
#
# 本条任务的边界是「只写 app/ docs/ artifacts/tracker-wiring/」，所以脚本放 artifacts 下不进 scripts/。
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ART="$SCRIPT_DIR"
DEVKIT="$ROOT/tools/devkit/devkit.mjs"
PKG="app.huchuan"
OPPO="${TW_OPPO:-3B1F5HEADK1TXSF0}"
PHASE="all"
PAYLOAD_MIB="${TW_PAYLOAD_MIB:-8}"
PEER_HOLD="${TW_PEER_HOLD:-2700}"
TRACKER_BASE="https://huchuan-relay.autoai233.workers.dev"

while [ $# -gt 0 ]; do
  case "$1" in
    --phase) shift; PHASE="${1:-all}" ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "FATAL: 未知参数 $1" >&2; exit 2 ;;
  esac
  shift
done
case "$PHASE" in a|b|all) ;; *) echo "FATAL: --phase 只接受 a|b|all" >&2; exit 2 ;; esac

mkdir -p "$ART/logs" "$ART/shots" "$ART/ui" "$ART/payload" "$ART/peer-data"

# 真机是独占资源：两个实例同时安装/清日志会让断言互相污染（实测踩过）。
LOCK="$ART/.running"
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK" 2>/dev/null)" 2>/dev/null; then
  echo "FATAL: 已有另一个 verify-tracker-wiring.sh 在跑（pid $(cat "$LOCK")）" >&2
  exit 3
fi
echo $$ > "$LOCK"

# Windows 原生程序（node/java/adb）只吃 Windows 路径；设备侧路径（/sdcard/...）不能被 MSYS 改写。
# 两者都要，所以：给原生程序的路径一律 w() 转一遍，同时关掉自动转换。
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

say() { printf '%s\n' "$*"; }
step() { printf '\n=== %s ===\n' "$1"; }
die() { say "FATAL: $*" >&2; say "FAIL"; exit 1; }
w() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

CHECKS_PASS=0
CHECKS_FAIL=0
SUMMARY="$ART/summary.txt"
: > "$SUMMARY"
chk() {
  local name="$1" ok="$2" detail="${3:-}"
  if [ "$ok" = "1" ]; then
    say "CHECK PASS $name${detail:+ ($detail)}"
    printf 'PASS\t%s\t%s\n' "$name" "$detail" >> "$SUMMARY"
    CHECKS_PASS=$((CHECKS_PASS + 1))
  else
    say "CHECK FAIL $name${detail:+ ($detail)}"
    printf 'FAIL\t%s\t%s\n' "$name" "$detail" >> "$SUMMARY"
    CHECKS_FAIL=$((CHECKS_FAIL + 1))
  fi
}
warn() { say "WARN  $*"; }
info() { say "INFO  $*"; }

PEER_PID=""
PEER_TAIL8=""
STREAM_PID=""
LOGSTREAM=""
PEER_LOG=""

peer_stop() {
  if [ -n "$PEER_PID" ] && kill -0 "$PEER_PID" 2>/dev/null; then
    kill "$PEER_PID" 2>/dev/null; sleep 0.3; kill -9 "$PEER_PID" 2>/dev/null
  fi
  PEER_PID=""
}
logstream_stop() {
  if [ -n "$STREAM_PID" ] && kill -0 "$STREAM_PID" 2>/dev/null; then
    kill "$STREAM_PID" 2>/dev/null; sleep 0.3
  fi
  STREAM_PID=""
}
on_exit() { logstream_stop; peer_stop; rm -f "$LOCK"; }
trap on_exit EXIT

resolve_adb() {
  local c
  for c in "${TW_ADB:-}" "${ADB:-}" \
    "/e/agentHome/android-sdk/platform-tools/adb.exe" \
    "${ANDROID_HOME:-}/platform-tools/adb.exe"; do
    [ -n "$c" ] || continue
    [ -f "$c" ] && { printf '%s' "$c"; return 0; }
  done
  command -v adb 2>/dev/null && return 0
  return 1
}
ADB="$(resolve_adb)" || die "找不到 adb"
[ -f "$DEVKIT" ] || die "找不到 tools/devkit/devkit.mjs"

SERIAL="$OPPO"
dks() { node "$(w "$DEVKIT")" -s "$SERIAL" --artifacts "$(w "$ART")" "$@" 2>&1; }
sh_() { "$ADB" -s "$SERIAL" shell "$@" 2>&1; }

# ------------------------------------------------------------------ UI 小工具

retry3() {
  local i
  for i in 1 2 3; do if "$@"; then return 0; fi; sleep 1; done
  return 1
}
tap() {
  local text="$1" exact="${2:-}"
  if [ "$exact" = "exact" ]; then
    retry3 dks tap-text "$text" --exact >/dev/null || { warn "tap 失败：$text"; return 1; }
  else
    retry3 dks tap-text "$text" >/dev/null || { warn "tap 失败：$text"; return 1; }
  fi
  sleep 1
  return 0
}
see_text() { dks wait-text "$1" --timeout "${2:-30000}" >/dev/null 2>&1; }

artifact_path() {
  local out
  out="$("$@" 2>&1 | tail -1)"
  printf '%s' "$out" | tr ' ' '\n' | sed -n 's/^path=//p' | head -1
}
shot() {
  local name="$1" path
  path="$(artifact_path dks screenshot --name "$name")"
  if [ -n "$path" ] && [ -f "$path" ]; then
    cp "$path" "$ART/shots/$(basename "$path")"
  else
    warn "截图失败：$name"
  fi
  return 0
}
uidump() {
  local path
  path="$(artifact_path dks ui-dump --name "$1")"
  [ -n "$path" ] && [ -f "$path" ] && cp "$path" "$ART/ui/$(basename "$path")"
  return 0
}
latest_xml() { ls -t "$ART"/ui-dumps/"$SERIAL"/*.xml 2>/dev/null | head -1; }

# OEM 的图标按钮（ColorOS 安装引导页的「更多」）与选择器里的文件行都没有可点的文案，
# 只能从 ui-dump 的 bounds 里点。两个助手共用一段 bounds → 中心点的换算。
tap_bounds() {
  local bounds="$1" x1 y1 x2 y2
  x1=$(printf '%s' "$bounds" | sed 's/bounds="\[\([0-9]*\),.*/\1/')
  y1=$(printf '%s' "$bounds" | sed 's/.*\[[0-9]*,\([0-9]*\)\]\[.*/\1/')
  x2=$(printf '%s' "$bounds" | sed 's/.*\]\[\([0-9]*\),.*/\1/')
  y2=$(printf '%s' "$bounds" | sed 's/.*,\([0-9]*\)\]".*/\1/')
  sh_ input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 )) >/dev/null 2>&1
}
tap_resid() {
  local resid="$1" xml bounds
  dks ui-dump >/dev/null 2>&1
  xml="$(latest_xml)"
  [ -n "$xml" ] || return 1
  bounds="$(grep -o "resource-id=\"$resid\"[^>]*bounds=\"\[[0-9,]*\]\[[0-9,]*\]\"" "$xml" \
    | grep -o 'bounds="\[[0-9,]*\]\[[0-9,]*\]"' | head -1)"
  [ -n "$bounds" ] || return 1
  tap_bounds "$bounds"
}
# 按「文本里包含这段字串」的节点点：选择器里的文件行靠它点（文案带换行、不可点）。
#
# 关键坑：uiautomator dump 出来的 XML 可能**整篇只有一行**（实测这台 OPPO 就是），
# 直接 grep -F 命中后取「第一个 bounds」会拿到根节点的 [0,0][1440,3168]，
# 点下去等于点屏幕正中——之前 SAF 三次都这么「点成功」了却什么都没选上。
# 所以先按 <node 切开、一行一个节点，再取命中那一行的 bounds。
tap_text_node() {
  local pat="$1" xml bounds
  dks ui-dump >/dev/null 2>&1
  xml="$(latest_xml)"
  [ -n "$xml" ] || return 1
  bounds="$(tr '<' '\n' < "$xml" | grep -F -A1 -- "$pat" \
    | grep -o 'bounds="\[[0-9,]*\]\[[0-9,]*\]"' | head -1)"
  [ -n "$bounds" ] || return 1
  tap_bounds "$bounds"
}

# ------------------------------------------------------------------ 日志断言

# 只读本次运行的日志流：OPPO 的 logcat 缓冲在安装 / UI dump 期间几十秒就被刷掉，
# 拿 logcat -d 做存在性断言会随机假阴性。
# 注意：这里必须是**单个** adb 进程，不能再套一层 "( adb | grep ) &"：
# 那样 STREAM_PID 是子 shell，kill 它不会带走 adb，每跑一轮就在机器上留一个
# 常驻的 adb logcat（踩过：一次验收留下 8 个孤儿 adb）。过滤放到读取时做。
logstream_start() {
  logstream_stop
  LOGSTREAM="$ART/logs/stream-$SERIAL-$1.log"
  : > "$LOGSTREAM"
  "$ADB" -s "$SERIAL" logcat -v time -s huchuan/m1:I > "$LOGSTREAM" 2>&1 &
  STREAM_PID=$!
  sleep 2
}
applog() {
  if [ -n "$LOGSTREAM" ] && [ -s "$LOGSTREAM" ]; then
    tr -d '\000\r' < "$LOGSTREAM" | grep -F 'huchuan/m1'
  else
    sh_ logcat -d -s huchuan/m1:I | tr -d '\000\r'
  fi
}
have_log() { applog | grep -qE "$1"; }
log_field() {
  applog | grep -E "event=$1( |$)" | tail -1 | grep -o "$2=[^ ]*" | head -1 | cut -d= -f2
}
log_count() { applog | grep -cE "$1" || true; }
wait_log() {
  local waited=0 timeout_ms="${2:-30000}"
  while [ "$waited" -lt "$timeout_ms" ]; do
    have_log "$1" && return 0
    sleep 1; waited=$((waited + 1000))
  done
  return 1
}

# ------------------------------------------------------------------ 安装 / 权限 / 落盘值

# ColorOS 对全新安装会插「安装增强防护」引导页（com.oplus.appdetail/InstallGuideActivity）：
# adb install 会一直挂着，必须点「更多 → 开始深度扫描」等它扫完，再点「继续安装」。
handle_install_guide() {
  local top
  top="$(sh_ dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity)"
  printf '%s' "$top" | grep -q 'InstallGuideActivity' || return 1
  say "  安装被 ColorOS「安装增强防护」拦住，自动点掉"
  tap_resid 'com.oplus.appdetail:id/menu_more' || { warn "找不到「更多」按钮"; return 1; }
  sleep 2
  see_text '开始深度扫描' 15000 || { warn "更多菜单没出现「开始深度扫描」"; return 1; }
  tap '开始深度扫描'
  if see_text '继续安装' 90000; then
    tap '继续安装'
    say "  已点「继续安装」"
  else
    warn "深度扫描没扫出「继续安装」"
  fi
  return 0
}

install_apk() {
  local apk="$1" log="$ART/logs/install-$SERIAL.log" i
  : > "$log"
  # 故意不带 -g：本地网络权限要靠 P0 的真实授权路径拿，-g 会把它预先授掉，权限门就没得验了。
  ( "$ADB" -s "$SERIAL" install -r -t "$(w "$apk")" > "$log" 2>&1 ) &
  local pid=$!
  for i in $(seq 1 300); do
    kill -0 "$pid" 2>/dev/null || break
    grep -q 'Success' "$log" 2>/dev/null && break
    # 前 10 s 不太可能弹引导页，少打点 dump；之后每 5 s 看一眼。
    if [ $((i % 5)) -eq 0 ] && [ "$i" -ge 10 ]; then handle_install_guide >/dev/null 2>&1; fi
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    kill -9 "$pid" 2>/dev/null
    warn "adb install 挂了 300 s（多半是 ColorOS「安装增强防护」拦住了），看 $log"
    return 1
  fi
  grep -E 'Success|Failure|Error' "$log" | tail -1
  grep -q 'Success' "$log"
}
perm_granted() {
  sh_ dumpsys package "$PKG" | grep -m1 'ACCESS_LOCAL_NETWORK: granted' | grep -q 'granted=true' && echo 1 || echo 0
}
pidof_app() { sh_ pidof "$PKG" | tr -d '\r' | tr -d '\n'; }

# 落盘值就是事实：SharedPreferences 里没有 tracker_discovery 这条 = 用户没动过 = 默认开。
switch_raw() {
  sh_ run-as "$PKG" cat "/data/data/$PKG/shared_prefs/huchuan.xml" 2>/dev/null \
    | grep -o 'name="tracker_discovery" value="[a-z]*"' | grep -o 'value="[a-z]*"' | cut -d'"' -f2
}
switch_state() { local v; v="$(switch_raw)"; case "$v" in false) echo off ;; *) echo on ;; esac; }
base_raw() {
  sh_ run-as "$PKG" cat "/data/data/$PKG/shared_prefs/huchuan.xml" 2>/dev/null \
    | grep -o 'name="tracker_base_url">[^<]*' | sed 's/.*>//'
}

back_to_home() {
  local i
  for i in 1 2 3 4 5; do
    if see_text "搜索群组" 4000; then return 0; fi
    sh_ input keyevent KEYCODE_BACK >/dev/null 2>&1
    sleep 1
  done
  return 1
}
open_p7() {
  back_to_home || return 1
  tap "设置" || return 1
  see_text "跨网发现" 15000
}
close_p7() {
  local i
  for i in 1 2 3; do
    if see_text "搜索群组" 3000; then return 0; fi
    sh_ input keyevent KEYCODE_BACK >/dev/null 2>&1
    sleep 1
  done
  return 1
}
ensure_app_running() {
  if [ -z "$(pidof_app)" ]; then
    dks launch "$PKG" >/dev/null 2>&1
    sleep 5
  fi
}

# ------------------------------------------------------------------ 桌面对端（LAN 上另一台设备）

peer_cleanup_stale() {
  # 只按命令行精确匹配清掉本脚本起的对端，不碰别人的 java（AGENTS.md §6.7）。
  powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*DesktopPeer*' -and \$_.CommandLine -like '*tracker-wiring*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1
}
peer_start() {
  local data log classdir cpo native i
  PEER_LOG="$ART/logs/peer.log"
  data="$ART/peer-data/desktop"
  rm -rf "$data"; mkdir -p "$data"
  classdir="$ART/peer-classes"
  [ -f "$classdir/DesktopPeer.class" ] || die "桌面对端没编译（$classdir）"
  cpo="$(w "$ROOT/core/build/libs/core.jar")"
  for j in "$ROOT"/core/build/m1-libs/*.jar; do [ -f "$j" ] && cpo="$cpo;$(w "$j")"; done
  cpo="$cpo;$(w "$classdir")"
  native="$(w "$ROOT/core/build/m1-native/libtorrent4j.dll")"
  [ -f "$ROOT/core/build/m1-native/libtorrent4j.dll" ] || die "缺 libtorrent4j.dll（先跑 gradlew :core:m1Native）"
  : > "$PEER_LOG"
  java -Dlibtorrent4j.jni.path="$native" -Dfile.encoding=UTF-8 -cp "$cpo" DesktopPeer host \
    data="$(w "$data")" work="$(w "$ART")" name="桌面电脑" msg_port=47100 bt_port=51413 \
    group_name="验收群" hold="$PEER_HOLD" > "$PEER_LOG" 2>&1 &
  PEER_PID=$!
  for i in $(seq 1 120); do
    PEER_TAIL8="$(grep -o 'PEER ready role=host tail8=[0-9A-Z]*' "$PEER_LOG" 2>/dev/null | head -1 | sed 's/.*tail8=//')"
    [ -n "$PEER_TAIL8" ] && break
    kill -0 "$PEER_PID" 2>/dev/null || break
    sleep 1
  done
  [ -n "$PEER_TAIL8" ] || { tail -20 "$PEER_LOG"; die "桌面对端没起来"; }
  info "peer tail8=$PEER_TAIL8 pid=$PEER_PID log=$PEER_LOG"
}
wait_peer_log() {
  local pattern="$1" timeout="${2:-120000}" waited=0
  while [ "$waited" -lt "$timeout" ]; do
    grep -qF -- "$pattern" "$PEER_LOG" 2>/dev/null && return 0
    sleep 1; waited=$((waited + 1000))
  done
  return 1
}

# ------------------------------------------------------------------ P0 无权限态（c 项的证据）

# ------------------------------------------------------------------ SAF 选文件（P3 添加文件）

# 真机文件选择器在这台 OPPO 上是 ColorOS 的文件管理器（com.coloros.filemanager PickerActivity），
# 和 AOSP Files 完全是两套 UI：
#   - 没有「显示根目录 / 下载 / 可用空间」这些 AOSP 抽屉控件；
#   - 「最近」按媒体库索引，.bin 这种非媒体类型根本不在里面（8 MiB 文件就找不到）；
#   - 能用的路径是搜索框：点「搜索文件」→ 打字 → 结果行点一下。
# 结果行本身不可点（clickable=false），只能按 ui-dump 的 bounds 点中心。
saf_pick() {
  local name="$1" stem attempt
  stem="${name%%.*}"
  for attempt in 1 2 3; do
    if ! see_text "添加文件" 20000; then return 1; fi
    tap "添加文件"
    if see_text "搜索文件" 25000; then
      # ColorOS 选择器：搜索框（只打前半段，避免搜索框自己也被 tap_text_node 命中）
      tap "搜索文件"; sleep 1
      # 同一个 PickerActivity 会记住上一次的查询，先清空再打字，否则变成 tracker-patracker-pa
      tap_text_node "清空" >/dev/null 2>&1 || true
      sleep 1
      dks input text "${stem:0:10}" >/dev/null 2>&1
      sleep 3
      shot "saf-search$attempt"
      if tap_text_node "$stem"; then
        if wait_log 'event=share_import' 30000; then sleep 1; return 0; fi
      fi
      sleep 2
      if tap_text_node "$stem"; then
        if wait_log 'event=share_import' 30000; then sleep 1; return 0; fi
      fi
      warn "SAF 搜不到/点不动 $stem（第 $attempt 次）"
      uidump "saf-search-miss$attempt"
      sh_ input keyevent KEYCODE_BACK >/dev/null 2>&1
      sleep 1
      continue
    fi
    # AOSP Files 的兜底路径（别的机型）：显示根目录 → 下载 → 点文件名。
    if ! see_text "显示根目录" 8000; then
      warn "文件选择器既没有搜索框也没有「显示根目录」"
      uidump "saf-unknown$attempt"
      sh_ input keyevent KEYCODE_BACK >/dev/null 2>&1
      sleep 1; continue
    fi
    sleep 1
    if ! see_text "可用空间" 3000; then
      tap "显示根目录" exact
      sleep 2
    fi
    tap "下载" exact
    sleep 2
    if see_text "中的文件" 15000 && see_text "$name" 15000; then
      shot "saf-downloads"
      tap "$name" exact
      return 0
    fi
    warn "SAF 第 $attempt 次没在下载目录里看到 $name"
    uidump "saf-attempt$attempt"
    sh_ input keyevent KEYCODE_BACK >/dev/null 2>&1
    sleep 1
  done
  return 1
}

# ------------------------------------------------------------------ 前置 + 构建 + 静态断言

step "0/7 前置"
"$ADB" devices | grep -q "$OPPO" || die "OPPO $OPPO 不在线"
info "OPPO $OPPO 在线"
SDK="$(sh_ getprop ro.build.version.sdk | tr -d '\r')"
chk "env.sdk37" "$([ "$SDK" = "37" ] && echo 1 || echo 0)" "sdk=$SDK"

step "1/7 构建（app + core + 桌面对端）+ 静态断言"
ANDROID_HOME="${ANDROID_HOME:-/e/agentHome/android-sdk}"
JAVA_HOME_SRC="${JAVA_HOME:-/e/dshHome/.jdks/temurin-17}"
ANDROID_HOME="$(w "$ANDROID_HOME")"; export ANDROID_HOME
JAVA_HOME="$(w "$JAVA_HOME_SRC")"; export JAVA_HOME
export PATH="$(printf '%s' "$JAVA_HOME" | sed 's|\\|/|g')/bin:$PATH"
( cd "$ROOT" && ./gradlew --console=plain -q :app:assembleDebug :core:jar :core:m1Deps :core:m1Native ) \
  || die "gradle 构建失败"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || die "APK 不存在：$APK"
info "APK $(stat -c '%s bytes' "$APK")"

CLASSDIR="$ART/peer-classes"
mkdir -p "$CLASSDIR"
if [ ! -f "$CLASSDIR/DesktopPeer.class" ] || [ "$ROOT/app/tools/peer/DesktopPeer.java" -nt "$CLASSDIR/DesktopPeer.class" ]; then
  CP="$(w "$ROOT/core/build/libs/core.jar")"
  for j in "$ROOT"/core/build/m1-libs/*.jar; do [ -f "$j" ] && CP="$CP;$(w "$j")"; done
  javac -encoding UTF-8 -cp "$CP" -d "$(w "$CLASSDIR")" "$(w "$ROOT/app/tools/peer/DesktopPeer.java")" \
    || die "javac 桌面对端失败"
fi

PAYLOAD="$ART/payload/tracker-payload-${PAYLOAD_MIB}MiB.bin"
if [ ! -f "$PAYLOAD" ]; then
  head -c $((PAYLOAD_MIB * 1024 * 1024)) /dev/urandom > "$PAYLOAD"
fi
PAYLOAD_NAME="$(basename "$PAYLOAD")"
PAYLOAD_SHA="$(sha256sum "$PAYLOAD" | cut -d' ' -f1)"
PAYLOAD_BYTES="$(stat -c %s "$PAYLOAD")"
info "payload $PAYLOAD_NAME $PAYLOAD_BYTES B sha=${PAYLOAD_SHA:0:16}"

# 源码静态断言：改造点必须在源码里（二进制里的符号不算源码证据，AGENTS.md §8.1）。
SRC_BASE_PATH="$ROOT/app/src/main/kotlin/app/huchuan/kernel/AppPrefs.kt"
SRC_RT_PATH="$ROOT/app/src/main/kotlin/app/huchuan/kernel/M1Runtime.kt"
SRC_UI_PATH="$ROOT/app/src/main/kotlin/app/huchuan/ui/SettingsDrawer.kt"
SRC_BASE="$(cat "$SRC_BASE_PATH")"
SRC_UI="$(cat "$SRC_UI_PATH")"
# 顺序断言靠行号比大小：grep 用 BRE，( ) 都是字面量，不用转义。
CALL_LINE="$(grep -n '^ *applyTrackerBaseUrl()' "$SRC_RT_PATH" | head -1 | cut -d: -f1)"
START_LINE="$(grep -n '^ *kernel.start()' "$SRC_RT_PATH" | head -1 | cut -d: -f1)"
chk "src.pref_default_base" \
  "$(printf '%s' "$SRC_BASE" | grep -q "DEFAULT_TRACKER_BASE = \"$TRACKER_BASE\"" && echo 1 || echo 0)" \
  "AppPrefs.DEFAULT_TRACKER_BASE"
chk "src.pref_switch_default_on" \
  "$(printf '%s' "$SRC_BASE" | grep -q 'KEY_TRACKER_DISCOVERY, true' && echo 1 || echo 0)" "默认开"
chk "src.before_kernel_start" \
  "$([ -n "$CALL_LINE" ] && [ -n "$START_LINE" ] && [ "$CALL_LINE" -lt "$START_LINE" ] && echo 1 || echo 0)" \
  "applyTrackerBaseUrl() 在第 $CALL_LINE 行，kernel.start() 在第 $START_LINE 行"
chk "src.p7_switch_row" \
  "$(printf '%s' "$SRC_UI" | grep -q 'R.string.p7_tracker' && echo 1 || echo 0)" "P7 跨网发现"

sh_ svc power stayon true >/dev/null 2>&1
sh_ input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
sh_ wm dismiss-keyguard >/dev/null 2>&1
sh_ settings put system install_app_detail_safeguard_state 0 >/dev/null 2>&1
peer_cleanup_stale

# ------------------------------------------------------------------ (a) 开启态

phase_a() {
  step "a/7 开启态：全新安装 → P0 授权 → 内核带 tracker 基址启动"
  peer_start
  sh_ am force-stop "$PKG" >/dev/null 2>&1
  "$ADB" -s "$SERIAL" uninstall "$PKG" 2>&1 | tail -1
  chk "a.uninstalled" "$(sh_ pm list packages | grep -q "package:$PKG" && echo 0 || echo 1)" "权限与设置一起复位"
  install_apk "$APK" || die "安装失败（看 $ART/logs/install-$SERIAL.log）"
  "$ADB" -s "$SERIAL" push "$(w "$PAYLOAD")" "/sdcard/Download/$PAYLOAD_NAME" 2>&1 | tail -1
  sh_ "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/$PAYLOAD_NAME" >/dev/null 2>&1

  sh_ logcat -b all -c >/dev/null 2>&1
  logstream_start a
  dks launch "$PKG" >/dev/null 2>&1
  if see_text "允许" 25000; then
    chk "a.permission_prompt" 1 "首次启动弹出本地网络授权框"
    shot "a-permission-dialog"
    tap "允许" exact
  else
    chk "a.permission_prompt" "$([ "$(perm_granted)" = "1" ] && echo 1 || echo 0)" "没有弹窗，权限已是 granted"
  fi
  chk "a.granted" "$([ "$(perm_granted)" = "1" ] && echo 1 || echo 0)" \
    "$(sh_ dumpsys package "$PKG" | grep -m1 'ACCESS_LOCAL_NETWORK: granted' | tr -d '\r')"
  wait_log 'p0_result status=pass' 40000 >/dev/null || true
  chk "c.a_p0_pass" "$(have_log 'p0_result status=pass' && echo 1 || echo 0)" "p0_result=$(log_field p0_result status)"
  chk "a.kernel_after_grant" \
    "$(applog | awk '/local_network_permission state=granted/{g=NR} /event=kernel_started/{k=NR} END{print (g && k && g<k) ? 1 : 0}')" \
    "granted 行在 kernel_started 之前"
  chk "a.tracker_config_on" "$(wait_log 'tracker_config enabled=on' 20000 && echo 1 || echo 0)" \
    "base=$(log_field tracker_config base)"
  chk "a.tracker_config_base_default" \
    "$([ "$(log_field tracker_config base)" = "$TRACKER_BASE" ] && echo 1 || echo 0)" \
    "base=$(log_field tracker_config base)"
  chk "a.pref_base_default" "$([ "$(base_raw)" = "$TRACKER_BASE" ] || [ -z "$(base_raw)" ] && echo 1 || echo 0)" \
    "prefs tracker_base_url=${base_raw:-（未写，用默认）}"
  chk "a.kernel_started_tracker_on" \
    "$(applog | grep -qE 'event=kernel_started .*tracker=on( |$)' && echo 1 || echo 0)" \
    "tracker_base=$(log_field kernel_started tracker_base)"
  chk "a.tracker_service_started" "$(wait_log 'tracker_service_started' 20000 && echo 1 || echo 0)" \
    "base=$(log_field tracker_service_started base)"
  chk "a.p7_switch_default_on" "$([ "$(switch_state)" = "on" ] && echo 1 || echo 0)" \
    "tracker_discovery 未写过 = 默认开（raw=${switch_raw:-空}）"

  step "a/7 P0 → P1 → P5：只给后 8 位，靠 mDNS 入群"
  see_text "用户名" 25000 || { shot "a-p1-missing"; die "授权后没进 P1"; }
  tap "保存"; sleep 1
  see_text "邀请码后 8 位" 25000 || { shot "a-p5-missing"; die "没进 P5"; }
  tap "邀请码后 8 位"; sleep 1
  sh_ input text "$PEER_TAIL8" >/dev/null 2>&1; sleep 1
  tap "加入"
  if wait_log 'event=group_joined' 120000; then
    chk "a.joined" 1 "gid=$(log_field group_joined gid)"
    LAN_N="$(log_field join_candidates lan)"
    chk "c.a_mdns_lan_candidates" "$([ "${LAN_N:-0}" -ge 1 ] && echo 1 || echo 0)" \
      "lan=$LAN_N addrs=$(log_field join_candidates addrs) join_via=$(log_field join_via source)"
  else
    shot "a-join-failed"
    applog | grep -E 'join_|mdns' | tail -10
    chk "a.joined" 0 "没有 group_joined"
    chk "c.a_mdns_lan_candidates" 0 "加入失败"
    die "开启态入群失败：局域网那一档本来就该照常"
  fi
  chk "a.p3_visible" "$(see_text "添加文件" 25000 && echo 1 || echo 0)"
  shot "a-p3-group"

  step "a/7 tracker 注册尝试（本机 DNS 被污染，失败可接受）"
  chk "a.tracker_started_group" "$(wait_log 'tracker_started gid=' 60000 && echo 1 || echo 0)" \
    "gid=$(log_field tracker_started gid) base=$(log_field tracker_started base)"
  ATTEMPT="none"
  if wait_log 'tracker_register_failed' 45000; then
    ATTEMPT="register_failed"
  elif have_log 'event=tracker_register '; then
    ATTEMPT="register"
  fi
  chk "a.tracker_register_attempt" "$([ "$ATTEMPT" != "none" ] && echo 1 || echo 0)" \
    "$ATTEMPT reason=$(log_field tracker_register_failed reason)"
  chk "a.alive_after_attempt" "$([ -n "$(pidof_app)" ] && echo 1 || echo 0)" "pid=$(pidof_app)"
  chk "a.no_crash" \
    "$(sh_ logcat -d -b crash | tr -d '\000\r' | grep -q 'app.huchuan' && echo 0 || echo 1)" "crash buffer 干净"
  chk "a.no_config_ignored" "$(have_log 'tracker_config_ignored' && echo 0 || echo 1)" "基址是 start() 之前给的"

  step "a/7 P3 SAF 共享 → 桌面端下载（局域网传输照常）"
  saf_pick "$PAYLOAD_NAME" || { shot "a-saf-failed"; die "SAF 选不到 $PAYLOAD_NAME"; }
  sleep 2
  if ! wait_log 'event=file_seeding' 90000; then
    shot "a-share-failed"; applog | tail -20; die "共享失败（没有 file_seeding）"
  fi
  chk "a.shared_bytes" \
    "$([ "$(log_field share_import bytes)" = "$PAYLOAD_BYTES" ] && echo 1 || echo 0)" \
    "name=$(log_field share_import name) bytes=$(log_field share_import bytes)"
  if wait_peer_log "PEER download_done" 300000; then
    PC_SHA="$(grep -o 'download_done .*sha256=[0-9a-f]*' "$PEER_LOG" | tail -1 | grep -o 'sha256=[0-9a-f]*' | cut -d= -f2)"
    PC_BYTES="$(grep -o 'download_done .*bytes=[0-9]*' "$PEER_LOG" | tail -1 | grep -o 'bytes=[0-9]*' | cut -d= -f2)"
    chk "a.transfer_sha256_equal" "$([ "$PC_SHA" = "$PAYLOAD_SHA" ] && echo 1 || echo 0)" \
      "pc=${PC_SHA:0:16} android=${PAYLOAD_SHA:0:16}"
    chk "a.transfer_bytes" "$([ "$PC_BYTES" = "$PAYLOAD_BYTES" ] && echo 1 || echo 0)" "$PC_BYTES B"
  else
    chk "a.transfer_sha256_equal" 0 "桌面端没下载完"
    chk "a.transfer_bytes" 0 "桌面端没下载完"
  fi
  shot "a-p3-shared"

  step "a/7 P7：开关默认开、整行可点"
  if open_p7; then
    shot "a-p7-tracker-on"
    chk "a.p7_switch_visible" 1 "跨网发现"
    chk "a.p7_switch_on" "$([ "$(switch_state)" = "on" ] && echo 1 || echo 0)" "tracker_discovery=on"
  else
    chk "a.p7_switch_visible" 0 "P7 里没有跨网发现"
    chk "a.p7_switch_on" 0 "打不开 P7"
  fi
  close_p7 || true
  applog > "$ART/logs/phase-a.log" 2>&1
  logstream_stop
}

# ------------------------------------------------------------------ (b) 关闭态

phase_b() {
  step "b/7 P7 关掉「跨网发现」→ 落盘"
  ensure_app_running
  logstream_start b-toggle
  if [ "$(switch_state)" != "on" ]; then
    # 单独跑 b 时可能已经是关的：先打开，保证「用户点过」这条路径有日志可断。
    open_p7 || die "打不开 P7"
    tap "跨网发现"; sleep 2
  fi
  open_p7 || die "打不开 P7（关闭态）"
  chk "b.p7_switch_on_before" "$([ "$(switch_state)" = "on" ] && echo 1 || echo 0)" "起点是开"
  shot "b-p7-before-off"
  tap "跨网发现"
  chk "b.setting_logged_off" "$(wait_log 'tracker_setting enabled=off' 15000 && echo 1 || echo 0)" \
    "apply=$(log_field tracker_setting apply)"
  chk "b.pref_switch_off" "$([ "$(switch_raw)" = "false" ] && echo 1 || echo 0)" "tracker_discovery=false"
  chk "b.restart_hint" "$(see_text "下次启动生效" 15000 && echo 1 || echo 0)" "下次启动生效"
  shot "b-p7-after-off"
  close_p7 || true
  applog > "$ART/logs/phase-b-toggle.log" 2>&1
  logstream_stop
  sh_ am force-stop "$PKG" >/dev/null 2>&1
  sleep 1
  chk "b.force_stopped" "$([ -z "$(pidof_app)" ] && echo 1 || echo 0)" "pid 空"

  step "b/7 冷启动（关闭态）：内核不建 tracker、不发请求"
  sh_ logcat -b all -c >/dev/null 2>&1
  logstream_start b
  dks launch "$PKG" >/dev/null 2>&1
  wait_log 'event=kernel_started' 60000 >/dev/null || true
  chk "b.tracker_config_off" "$(wait_log 'tracker_config enabled=off' 20000 && echo 1 || echo 0)" \
    "base=$(log_field tracker_config base)"
  chk "b.config_keeps_pref" \
    "$([ "$(log_field tracker_config configured_base)" = "$TRACKER_BASE" ] && echo 1 || echo 0)" \
    "configured_base=$(log_field tracker_config configured_base)"
  chk "b.kernel_started_tracker_off" \
    "$(applog | grep -qE 'event=kernel_started .*tracker=off( |$)' && echo 1 || echo 0)" "tracker=off"
  chk "b.tracker_disabled_empty" \
    "$(have_log 'event=tracker_disabled reason=base_url_empty' && echo 1 || echo 0)" "reason=base_url_empty"
  chk "b.app_kernel_ready_off" \
    "$(applog | grep -qE 'event=app_kernel_ready .*tracker=off( |$)' && echo 1 || echo 0)" \
    "app_kernel_ready tracker=off"
  TRK_NET="$(log_count 'event=tracker_(service_started|started|stopped|register|register_failed|register_backfilled|announce|announce_failed|announce_rejected|candidates_error|tick_error|no_message_port)')"
  chk "b.zero_tracker_requests" "$([ "${TRK_NET:-1}" = "0" ] && echo 1 || echo 0)" \
    "tracker 网络事件 ${TRK_NET:-?} 条"
  chk "b.no_config_ignored" "$(have_log 'tracker_config_ignored' && echo 0 || echo 1)" "没有 ignored"
  chk "b.alive" "$([ -n "$(pidof_app)" ] && echo 1 || echo 0)" "pid=$(pidof_app)"
  chk "b.no_crash" \
    "$(sh_ logcat -d -b crash | tr -d '\000\r' | grep -q 'app.huchuan' && echo 0 || echo 1)" "crash buffer 干净"

  step "b/7 关闭态下 P0 权限门与 mDNS 照常"
  wait_log 'p0_result status=pass' 40000 >/dev/null || true
  chk "c.b_p0_pass" "$(have_log 'p0_result status=pass' && echo 1 || echo 0)" "p0_result=$(log_field p0_result status)"
  chk "c.b_mdns_scan" "$(wait_log 'reconnect_lan_scan entries=[1-9]' 45000 && echo 1 || echo 0)" \
    "entries=$(log_field reconnect_lan_scan entries) addrs=$(log_field reconnect_lan_scan addrs)"
  # 链路真的回来了：phase-b 这条日志流是重启之后才开始抓的，所以这条事件不可能是
  # phase A 那次的残留（早先按对端日志「计数增加」判过，时间戳会跨过重启点，判据不干净）。
  chk "c.b_relink" "$(wait_log 'event=peer_bound' 90000 && echo 1 || echo 0)" \
    "重启后 $(log_field peer_bound dev) role=$(log_field peer_bound role)"
  shot "b-p3-after-restart"
  if open_p7; then
    shot "b-p7-off-state"
    chk "b.hint_gone_after_restart" "$(see_text "下次启动生效" 3000 && echo 0 || echo 1)" \
      "设置与内核一致，不再提示"
  fi
  close_p7 || true
  applog > "$ART/logs/phase-b.log" 2>&1
  logstream_stop
}

case "$PHASE" in
  a) phase_a ;;
  b) phase_b ;;
  all) phase_a; phase_b ;;
esac

step "汇总"
cp "$SUMMARY" "$ART/summary-$PHASE.txt" 2>/dev/null || true
say "check pass=$CHECKS_PASS fail=$CHECKS_FAIL"
say "evidence: $ART"
if [ "$CHECKS_FAIL" -eq 0 ] && [ "$CHECKS_PASS" -gt 0 ]; then
  say PASS
  exit 0
fi
say FAIL
exit 1
