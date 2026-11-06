#!/usr/bin/env bash
#
# capture-tracker-ui.sh — P7「跨网发现」关闭态的一张界面取证
#
#   bash windows/scripts/capture-tracker-ui.sh
#   M1_SKIP_BUILD=1 bash windows/scripts/capture-tracker-ui.sh   # 复用已构建的 jar 与 exe
#
# 装置：一个 daemon（127.0.0.1:47260），数据目录里启动前就写好 trackerEnabled=false。
# P7 应该显示：跨网发现开关=关、地址只读、网络状态=仅局域网。
# 出图走壳的离屏截图（Huchuan.exe --shot=…），跟桌面有没有渲染无关，见 docs/windows-ui.md §5.1。
# 产物：artifacts/windows/ui/p7-tracker.png + p7-tracker.log + tracker-index.json
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ART="$ROOT/artifacts/windows/tracker"
UI="$ROOT/artifacts/windows/ui"
BUILD_LOG="$ROOT/artifacts/windows/tracker-build.log"
JAR="$ROOT/daemon/build/dist/daemon.jar"
EXE="$ROOT/windows/build/Huchuan.exe"

# 不用 47200：同机可能已经有别的服务占着默认端口。47260 只给这条取证用，
# 壳通过 --daemon-port 连它（jobs 文件里的 daemonPort 与这里必须一致）。
PORT=47260
MSG_PORT=47510
BT_PORT=51810
# 这个装置自己的设备名：同机可能还有别的 daemon，health 的 deviceName 用来认领
# 「这个端口上应答的是不是我拉起的那个进程」（MSYS 的 $! 不等于 java.exe 的 Windows pid）。
DEVICE_NAME=Tracker-Off
# 与 daemon 的 SettingsStore.DEFAULT_TRACKER_BASE_URL 同一口径：Pages 入口（不是 workers.dev）。
TRACKER_URL="https://huchuan-a9f.pages.dev"

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

# 任意本地地址上的监听都算占用：同机服务可能绑在 0.0.0.0 或 LAN 地址上，
# 只查 127.0.0.1 会漏掉，随后在 bind 时才失败。
port_pid() { netstat -ano 2>/dev/null | grep -E "TCP +[^ ]+:$1 +[^ ]+ +LISTENING" | awk '{print $NF}' | head -1; }
port_busy() { [ -n "$(port_pid "$1")" ]; }
kill_port() {
  local pid
  pid=$(port_pid "$1")
  [ -n "$pid" ] || return 0
  taskkill //PID "$pid" //F >/dev/null 2>&1 || true
}

cleanup() {
  if [ -n "$PID" ]; then
    kill -9 "$PID" >/dev/null 2>&1 || true
    PID=""
  fi
  kill_port "$PORT"
  sleep 0.5
}
trap cleanup EXIT

# Windows 原生程序（java.exe / Huchuan.exe）只认 Windows 路径：统一给 E:/a/b 形式。
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
bval() { printf '%s' "$1" | grep -o "\"$2\":\\(true\\|false\\)" | head -1 | sed 's/^[^:]*://'; }

wait_port_free() { # wait_port_free <端口> <秒>
  local port=$1 limit=$2 i=0
  while [ "$i" -lt "$((limit * 2))" ]; do
    port_busy "$port" || return 0
    sleep 0.5
    i=$((i + 1))
  done
  return 1
}

# 只在「这个端口上应答的确实是我们拉起的那个进程」时算成功：同机可能有别的 daemon 占着同一
# 端口，只看 health 会把别人的进程当成自己的。认领靠 --device-name 给的这个装置专用名字。
wait_own_health() { # wait_own_health <端口> <deviceName> <秒>
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
    # 自己的进程已经因为端口被占退出，就别再等满超时
    grep -q 'E_PORT_IN_USE' "$ART/daemon.out" 2>/dev/null && return 1
    sleep 0.5
    i=$((i + 1))
  done
  return 1
}

# 依赖比产物新就要重建：页面是 jar 与 exe 各自打包的那一份，拿旧的出图等于验旧页面。
newer_than() { # newer_than <产物> <源码路径...>
  local target=$1
  shift
  find "$@" -type f -newer "$target" -print -quit 2>/dev/null | grep -q .
}

say "=== P7 跨网发现（关闭态）界面取证 ==================================="
say "root: $ROOT"

