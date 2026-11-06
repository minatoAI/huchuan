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
# 断言分八组：
#   1. flow    ：建群 / 加入 / 共享 / 下载 / 评论，每一步都要有本轮证据
#   2. sha256  ：两端文件逐字节一致，脚本自己重算，不信 daemon 的自述
#   3. push    ：WS 真的在推（state 推送 + 传输中的 tick 采样）
#   4. web     ：A 的 Web UI 数据通路返回的是真数据（页面、data.js、/api/state）
#   5. package ：绿色版 dist/Huchuan/Huchuan.exe 在「PATH 里没有 java、JAVA_HOME 为空」
#                的环境里也能把 daemon 拉起来（复现没装 Java 的机器）
#   6. limit   ：全局限速下发内核后**读回**会话里生效的值（512 ↔ 0），并采队友数，
#                确认做种者不计入队友（spec §306）；≥1 的组队取证见
#                windows/scripts/capture-team-ui.sh
#   7. tracker ：跨网发现默认开、地址是已部署的 Pages 入口（pages.dev），建群后确实发起注册尝试
#                （成功 / 失败都算，断言的是「有尝试」）；另起一个启动前就关掉 tracker 的 C 节点，
#                要求 kernel_started tracker=off、建群后一条 tracker 请求都没有
#   8. magnet  ：公开种子 / 磁力导入：非法链接 400 invalid_link、同一链接第二次
#                duplicate、state 里 scope=public + magnet、本机移除后从 state 消失但同群 B 还在
#   9. link    ：链接分享（W1）：可用性、创建（link/qrText/7 天默认）、列表、状态、撤销（幂等、
#                未知 token 404）、过期（ttlSec=60 自然过期），以及一条真端到端：daemon 发 8 MiB，
#                Edge headless 打开 Pages 接收页，经真 Worker 的 /signal 建链收字节，浏览器自己
#                算 SHA-256 再从 DataChannel 回 verify；证据在 artifacts/link-share-win/。
#                绿色版那台（jlink 精简 runtime）也要能 probe 出 available=true。
#
# 端口用文档里写死的固定值 47200 / 47201 / 47202。占用就 FAIL，不静默换端口 ——
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
DATA_C="$ART/c-data"
SHARE_DIR="$ART/a-share"
SHARE_FILE="$SHARE_DIR/payload-32m.bin"
LINK_DIR="$ART/link"
LINK_FILE="$LINK_DIR/payload-8m.bin"
OUT_A="$ART/daemon-A.out"
OUT_B="$ART/daemon-B.out"
OUT_C="$ART/daemon-C.out"
LOG_A="$ART/daemon-A.log"
LOG_B="$ART/daemon-B.log"
LOG_C="$ART/daemon-C.log"

PORT_A=47200
PORT_B=47201
# C 只验「跨网关闭」，跑完就在本轮收掉，不参与主流程。
PORT_C=47202
# 绿色版断言单独占一个端口：它要独占「没人监听」这个前提，才能证明 daemon 是自己拉起来的。
PORT_PKG=47290
MSG_A=47300
MSG_B=47310
MSG_C=47320
BT_A=51500
BT_B=51510
BT_C=51520

# 默认的跨网发现地址：与 daemon 的 SettingsStore.DEFAULT_TRACKER_BASE_URL 同一个值。
# 写 Pages 入口而不是 workers.dev：中国大陆下 workers.dev 被 DNS 污染 / SNI 阻断，pages.dev 通
# （Pages Functions 把 /api/tracker/register 与 /announce 转给 Worker，见 docs/deploy-cloudflare.md §5）。
# 换地址时两处一起改。
TRACKER_URL="https://huchuan-a9f.pages.dev"

FILE_BYTES=33554432
# 链接分享的端到端用 8 MiB（spike 的同一口径）：单独一个文件，不跟 32 MiB 的 BT 用例混。
LINK_BYTES=8388608
# 链接分享的服务地址：与 daemon 的 LinkDefaults.BASE_URL / SettingsStore 的默认值同一个入口。
LINK_API="https://huchuan-a9f.pages.dev"
# 端到端验收的留证目录（daemon/e2e/link-share-e2e.mjs 写这里；不进 artifacts/m1-windows，避免被清场删掉）。
LINK_E2E="$ROOT/artifacts/link-share-win/e2e"
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
PID_C=""
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

# 壳进程（Huchuan.exe）的 pid 列表。绿色版断言用它认出本轮启动的那个窗口，
# 收尾时只关自己开的，不碰用户手动开着的壳。
huchuan_pids() {
  powershell.exe -NoProfile -Command "(Get-Process -Name Huchuan -ErrorAction SilentlyContinue).Id" 2>/dev/null \
    | tr -d '\r' | tr ' ' '\n' | grep -E '^[0-9]+$' | sort -u
}

