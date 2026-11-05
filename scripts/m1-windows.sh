#!/usr/bin/env bash
#
# m1-windows.sh — Windows 双 daemon 端到端验收
#
#   bash scripts/m1-windows.sh          # 跑完整流程；最后一行 PASS（rc 0）/ FAIL（rc 1）
#   M1_SKIP_BUILD=1 bash scripts/m1-windows.sh   # 复用已构建的 daemon.jar
#
# 装置（全程只走 daemon 的 HTTP/WS 接口，不碰 core 内部）：
#   A（127.0.0.1:47200）建群 → 共享 32 MiB → 等 B 的评论
#   B（127.0.0.1:47201）只用邀请码后 8 位 + 手动地址 127.0.0.1:<A 的消息端口> 加入
#   B 激活下载 → 等到 complete → 两端 SHA-256 比对 → B 发评论 → A 侧看到
#
# 断言分四组：
#   1. flow   ：建群 / 加入 / 共享 / 下载 / 评论，每一步都要有本轮证据
#   2. sha256 ：两端文件逐字节一致，脚本自己重算，不信 daemon 的自述
#   3. push   ：WS 真的在推（state 推送 + 传输中的 tick 采样）
#   4. web    ：A 的 Web UI 数据通路返回的是真数据（页面、data.js、/api/state）
#
# 端口用文档里写死的固定值 47200 / 47201。占用就 FAIL，不静默换端口 ——
# 换端口等于没验「默认端口能起」。
#
# 关于「手动地址」：同机两个 daemon 的 mDNS 也会互相发现，B 有可能经 LAN 候选先连上。
# 脚本因此断言「手动地址确实进了候选表」，并把实际生效的来源记成 join.via_<source>；
# 要验跨网路径，把 B 换到另一台机器上跑（或断掉组播）即可。
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ART="$ROOT/artifacts/m1-windows"

DATA_A="$ART/a-data"
DATA_B="$ART/b-data"
SHARE_DIR="$ART/a-share"
SHARE_FILE="$SHARE_DIR/payload-32m.bin"
OUT_A="$ART/daemon-A.out"
OUT_B="$ART/daemon-B.out"
LOG_A="$ART/daemon-A.log"
LOG_B="$ART/daemon-B.log"

PORT_A=47200
PORT_B=47201
MSG_A=47300
MSG_B=47310
BT_A=51500
BT_B=51510

FILE_BYTES=33554432
# 给 A 的共享文件压 8 MiB/s：顺带验单文件限速，并让 WS 的 tick 采样有东西可采。
A_UPLOAD_LIMIT_KBPS=8192
WAIT_TRANSFER_S=240
MAYBE_WAIT=$(printenv M1_WAIT_TRANSFER_S 2>/dev/null || true)
if [ -n "$MAYBE_WAIT" ]; then WAIT_TRANSFER_S="$MAYBE_WAIT"; fi

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

PID_A=""
PID_B=""
WS_PID=""

port_pid() { # 监听该端口的进程 pid（没有就空）
  netstat -ano 2>/dev/null | grep -E "TCP +127\.0\.0\.1:$1 +" | grep LISTENING | awk '{print $NF}' | head -1
}
port_busy() { [ -n "$(port_pid "$1")" ]; }

kill_port() {
  local pid
  pid=$(port_pid "$1")
  [ -n "$pid" ] || return 0
  taskkill //PID "$pid" //F >/dev/null 2>&1 || true
}

cleanup() {
  for pid in "$WS_PID" "$PID_A" "$PID_B"; do
    [ -n "$pid" ] || continue
    kill -9 "$pid" >/dev/null 2>&1 || true
  done
  WS_PID=""
  PID_A=""
  PID_B=""
  kill_port "$PORT_A"
  kill_port "$PORT_B"
  # 给 bash 一点时间把「Killed」这类作业消息吐出来，别让它落在 PASS 后面
  sleep 0.5
}
trap cleanup EXIT

# Windows 原生程序（java.exe）只认 Windows 路径，且反斜杠会被当转义符：统一给 E:/a/b 形式。
w() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

