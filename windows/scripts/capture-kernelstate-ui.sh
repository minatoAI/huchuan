#!/usr/bin/env bash
#
# capture-kernelstate-ui.sh — 内核态（kernelState）两态取证：acquiring（正在寻找来源）与 parked（休眠）
#
#   bash windows/scripts/capture-kernelstate-ui.sh
#   M1_SKIP_BUILD=1 bash windows/scripts/capture-kernelstate-ui.sh   # 复用已构建的 jar 与 exe
#
# 装置：一个 daemon（127.0.0.1:47280），群里只有一条**没有来源**的公开磁力条目
# （随机 infohash + timeoutMs=0）。脚本对它 POST /active {active:true}，内核态当场变成
# acquiring：元数据还没到手，state 仍是 sleeping、role 仍是 none、字节都是 0，但用户刚点了激活，
# 界面要说「正在寻找来源」而不是「休眠」（docs/daemon-api.md §3.3、docs/core.md §3.12）。
#
# 三张图（走壳的离屏截图，跟桌面有没有渲染无关，见 docs/windows-ui.md §5.1）：
#   p3-acquiring.png  P3 行状态 chip「正在寻找来源」（尺寸仍不显示）
#   p4-acquiring.png  P4 chip「正在寻找来源」+ 激活开关保持「已激活」
#   p3-parked.png     P3 行 chip「休眠」（kernelState=parked，没有任何 API 造得出来，见下）
#
# parked 为什么用页面夹具：daemon 的正常流程走不到这个态 —— 磁力导入元数据一到就摘任务（absent），
# 点激活是 acquiring / downloading，私有文件挂进会话就直接做种。core 的 GroupRuntime.kernelState
# 读得到它（daemon 照实投成 paused=true），但 API 没有哪一步能让它停在这。所以这一张走取证别名
# p3-parked，由 app.js 的 fixtureGroup() 造一条与 daemon 同口径的条目（文档 §6.5）。
#
# 除截图外还有一层机读断言：Edge headless + CDP（windows/scripts/kernel-state-probe.mjs）读回
# P3 的 chip 文案、P4 开关的 aria-checked，以及六个 kernelState 值的映射表。
#
# 产物：artifacts/windows/ui/{p3-acquiring,p4-acquiring,p3-parked}.png 与同名 .log
#       artifacts/windows/ui/kernelstate-index.json
#       artifacts/windows/kernelstate/{daemon.log,api-*.json,probe.log}
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ART="$ROOT/artifacts/windows/kernelstate"
UI="$ROOT/artifacts/windows/ui"
BUILD_LOG="$ROOT/artifacts/windows/kernelstate-build.log"
JAR="$ROOT/daemon/build/dist/daemon.jar"
EXE="$ROOT/windows/build/Huchuan.exe"

# 不用 47200：同机可能已经有别的服务占着默认端口。47280 只给这条取证用，
# 壳通过 --daemon-port 连它（jobs 文件里的 daemonPort 与这里必须一致）。
PORT=47280
MSG_PORT=47520
BT_PORT=51820
CDP_PORT=9338
# 这台装置自己的设备名：health 用它认领「这个端口上应答的是不是我拉起的那个进程」。
DEVICE_NAME=KernelState-Evidence
EDGE="/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"

OK=0
BAD=0

say() { printf '%s\n' "$*"; }
chk() { # chk <名字> <1=通过 0=失败>
  if [ "$2" = "1" ]; then
    say "  [ok]   $1"
    OK=$((OK + 1))
  else
    say "  [FAIL] $1"
    BAD=$((BAD + 1))
  fi
}
note() { say "  [note] $*"; }
die() {
  say ""
  say "FATAL: $*"
  cleanup
  trap - EXIT
  say "FAIL"
  exit 1
}

PID=""
EDGE_PID=""

port_pid() { netstat -ano 2>/dev/null | grep -E "TCP +[^ ]+:$1 +[^ ]+ +LISTENING" | awk '{print $NF}' | head -1; }
port_busy() { [ -n "$(port_pid "$1")" ]; }
kill_port() {
  local pid
  pid=$(port_pid "$1")
  [ -n "$pid" ] || return 0
  taskkill //PID "$pid" //F >/dev/null 2>&1 || true
}