command -v java >/dev/null 2>&1 || die "java 不在 PATH（要 JDK 17+）"
command -v curl >/dev/null 2>&1 || die "curl 不在 PATH"
command -v powershell.exe >/dev/null 2>&1 || die "powershell.exe 不在 PATH（出图要靠它驱动壳）"
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
mkdir -p "$ART/data" "$UI" || die "建不出取证目录"
# daemon 启动前就关掉跨网：这条设置只在建内核时读一次（docs/daemon-api.md §3.1）。
printf '%s' '{"format":"huchuan-daemon-settings/1","trackerEnabled":false,"trackerBaseUrl":"'"$TRACKER_URL"'"}' > "$ART/data/settings.json"

say "--- 起 daemon（api=$PORT msg=$MSG_PORT bt=$BT_PORT，trackerEnabled=false）"
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
# P7 的用户名取 userName，留空就显示设备名（这里设备名是取证标记，见上面的 DEVICE_NAME）。
post "http://127.0.0.1:$PORT/api/settings" '{"userName":"WIN11-0715"}' > /dev/null

# 建一个群：P7 的通知一节要有对象，同时证明「关了 tracker 建群也不会发注册请求」。
printf '%s' '{"name":"跨网关闭"}' > "$ART/group.json"
CREATED=$(post_json "http://127.0.0.1:$PORT/api/groups" "$ART/group.json")
GID=$(sval "$CREATED" id)
chk "setup.group_created (gid=$GID)" "$([ -n "$GID" ] && echo 1 || echo 0)"

TRACKER=$(get "http://127.0.0.1:$PORT/api/settings/tracker")
T_ENABLED=$(bval "$TRACKER" enabled)
T_ACTIVE=$(bval "$TRACKER" active)
chk "tracker.off_read_back (enabled=$T_ENABLED active=$T_ACTIVE)" \
  "$([ "$T_ENABLED" = "false" ] && [ "$T_ACTIVE" = "false" ] && echo 1 || echo 0)"
chk "daemon.kernel_started_tracker_off" \
  "$(grep -q 'event=kernel_started .*tracker=off' "$ART/daemon.log" 2>/dev/null && echo 1 || echo 0)"
sleep 2
chk "daemon.no_tracker_service" \
  "$(grep -q 'event=tracker_service_started' "$ART/daemon.log" 2>/dev/null && echo 0 || echo 1)"
chk "daemon.no_tracker_request" \
  "$(grep -q 'event=tracker_register_failed\|event=tracker_register \|event=tracker_announce' "$ART/daemon.log" 2>/dev/null && echo 0 || echo 1)"

# ------------------------------------------------------------------ 出图
say "--- 离屏截图 P7（一个一次性进程）"
rm -f "$UI/p7-tracker.png" "$UI/p7-tracker.log"
if ! powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(winpath "$SCRIPT_DIR/capture-ui.ps1")" \
      -ExePath "$(winpath "$EXE")" -OutDir "$(winpath "$UI")" \
      -JobsPath "$(winpath "$SCRIPT_DIR/ui-capture-tracker.json")" \
      -IndexName tracker-index.json > "$ART/capture.stdout.log" 2>&1; then
  tail -n 25 "$ART/capture.stdout.log" >&2
  note "截图失败，完整日志：$ART/capture.stdout.log"
fi
SHOT_BYTES=0
[ -f "$UI/p7-tracker.png" ] && SHOT_BYTES=$(stat -c %s "$UI/p7-tracker.png")
SHOT_FAILED=$(tr -d '\r' < "$UI/tracker-index.json" 2>/dev/null | sed -n 's/.*"failed": *\([0-9]*\).*/\1/p' | head -n1)
chk "shot.p7_tracker (${SHOT_BYTES} 字节)" "$([ "$SHOT_BYTES" -gt 20000 ] && echo 1 || echo 0)"
chk "shot.no_failed_page (failed=${SHOT_FAILED:-?})" "$([ "${SHOT_FAILED:-1}" = "0" ] && echo 1 || echo 0)"
# 页面文案的三条硬证据：截图那一刻的 DOM 探针（capture-ui.ps1 原样存成 p7-tracker.log）
chk "shot.shows_tracker_switch" \
  "$(grep -q '跨网发现' "$UI/p7-tracker.log" 2>/dev/null && echo 1 || echo 0)"
chk "shot.shows_lan_only" \
  "$(grep -q '仅局域网' "$UI/p7-tracker.log" 2>/dev/null && echo 1 || echo 0)"
chk "shot.shows_readonly_base" \
  "$(grep -qF "$TRACKER_URL" "$UI/p7-tracker.log" 2>/dev/null && echo 1 || echo 0)"

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
say "截图：$UI/p7-tracker.png"
say "日志：$ART/daemon.log  $UI/p7-tracker.log"
if [ "$BAD" -eq 0 ]; then
  say "PASS"
  exit 0
fi
say "FAIL"
exit 1
