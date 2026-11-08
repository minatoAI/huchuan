#!/usr/bin/env bash
#
# capture-team-ui.sh — 组队通告与限速的界面取证（三个 daemon 一套装置）
#
#   bash windows/scripts/capture-team-ui.sh
#   M1_SKIP_BUILD=1 bash windows/scripts/capture-team-ui.sh   # 复用已构建的 jar 与 exe
#
# 为什么非要三个进程：spec §306 的队友数只数「真互连、同处于该文件『部分』、且不是做种者」的成员。
# 「一端做种、一端下载」永远数出 0（做种者不计入），所以队友 ≥1 的画面必须有第三者：
#   S （api 47201）：做种者，建群、共享 32 MiB
#   C1（api 47200）：下载者 —— 截图截它（壳默认连 47200）
#   C2（api 47202）：下载者 —— C1 的队友
# C1 先加入、C2 后加入：S 收到 C2 的 peer.announce 后转发给 C1，C1 因此拿到 C2 的 BT 端点，
# 内核每 2 s 按成员拨号一次，连上之后 C1 的 teammates=1 → 「组队 2 人」「正在组队 · 2 人」。
#
# 三个都得慢慢下（全局限速 128 KB/s）：一个 32 MiB 文件慢慢下才有一分多钟的窗口用来出图，
# 顺带证明限速真的作用在会话上，而不只是回显一个数字。
#
# 出图走壳的离屏截图（Huchuan.exe --shot=…），跟桌面有没有渲染无关，见 docs/windows-ui.md §5.1。
# 产物：artifacts/windows/ui/{p3-team,p4-team,p7-limits}.png + 同名 .log + teammates-index.json
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ART="$ROOT/artifacts/windows/team"
UI="$ROOT/artifacts/windows/ui"

PORT_S=47201
PORT_C1=47200
PORT_C2=47202
MSG_S=47310
MSG_C1=47300
MSG_C2=47320
BT_S=51510
BT_C1=51500
BT_C2=51520

FILE_MB=32
FILE_BYTES=$((FILE_MB * 1024 * 1024))
# 两个下载者压到 128 KB/s：32 MiB 要下几分钟，出三张图绰绰有余。
THROTTLE_KBPS=128
# P7 那一张要的是「上行 512」，所以出图前把上行改成 512 并读回。
P7_UP_KBPS=512
# P4 那一张要的是「单文件限速已生效」，所以出图前给 C1 的文件设一条限速。
P4_FILE_KBPS=512

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

PID_S=""
PID_C1=""
PID_C2=""

port_pid() { netstat -ano 2>/dev/null | grep -E "TCP +127\\.0\\.0\\.1:$1 +" | grep LISTENING | awk '{print $NF}' | head -1; }
port_busy() { [ -n "$(port_pid "$1")" ]; }
kill_port() {
  local pid
  pid=$(port_pid "$1")
  [ -n "$pid" ] || return 0
  taskkill //PID "$pid" //F >/dev/null 2>&1 || true
}