cleanup() {
  for pid in "$WS_PID" "$PID_A" "$PID_B" "$PID_C"; do
    [ -n "$pid" ] || continue
    kill -9 "$pid" >/dev/null 2>&1 || true
  done
  WS_PID=""
  PID_A=""
  PID_B=""
  PID_C=""
  kill_port "$PORT_A"
  kill_port "$PORT_B"
  kill_port "$PORT_C"
  kill_port "$PORT_PKG"
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
# POST 把响应体写进文件、stdout 给 HTTP 状态码：磁力导入要同时断言状态码与 body 里的 code。
post_file() { # <url> <body> <响应文件>
  curl -s -m 90 -o "$3" -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data-binary "$2" "$1"
}

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
# 链接分享的端到端要驱动 Edge headless（CDP）：Node 18+ 自带的 fetch / WebSocket 就够了。
command -v node      >/dev/null 2>&1 || die "node 不在 PATH（链接分享端到端要 node 18+）"

for port in "$PORT_A" "$PORT_B" "$PORT_C"; do
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
mkdir -p "$DATA_A" "$DATA_B" "$SHARE_DIR" "$LINK_DIR" || die "建不出 $ART 下的目录"

say "--- 造料：$FILE_BYTES 字节"
head -c "$FILE_BYTES" /dev/urandom > "$SHARE_FILE" || die "生成测试文件失败"
MADE_BYTES=$(wc -c < "$SHARE_FILE" | tr -d ' ')
[ "$MADE_BYTES" = "$FILE_BYTES" ] || die "测试文件大小不对：$MADE_BYTES"
SRC_SHA=$(sha256sum < "$SHARE_FILE" | cut -d' ' -f1)
say "      源文件 sha256=$SRC_SHA"

say "--- 造料：链接分享 $LINK_BYTES 字节"
head -c "$LINK_BYTES" /dev/urandom > "$LINK_FILE" || die "生成链接分享测试文件失败"
LINK_MADE_BYTES=$(wc -c < "$LINK_FILE" | tr -d ' ')
[ "$LINK_MADE_BYTES" = "$LINK_BYTES" ] || die "链接分享测试文件大小不对：$LINK_MADE_BYTES"
LINK_SRC_SHA=$(sha256sum < "$LINK_FILE" | cut -d' ' -f1)
say "      链接源文件 sha256=$LINK_SRC_SHA"

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

# ------------------------------------------------------------------ 3b. 跨网发现（开）
# 默认开 + 内置地址：daemon 把地址交给内核，群一建好 tracker 线程就开始注册。
# pages.dev 入口可达时通常会 register 成功，不可达时是 register_failed；两种都算一次真实尝试
# —— 断言的是「确实发起了尝试」，不是注册成功。
say ""
say "--- A 跨网发现：默认开、地址是已部署的 $TRACKER_URL"
TRACKER_A=$(get "http://127.0.0.1:$PORT_A/api/settings/tracker")
T_ENABLED=$(bval "$TRACKER_A" enabled)
T_BASE=$(sval "$TRACKER_A" baseUrl)
T_ACTIVE=$(bval "$TRACKER_A" active)
chk "tracker.default_on (enabled=$T_ENABLED)" "$([ "$T_ENABLED" = "true" ] && echo 1 || echo 0)"
chk "tracker.deployed_base ($T_BASE)" "$([ "$T_BASE" = "$TRACKER_URL" ] && echo 1 || echo 0)"
chk "tracker.active_in_kernel (active=$T_ACTIVE)" "$([ "$T_ACTIVE" = "true" ] && echo 1 || echo 0)"
chk "tracker.kernel_started_on" \
  "$(grep -q "event=kernel_started .*tracker=on .*tracker_base=$TRACKER_URL" "$LOG_A" 2>/dev/null && echo 1 || echo 0)"
chk "tracker.group_watched (gid=$GID)" \
  "$(grep -q "event=tracker_started gid=$GID " "$LOG_A" 2>/dev/null && echo 1 || echo 0)"
# 注册尝试：成功（tracker_register）与失败（tracker_register_failed）都算；等 30 s 给 DNS 超时。
T_ATTEMPT=""
i=0
while [ "$i" -lt 60 ]; do
  T_ATTEMPT=$(grep -o 'event=tracker_register_failed\|event=tracker_register ' "$LOG_A" 2>/dev/null | head -1)
  [ -n "$T_ATTEMPT" ] && break
  sleep 0.5
  i=$((i + 1))
done
chk "tracker.register_attempted (${T_ATTEMPT:-none})" "$([ -n "$T_ATTEMPT" ] && echo 1 || echo 0)"

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

# ------------------------------------------------------------------ 5b. 全局限速（P7）
# 断言的是「内核读回」而不是「daemon 回显了请求」：settings 里存的值和会话里生效的值是两回事，
# libtorrent 的 settings_pack 不持久化，只有 applySettings 之后读回来的一手数据才算数。
say "--- B 全局限速：下发 512 → 读回 512，下发 0 → 读回 0"
LIMITS_512=$(post "http://127.0.0.1:$PORT_B/api/settings/limits" '{"uploadKbps":512,"downloadKbps":512}')
LIM_UP=$(ival "$LIMITS_512" sessionUploadLimitKbps)
LIM_DOWN=$(ival "$LIMITS_512" sessionDownloadLimitKbps)
chk "limits.512_readback (up=$LIM_UP down=$LIM_DOWN)" \
  "$([ "$LIM_UP" = "512" ] && [ "$LIM_DOWN" = "512" ] && echo 1 || echo 0)"

LIMITS_ZERO=$(post "http://127.0.0.1:$PORT_B/api/settings/limits" '{"uploadKbps":0,"downloadKbps":0}')
LIM_UP0=$(ival "$LIMITS_ZERO" sessionUploadLimitKbps)
LIM_DOWN0=$(ival "$LIMITS_ZERO" sessionDownloadLimitKbps)
chk "limits.0_readback (up=$LIM_UP0 down=$LIM_DOWN0)" \
  "$([ "$LIM_UP0" = "0" ] && [ "$LIM_DOWN0" = "0" ] && echo 1 || echo 0)"

LIMITS_GET=$(get "http://127.0.0.1:$PORT_B/api/settings/limits")
chk "limits.get_matches_kernel ($(ival "$LIMITS_GET" sessionDownloadLimitKbps))" \
  "$([ "$(ival "$LIMITS_GET" sessionDownloadLimitKbps)" = "0" ] && echo 1 || echo 0)"
chk "limits.kernel_applied_log" \
  "$(grep -q 'event=session_limit_applied .*session_up_kbps=512 .*ok=true' "$LOG_B" 2>/dev/null && echo 1 || echo 0)"
chk "limits.no_apply_error" \
  "$(grep -q 'event=session_limit_applied .*ok=false' "$LOG_B" 2>/dev/null && echo 0 || echo 1)"

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
# 下载期间顺带采样队友数：这一轮只有 A 一个做种者，按 spec §306 做种者不计入队友，
# 所以 B 侧全程必须是 0（读口真的接上了，而不是恒 0 的占位字段 —— ≥1 的取证见
# windows/scripts/capture-team-ui.sh 的三进程场景）。
TEAM_MAX=0
TEAM_SAMPLES=0
i=0
while [ "$i" -lt "$((WAIT_TRANSFER_S * 2))" ]; do
  DETAIL_B=$(get "http://127.0.0.1:$PORT_B/api/groups/$GID_B/files/$FID")
  case "$DETAIL_B" in *'"complete":true'*) break ;; esac
  T=$(ival "$DETAIL_B" teammates)
  if [ -n "$T" ]; then
    TEAM_SAMPLES=$((TEAM_SAMPLES + 1))
    [ "$T" -gt "$TEAM_MAX" ] && TEAM_MAX=$T
  fi
  sleep 0.5
  i=$((i + 1))