cleanup() {
  if [ -n "$EDGE_PID" ]; then
    kill -9 "$EDGE_PID" >/dev/null 2>&1 || true
    EDGE_PID=""
  fi
  # Edge 的子进程按命令行精确匹配（不按镜像名清进程：同机还有别的 agent 的浏览器）
  powershell.exe -NoProfile -Command 'Get-CimInstance Win32_Process | Where-Object { $_.Name -eq "msedge.exe" -and $_.CommandLine -like "*kernelstate*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }' >/dev/null 2>&1 || true
  if [ -n "$PID" ]; then
    kill -9 "$PID" >/dev/null 2>&1 || true
    PID=""
  fi
  kill_port "$PORT"
  sleep 0.5
}
trap cleanup EXIT

w() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}
winpath() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

get() { curl -s -m 15 "$1"; }
post() { curl -s -m 30 -X POST -H 'Content-Type: application/json' --data-binary "$2" "$1"; }
post_json() { curl -s -m 30 -X POST -H 'Content-Type: application/json' --data-binary "@$(winpath "$2")" "$1"; }
sval() { printf '%s' "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed 's/^[^:]*:"//; s/"$//'; }
nval() { printf '%s' "$1" | grep -o "\"$2\":[0-9]*" | head -1 | sed 's/^[^:]*://'; }

wait_port_free() {
  local port=$1 limit=$2 i=0
  while [ "$i" -lt "$((limit * 2))" ]; do
    port_busy "$port" || return 0
    sleep 0.5
    i=$((i + 1))
  done
  return 1
}

# 只在「这个端口上应答的确实是我们拉起的那个进程」时算成功（同机可能有别的 daemon）。
wait_own_health() {
  local port=$1 want=$2 limit=$3 i=0 body=""
  while [ "$i" -lt "$((limit * 2))" ]; do
    body=$(curl -s -m 2 "http://127.0.0.1:$port/api/health" 2>/dev/null || true)
    case "$body" in
      *'"status":"ok"'*)
        if [ "$(sval "$body" deviceName)" = "$want" ]; then
          printf '%s' "$body"
          return 0
        fi
        ;;
    esac
    grep -q 'E_PORT_IN_USE' "$ART/daemon.out" 2>/dev/null && return 1
    sleep 0.5
    i=$((i + 1))
  done
  return 1
}

newer_than() {
  local target=$1
  shift
  find "$@" -type f -newer "$target" -print -quit 2>/dev/null | grep -q .
}

say "=== 内核态（acquiring / parked）界面取证 ==============================="
say "root: $ROOT"

command -v java >/dev/null 2>&1 || die "java 不在 PATH（要 JDK 17+）"
command -v curl >/dev/null 2>&1 || die "curl 不在 PATH"
command -v node >/dev/null 2>&1 || die "node 不在 PATH（CDP 那条断言要它）"
command -v powershell.exe >/dev/null 2>&1 || die "powershell.exe 不在 PATH（出图要靠它驱动壳）"
[ -x "$EDGE" ] || note "没找到 Edge：$EDGE（没有它就只能出图，读不回 aria-checked）"
if ! wait_port_free "$PORT" 60; then
  die "端口 $PORT 一直被占用（pid $(port_pid "$PORT")）：先停掉手动起的 daemon 再跑本脚本。"
fi

mkdir -p "$ROOT/artifacts/windows" || die "建不出 artifacts/windows"
MAYBE_SKIP=$(printenv M1_SKIP_BUILD 2>/dev/null || true)

JAR_STALE=0
if [ ! -f "$JAR" ]; then
  JAR_STALE=1
elif newer_than "$JAR" "$ROOT/daemon/src" "$ROOT/core/src" "$ROOT/windows/resources/web"; then
  JAR_STALE=1