cleanup() {
  for pid in "$PID_C1" "$PID_C2" "$PID_S"; do
    [ -n "$pid" ] || continue
    kill -9 "$pid" >/dev/null 2>&1 || true
  done
  PID_S=""
  PID_C1=""
  PID_C2=""
  kill_port "$PORT_S"
  kill_port "$PORT_C1"
  kill_port "$PORT_C2"
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
post() { curl -s -m 90 -X POST -H 'Content-Type: application/json' --data-binary "$2" "$1"; }
# 带中文的请求体走文件：直接塞进命令行会被 MSYS 按 ANSI 代码页转码，中文变 ????（P3 标题踩过）。
post_json() { curl -s -m 90 -X POST -H 'Content-Type: application/json' --data-binary "@$(winpath "$2")" "$1"; }
ival() { printf '%s' "$1" | grep -o "\"$2\":[0-9][0-9]*" | head -1 | sed 's/^[^:]*://'; }
bval() { printf '%s' "$1" | grep -o "\"$2\":\(true\|false\)" | head -1 | sed 's/^[^:]*://'; }
sval() { printf '%s' "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed 's/^[^:]*:"//; s/"$//'; }

# /api/state 的第一个群现在是个人空间（isPersonal，docs/design/personal-space.md），它的 teamSize 恒 0 ——
# 「第一个 teamSize」抓到的会是它。要哪个群就按 gid 取，别按字段出现的先后猜。
group_ival() { # group_ival <json 文件> <gid> <字段>
  node -e 'const fs=require("fs");const j=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));const g=(j.data.groups||[]).filter(function(x){return x.id===process.argv[2];})[0];process.stdout.write(g?String(g[process.argv[3]]):"")' "$(winpath "$1")" "$2" "$3"
}

wait_health() { # wait_health <端口> <秒>
  local port=$1 limit=$2 i=0 body=""
  while [ "$i" -lt "$((limit * 2))" ]; do
    body=$(curl -s -m 2 "http://127.0.0.1:$port/api/health" 2>/dev/null || true)
    case "$body" in
      *'"status":"ok"'*) printf '%s' "$body"; return 0 ;;
    esac
    sleep 0.5
    i=$((i + 1))
  done
  return 1
}

say "=== Windows 组队通告 + 限速取证 ====================================="
say "root: $ROOT"

# ------------------------------------------------------------------ 0. 前置
command -v java >/dev/null 2>&1 || die "java 不在 PATH（要 JDK 17+）"
command -v curl >/dev/null 2>&1 || die "curl 不在 PATH"
command -v powershell.exe >/dev/null 2>&1 || die "powershell.exe 不在 PATH（出图要靠它驱动壳）"

for port in "$PORT_S" "$PORT_C1" "$PORT_C2"; do
  if port_busy "$port"; then
    die "端口 $port 已被占用（pid $(port_pid "$port")）：先停掉手动起的 daemon 再跑本脚本。"
  fi
done

# ------------------------------------------------------------------ 1. 构建
JAR="$ROOT/daemon/build/dist/daemon.jar"
MAYBE_SKIP=$(printenv M1_SKIP_BUILD 2>/dev/null || true)
if [ "$MAYBE_SKIP" = "1" ] && [ -f "$JAR" ]; then
  note "跳过构建，复用 $JAR"
else
  say "--- 构建 :daemon:daemonJar"
  ( cd "$ROOT" && ./gradlew --console=plain -q :daemon:daemonJar ) || die "gradle :daemon:daemonJar 失败"
fi
[ -f "$JAR" ] || die "没有 daemon.jar：$JAR"

EXE="$ROOT/windows/build/Huchuan.exe"
BUILD_LOG="$ROOT/artifacts/windows/team-build.log"
if [ ! -f "$EXE" ]; then
  say "--- 没有壳，先编译（scripts/build-windows.sh --no-evidence）"
  mkdir -p "$ROOT/artifacts/windows"
  bash "$ROOT/scripts/build-windows.sh" --no-evidence > "$BUILD_LOG" 2>&1 \
    || die "壳编译失败（日志 $BUILD_LOG）"
fi
[ -f "$EXE" ] || die "没有壳：$EXE（先跑 bash scripts/build-windows.sh --no-evidence）"

# ------------------------------------------------------------------ 2. 装置
say "--- 清场"
rm -rf "$ART" || die "删不掉 $ART"
mkdir -p "$ART/s" "$ART/c1" "$ART/c2" "$ART/share" "$UI" || die "建不出取证目录"
SHARE="$ART/share/payload-${FILE_MB}m.bin"
head -c "$FILE_BYTES" /dev/urandom > "$SHARE" || die "生成测试文件失败"

launch() { # launch <端口> <消息端口> <bt端口> <数据目录> <日志> <设备名>
  java -jar "$(w "$JAR")" \
    --data-dir="$(w "$4")" --port="$1" --msg-port="$2" --bt-port="$3" \
    --device-name="$6" --log="$(w "$5")" > "$5.out" 2>&1 &
  LAST_PID=$!
  disown 2>/dev/null || true
}

say "--- 起 S（api=$PORT_S msg=$MSG_S bt=$BT_S）"
launch "$PORT_S" "$MSG_S" "$BT_S" "$ART/s" "$ART/s.log" Seed-Node
PID_S=$LAST_PID
say "--- 起 C1（api=$PORT_C1 msg=$MSG_C1 bt=$BT_C1）"
launch "$PORT_C1" "$MSG_C1" "$BT_C1" "$ART/c1" "$ART/c1.log" Client-1
PID_C1=$LAST_PID
say "--- 起 C2（api=$PORT_C2 msg=$MSG_C2 bt=$BT_C2）"
launch "$PORT_C2" "$MSG_C2" "$BT_C2" "$ART/c2" "$ART/c2.log" Client-2
PID_C2=$LAST_PID

HS=$(wait_health "$PORT_S" 60) || die "S 起不来（$(tail -3 "$ART/s.log.out" 2>/dev/null | tr '\n' ' ')）"
HC1=$(wait_health "$PORT_C1" 60) || die "C1 起不来（$(tail -3 "$ART/c1.log.out" 2>/dev/null | tr '\n' ' ')）"
HC2=$(wait_health "$PORT_C2" 60) || die "C2 起不来（$(tail -3 "$ART/c2.log.out" 2>/dev/null | tr '\n' ' ')）"
S_MSG=$(ival "$HS" msgPort)

# ------------------------------------------------------------------ 3. S 建群 + 共享
say "--- S 建群并共享 ${FILE_MB} MiB"
printf '%s' '{"name":"组队取证"}' > "$ART/create-group.json"
CREATED=$(post_json "http://127.0.0.1:$PORT_S/api/groups" "$ART/create-group.json")
GID=$(sval "$CREATED" id)
TAIL8=$(sval "$CREATED" inviteTail8)
chk "setup.group_created (gid=$GID)" "$([ -n "$GID" ] && echo 1 || echo 0)"
ADDED=$(post "http://127.0.0.1:$PORT_S/api/groups/$GID/files" "{\"path\":\"$(w "$SHARE")\"}")
FID=$(sval "$ADDED" id)
chk "setup.file_shared (fid=$FID)" "$([ -n "$FID" ] && echo 1 || echo 0)"

# ------------------------------------------------------------------ 4. 两个下载者入群
say "--- C1、C2 入群（顺序有意义：C2 后加入，S 会把它的 BT 端点转给 C1）"
J1=$(post "http://127.0.0.1:$PORT_C1/api/join" "{\"tail8\":\"$TAIL8\",\"manual\":[\"127.0.0.1:$S_MSG\"],\"timeoutMs\":25000}")
chk "setup.c1_joined (gid=$(sval "$J1" id))" "$([ "$(sval "$J1" id)" = "$GID" ] && echo 1 || echo 0)"
J2=$(post "http://127.0.0.1:$PORT_C2/api/join" "{\"tail8\":\"$TAIL8\",\"manual\":[\"127.0.0.1:$S_MSG\"],\"timeoutMs\":25000}")
chk "setup.c2_joined (gid=$(sval "$J2" id))" "$([ "$(sval "$J2" id)" = "$GID" ] && echo 1 || echo 0)"

# ------------------------------------------------------------------ 5. 全局限速（顺便把传输压慢）
say "--- 两个下载者全局限速 $THROTTLE_KBPS KB/s（读回值）"
L1=$(post "http://127.0.0.1:$PORT_C1/api/settings/limits" "{\"uploadKbps\":$THROTTLE_KBPS,\"downloadKbps\":$THROTTLE_KBPS}")
L2=$(post "http://127.0.0.1:$PORT_C2/api/settings/limits" "{\"uploadKbps\":$THROTTLE_KBPS,\"downloadKbps\":$THROTTLE_KBPS}")
chk "limits.c1_readback ($(ival "$L1" sessionUploadLimitKbps)/$(ival "$L1" sessionDownloadLimitKbps))" \
  "$([ "$(ival "$L1" sessionUploadLimitKbps)" = "$THROTTLE_KBPS" ] && echo 1 || echo 0)"
chk "limits.c2_readback ($(ival "$L2" sessionDownloadLimitKbps))" \
  "$([ "$(ival "$L2" sessionDownloadLimitKbps)" = "$THROTTLE_KBPS" ] && echo 1 || echo 0)"

# ------------------------------------------------------------------ 6. 两个下载者开始下
say "--- 等文件清单到 C1、C2"
wait_file_visible() { # <端口> <gid> <fid> <秒>
  local port=$1 gid=$2 fid=$3 limit=$4 i=0 body=""
  while [ "$i" -lt "$((limit * 2))" ]; do
    body=$(get "http://127.0.0.1:$port/api/groups/$gid/files/$fid")
    case "$body" in *"\"id\":\"$fid\""*) return 0 ;; esac
    sleep 0.5
    i=$((i + 1))
  done
  return 1
}
wait_file_visible "$PORT_C1" "$GID" "$FID" 40 || die "C1 一直没收到文件清单（$ART/c1.log）"
wait_file_visible "$PORT_C2" "$GID" "$FID" 40 || die "C2 一直没收到文件清单（$ART/c2.log）"

