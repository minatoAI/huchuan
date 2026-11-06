#!/usr/bin/env bash
#
# capture-share-ui.sh — P8 链接分享 / P9 分享管理的两张界面取证
#
#   bash windows/scripts/capture-share-ui.sh
#   M1_SKIP_BUILD=1 bash windows/scripts/capture-share-ui.sh   # 复用已构建的 jar 与 exe
#
# 为什么不进构建门禁：这两张要真的向 Pages 的 Worker 登记一条 7 天有效链接
# （p8-share / p9-list 两个取证别名会先确保 daemon 里有一条有效分享，没有就建一条）。
# 构建不该依赖外网与 pages.dev 的可用性，所以门禁仍是 16 张（scripts/build-windows.sh），
# 这两张在这里单独跑、单独出 share-index.json。pages.dev 不可达时本脚本会明确失败。
#
# 装置：一个 daemon（127.0.0.1:47270），一个群 + 一个 1 MiB 私有文件。
# 出图走壳的离屏截图（Huchuan.exe --shot=…），见 docs/windows-ui.md §5.1 / §6.4。
# 产物：artifacts/windows/ui/{p8-share,p9-list}.png + 同名 .log + share-index.json
# 收尾会把自己登记的那条链接撤销（不在 Worker 上留 7 天垃圾记录）。
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ART="$ROOT/artifacts/windows/share-capture"
UI="$ROOT/artifacts/windows/ui"
BUILD_LOG="$ROOT/artifacts/windows/share-build.log"
JAR="$ROOT/daemon/build/dist/daemon.jar"
EXE="$ROOT/windows/build/Huchuan.exe"

# 不用 47200：同机可能已经有别的服务占着默认端口。47270 只给这条取证用，
# 壳通过 --daemon-port 连它（jobs 文件里的 daemonPort 与这里必须一致）。
PORT=47270
MSG_PORT=47520
BT_PORT=51820
DEVICE_NAME=Share-Evidence

WEB_NAME=payload-share.bin
WEB_BYTES=1048576
GROUP_NAME=链接分享取证

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