# ------------------------------------------------------------------ HTTP 小工具
get() { curl -s -m 15 "$1"; }
post() { curl -s -m 90 -X POST -H 'Content-Type: application/json' --data-binary "$2" "$1"; }

# 只取第一个匹配：本脚本的响应形状固定，不需要完整 JSON 解析器。
sval() { printf '%s' "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed 's/^[^:]*:"//; s/"$//'; }
ival() { printf '%s' "$1" | grep -o "\"$2\":[0-9][0-9]*" | head -1 | sed 's/^[^:]*://'; }
bval() { printf '%s' "$1" | grep -o "\"$2\":\(true\|false\)" | head -1 | sed 's/^[^:]*://'; }
# JSON 里被转义的反斜杠还原（savedPath 在 Windows 上一定是 C:\\a\\b 这种形式）
unbackslash() { printf '%s' "$1" | sed 's/\\\\/\\/g'; }

wait_health() { # wait_health <端口> <秒>；成功时把 /api/health 的响应打到 stdout
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

# 原始 WS 客户端：curl 8 支持 101 升级后按原样透传帧，文本帧里的 JSON 可以直接 grep。
ws_probe() { # ws_probe <端口> <秒> <输出文件>
  curl -s -N --max-time "$2" \
    -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
    -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
    "http://127.0.0.1:$1/ws" > "$3" 2>/dev/null || true
}

say "=== M1-Windows 双 daemon 验收 ======================================="
say "root: $ROOT"
say "时间: $(date '+%Y-%m-%d %H:%M:%S')"
SCRIPT_T0=$(date +%s)

# ------------------------------------------------------------------ 0. 前置
command -v java      >/dev/null 2>&1 || die "java 不在 PATH（要 JDK 17+）"
command -v curl      >/dev/null 2>&1 || die "curl 不在 PATH"
command -v sha256sum >/dev/null 2>&1 || die "sha256sum 不在 PATH（脚本要独立重算 SHA-256）"

for port in "$PORT_A" "$PORT_B"; do
  if port_busy "$port"; then
    die "端口 $port 已被占用（pid $(port_pid "$port")）：先停掉手动起的 daemon 再跑本脚本。"
  fi
done

# ------------------------------------------------------------------ 1. 构建
JAR="$ROOT/daemon/build/dist/daemon.jar"
MAYBE_SKIP=$(printenv M1_SKIP_BUILD 2>/dev/null || true)
if [ "$MAYBE_SKIP" = "1" ] && [ -f "$JAR" ]; then
  say "--- 构建：跳过（M1_SKIP_BUILD=1，复用 $JAR）"
else
  say "--- 构建 :daemon:daemonJar"
  ( cd "$ROOT" && ./gradlew --console=plain -q :daemon:daemonJar ) || die "gradle :daemon:daemonJar 失败"
fi
[ -f "$JAR" ] || die "没有 daemon.jar：$JAR"

# ------------------------------------------------------------------ 2. 装置
say "--- 清场"
rm -rf "$ART" || die "删不掉 $ART"
mkdir -p "$DATA_A" "$DATA_B" "$SHARE_DIR" || die "建不出 $ART 下的目录"

say "--- 造料：$FILE_BYTES 字节"
head -c "$FILE_BYTES" /dev/urandom > "$SHARE_FILE" || die "生成测试文件失败"
MADE_BYTES=$(wc -c < "$SHARE_FILE" | tr -d ' ')
[ "$MADE_BYTES" = "$FILE_BYTES" ] || die "测试文件大小不对：$MADE_BYTES"
SRC_SHA=$(sha256sum < "$SHARE_FILE" | cut -d' ' -f1)
say "      源文件 sha256=$SRC_SHA"

# 起一个 daemon；pid 落在 LAST_PID 里。disown 是为了让 bash 不要在收尾时
# 打印「Killed」把 PASS 顶到上面去。
LAST_PID=""
launch_daemon() { # <端口> <消息端口> <bt 端口> <数据目录> <日志> <stdout> <设备名>
  java -jar "$(w "$JAR")" \
    --data-dir="$(w "$4")" --port="$1" --msg-port="$2" --bt-port="$3" \
    --device-name="$7" --log="$(w "$5")" > "$6" 2>&1 &
  LAST_PID=$!
  disown 2>/dev/null || true
}

# 起不来时把 daemon 自己说的原因带出来（端口占用 / 原生库 / 内核），别只丢一个路径给人猜。
why_not() { # <stdout 文件>
  if grep -q "daemon_bind_failed" "$1" 2>/dev/null; then
    printf '端口被占用：%s' "$(grep -m1 'daemon_bind_failed' "$1" | sed 's/.*error=//')"
  else
    printf '%s' "$(tail -2 "$1" 2>/dev/null | tr '\n' ' ')"
  fi
}

say "--- 起 A（api=$PORT_A msg=$MSG_A bt=$BT_A）"
launch_daemon "$PORT_A" "$MSG_A" "$BT_A" "$DATA_A" "$LOG_A" "$OUT_A" A-Node
PID_A=$LAST_PID

say "--- 起 B（api=$PORT_B msg=$MSG_B bt=$BT_B）"
launch_daemon "$PORT_B" "$MSG_B" "$BT_B" "$DATA_B" "$LOG_B" "$OUT_B" B-Node
PID_B=$LAST_PID

if ! HEALTH_A=$(wait_health $PORT_A 60); then
  # 上一轮的 daemon 可能还在退（socket 没释放完）。等它彻底走，再试一次。
  if grep -q "daemon_bind_failed" "$OUT_A" 2>/dev/null; then
    note "端口 $PORT_A 一开始被占着，等 5 秒重试一次"
    kill_port "$PORT_A"
    sleep 5
    launch_daemon "$PORT_A" "$MSG_A" "$BT_A" "$DATA_A" "$LOG_A" "$OUT_A" A-Node
    PID_A=$LAST_PID
    HEALTH_A=$(wait_health $PORT_A 60) || die "A 起不来：$(why_not "$OUT_A")（日志 $OUT_A）"
  else
    die "A 起不来：$(why_not "$OUT_A")（日志 $OUT_A）"
  fi
fi
HEALTH_B=$(wait_health $PORT_B 60) || die "B 起不来：$(why_not "$OUT_B")（日志 $OUT_B）"

A_MSG_PORT=$(ival "$HEALTH_A" msgPort)
B_MSG_PORT=$(ival "$HEALTH_B" msgPort)
A_API_PORT=$(ival "$HEALTH_A" apiPort)
B_API_PORT=$(ival "$HEALTH_B" apiPort)
say "      A api=$A_API_PORT msg=$A_MSG_PORT   B api=$B_API_PORT msg=$B_MSG_PORT"

say ""
say "=== 断言 ============================================================"

chk "boot.A_on_fixed_port (api=$A_API_PORT)" "$([ "$A_API_PORT" = "$PORT_A" ] && echo 1 || echo 0)"
chk "boot.B_on_fixed_port (api=$B_API_PORT)" "$([ "$B_API_PORT" = "$PORT_B" ] && echo 1 || echo 0)"
chk "boot.msg_ports_differ (A=$A_MSG_PORT B=$B_MSG_PORT)" \
  "$([ -n "$A_MSG_PORT" ] && [ -n "$B_MSG_PORT" ] && [ "$A_MSG_PORT" != "$B_MSG_PORT" ] && echo 1 || echo 0)"
A_MDNS=$(bval "$HEALTH_A" mdns)
chk "boot.mdns_started (A mdns=$A_MDNS)" "$([ "$A_MDNS" = "true" ] && echo 1 || echo 0)"

# ------------------------------------------------------------------ 3. A 建群
say ""
say "--- A 建群"
GROUP_NAME="m1-windows-group"
CREATED=$(post "http://127.0.0.1:$PORT_A/api/groups" "{\"name\":\"$GROUP_NAME\"}")
GID=$(sval "$CREATED" id)
INVITE=$(sval "$CREATED" inviteCode)
TAIL8=$(sval "$CREATED" inviteTail8)
INVITE_LEN=$(printf '%s' "$INVITE" | wc -c | tr -d ' ')
chk "group.created (gid=$GID)" "$([ -n "$GID" ] && echo 1 || echo 0)"
chk "group.invite_code_16 (code=$INVITE)" "$([ "$INVITE_LEN" = "16" ] && echo 1 || echo 0)"
chk "group.invite_tail8 (tail8=$TAIL8)" \
  "$([ "$TAIL8" = "$(printf '%s' "$INVITE" | tail -c 8)" ] && echo 1 || echo 0)"

# ------------------------------------------------------------------ 4. A 共享 32 MiB
say "--- A 共享 $((FILE_BYTES / 1024 / 1024)) MiB"
ADDED=$(post "http://127.0.0.1:$PORT_A/api/groups/$GID/files" "{\"path\":\"$(w "$SHARE_FILE")\"}")
FID=$(sval "$ADDED" id)
ADDED_SIZE=$(ival "$ADDED" sizeBytes)
chk "file.shared (id=$FID)" "$([ -n "$FID" ] && echo 1 || echo 0)"
chk "file.shared_size_32MiB ($ADDED_SIZE)" "$([ "$ADDED_SIZE" = "$FILE_BYTES" ] && echo 1 || echo 0)"

LIMITED=$(post "http://127.0.0.1:$PORT_A/api/groups/$GID/files/$FID/upload-limit" "{\"kbps\":$A_UPLOAD_LIMIT_KBPS}")
LIMIT_GOT=$(ival "$LIMITED" uploadLimitKbps)
chk "file.upload_limit_set ($LIMIT_GOT kbps)" "$([ "$LIMIT_GOT" = "$A_UPLOAD_LIMIT_KBPS" ] && echo 1 || echo 0)"

# ------------------------------------------------------------------ 5. B 用后 8 位 + 手动地址加入
say "--- B 用后 8 位加入（手动地址 127.0.0.1:$A_MSG_PORT）"
JOINED=$(post "http://127.0.0.1:$PORT_B/api/join" \
  "{\"tail8\":\"$TAIL8\",\"manual\":[\"127.0.0.1:$A_MSG_PORT\"],\"timeoutMs\":25000}")
JOIN_OK=$(bval "$JOINED" ok)
GID_B=$(sval "$JOINED" id)
chk "join.ok (B gid=$GID_B)" \
  "$([ "$JOIN_OK" != "false" ] && [ "$GID_B" = "$GID" ] && echo 1 || echo 0)"
if [ "$GID_B" != "$GID" ]; then
  note "join 响应：$(printf '%s' "$JOINED" | head -c 300)"
  die "B 没加入成功"
fi
chk "join.manual_candidate_offered" \
  "$(grep -q "event=join_candidates .*127.0.0.1:$A_MSG_PORT" "$LOG_B" 2>/dev/null && echo 1 || echo 0)"
JOIN_SRC="unknown"
JOIN_SRC_FOUND=$(grep -o 'event=join_via source=[a-z]*' "$LOG_B" 2>/dev/null | head -1 | sed 's/.*source=//')
if [ -n "$JOIN_SRC_FOUND" ]; then JOIN_SRC="$JOIN_SRC_FOUND"; fi
chk "join.via_$JOIN_SRC" "$([ "$JOIN_SRC" != "unknown" ] && echo 1 || echo 0)"

# ------------------------------------------------------------------ 6. B 看到文件并下载
say "--- B 等文件清单、激活下载"
SEEN_B=""
i=0
while [ "$i" -lt 40 ]; do
  LIST_B=$(get "http://127.0.0.1:$PORT_B/api/groups/$GID_B/files")
  case "$LIST_B" in *payload-32m.bin*) SEEN_B="$LIST_B"; break ;; esac
  sleep 0.5
  i=$((i + 1))
done
chk "file.visible_on_B" "$([ -n "$SEEN_B" ] && echo 1 || echo 0)"
[ -n "$SEEN_B" ] || die "B 没收到文件清单，后面的断言没有意义"

# WS 探针：A 侧，从激活前开始录，用来证明「传输状态在推」
ws_probe "$PORT_A" 60 "$ART/ws-A.txt" &
WS_PID=$!
disown 2>/dev/null || true
sleep 1

ACTIVATED=$(post "http://127.0.0.1:$PORT_B/api/groups/$GID_B/files/$FID/active" '{"active":true}')
ACT_STATE=$(sval "$ACTIVATED" state)
chk "download.activated (state=$ACT_STATE)" "$([ "$ACT_STATE" = "downloading" ] && echo 1 || echo 0)"

TRANSFER_T0=$(date +%s)
DETAIL_B=""
i=0
while [ "$i" -lt "$((WAIT_TRANSFER_S * 2))" ]; do
  DETAIL_B=$(get "http://127.0.0.1:$PORT_B/api/groups/$GID_B/files/$FID")
  case "$DETAIL_B" in *'"complete":true'*) break ;; esac
  sleep 0.5
  i=$((i + 1))