say "--- C1、C2 激活下载"
A1=$(post "http://127.0.0.1:$PORT_C1/api/groups/$GID/files/$FID/active" '{"active":true}')
A2=$(post "http://127.0.0.1:$PORT_C2/api/groups/$GID/files/$FID/active" '{"active":true}')
chk "download.c1_activated (state=$(sval "$A1" state))" "$([ "$(sval "$A1" state)" = "downloading" ] && echo 1 || echo 0)"
chk "download.c2_activated (state=$(sval "$A2" state))" "$([ "$(sval "$A2" state)" = "downloading" ] && echo 1 || echo 0)"

say "--- 等 C1 看到队友（内核观测口径）"
TEAM_MAX=0
i=0
while [ "$i" -lt 180 ]; do
  STATE_C1=$(get "http://127.0.0.1:$PORT_C1/api/state")
  T=$(ival "$STATE_C1" teammates)
  if [ -n "$T" ] && [ "$T" -gt "$TEAM_MAX" ]; then TEAM_MAX=$T; fi
  [ "$TEAM_MAX" -ge 1 ] && break
  sleep 0.5
  i=$((i + 1))
done
chk "team.c1_teammates_ge_1 (teammates=$TEAM_MAX)" "$([ "$TEAM_MAX" -ge 1 ] && echo 1 || echo 0)"
[ "$TEAM_MAX" -ge 1 ] || die "C1 一直没看到队友：查 $ART/c1.log 的 peer_announce / bt_connect_peer 两行"