done
chk "team.teammates_field_read ($TEAM_SAMPLES 次采样)" "$([ "$TEAM_SAMPLES" -gt 0 ] && echo 1 || echo 0)"
chk "team.seeder_not_counted (teammates_max=$TEAM_MAX)" "$([ "$TEAM_MAX" = "0" ] && echo 1 || echo 0)"
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
# /api/state 是页面的唯一读口：队友数与全局限速的内核读回都得在这一份里（页面不另发请求）
chk "web.state_has_teammates_field" "$(printf '%s' "$STATE_A" | grep -q '\"teammates\":' && echo 1 || echo 0)"
chk "web.state_has_team_size" "$(printf '%s' "$STATE_A" | grep -q '\"teamSize\":' && echo 1 || echo 0)"
chk "web.state_has_session_limits" \
  "$(printf '%s' "$STATE_A" | grep -q '\"sessionUploadLimitKbps\":' && printf '%s' "$STATE_A" | grep -q '\"sessionDownloadLimitKbps\":' && echo 1 || echo 0)"

SET_SAVED=$(post "http://127.0.0.1:$PORT_A/api/settings" '{"userName":"A-Node-User"}')
chk "settings.user_name_saved" \
  "$(printf '%s' "$SET_SAVED" | grep -q '"userName":"A-Node-User"' && echo 1 || echo 0)"

# ------------------------------------------------------------------ 10b. 跨网发现（关）
# 关闭状态必须是「内核里根本没有 tracker」：不是把开关置灰，也不是发请求失败。
# 造一个启动前就写好 settings.json 的数据目录（trackerEnabled=false），建群也不该出现
# 任何 tracker 线程或请求；kernel_started 必须是 tracker=off。
say ""
say "--- 跨网发现关闭（C 节点，settings.json 里 trackerEnabled=false）"
mkdir -p "$DATA_C" || die "建不出 $DATA_C"
printf '%s' '{"format":"huchuan-daemon-settings/1","trackerEnabled":false,"trackerBaseUrl":"'"$TRACKER_URL"'"}' > "$DATA_C/settings.json"
launch_daemon "$PORT_C" "$MSG_C" "$BT_C" "$DATA_C" "$LOG_C" "$OUT_C" C-Node
PID_C=$LAST_PID
wait_health "$PORT_C" 60 > /dev/null || die "C 起不来：$(why_not "$OUT_C")（日志 $OUT_C）"

TRACKER_C=$(get "http://127.0.0.1:$PORT_C/api/settings/tracker")
TC_ENABLED=$(bval "$TRACKER_C" enabled)
TC_ACTIVE=$(bval "$TRACKER_C" active)
TC_BASE=$(sval "$TRACKER_C" baseUrl)
chk "tracker.off_setting_read_back (enabled=$TC_ENABLED active=$TC_ACTIVE)" \
  "$([ "$TC_ENABLED" = "false" ] && [ "$TC_ACTIVE" = "false" ] && echo 1 || echo 0)"
chk "tracker.off_base_still_stored ($TC_BASE)" "$([ "$TC_BASE" = "$TRACKER_URL" ] && echo 1 || echo 0)"
chk "tracker.kernel_started_off" \
  "$(grep -q 'event=kernel_started .*tracker=off' "$LOG_C" 2>/dev/null && echo 1 || echo 0)"
chk "tracker.disabled_reason_logged" \
  "$(grep -q 'event=tracker_disabled .*reason=base_url_empty' "$LOG_C" 2>/dev/null && echo 1 || echo 0)"

# 建一个群：开着的时候这里会拉起 tracker 线程并注册；关着就一条请求都不该有。
OFF_GROUP=$(post "http://127.0.0.1:$PORT_C/api/groups" '{"name":"tracker-off-group"}')
OFF_GID=$(sval "$OFF_GROUP" id)
chk "tracker.off_group_created (gid=$OFF_GID)" "$([ -n "$OFF_GID" ] && echo 1 || echo 0)"
sleep 3
chk "tracker.off_no_service" \
  "$(grep -q 'event=tracker_service_started' "$LOG_C" 2>/dev/null && echo 0 || echo 1)"
chk "tracker.off_no_register_request" \
  "$(grep -q 'event=tracker_register_failed\|event=tracker_register ' "$LOG_C" 2>/dev/null && echo 0 || echo 1)"
chk "tracker.off_no_announce_request" \
  "$(grep -q 'event=tracker_announce' "$LOG_C" 2>/dev/null && echo 0 || echo 1)"

# 运行期改开关：只落盘，内核不动（重启后生效）；回包必须把这件事说清楚。
TRACKER_ON=$(post "http://127.0.0.1:$PORT_C/api/settings/tracker" '{"enabled":true}')
chk "tracker.toggle_persisted (enabled=$(bval "$TRACKER_ON" enabled))" \
  "$([ "$(bval "$TRACKER_ON" enabled)" = "true" ] && echo 1 || echo 0)"