done
TRANSFER_S=$(( $(date +%s) - TRANSFER_T0 ))
COMPLETE=$(bval "$DETAIL_B" complete)
PCT=$(ival "$DETAIL_B" progressPercent)
chk "download.complete (complete=$COMPLETE progress=$PCT% 用时 $TRANSFER_S s)" \
  "$([ "$COMPLETE" = "true" ] && echo 1 || echo 0)"
[ "$COMPLETE" = "true" ] || die "下载没完成（等了 $TRANSFER_S s，上限 $WAIT_TRANSFER_S s）"

SAVED_RAW=$(sval "$DETAIL_B" savedPath)
SAVED=$(unbackslash "$SAVED_RAW")
chk "download.saved_file_exists" "$([ -n "$SAVED" ] && [ -f "$SAVED" ] && echo 1 || echo 0)"
[ -f "$SAVED" ] || die "B 的下载文件不存在：$SAVED"
note "B 的落盘路径：$SAVED"

# ------------------------------------------------------------------ 7. SHA-256
say "--- 两端 SHA-256"
DST_SHA=$(sha256sum < "$SAVED" | cut -d' ' -f1)
say "      A: $SRC_SHA"
say "      B: $DST_SHA"
chk "sha256.match" "$([ "$SRC_SHA" = "$DST_SHA" ] && echo 1 || echo 0)"