STATE_C1=$(get "http://127.0.0.1:$PORT_C1/api/state")
printf '%s' "$STATE_C1" > "$ART/c1-state.json"
C1_STATE=$(sval "$STATE_C1" state)
C1_TEAM_SIZE=$(group_ival "$ART/c1-state.json" "$GID" teamSize)
chk "team.c1_downloading (state=$C1_STATE)" "$([ "$C1_STATE" = "downloading" ] && echo 1 || echo 0)"
chk "team.c1_team_size_2 (teamSize=$C1_TEAM_SIZE)" "$([ "$C1_TEAM_SIZE" = "2" ] && echo 1 || echo 0)"

# ------------------------------------------------------------------ 7. P7 用的限速读数
say "--- C1 全局限速改成 上行 $P7_UP_KBPS / 下行 $THROTTLE_KBPS（P7 截图要这个读数）"
L7=$(post "http://127.0.0.1:$PORT_C1/api/settings/limits" "{\"uploadKbps\":$P7_UP_KBPS,\"downloadKbps\":$THROTTLE_KBPS}")
L7_UP=$(ival "$L7" sessionUploadLimitKbps)
L7_DOWN=$(ival "$L7" sessionDownloadLimitKbps)
chk "limits.p7_readback (up=$L7_UP down=$L7_DOWN)" \
  "$([ "$L7_UP" = "$P7_UP_KBPS" ] && [ "$L7_DOWN" = "$THROTTLE_KBPS" ] && echo 1 || echo 0)"