chk "tracker.toggle_needs_restart (restartRequired=$(bval "$TRACKER_ON" restartRequired) active=$(bval "$TRACKER_ON" active))" \
  "$([ "$(bval "$TRACKER_ON" restartRequired)" = "true" ] && [ "$(bval "$TRACKER_ON" active)" = "false" ] && echo 1 || echo 0)"
chk "tracker.toggle_logged" \
  "$(grep -q 'event=api_tracker_set enabled=true .*restart_required=true' "$LOG_C" 2>/dev/null && echo 1 || echo 0)"

post "http://127.0.0.1:$PORT_C/api/shutdown" '{}' > /dev/null
i=0
while [ "$i" -lt 20 ]; do
  port_busy "$PORT_C" || break
  sleep 0.5
  i=$((i + 1))
done
chk "tracker.off_node_stopped" "$(port_busy $PORT_C && echo 0 || echo 1)"
PID_C=""

# ------------------------------------------------------------------ 11. 链接分享（W1）
# 这一段只走 daemon 的 REST（不碰 core 内部）；8 MiB 的端到端在 11b，用 Edge headless 打开
# Pages 的接收页、经真 Worker 的 /signal 建链，浏览器自己算 SHA-256 再从 DataChannel 回执。
say ""
say "--- 链接分享：可用性 / 创建 / 列表 / 状态"
LINK_AVAIL=$(get "http://127.0.0.1:$PORT_A/api/shares")
LINK_AVAILABLE=$(bval "$LINK_AVAIL" available)
LINK_WEBRTC=$(sval "$LINK_AVAIL" webRtc)
LINK_BASE=$(sval "$LINK_AVAIL" baseUrl)
chk "link.available (available=$LINK_AVAILABLE webRtc=$LINK_WEBRTC)" \
  "$([ "$LINK_AVAILABLE" = "true" ] && echo 1 || echo 0)"
chk "link.base_is_pages ($LINK_BASE)" "$([ "$LINK_BASE" = "$LINK_API" ] && echo 1 || echo 0)"
chk "link.ready_logged" \
  "$(grep -q "event=link_ready .*base=$LINK_API" "$LOG_A" 2>/dev/null && echo 1 || echo 0)"

say "--- A 共享链接分享用的 8 MiB"
LINK_ADDED=$(post "http://127.0.0.1:$PORT_A/api/groups/$GID/files" "{\"path\":\"$(w "$LINK_FILE")\"}")
LINK_FID=$(sval "$LINK_ADDED" id)
chk "link.file_shared (id=$LINK_FID)" "$([ -n "$LINK_FID" ] && echo 1 || echo 0)"

# 请求体校验：缺 fileId / expireDays 越界 / 两个时长一起给，都必须是 400。
BAD_SHARE_STATUS=$(curl -s -m 20 -o "$ART/link-no-file.json" -w '%{http_code}' \
  -X POST -H 'Content-Type: application/json' --data-binary '{}' "http://127.0.0.1:$PORT_A/api/shares")
chk "link.create_requires_fileId (status=$BAD_SHARE_STATUS)" \
  "$([ "$BAD_SHARE_STATUS" = "400" ] && grep -q '"code":"E_BAD_FILE"' "$ART/link-no-file.json" && echo 1 || echo 0)"
BAD_EXPIRE_STATUS=$(curl -s -m 20 -o "$ART/link-bad-expire.json" -w '%{http_code}' \
  -X POST -H 'Content-Type: application/json' --data-binary "{\"fileId\":\"$LINK_FID\",\"expireDays\":9}" \
  "http://127.0.0.1:$PORT_A/api/shares")
chk "link.create_rejects_expireDays (status=$BAD_EXPIRE_STATUS)" \
  "$([ "$BAD_EXPIRE_STATUS" = "400" ] && grep -q '"code":"E_BAD_EXPIRE"' "$ART/link-bad-expire.json" && echo 1 || echo 0)"
BAD_BOTH_STATUS=$(curl -s -m 20 -o "$ART/link-both-expire.json" -w '%{http_code}' \
  -X POST -H 'Content-Type: application/json' --data-binary "{\"fileId\":\"$LINK_FID\",\"expireDays\":1,\"ttlSec\":60}" \
  "http://127.0.0.1:$PORT_A/api/shares")
chk "link.create_rejects_both_ttl (status=$BAD_BOTH_STATUS)" \
  "$([ "$BAD_BOTH_STATUS" = "400" ] && grep -q '"code":"E_BAD_EXPIRE"' "$ART/link-both-expire.json" && echo 1 || echo 0)"

LINK_CREATED=$(post "http://127.0.0.1:$PORT_A/api/shares" "{\"fileId\":\"$LINK_FID\"}")
LINK_TOKEN=$(sval "$LINK_CREATED" token)
LINK_TOKEN_TAG=$(printf '%s' "$LINK_TOKEN" | cut -c1-4)
LINK_SHARE_ID=$(sval "$LINK_CREATED" shareId)
LINK_SHARE_TAG=$(printf '%s' "$LINK_SHARE_ID" | cut -c1-8)
LINK_LINK=$(sval "$LINK_CREATED" link)
LINK_QR=$(sval "$LINK_CREATED" qrText)
LINK_CREATED_AT=$(ival "$LINK_CREATED" createdAt)
LINK_EXPIRES_AT=$(ival "$LINK_CREATED" expiresAt)
LINK_TTL_DAYS=$(( (LINK_EXPIRES_AT - LINK_CREATED_AT) / 86400000 ))
LINK_STATUS=$(sval "$LINK_CREATED" status)
LINK_PHASE=$(sval "$LINK_CREATED" phase)
chk "link.create_ok (share=$LINK_SHARE_TAG status=$LINK_STATUS phase=$LINK_PHASE)" \
  "$([ -n "$LINK_TOKEN" ] && [ "$LINK_STATUS" = "waiting" ] && [ "$LINK_PHASE" = "active" ] && echo 1 || echo 0)"