fi
if [ "$JAR_STALE" = "1" ] && [ "$MAYBE_SKIP" != "1" ]; then
  say "--- 构建 :daemon:daemonJar（源码或页面比 jar 新）"
  ( cd "$ROOT" && ./gradlew --console=plain -q :daemon:daemonJar ) || die "gradle :daemon:daemonJar 失败"
fi
[ -f "$JAR" ] || die "没有 daemon.jar：$JAR"

EXE_STALE=0
if [ ! -f "$EXE" ]; then
  EXE_STALE=1
elif newer_than "$EXE" "$ROOT/windows/resources/web" "$ROOT/windows/src" "$ROOT/windows/CMakeLists.txt"; then
  EXE_STALE=1
fi
if [ "$EXE_STALE" = "1" ] && [ "$MAYBE_SKIP" != "1" ]; then
  say "--- 编译壳（scripts/build-windows.sh --no-evidence）"
  bash "$ROOT/scripts/build-windows.sh" --no-evidence > "$BUILD_LOG" 2>&1 \
    || { tail -n 20 "$BUILD_LOG" >&2; die "壳编译失败（日志 $BUILD_LOG）"; }
fi
[ -f "$EXE" ] || die "没有壳：$EXE（先跑 bash scripts/build-windows.sh --no-evidence）"

# ------------------------------------------------------------------ 装置
say "--- 清场"
rm -rf "$ART" || die "删不掉 $ART"
mkdir -p "$ART/data" "$ART/edge" "$UI" || die "建不出取证目录"

say "--- 起 daemon（api=$PORT msg=$MSG_PORT bt=$BT_PORT）"
STARTED=0
for attempt in 1 2 3; do
  wait_port_free "$PORT" 60 || break
  java -jar "$(w "$JAR")" \
    --data-dir="$(w "$ART/data")" --port="$PORT" --msg-port="$MSG_PORT" --bt-port="$BT_PORT" \
    --device-name="$DEVICE_NAME" --log="$(w "$ART/daemon.log")" > "$ART/daemon.out" 2>&1 &
  PID=$!
  disown 2>/dev/null || true
  if wait_own_health "$PORT" "$DEVICE_NAME" 30 > /dev/null; then
    STARTED=1
    break
  fi
  note "第 $attempt 次起不来（$(tail -2 "$ART/daemon.out" 2>/dev/null | tr '\n' ' ')）"
  kill -9 "$PID" >/dev/null 2>&1 || true
  kill_port "$PORT"
  PID=""
  sleep 3
done
[ "$STARTED" = "1" ] || die "daemon 起不来（$ART/daemon.out）"
post "http://127.0.0.1:$PORT/api/settings" '{"userName":"WIN11-0715"}' > /dev/null

printf '%s' '{"name":"可见性"}' > "$ART/group.json"
CREATED=$(post_json "http://127.0.0.1:$PORT/api/groups" "$ART/group.json")
GID=$(sval "$CREATED" id)
chk "setup.group_created (gid=$GID)" "$([ -n "$GID" ] && echo 1 || echo 0)"

# 没有来源的公开磁力：随机 infohash、timeoutMs=0（不等元数据）。条目已建好、内核在找源。
HASH=$(head -c 20 /dev/urandom | od -An -tx1 | tr -d ' \n')
printf '{"magnet":"magnet:?xt=urn:btih:%s&dn=yuanfang","name":"远方 4K.mp4","timeoutMs":0}' "$HASH" \
  > "$ART/magnet.json"
IMPORTED=$(post_json "http://127.0.0.1:$PORT/api/groups/$GID/files/import-magnet" "$ART/magnet.json")
printf '%s' "$IMPORTED" > "$ART/api-import.json"
chk "api.public_entry_created (fid=$HASH)" \
  "$([ "$(sval "$IMPORTED" scope)" = "public" ] && echo 1 || echo 0)"
chk "api.waiting_before_activate (kernelState=$(sval "$IMPORTED" kernelState))" \
  "$([ "$(sval "$IMPORTED" kernelState)" = "waiting_metadata" ] && echo 1 || echo 0)"
chk "api.no_source (code=$(sval "$IMPORTED" code))" \
  "$([ "$(sval "$IMPORTED" code)" = "no_source" ] && echo 1 || echo 0)"