# ------------------------------------------------------------------ 7b. P4 的单文件限速
say "--- C1 单文件限速 $P4_FILE_KBPS KB/s（P4 截图要这个读数）"
FL=$(post "http://127.0.0.1:$PORT_C1/api/groups/$GID/files/$FID/upload-limit" "{\"kbps\":$P4_FILE_KBPS}")
FL_KBPS=$(ival "$FL" uploadLimitKbps)
FL_FORCE=$(bval "$FL" uploadLimitInForce)
chk "limits.file_readback (uploadLimitKbps=$FL_KBPS inForce=$FL_FORCE)" \
  "$([ "$FL_KBPS" = "$P4_FILE_KBPS" ] && [ "$FL_FORCE" = "true" ] && echo 1 || echo 0)"

# ------------------------------------------------------------------ 8. 出图
say "--- 离屏截图（P3 / P4 / P7，每页一个一次性进程）"
for stale in p3-team p4-team p7-limits; do rm -f "$UI/$stale.png" "$UI/$stale.log"; done
if ! powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(winpath "$SCRIPT_DIR/capture-ui.ps1")" \
      -ExePath "$(winpath "$EXE")" -OutDir "$(winpath "$UI")" \
      -JobsPath "$(winpath "$SCRIPT_DIR/ui-capture-teammates.json")" \
      -IndexName teammates-index.json > "$ART/capture.stdout.log" 2>&1; then
  tail -n 25 "$ART/capture.stdout.log" >&2
  note "截图失败，完整日志：$ART/capture.stdout.log"
fi
SHOT_FAILED=$(tr -d '\r' < "$UI/teammates-index.json" 2>/dev/null | sed -n 's/.*"failed": *\([0-9]*\).*/\1/p' | head -n1)
for shot in p3-team p4-team p7-limits; do
  BYTES=0
  [ -f "$UI/$shot.png" ] && BYTES=$(stat -c %s "$UI/$shot.png")
  chk "shot.$shot (${BYTES} 字节)" "$([ "$BYTES" -gt 20000 ] && echo 1 || echo 0)"
done
chk "shot.no_failed_page (failed=${SHOT_FAILED:-?})" "$([ "${SHOT_FAILED:-1}" = "0" ] && echo 1 || echo 0)"
# 页面文案的两条硬证据：截图那一刻的 DOM 探针（capture-ui.ps1 原样存成 <页名>.log）
chk "shot.p4_shows_file_limit_readback" \
  "$(grep -q "已生效 $P4_FILE_KBPS KB/s" "$UI/p4-team.log" 2>/dev/null && echo 1 || echo 0)"
chk "shot.p7_shows_kernel_readback" \
  "$(grep -q "内核读回 上行 $P7_UP_KBPS KB/s" "$UI/p7-limits.log" 2>/dev/null && echo 1 || echo 0)"

# ------------------------------------------------------------------ 9. 收尾
say "--- 停 daemon"
for port in "$PORT_S" "$PORT_C1" "$PORT_C2"; do
  post "http://127.0.0.1:$port/api/shutdown" '{}' > /dev/null 2>&1 || true
done
i=0
while [ "$i" -lt 20 ]; do
  port_busy "$PORT_S" || port_busy "$PORT_C1" || port_busy "$PORT_C2" || break
  sleep 0.5
  i=$((i + 1))
done
chk "shutdown.all_stopped" \
  "$( (port_busy "$PORT_S" || port_busy "$PORT_C1" || port_busy "$PORT_C2") && echo 0 || echo 1)"

cleanup
trap - EXIT
say ""
say "断言：通过 $OK / 失败 $BAD"
say "截图：$UI/p3-team.png  $UI/p4-team.png  $UI/p7-limits.png"
say "日志：$ART/s.log  $ART/c1.log  $ART/c2.log"
if [ "$BAD" -eq 0 ]; then
  say "PASS"
  exit 0
fi
say "FAIL"
exit 1