MTIME=$(date -r "$SAVED" +%s 2>/dev/null || echo 0)
chk "sha256.file_written_this_run" "$([ "$MTIME" -ge "$SCRIPT_T0" ] && echo 1 || echo 0)"

# ------------------------------------------------------------------ 8. B 评论 → A 可见
say "--- B 发评论，A 侧收回"
COMMENT_BODY="B-downloaded-and-verified"
COMMENTED=$(post "http://127.0.0.1:$PORT_B/api/groups/$GID_B/files/$FID/comments" "{\"body\":\"$COMMENT_BODY\"}")
COMMENT_ID=$(sval "$COMMENTED" id)
chk "comment.posted_on_B (id=$COMMENT_ID)" "$([ -n "$COMMENT_ID" ] && echo 1 || echo 0)"

COMMENTS_A=""
i=0
while [ "$i" -lt 40 ]; do
  COMMENTS_A=$(get "http://127.0.0.1:$PORT_A/api/groups/$GID/files/$FID/comments")
  case "$COMMENTS_A" in *"$COMMENT_BODY"*) break ;; esac
  sleep 0.5
  i=$((i + 1))
done
chk "comment.reached_A" "$(printf '%s' "$COMMENTS_A" | grep -q "$COMMENT_BODY" && echo 1 || echo 0)"
chk "comment.author_is_B" "$(printf '%s' "$COMMENTS_A" | grep -q '"author":"B-Node"' && echo 1 || echo 0)"