chk "link.link_shape ($LINK_LINK)" "$([ "$LINK_LINK" = "$LINK_API/#s=$LINK_TOKEN" ] && echo 1 || echo 0)"
chk "link.qr_is_link" "$([ "$LINK_QR" = "$LINK_LINK" ] && echo 1 || echo 0)"
chk "link.expiry_default_7d (days=$LINK_TTL_DAYS)" "$([ "$LINK_TTL_DAYS" = "7" ] && echo 1 || echo 0)"
chk "link.log_created" \
  "$(grep -qF "event=link_share_created token=$LINK_TOKEN link=$LINK_LINK" "$LOG_A" 2>/dev/null && echo 1 || echo 0)"

LINK_LIST=$(get "http://127.0.0.1:$PORT_A/api/shares")
LINK_LIST_ENTRY=$(printf '%s' "$LINK_LIST" | grep -o "\"token\":\"$LINK_TOKEN\"[^}]*" | head -1)
chk "link.list_has_share" "$([ -n "$LINK_LIST_ENTRY" ] && echo 1 || echo 0)"
chk "link.list_status_waiting" \
  "$(printf '%s' "$LINK_LIST_ENTRY" | grep -q '"status":"waiting"' && echo 1 || echo 0)"
chk "link.list_visitor_null" \
  "$(printf '%s' "$LINK_LIST_ENTRY" | grep -q '"visitor":null' && echo 1 || echo 0)"

LINK_STATUS_JSON=$(get "http://127.0.0.1:$PORT_A/api/shares/$LINK_TOKEN/status")
chk "link.status_waiting (status=$(sval "$LINK_STATUS_JSON" status) phase=$(sval "$LINK_STATUS_JSON" phase))" \
  "$([ "$(sval "$LINK_STATUS_JSON" status)" = "waiting" ] && [ "$(sval "$LINK_STATUS_JSON" phase)" = "active" ] && echo 1 || echo 0)"

# 过期用的分享：Worker 的有效期下限是 60 s，正好可以在这一轮跑完之前自然过期。
EXP_CREATED=$(post "http://127.0.0.1:$PORT_A/api/shares" "{\"fileId\":\"$LINK_FID\",\"ttlSec\":60}")
EXP_TOKEN=$(sval "$EXP_CREATED" token)
EXP_TOKEN_TAG=$(printf '%s' "$EXP_TOKEN" | cut -c1-4)
EXP_AT=$(ival "$EXP_CREATED" expiresAt)
chk "link.expire_share_created (ttl=60s token=$EXP_TOKEN_TAG)" \
  "$([ -n "$EXP_TOKEN" ] && [ -n "$EXP_AT" ] && echo 1 || echo 0)"

# ------------------------------------------------------------------ 11b. 链接分享端到端（8 MiB）
say ""
say "--- 链接分享端到端：daemon 发 8 MiB，Edge headless 接收页收并自己算 SHA-256"
LINK_E2E_TAG="m1-$(date +%Y%m%d-%H%M%S)"
LINK_E2E_RC=1
node "$ROOT/daemon/e2e/link-share-e2e.mjs" \
  --port "$PORT_A" --file "$(w "$LINK_FILE")" --out "$(w "$LINK_E2E")" \
  --tag "$LINK_E2E_TAG" --api "$LINK_API" --log "$(w "$LOG_A")" > "$ART/link-e2e.out" 2>&1
LINK_E2E_RC=$?
chk "link.e2e_pass (rc=$LINK_E2E_RC)" "$([ "$LINK_E2E_RC" = "0" ] && echo 1 || echo 0)"
if [ -f "$LINK_E2E/result.json" ]; then
  chk "link.e2e_browser_sha_matches" \
    "$(grep -q '\"shaOk\": true' "$LINK_E2E/result.json" && echo 1 || echo 0)"
  chk "link.e2e_daemon_status_done" \
    "$(grep -q '\"daemonStatus\": \"done\"' "$LINK_E2E/result.json" && echo 1 || echo 0)"
else
  note "端到端没有 result.json（见 $ART/link-e2e.out）"
  chk "link.e2e_browser_sha_matches" "0"
  chk "link.e2e_daemon_status_done" "0"
fi
chk "link.e2e_transfer_log" \
  "$(grep -q "event=link_transfer_done bytes=$LINK_BYTES sha256=$LINK_SRC_SHA" "$LOG_A" 2>/dev/null && echo 1 || echo 0)"
chk "link.e2e_screenshot" "$([ -f "$LINK_E2E/view-done.png" ] && echo 1 || echo 0)"

# 撤销：本机先断流，再让 Worker 标 revoked；DELETE 幂等，未知 token 404。
say "--- 撤销分享 / 幂等 / 未知 token"
LINK_REVOKED=$(curl -s -m 30 -X DELETE "http://127.0.0.1:$PORT_A/api/shares/$LINK_TOKEN")
chk "link.revoke (remoteRevoked=$(bval "$LINK_REVOKED" remoteRevoked) status=$(sval "$LINK_REVOKED" status))" \
  "$([ "$(bval "$LINK_REVOKED" remoteRevoked)" = "true" ] && [ "$(sval "$LINK_REVOKED" status)" = "revoked" ] && echo 1 || echo 0)"
chk "link.log_revoked" \
  "$(grep -q "event=link_share_revoked token=$LINK_TOKEN " "$LOG_A" 2>/dev/null && echo 1 || echo 0)"