# 任意本地地址上的监听都算占用：同机服务可能绑在 0.0.0.0 或 LAN 地址上。
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
post() { curl -s -m 60 -X POST -H 'Content-Type: application/json' --data-binary "$2" "$1"; }
post_json() { curl -s -m 60 -X POST -H 'Content-Type: application/json' --data-binary "@$(winpath "$2")" "$1"; }
del() { curl -s -m 30 -X DELETE "$1"; }
sval() { printf '%s' "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed 's/^[^:]*:"//; s/"$//'; }
bval() { printf '%s' "$1" | grep -o "\"$2\":\(true\|false\)" | head -1 | sed 's/^[^:]*://'; }
ival() { printf '%s' "$1" | grep -o "\"$2\":[0-9][0-9]*" | head -1 | sed 's/^[^:]*://'; }

wait_port_free() { # wait_port_free <端口> <秒>
  local port=$1 limit=$2 i=0
  while [ "$i" -lt "$((limit * 2))" ]; do
    port_busy "$port" || return 0
    sleep 0.5
    i=$((i + 1))
  done
  return 1
}

# 只认「这个端口上应答的确实是我们拉起的那个进程」：认领靠 --device-name。
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

say "=== P8 / P9 链接分享界面取证 ========================================="
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
elif newer_than "$JAR" "$ROOT/daemon/src" "$ROOT/core/src" "$ROOT/windows/resources/web" \
      "$ROOT/daemon/build.gradle.kts" "$ROOT/core/build.gradle.kts" "$ROOT/build.gradle.kts" \
      "$ROOT/settings.gradle.kts" "$ROOT/gradle.properties" "$ROOT/gradle/libs.versions.toml"; then
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

say "--- 起 daemon（api=$PORT msg=$MSG_PORT bt=$BT_PORT）"
STARTED=0
for attempt in 1 2 3; do
  wait_port_free "$PORT" 60 || break
  java -jar "$(w "$JAR")" \
    --data-dir="$(w "$ART/data")" --port="$PORT" --msg-port="$MSG_PORT" --bt-port="$BT_PORT" \
    --device-name="$DEVICE_NAME" --log="$(w "$ART/daemon.log")" > "$ART/daemon.out" 2>&1 &
  PID=$!
  disown 2>/dev/null || true
  if wait_own_health "$PORT" "$DEVICE_NAME" 60 > /dev/null; then
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

# 一个群 + 一个私有文件：p8-share 别名要拿它建链接（daemon 只对「本机有副本」的条目建链接）。
printf '%s' '{"name":"'"$GROUP_NAME"'"}' > "$ART/group.json"
CREATED=$(post_json "http://127.0.0.1:$PORT/api/groups" "$ART/group.json")
GID=$(sval "$CREATED" id)
chk "setup.group_created (gid=$GID)" "$([ -n "$GID" ] && echo 1 || echo 0)"
head -c "$WEB_BYTES" /dev/urandom > "$ART/$WEB_NAME" || die "生成测试文件失败"
ADDED=$(post "http://127.0.0.1:$PORT/api/groups/$GID/files" "{\"path\":\"$(w "$ART/$WEB_NAME")\"}")
FID=$(sval "$ADDED" id)
chk "setup.file_shared (fid=$FID)" "$([ -n "$FID" ] && echo 1 || echo 0)"

# 链接层可用性：不可用（缺 webrtc 原生库 / daemon 还在启动）时这两张没有意义，早点失败。
SHARES0=$(get "http://127.0.0.1:$PORT/api/shares")
AVAILABLE=$(bval "$SHARES0" available)
REASON=$(sval "$SHARES0" reason)
chk "setup.link_available (available=$AVAILABLE reason=$REASON)" "$([ "$AVAILABLE" = "true" ] && echo 1 || echo 0)"
[ "$AVAILABLE" = "true" ] || die "链接分享不可用（reason=$REASON）：检查 WebRTC 原生库与 daemon 日志"

# ------------------------------------------------------------------ 出图
say "--- 离屏截图 P8 / P9（每页一个一次性进程；p8-share / p9-list 是取证别名）"
rm -f "$UI/p8-share.png" "$UI/p8-share.log" "$UI/p9-list.png" "$UI/p9-list.log"
if ! powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(winpath "$SCRIPT_DIR/capture-ui.ps1")" \
      -ExePath "$(winpath "$EXE")" -OutDir "$(winpath "$UI")" \
      -JobsPath "$(winpath "$SCRIPT_DIR/ui-capture-share.json")" \
      -IndexName share-index.json > "$ART/capture.stdout.log" 2>&1; then
  tail -n 25 "$ART/capture.stdout.log" >&2
  note "截图失败，完整日志：$ART/capture.stdout.log"
fi

P8_BYTES=0
P9_BYTES=0
[ -f "$UI/p8-share.png" ] && P8_BYTES=$(stat -c %s "$UI/p8-share.png")
[ -f "$UI/p9-list.png" ] && P9_BYTES=$(stat -c %s "$UI/p9-list.png")
SHOT_FAILED=$(tr -d '\r' < "$UI/share-index.json" 2>/dev/null | sed -n 's/.*"failed": *\([0-9]*\).*/\1/p' | head -n1)
SHOT_MODE=$(tr -d '\r' < "$UI/share-index.json" 2>/dev/null | sed -n 's/.*"mode": *"\([a-z]*\)".*/\1/p' | head -n1)
SHOT_COUNT=$(tr -d '\r' < "$UI/share-index.json" 2>/dev/null | sed -n 's/.*"count": *\([0-9]*\).*/\1/p' | head -n1)
[ -z "$SHOT_FAILED" ] && SHOT_FAILED=1
# 字节数只是"文件写出了东西"的兜底：像素判据在 share-index.json 里（颜色数 / 亮度），
# P9 大半是底色，PNG 只有约 20 KB，所以门槛按 10 KB 定。
chk "shot.p8_share ($P8_BYTES 字节)" "$([ "$P8_BYTES" -gt 10000 ] && echo 1 || echo 0)"
chk "shot.p9_list ($P9_BYTES 字节)" "$([ "$P9_BYTES" -gt 10000 ] && echo 1 || echo 0)"
chk "shot.offscreen_index (mode=$SHOT_MODE count=$SHOT_COUNT)" \
  "$([ "$SHOT_MODE" = "offscreen" ] && [ "$SHOT_COUNT" = "2" ] && echo 1 || echo 0)"
chk "shot.no_failed_page (failed=$SHOT_FAILED)" "$([ "$SHOT_FAILED" = "0" ] && echo 1 || echo 0)"

# 页面文案的硬证据：截图那一刻的 DOM 探针（capture-ui.ps1 原样存成 <页名>.log）。
chk "shot.p8_shows_copy_button" "$(grep -q '复制链接' "$UI/p8-share.log" 2>/dev/null && echo 1 || echo 0)"
chk "shot.p8_shows_waiting" "$(grep -q '等待访客' "$UI/p8-share.log" 2>/dev/null && echo 1 || echo 0)"
chk "shot.p8_shows_expiry" "$(grep -q '天后失效' "$UI/p8-share.log" 2>/dev/null && echo 1 || echo 0)"
chk "shot.p9_shows_file_name" "$(grep -qF "$WEB_NAME" "$UI/p9-list.log" 2>/dev/null && echo 1 || echo 0)"
chk "shot.p9_shows_visits" "$(grep -q '被访问 0 次' "$UI/p9-list.log" 2>/dev/null && echo 1 || echo 0)"
chk "shot.p9_shows_revoke" "$(grep -q '撤销' "$UI/p9-list.log" 2>/dev/null && echo 1 || echo 0)"

# 两个别名必须复用同一条分享：创建只发生一次，p8 页面里的链接就是 daemon 日志里那条。
CREATED_COUNT=$(grep -c 'event=link_share_created token=' "$ART/daemon.log" 2>/dev/null || true)
[ -z "$CREATED_COUNT" ] && CREATED_COUNT=0
SHARE_LINK=$(grep 'event=link_share_created token=' "$ART/daemon.log" 2>/dev/null | tail -1 | sed -n 's/.* link=\([^ ]*\).*/\1/p')
chk "daemon.one_share_created (count=$CREATED_COUNT)" "$([ "$CREATED_COUNT" = "1" ] && echo 1 || echo 0)"
chk "daemon.share_link_logged" "$([ -n "$SHARE_LINK" ] && echo 1 || echo 0)"
chk "shot.p8_link_matches_daemon" "$(grep -qF "$SHARE_LINK" "$UI/p8-share.log" 2>/dev/null && echo 1 || echo 0)"

# API 侧对齐：列表里只有这一条，状态 waiting、被访问 0 次。
SHARES=$(get "http://127.0.0.1:$PORT/api/shares")
TOKEN=$(sval "$SHARES" token)
STATUS=$(sval "$SHARES" status)
TRANSFERS=$(ival "$SHARES" transfers)
TOKEN_LEN=$(printf '%s' "$TOKEN" | wc -c | tr -d ' ')
chk "api.share_listed (token_len=$TOKEN_LEN status=$STATUS transfers=$TRANSFERS)" \
  "$([ "$TOKEN_LEN" = "26" ] && [ "$STATUS" = "waiting" ] && [ "$TRANSFERS" = "0" ] && echo 1 || echo 0)"

# ------------------------------------------------------------------ 收尾
say "--- 撤销这条取证用的链接（不在 Worker 上留 7 天记录）"
if [ -n "$TOKEN" ]; then
  REVOKED=$(del "http://127.0.0.1:$PORT/api/shares/$TOKEN")
  REMOTE=$(bval "$REVOKED" remoteRevoked)
  chk "cleanup.revoked_remote (remoteRevoked=$REMOTE)" "$([ "$REMOTE" = "true" ] && echo 1 || echo 0)"
fi

say "--- 停 daemon"
curl -s -m 10 -X POST -H 'Content-Type: application/json' --data-binary '{}' \
  "http://127.0.0.1:$PORT/api/shutdown" > /dev/null 2>&1 || true
i=0
while [ "$i" -lt 20 ]; do
  port_busy "$PORT" || break
  sleep 0.5
  i=$((i + 1))
done
chk "shutdown.daemon_stopped" "$(port_busy "$PORT" && echo 0 || echo 1)"
PID=""
cleanup
trap - EXIT

say ""
say "断言：通过 $OK / 失败 $BAD"
say "截图：$UI/p8-share.png  $UI/p9-list.png"
say "索引：$UI/share-index.json    日志：$ART/daemon.log  $UI/p8-share.log  $UI/p9-list.log"
if [ "$BAD" -eq 0 ]; then
  say "PASS"
  exit 0
fi
say "FAIL"
exit 1