# ------------------------------------------------------------------ 9. WS 推送
say "--- WS 推送（A 侧，期间有 32 MiB 上传）"
if [ -n "$WS_PID" ]; then
  kill -9 "$WS_PID" >/dev/null 2>&1 || true
  sleep 0.5
  WS_PID=""
fi
WS_FRAMES=$(grep -ao '"type":"[a-z]*"' "$ART/ws-A.txt" 2>/dev/null | wc -l | tr -d ' ')
WS_STATES=$(grep -ao '"type":"state"' "$ART/ws-A.txt" 2>/dev/null | wc -l | tr -d ' ')
WS_TICKS=$(grep -ao '"type":"tick"' "$ART/ws-A.txt" 2>/dev/null | wc -l | tr -d ' ')
WS_MAXREV=$(grep -ao '"rev":[0-9]*' "$ART/ws-A.txt" 2>/dev/null | sed 's/.*://' | sort -n | tail -1)
say "      帧=$WS_FRAMES state=$WS_STATES tick=$WS_TICKS 最大 rev=$WS_MAXREV"
chk "ws.frames_received ($WS_FRAMES)" "$([ "$WS_FRAMES" -gt 0 ] && echo 1 || echo 0)"
chk "ws.state_pushed ($WS_STATES)" "$([ "$WS_STATES" -gt 0 ] && echo 1 || echo 0)"
chk "ws.tick_pushed_during_transfer ($WS_TICKS)" "$([ "$WS_TICKS" -gt 0 ] && echo 1 || echo 0)"