LINK_REVOKED_STATUS=$(get "http://127.0.0.1:$PORT_A/api/shares/$LINK_TOKEN/status")
chk "link.revoked_status (status=$(sval "$LINK_REVOKED_STATUS" status) phase=$(sval "$LINK_REVOKED_STATUS" phase))" \
  "$([ "$(sval "$LINK_REVOKED_STATUS" status)" = "revoked" ] && [ "$(sval "$LINK_REVOKED_STATUS" phase)" = "finished" ] && echo 1 || echo 0)"
LINK_REVOKED_AGAIN=$(curl -s -m 30 -X DELETE "http://127.0.0.1:$PORT_A/api/shares/$LINK_TOKEN")
chk "link.revoke_idempotent (alreadyRevoked=$(bval "$LINK_REVOKED_AGAIN" alreadyRevoked))" \
  "$([ "$(bval "$LINK_REVOKED_AGAIN" alreadyRevoked)" = "true" ] && echo 1 || echo 0)"
LINK_UNKNOWN_STATUS=$(curl -s -m 20 -o /dev/null -w '%{http_code}' \
  "http://127.0.0.1:$PORT_A/api/shares/00000000000000000000000000/status")
chk "link.unknown_token_404 (status=$LINK_UNKNOWN_STATUS)" \
  "$([ "$LINK_UNKNOWN_STATUS" = "404" ] && echo 1 || echo 0)"

# ------------------------------------------------------------------ 12. 绿色版：没有 Java 的机器
# 复现「目标机没装 Java」：用 cmd 把 JAVA_HOME 清空、PATH 只留 System32，再双击同一个 exe。
# 判定三条同时成立才算过：
#   1) 这个端口本来没人监听（上面清场时查过），/api/health 能通 = daemon 是本轮拉起来的；
#   2) 壳日志里「拉起 daemon」那一行的 java 路径是 exe 同级的 runtime\bin\javaw.exe；
#   3) 那条命令行里没有出现 PATH 上的 javaw.exe（第 2 条已经蕴含，留着给日志看）。
say ""
say "--- 绿色版：PATH 里没有 java、JAVA_HOME 为空"
PKG_DIR="$ROOT/dist/Huchuan"
PKG_DATA="$ART/package-data"
PKG_SHELL_LOG="$(cygpath -u "${LOCALAPPDATA:-$HOME}")/Huchuan/logs/shell.log"

# 绿色版要验的是**这一轮**的产物：dist/Huchuan/daemon.jar 比刚构建的旧也重打，
# 否则测试会拿上一轮的 jar 冒充本轮（链接分享这类新端点尤其容易假绿）。
PKG_STALE=0
if [ ! -f "$PKG_DIR/daemon.jar" ] || [ "$JAR" -nt "$PKG_DIR/daemon.jar" ]; then PKG_STALE=1; fi
if [ ! -f "$PKG_DIR/Huchuan.exe" ] || [ "$PKG_STALE" = "1" ]; then
  note "dist/Huchuan 不存在或 daemon.jar 过期，先打包（scripts/package-windows.sh）"
  bash "$SCRIPT_DIR/package-windows.sh" > "$ART/package.log" 2>&1 \
    || { tail -n 15 "$ART/package.log" >&2; note "打包失败，完整日志：artifacts/m1-windows/package.log"; }
fi

PKG_HEALTH=""
PKG_JAVA_LINE=""
PKG_PID=""
if [ -f "$PKG_DIR/Huchuan.exe" ] && [ -x "$PKG_DIR/runtime/bin/javaw.exe" ]; then
  rm -rf "$PKG_DATA"
  mkdir -p "$PKG_DATA"
  BEFORE_PIDS=$(huchuan_pids)
  rm -f "$PKG_SHELL_LOG"
  # 环境变量只在这条 cmd 里生效：JAVA_HOME 清空、PATH 只留 System32 —— 就是「没装 Java 的机器」。
  # start /b 让 cmd 立刻返回（cmd 自己会等 GUI 进程退出，不能直接写 Huchuan.exe）。
  cmd.exe //c "set JAVA_HOME=&&set PATH=C:\\Windows\\System32&&cd /d $(w "$PKG_DIR")&&start /b Huchuan.exe --daemon-port=$PORT_PKG --daemon-data-dir=$(w "$PKG_DATA")" \
    > "$ART/package-shell.out" 2>&1
  PKG_HEALTH=$(wait_health "$PORT_PKG" 90) || PKG_HEALTH=""
  AFTER_PIDS=$(huchuan_pids)
  PKG_PID=$(comm -13 <(printf '%s\n' "$BEFORE_PIDS") <(printf '%s\n' "$AFTER_PIDS") 2>/dev/null | head -1)
  PKG_JAVA_LINE=$(tr -d '\r' < "$PKG_SHELL_LOG" 2>/dev/null | grep -m1 '拉起 daemon' || true)
  [ -n "$PKG_JAVA_LINE" ] || PKG_JAVA_LINE=$(tr -d '\r' < "$PKG_SHELL_LOG" 2>/dev/null | grep -m1 'Java：' || true)
  case "$PKG_HEALTH" in
    *'"status":"ok"'*) note "绿色版 daemon 就绪：$PKG_HEALTH" ;;
    *) note "绿色版 daemon 没起来（壳日志 $PKG_SHELL_LOG）" ;;
  esac
  [ -n "$PKG_JAVA_LINE" ] && note "壳用的 java：$PKG_JAVA_LINE"
fi

# 绿色版的 runtime 是 jlink 裁出来的：链接分享要的是原生 WebRTC 库，光把 jar 装进去不够。
# GET /api/shares 会做一次真探测（WebRtc.create 加载 22.8 MB 的 DLL），能回 available=true
# 才算这个精简 JRE 把模块带对了；模块少了这里会变成 false，而不是等用户点分享才炸。
PKG_LINK=""
if [ -n "$PKG_HEALTH" ]; then
  PKG_LINK=$(get "http://127.0.0.1:$PORT_PKG/api/shares")