# 点激活：元数据还没到手，内核态当场变 acquiring，但字节与百分比都不该动。
ACTIVATED=$(post "http://127.0.0.1:$PORT/api/groups/$GID/files/$HASH/active" '{"active":true}')
printf '%s' "$ACTIVATED" > "$ART/api-active.json"
chk "api.activate_acquiring (kernelState=$(sval "$ACTIVATED" kernelState))" \
  "$([ "$(sval "$ACTIVATED" kernelState)" = "acquiring" ] && echo 1 || echo 0)"
chk "api.activate_state_still_sleeping (state=$(sval "$ACTIVATED" state))" \
  "$([ "$(sval "$ACTIVATED" state)" = "sleeping" ] && echo 1 || echo 0)"
chk "api.activate_role_none (role=$(sval "$ACTIVATED" role))" \
  "$([ "$(sval "$ACTIVATED" role)" = "none" ] && echo 1 || echo 0)"
chk "api.activate_bytes_zero (downloaded=$(nval "$ACTIVATED" downloadedBytes) disk=$(nval "$ACTIVATED" diskBytes))" \
  "$([ "$(nval "$ACTIVATED" downloadedBytes)" = "0" ] && [ "$(nval "$ACTIVATED" diskBytes)" = "0" ] && echo 1 || echo 0)"
chk "daemon.log api_file_active kernel_state=acquiring" \
  "$(grep -q 'event=api_file_active .*kernel_state=acquiring' "$ART/daemon.log" 2>/dev/null && echo 1 || echo 0)"

# ------------------------------------------------------------------ 出图
say "--- 离屏截图三张（每张一个一次性进程）"
rm -f "$UI/p3-acquiring.png" "$UI/p4-acquiring.png" "$UI/p3-parked.png"
rm -f "$UI/p3-acquiring.log" "$UI/p4-acquiring.log" "$UI/p3-parked.log"
CAPTURE_RC=0
if ! powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(winpath "$SCRIPT_DIR/capture-ui.ps1")" \
      -ExePath "$(winpath "$EXE")" -OutDir "$(winpath "$UI")" \
      -JobsPath "$(winpath "$SCRIPT_DIR/ui-capture-kernelstate.json")" \
      -IndexName kernelstate-index.json > "$ART/capture.stdout.log" 2>&1; then
  CAPTURE_RC=1
  tail -n 25 "$ART/capture.stdout.log" >&2
  note "截图失败，完整日志：$ART/capture.stdout.log"
fi
SHOT_FAILED=$(tr -d '\r' < "$UI/kernelstate-index.json" 2>/dev/null | sed -n 's/.*"failed": *\([0-9]*\).*/\1/p' | head -n1)
if [ -z "$SHOT_FAILED" ]; then SHOT_FAILED=1; fi
chk "shot.capture_exit (rc=$CAPTURE_RC)" "$([ "$CAPTURE_RC" = "0" ] && echo 1 || echo 0)"
chk "shot.no_failed_page (failed=$SHOT_FAILED)" "$([ "$SHOT_FAILED" = "0" ] && echo 1 || echo 0)"
shot_bytes() { if [ -f "$1" ]; then stat -c %s "$1"; else echo 0; fi; }
for name in p3-acquiring p4-acquiring p3-parked; do
  bytes=$(shot_bytes "$UI/$name.png")
  chk "shot.$name ($bytes 字节)" "$([ "$bytes" -gt 20000 ] && echo 1 || echo 0)"
done

# 页面文案的硬证据：截图那一刻的 DOM 探针（capture-ui.ps1 原样存成同名 .log）
chk "shot.p3_acquiring_chip" \
  "$(grep -q '正在寻找来源' "$UI/p3-acquiring.log" 2>/dev/null && echo 1 || echo 0)"
chk "shot.p3_acquiring_not_sleeping" \
  "$(grep -q '休眠' "$UI/p3-acquiring.log" 2>/dev/null && echo 0 || echo 1)"