# ------------------------------------------------------------------ 10. A 的 Web UI 数据通路
say "--- A 的 Web UI（页面 + 数据）"
INDEX=$(get "http://127.0.0.1:$PORT_A/")
DATA_JS=$(get "http://127.0.0.1:$PORT_A/js/data.js")
APP_JS=$(get "http://127.0.0.1:$PORT_A/js/app.js")
STATE_A=$(get "http://127.0.0.1:$PORT_A/api/state")

chk "web.index_served" "$(printf '%s' "$INDEX" | grep -q 'js/data.js' && echo 1 || echo 0)"
chk "web.app_js_served" "$(printf '%s' "$APP_JS" | grep -q 'function render' && echo 1 || echo 0)"
chk "web.data_js_talks_to_daemon" "$(printf '%s' "$DATA_JS" | grep -q '/api/state' && echo 1 || echo 0)"
chk "web.state_has_group ($GROUP_NAME)" "$(printf '%s' "$STATE_A" | grep -q "$GROUP_NAME" && echo 1 || echo 0)"
chk "web.state_has_file (payload-32m.bin)" "$(printf '%s' "$STATE_A" | grep -q 'payload-32m.bin' && echo 1 || echo 0)"
chk "web.state_has_comment" "$(printf '%s' "$STATE_A" | grep -q "$COMMENT_BODY" && echo 1 || echo 0)"
chk "web.state_file_complete" "$(printf '%s' "$STATE_A" | grep -q '"complete":true' && echo 1 || echo 0)"
STATE_MEMBERS=$(ival "$STATE_A" memberCount)
chk "web.state_member_count_2 ($STATE_MEMBERS)" "$([ "$STATE_MEMBERS" = "2" ] && echo 1 || echo 0)"

SET_SAVED=$(post "http://127.0.0.1:$PORT_A/api/settings" '{"userName":"A-Node-User"}')
chk "settings.user_name_saved" \
  "$(printf '%s' "$SET_SAVED" | grep -q '"userName":"A-Node-User"' && echo 1 || echo 0)"

# ------------------------------------------------------------------ 11. 停
say ""
say "--- 停 daemon"
post "http://127.0.0.1:$PORT_A/api/shutdown" '{}' > /dev/null
post "http://127.0.0.1:$PORT_B/api/shutdown" '{}' > /dev/null
i=0
while [ "$i" -lt 20 ]; do
  port_busy "$PORT_A" || port_busy "$PORT_B" || break
  sleep 0.5
  i=$((i + 1))
done
chk "shutdown.A_stopped" "$(port_busy $PORT_A && echo 0 || echo 1)"
chk "shutdown.B_stopped" "$(port_busy $PORT_B && echo 0 || echo 1)"

# ------------------------------------------------------------------ 12. 汇总
# 先彻底收尾再打分：PASS / FAIL 必须是脚本的最后一行。
cleanup
trap - EXIT
say ""
say "断言：通过 $OK / 失败 $BAD"
say "日志：A $LOG_A"
say "      B $LOG_B"
say "      WS $ART/ws-A.txt"
if [ "$BAD" -eq 0 ]; then
  say "PASS"
  exit 0
fi
say "FAIL"
exit 1