fi
PKG_LINK_OK=$(bval "$PKG_LINK" available)
[ -n "$PKG_LINK_OK" ] || PKG_LINK_OK="none"
PKG_LINK_WEBRTC=$(sval "$PKG_LINK" webRtc)
[ -n "$PKG_LINK_WEBRTC" ] || PKG_LINK_WEBRTC="none"
note "绿色版的链接分享：available=$PKG_LINK_OK webRtc=$PKG_LINK_WEBRTC reason=$(sval "$PKG_LINK" reason)"

# 收尾：先请 daemon 自己停，再关壳窗口（壳退出时会再试一次，不会留孤儿）
if [ -n "$PKG_HEALTH" ]; then
  post "http://127.0.0.1:$PORT_PKG/api/shutdown" '{}' > /dev/null 2>&1 || true
fi
if [ -n "$PKG_PID" ]; then
  powershell.exe -NoProfile -Command "try { (Get-Process -Id $PKG_PID -ErrorAction Stop).CloseMainWindow() | Out-Null } catch {}" > /dev/null 2>&1
  sleep 2
  kill_port "$PORT_PKG"
fi

case "$PKG_HEALTH" in *'"status":"ok"'*) PKG_HEALTH_OK=1 ;; *) PKG_HEALTH_OK=0 ;; esac
case "$PKG_JAVA_LINE" in
  *'runtime\bin\javaw.exe'*) PKG_RUNTIME_OK=1 ;;
  *) PKG_RUNTIME_OK=0 ;;
esac
chk "package.shell_runs_without_java (api=$PORT_PKG, PATH=C:\\Windows\\System32, JAVA_HOME 空)" \
  "$([ "$PKG_HEALTH_OK" = "1" ] && [ "$PKG_RUNTIME_OK" = "1" ] && echo 1 || echo 0)"

chk "package.link_available_in_green_runtime (available=$PKG_LINK_OK webRtc=$PKG_LINK_WEBRTC)" \
  "$([ "$PKG_LINK_OK" = "true" ] && echo 1 || echo 0)"

# ------------------------------------------------------------------ 12c. 链接分享：过期
# 11 段创建的 ttlSec=60 分享到这里一定过期了；若这一轮跑得比 60 s 还快，就等到 expiresAt。
say ""
say "--- 链接分享：过期（ttlSec=60）"
EXP_STATUS=""
EXP_DONE=0
EXP_LIMIT=$(( (EXP_AT + 2000) / 1000 ))
while [ "$EXP_DONE" = "0" ]; do
  EXP_STATUS=$(get "http://127.0.0.1:$PORT_A/api/shares/$EXP_TOKEN/status")
  case "$EXP_STATUS" in
    *'"status":"expired"'*) EXP_DONE=1 ;;
  esac
  [ "$EXP_DONE" = "1" ] && break
  NOW_S=$(date +%s)
  if [ "$NOW_S" -gt "$((EXP_LIMIT + 30))" ]; then break; fi
  sleep 2
done
chk "link.expired_status (status=$(sval "$EXP_STATUS" status) phase=$(sval "$EXP_STATUS" phase))" \
  "$([ "$(sval "$EXP_STATUS" status)" = "expired" ] && [ "$(sval "$EXP_STATUS" phase)" = "finished" ] && echo 1 || echo 0)"
chk "link.expired_log" \
  "$(grep -q "event=link_share_expired token=$EXP_TOKEN " "$LOG_A" 2>/dev/null && echo 1 || echo 0)"
chk "link.expired_session_closed" \
  "$(grep -q "event=link_session_closed .*reason=expired" "$LOG_A" 2>/dev/null && echo 1 || echo 0)"

# ------------------------------------------------------------------ 12b. 公开种子（磁力）
# 放在最后一组：磁力导入会拉起公开会话（DHT），与前面的私有传输无关；把潜在的公开会话异常
# 隔离在收尾之前，前面的断言已经全部跑完。断言只走 daemon 的 REST 与 /api/state，不碰 core 内部。
say ""
say "--- 公开种子：非法磁力 / 重复 / state / 本地移除"
PUB_HASH="0123456789abcdef0123456789abcdef01234567"
PUB_MAGNET="magnet:?xt=urn:btih:$PUB_HASH&dn=m1-public.bin&xl=1048576"
MAGNET_URL="http://127.0.0.1:$PORT_A/api/groups/$GID/files/import-magnet"

BAD_STATUS=$(post_file "$MAGNET_URL" '{"magnet":"not-a-magnet-link"}' "$ART/import-bad.json")
BAD_BODY=$(cat "$ART/import-bad.json")
BAD_CODE=$(sval "$BAD_BODY" code)
BAD_MSG=$(sval "$BAD_BODY" message)
chk "magnet.invalid_link_400 (status=$BAD_STATUS code=$BAD_CODE)" \
  "$([ "$BAD_STATUS" = "400" ] && [ "$BAD_CODE" = "invalid_link" ] && echo 1 || echo 0)"
chk "magnet.invalid_link_message ($BAD_MSG)" \
  "$([ "$BAD_MSG" = "链接无法识别" ] && echo 1 || echo 0)"

IMP_STATUS=$(post_file "$MAGNET_URL" \
  "{\"magnet\":\"$PUB_MAGNET\",\"name\":\"m1-public.bin\",\"timeoutMs\":0}" "$ART/import-1.json")
IMP_BODY=$(cat "$ART/import-1.json")
PUB_FID=$(sval "$IMP_BODY" fileId)
IMP_CODE=$(sval "$IMP_BODY" code)
MAGNET_PAYLOAD_OK=$(printf '%s' "$IMP_BODY" | grep -o '"ok":[a-z]*' | tail -1 | sed 's/.*://')
chk "magnet.import_accepted (status=$IMP_STATUS fileId=$PUB_FID)" \
  "$([ "$IMP_STATUS" = "200" ] && [ "$PUB_FID" = "$PUB_HASH" ] && echo 1 || echo 0)"