chk "shot.p3_acquiring_no_zero_size" \
  "$(grep -q '0 B' "$UI/p3-acquiring.log" 2>/dev/null && echo 0 || echo 1)"
chk "shot.p4_acquiring_chip" \
  "$(grep -q '正在寻找来源' "$UI/p4-acquiring.log" 2>/dev/null && echo 1 || echo 0)"
chk "shot.p4_acquiring_no_disk_line" \
  "$(grep -q '已落盘' "$UI/p4-acquiring.log" 2>/dev/null && echo 0 || echo 1)"
chk "shot.p3_parked_chip" \
  "$(grep -q '休眠' "$UI/p3-parked.log" 2>/dev/null && echo 1 || echo 0)"
chk "shot.p3_parked_not_acquiring" \
  "$(grep -q '正在寻找来源' "$UI/p3-parked.log" 2>/dev/null && echo 0 || echo 1)"

# ------------------------------------------------------------------ CDP：chip / aria-checked / 映射表
say "--- Edge headless + CDP 读回页面（P3 chip、P4 aria-checked、六值映射）"
PROBE_RC=1
if [ -x "$EDGE" ] && command -v node >/dev/null 2>&1; then
  "$EDGE" --headless=new --disable-gpu --remote-debugging-port=$CDP_PORT \
    --user-data-dir="$(winpath "$ART/edge")" --no-first-run --no-default-browser-check \
    "http://127.0.0.1:$PORT/index.html?route=p3" > "$ART/edge.out" 2>&1 &
  EDGE_PID=$!
  CDP_READY=0
  for _ in $(seq 1 60); do
    curl -s -m 2 "http://127.0.0.1:$CDP_PORT/json/version" 2>/dev/null | grep -q webSocketDebuggerUrl && { CDP_READY=1; break; }
    sleep 0.5
  done
  chk "cdp.edge_started (port=$CDP_PORT)" "$([ "$CDP_READY" = "1" ] && echo 1 || echo 0)"
  if [ "$CDP_READY" = "1" ]; then
    CDP_PORT=$CDP_PORT node "$SCRIPT_DIR/kernel-state-probe.mjs" > "$ART/probe.log" 2>&1
    PROBE_RC=$?
  fi
  cat "$ART/probe.log" 2>/dev/null
else
  note "跳过 CDP：没有 Edge 或 node"
fi
chk "cdp.probe_passed (rc=$PROBE_RC)" "$([ "$PROBE_RC" = "0" ] && echo 1 || echo 0)"
chk "cdp.probe_ok_line" \
  "$(grep -q 'KERNEL_STATE_UI_OK' "$ART/probe.log" 2>/dev/null && echo 1 || echo 0)"
chk "cdp.p4_switch_stayed_on" \
  "$(grep -q 'p4 acquiring checked=true' "$ART/probe.log" 2>/dev/null && echo 1 || echo 0)"
chk "cdp.parked_switch_off" \
  "$(grep -q 'parked p4 checked=false' "$ART/probe.log" 2>/dev/null && echo 1 || echo 0)"

# ------------------------------------------------------------------ 收尾
say "--- 停 daemon"
curl -s -m 10 -X POST -H 'Content-Type: application/json' --data-binary '{}' \
  "http://127.0.0.1:$PORT/api/shutdown" > /dev/null 2>&1 || true
i=0
while [ "$i" -lt 20 ]; do
  port_busy "$PORT" || break
  sleep 0.5
  i=$((i + 1))
done
chk "shutdown.daemon_stopped" "$(port_busy $PORT && echo 0 || echo 1)"
PID=""
cleanup
trap - EXIT

say ""
say "断言：通过 $OK / 失败 $BAD"
say "截图：$UI/p3-acquiring.png  $UI/p4-acquiring.png  $UI/p3-parked.png"
say "索引：$UI/kernelstate-index.json"
say "日志：$ART/daemon.log  $ART/probe.log  $UI/p3-acquiring.log  $UI/p4-acquiring.log  $UI/p3-parked.log"
if [ "$BAD" -eq 0 ]; then
  say "PASS"
  exit 0
fi
say "FAIL"
exit 1