case "$IMP_CODE" in
  "") MAGNET_EXPECT_OK=true ;;
  no_source|timeout|bad_metadata) MAGNET_EXPECT_OK=false ;;
  *) MAGNET_EXPECT_OK=unknown ;;
esac
chk "magnet.import_payload (ok=$MAGNET_PAYLOAD_OK code=$IMP_CODE)" \
  "$([ "$MAGNET_PAYLOAD_OK" = "$MAGNET_EXPECT_OK" ] && echo 1 || echo 0)"

# state：条目以 scope=public 出现并带规范化后的 magnet；尺寸未知时 sizeBytes 整条不给。
STATE_PUB=""
i=0
while [ "$i" -lt 40 ]; do
  STATE_PUB=$(get "http://127.0.0.1:$PORT_A/api/state")
  case "$STATE_PUB" in *"\"id\":\"$PUB_HASH\""*) break ;; esac
  sleep 0.5
  i=$((i + 1))
done
PUB_ENTRY=$(printf '%s' "$STATE_PUB" | grep -o "\"id\":\"$PUB_HASH\"[^}]*" | head -1)
chk "magnet.entry_in_state (fileId=$PUB_FID)" "$([ -n "$PUB_ENTRY" ] && echo 1 || echo 0)"
chk "magnet.entry_scope_public" \
  "$(printf '%s' "$PUB_ENTRY" | grep -q '"scope":"public"' && echo 1 || echo 0)"
chk "magnet.entry_has_magnet" \
  "$(printf '%s' "$PUB_ENTRY" | grep -qF "\"magnet\":\"$PUB_MAGNET\"" && echo 1 || echo 0)"
SIZE_KNOWN=$(bval "$PUB_ENTRY" sizeKnown)
HAS_SIZE_BYTES=$(printf '%s' "$PUB_ENTRY" | grep -q '"sizeBytes"' && echo 1 || echo 0)
if [ "$SIZE_KNOWN" = "false" ]; then
  SIZE_CONTRACT=$([ "$HAS_SIZE_BYTES" = "0" ] && echo 1 || echo 0)
else
  SIZE_CONTRACT=$([ "$SIZE_KNOWN" = "true" ] && [ "$HAS_SIZE_BYTES" = "1" ] && echo 1 || echo 0)
fi
chk "magnet.size_known_contract (sizeKnown=$SIZE_KNOWN sizeBytes=$HAS_SIZE_BYTES)" "$SIZE_CONTRACT"

# 同一合法磁力再导入一次：同群按 infohash 去重，第二次是 409 duplicate。
DUP_STATUS=$(post_file "$MAGNET_URL" "{\"magnet\":\"$PUB_MAGNET\"}" "$ART/import-2.json")
DUP_BODY=$(cat "$ART/import-2.json")
DUP_CODE=$(sval "$DUP_BODY" code)
chk "magnet.duplicate_409 (status=$DUP_STATUS code=$DUP_CODE)" \
  "$([ "$DUP_STATUS" = "409" ] && [ "$DUP_CODE" = "duplicate" ] && echo 1 || echo 0)"
chk "magnet.duplicate_message ($(sval "$DUP_BODY" message))" \
  "$(printf '%s' "$DUP_BODY" | grep -q '这个种子已经在群里' && echo 1 || echo 0)"

# 条目是广播出去的（同群 B 应该看得到），移除只影响本机 —— 这一对断言证明「不广播」。
STATE_B_PUB=""
i=0
while [ "$i" -lt 40 ]; do
  STATE_B_PUB=$(get "http://127.0.0.1:$PORT_B/api/state")
  case "$STATE_B_PUB" in *"\"id\":\"$PUB_HASH\""*) break ;; esac
  sleep 0.5
  i=$((i + 1))
done
chk "magnet.broadcast_reaches_B" \
  "$(printf '%s' "$STATE_B_PUB" | grep -qF "\"id\":\"$PUB_HASH\"" && echo 1 || echo 0)"

REMOVED=$(post "http://127.0.0.1:$PORT_A/api/groups/$GID/files/$PUB_FID/remove" '{}')
chk "magnet.remove_ok (removed=$(bval "$REMOVED" removed) broadcast=$(bval "$REMOVED" broadcast))" \
  "$([ "$(bval "$REMOVED" removed)" = "true" ] && echo 1 || echo 0)"
STATE_AFTER=$(get "http://127.0.0.1:$PORT_A/api/state")
chk "magnet.removed_from_state" \
  "$(printf '%s' "$STATE_AFTER" | grep -qF "\"id\":\"$PUB_HASH\"" && echo 0 || echo 1)"
FILE_AFTER_STATUS=$(curl -s -m 15 -o /dev/null -w '%{http_code}' \
  "http://127.0.0.1:$PORT_A/api/groups/$GID/files/$PUB_FID")
chk "magnet.removed_file_404 (status=$FILE_AFTER_STATUS)" \
  "$([ "$FILE_AFTER_STATUS" = "404" ] && echo 1 || echo 0)"
chk "magnet.hidden_persisted (settings.json)" \
  "$(grep -qF "$GID/$PUB_HASH" "$DATA_A/settings.json" 2>/dev/null && echo 1 || echo 0)"
chk "magnet.remove_logged" \
  "$(grep -q "event=api_file_remove_local .*file_id=$PUB_HASH" "$LOG_A" 2>/dev/null && echo 1 || echo 0)"
STATE_B_AFTER=$(get "http://127.0.0.1:$PORT_B/api/state")
chk "magnet.remove_not_broadcast (B still has it)" \
  "$(printf '%s' "$STATE_B_AFTER" | grep -qF "\"id\":\"$PUB_HASH\"" && echo 1 || echo 0)"

# ------------------------------------------------------------------ 13. 停
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

# ------------------------------------------------------------------ 14. 汇总
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
