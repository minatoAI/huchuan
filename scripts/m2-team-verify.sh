#!/usr/bin/env bash
#
# m2-team-verify.sh — 组队通告「队友 ≥ 1」的真机展示验收（M2）
#
#   bash scripts/m2-team-verify.sh          # 一条命令跑完，最后一行打印 PASS / FAIL（退出码 0 / 1）
#
# 场景（docs/m1-device.md §6-4 记的缺口：n ≥ 1 的展示一直没有真机证据）：
#   S（桌面 JVM，做种 64 MiB）→ B（桌面 JVM，加入同一群并下载同一文件）→ 手机（加入并下载）。
#   手机下载期间 B 也处于「部分」，于是手机的队友数 = 1、队伍人数 = 2：
#     P3 文件行「组队 2 人」、P4 进度区「正在组队 · 2 人」；手机到 100% 两者都消失。
#
# 四条断言（缺一条不算 PASS）：
#   team.teammates_logged   内核日志出现 event=teammates_changed 且 count ≥ 1
#   team.p4_line_2          P4 出现「正在组队 · 2 人」（ui-dump 取全屏文本）
#   team.p3_chip_2          P3 文件行出现「组队 2 人」
#   team.cleared_at_100     手机到 100% 后两处都不再出现
#   team.drop_caused_by_completion  过程中没有 teammates_changed count=0（消失是本机完成造成的，
#                           不是队友先跑光）
#
# 为什么能稳定跑出 n=1：
#   - S 的单文件上传限速 512 KB/s → 两份下载各自 ~256 KB/s，窗口有几分钟；
#   - 手机侧单文件上行限速 64 KB/s（P4 输入框）→ B 追不平手机的点位，手机先到 100%；
#   - B 在手机进度 ≥ 25% 时才启动，保证「手机在下载、B 也在下载」的窗口足够长。
#   三项都能用环境变量改。
#
# 边界：只写 artifacts/team-verify/；不构建 app/ 与 core/（用现成产物）；
#       只碰 5b94673a 这一台设备；只清自己的对端（命令行含 team-verify 的 java）。
#
# 环境变量：M2_SERIAL（默认 5b94673a）、M2_MIB（64）、M2_SEED_KBPS（512）、
#           M2_PHONE_UP_KBPS（64，0 = 不设）、M2_B_START_PCT（25）、M2_KEEP_APK（1 = 不重装）
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ART="$ROOT/artifacts/team-verify"
DEVKIT="$ROOT/tools/devkit/devkit.mjs"
PKG="app.huchuan"
ACTIVITY=".MainActivity"

SERIAL="${M2_SERIAL:-5b94673a}"
MIB="${M2_MIB:-64}"
SEED_KBPS="${M2_SEED_KBPS:-512}"
PHONE_UP_KBPS="${M2_PHONE_UP_KBPS:-64}"
B_START_PCT="${M2_B_START_PCT:-25}"
S_MSG_PORT="${M2_S_MSG_PORT:-47200}"
S_BT_PORT="${M2_S_BT_PORT:-51513}"
B_MSG_PORT="${M2_B_MSG_PORT:-47210}"
B_BT_PORT="${M2_B_BT_PORT:-51523}"
GROUP_NAME="${M2_GROUP_NAME:-验收群}"
S_NAME="${M2_S_NAME:-种子机}"
B_NAME="${M2_B_NAME:-队友机}"

say() { printf '%s\n' "$*"; }
step() { printf '\n=== %s ===\n' "$1"; }
die() { say "FATAL: $*" >&2; say "FAIL"; exit 1; }
warn() { say "WARN  $*"; }
info() { say "INFO  $*"; }

CHECKS_FAIL=0
CHECKS_PASS=0
chk() {
  local name="$1" ok="$2" detail="${3:-}"
  if [ "$ok" = "1" ]; then
    say "CHECK PASS ${name}${detail:+ ($detail)}"
    CHECKS_PASS=$((CHECKS_PASS + 1))
  else
    say "CHECK FAIL ${name}${detail:+ ($detail)}"
    CHECKS_FAIL=$((CHECKS_FAIL + 1))
  fi
}

# Windows 原生程序（java / javac / node / adb）只认 Windows 路径。
w() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

# 设备路径不能被 MSYS 改写（/sdcard → C:/Program Files/Git/sdcard）。
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

resolve_adb() {
  local candidate
  for candidate in "${M2_ADB:-}" "${ADB:-}" "${DEVKIT_ADB:-}" \
    "/e/agentHome/android-sdk/platform-tools/adb.exe" \
    "${ANDROID_HOME:-}/platform-tools/adb.exe" \
    "${ANDROID_SDK_ROOT:-}/platform-tools/adb.exe"; do
    [ -n "$candidate" ] || continue
    if [ -f "$candidate" ]; then printf '%s' "$candidate"; return 0; fi
  done
  command -v adb 2>/dev/null && return 0
  return 1
}

ADB="$(resolve_adb)" || die "找不到 adb（设 M2_ADB 或 ANDROID_HOME）"
[ -f "$DEVKIT" ] || die "找不到 tools/devkit/devkit.mjs"

# devkit 是唯一的真机操作入口（docs/m1-device.md §6-2）；产物全部落在本任务的证据目录。
dks() { node "$(w "$DEVKIT")" -s "$SERIAL" --artifacts "$(w "$ART")" "$@" 2>&1; }
sh_() { "$ADB" -s "$SERIAL" shell "$@" 2>&1; }

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

# 手机内核事件日志（tag = huchuan/m1）：「event=xxx k=v ...」。
applog() {
  "$ADB" -s "$SERIAL" logcat -d -v brief 2>/dev/null \
    | grep -F "huchuan/m1" \
    | sed 's/.*huchuan\/m1[^:]*: //'
}

count_log() { applog | grep -cF -- "$1" || true; }

wait_log() {
  local pattern="$1" timeout="${2:-60000}" start
  start=$(date +%s)
  while [ $(( $(date +%s) - start )) -lt "$timeout" ]; do
    if applog | grep -qF -- "$pattern"; then return 0; fi
    sleep 1
  done
  return 1
}

wait_log_count() {
  local pattern="$1" want="$2" timeout="${3:-60000}" start
  start=$(date +%s)
  while [ $(( $(date +%s) - start )) -lt "$timeout" ]; do
    if [ "$(count_log "$pattern")" -ge "$want" ]; then return 0; fi
    sleep 1
  done
  return 1
}

log_field() {
  applog | grep -F -- "$1" | grep -o "$2=[^ ]*" | tail -1 | cut -d= -f2
}

# 手机当前下载进度（logcat 的 download_progress pct=）；本流程只有一个在下的文件。
phone_pct() {
  applog | grep -F 'event=download_progress' | grep -o 'pct=[0-9]*' | tail -1 | cut -d= -f2
}

# 全屏文本 + 一份 XML（devkit 落在 artifacts/team-verify/ui-dumps/<serial>/）。
ui_snapshot() { dks ui-dump --texts --name "$1" 2>/dev/null; }

back_to_group() {
  local i
  for i in 1 2 3 4; do
    if see_text "添加文件" 4000; then return 0; fi
    sh_ input keyevent KEYCODE_BACK >/dev/null 2>&1
    sleep 1
  done
  return 1
}

peer_field() {
  grep -F -- "$2" "$1" 2>/dev/null | grep -o "$3=[^ ]*" | tail -1 | cut -d= -f2
}

sha256_of() { sha256sum "$1" | cut -d' ' -f1; }

# ------------------------------------------------------------------ 0. 目录 / 锁 / 收尾

mkdir -p "$ART/screenshots" "$ART/ui-dumps" "$ART/logs" "$ART/payload" "$ART/desktop"
LOCK="$ART/.running"
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK" 2>/dev/null)" 2>/dev/null; then
  die "已有另一个 m2-team-verify.sh 在跑（pid $(cat "$LOCK")），先结束它"
fi
echo $$ > "$LOCK"

S_LOG="$ART/logs/desktop-S.log"
B_LOG="$ART/logs/desktop-B.log"
rm -f "$S_LOG" "$B_LOG"
S_PID=""
B_PID=""
cleanup() {
  local pid
  for pid in "$B_PID" "$S_PID"; do
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null
      sleep 0.3
      kill -9 "$pid" 2>/dev/null
    fi
  done
  rm -f "$LOCK"
}
trap cleanup EXIT

# ------------------------------------------------------------------ 1. 预检

say "=== M2 组队通告真机验证 ============================================"
say "root      : $ROOT"
say "device    : $SERIAL"
say "scenario  : S(seed ${MIB} MiB) + B(join & download) + phone(join & download)"

[ -n "$ADB" ] || die "adb 不可用"
STATE="$("$ADB" -s "$SERIAL" get-state 2>&1 | tr -d '\r')"
[ "$STATE" = "device" ] || die "设备 $SERIAL 不在线（get-state=$STATE）"
MODEL="$(sh_ getprop ro.product.model | tr -d '\r')"
SDK="$(sh_ getprop ro.build.version.sdk | tr -d '\r')"
say "model     : $MODEL  API $SDK"
chk "env.device_online" "$([ -n "$MODEL" ] && echo 1 || echo 0)" "$SERIAL $MODEL"

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || die "APK 不存在：$APK（本脚本不构建 app/，请先让 UI agent 出包）"
CORE_JAR="$(ls "$ROOT"/core/build/libs/*.jar 2>/dev/null | head -1)"
[ -n "$CORE_JAR" ] || die "core jar 不存在（core/build/libs）"
PEER_NATIVE="$ROOT/core/build/m1-native/libtorrent4j.dll"
[ -f "$PEER_NATIVE" ] || die "libtorrent4j.dll 未解出（core/build/m1-native）"

PEER_CP="$(w "$CORE_JAR")"
for jar in "$ROOT"/core/build/m1-libs/*.jar; do
  [ -f "$jar" ] && PEER_CP="$PEER_CP;$(w "$jar")"
done
PEER_CLASSES="$ART/peer-classes"
mkdir -p "$PEER_CLASSES"
javac -encoding UTF-8 -cp "$PEER_CP" -d "$(w "$PEER_CLASSES")" "$(w "$ROOT/app/tools/peer/DesktopPeer.java")" \
  || die "javac 桌面对端失败"
PEER_CP="$PEER_CP;$(w "$PEER_CLASSES")"

# 端口必须空着：本机同时可能跑着别的 agent 的对端，抢端口会把他们的验证搅坏。
for port in "$S_MSG_PORT" "$S_BT_PORT" "$B_MSG_PORT" "$B_BT_PORT"; do
  if netstat -ano 2>/dev/null | grep LISTENING | grep -qE "[:.]$port[[:space:]]"; then
    die "端口 $port 已被占用（另一个对端在跑？），换 M2_*_PORT 或先结束它"
  fi
done

# ------------------------------------------------------------------ 2. 清场与安装

step "1/8 清场并安装"
# 只清自己的对端：命令行里带 artifacts/team-verify 的 java 进程。
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter 'Name=\"java.exe\"' | Where-Object { \$_.CommandLine -like '*team-verify*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1
sh_ am force-stop "$PKG" >/dev/null 2>&1
if [ "${M2_KEEP_APK:-0}" = "1" ]; then
  info "M2_KEEP_APK=1：不重装 APK"
else
  sh_ pm clear "$PKG" >/dev/null 2>&1
  "$ADB" -s "$SERIAL" install -r -t -g "$(w "$APK")" 2>&1 | grep -E "Success|Failure|Error" | tail -1
fi
"$ADB" -s "$SERIAL" logcat -b all -c >/dev/null 2>&1
info "logcat / crash buffer cleared"

PAYLOAD="$ART/payload/team-${MIB}m.bin"
WANT_BYTES=$(( MIB * 1024 * 1024 ))
if [ ! -f "$PAYLOAD" ] || [ "$(stat -c %s "$PAYLOAD" 2>/dev/null)" != "$WANT_BYTES" ]; then
  head -c "$WANT_BYTES" /dev/urandom > "$PAYLOAD" || die "生成 ${MIB} MiB 素材失败"
fi
PAYLOAD_NAME="$(basename "$PAYLOAD")"
PAYLOAD_SHA="$(sha256_of "$PAYLOAD")"
info "payload ${MIB} MiB sha256=$PAYLOAD_SHA"

PHONE_IP="$(sh_ "ip -4 addr show wlan0" | grep -o 'inet [0-9.]*' | head -1 | awk '{print $2}')"
[ -n "$PHONE_IP" ] || die "手机没有 wlan0 IPv4"
PC_IP="$(node -e '
const os=require("os");const want=process.argv[1].split(".").slice(0,3).join(".");
for (const list of Object.values(os.networkInterfaces())) for (const i of list||[]) {
  if (i.family==="IPv4" && !i.internal && i.address.startsWith(want+".")) { console.log(i.address); process.exit(0); }
}
' "$PHONE_IP" 2>/dev/null)"
[ -n "$PC_IP" ] || die "找不到与手机同网段的桌面 IPv4（手机 $PHONE_IP）"
info "phone=$PHONE_IP desktop=$PC_IP"

sh_ svc power stayon true >/dev/null 2>&1
sh_ settings put system screen_off_timeout 1800000 >/dev/null 2>&1
sh_ input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
sh_ wm dismiss-keyguard >/dev/null 2>&1

# ------------------------------------------------------------------ 3. S：做种

step "2/8 起 S：建群 + 共享 ${MIB} MiB（上传限速 ${SEED_KBPS} KB/s）"
rm -rf "$ART/desktop/S-data" "$ART/desktop/B-data"
mkdir -p "$ART/desktop/S-data" "$ART/desktop/B-data"
java -Dlibtorrent4j.jni.path="$(w "$PEER_NATIVE")" -Dfile.encoding=UTF-8 -cp "$PEER_CP" DesktopPeer host \
  data="$(w "$ART/desktop/S-data")" work="$(w "$ART/desktop")" \
  name="$S_NAME" group_name="$GROUP_NAME" \
  msg_port="$S_MSG_PORT" bt_port="$S_BT_PORT" \
  share="$(w "$PAYLOAD")" throttle="0:${SEED_KBPS}" hold=2400 download=0 \
  > "$S_LOG" 2>&1 &
S_PID=$!

TAIL8=""
S_MSG=""
for _ in $(seq 1 160); do
  TAIL8="$(peer_field "$S_LOG" "PEER ready" tail8)"
  S_MSG="$(peer_field "$S_LOG" "PEER ready" msg_port)"
  [ -n "$TAIL8" ] && [ -n "$S_MSG" ] && break
  kill -0 "$S_PID" 2>/dev/null || break
  sleep 0.5
done
[ -n "$TAIL8" ] && [ -n "$S_MSG" ] || { tail -20 "$S_LOG"; die "S 没起来（没打印 tail8 / msg_port）"; }
say "S         : tail8=$TAIL8 msg_port=$S_MSG pid=$S_PID"
[ "$S_MSG" = "$S_MSG_PORT" ] || die "S 的消息端口不是 $S_MSG_PORT（$S_MSG）"

for _ in $(seq 1 120); do
  [ -n "$(peer_field "$S_LOG" "PEER shared_count" "n")" ] && break
  sleep 0.5
done
S_SHARED="$(peer_field "$S_LOG" "PEER shared_count" n)"
# 「 size=」要带前导空格：piece_size= 里也含 size=，不加锚点会取到片大小 65536。
S_SIZE="$(grep -F 'PEER share file_id' "$S_LOG" | grep -o ' size=[0-9]*' | head -1 | sed 's/ *size=//')"
chk "desktop.S_share_${MIB}mib" "$([ "${S_SIZE:-0}" = "$WANT_BYTES" ] && echo 1 || echo 0)" "size=${S_SIZE:-?} n=${S_SHARED:-?}"
S_THROTTLE="$(grep -c 'PEER throttle' "$S_LOG" 2>/dev/null || true)"
info "S throttle=${SEED_KBPS} KB/s（throttle 行数=${S_THROTTLE:-0}）"

# ------------------------------------------------------------------ 4. 手机加入

step "3/8 手机加入（P5 只输后 8 位）→ P3"
sh_ am start -n "$PKG/$ACTIVITY" --es huchuan.debug.manual "$PC_IP:$S_MSG" 2>&1 | tail -1
if ! wait_log "app_kernel_ready" 60000; then applog | tail -20; die "内核没起来"; fi
if ! see_text "用户名" 180000; then die "P0 没进 P1（自检卡住？）"; fi
tap "保存"
if ! see_text "邀请码后 8 位" 30000; then die "没进 P5"; fi
tap "邀请码后 8 位"
sh_ input text "$TAIL8" >/dev/null 2>&1
sleep 1
tap "加入"
if ! wait_log "event=group_joined" 120000; then applog | grep -E "join" | tail -20; die "加入失败"; fi
if ! see_text "添加文件" 30000; then die "加入后没进 P3"; fi
if ! see_text "$PAYLOAD_NAME" 40000; then die "P3 上没有 $PAYLOAD_NAME"; fi
ui_snapshot "p3-before-download" >/dev/null
say "phone     : 已在群内，P3 可见 $PAYLOAD_NAME"

# ------------------------------------------------------------------ 5. 手机开始下载

step "4/8 手机进 P4 并激活（开始下载同一文件）"
tap "$PAYLOAD_NAME" exact
if ! see_text "写评论" 30000; then die "点文件行没进 P4"; fi
ui_snapshot "p4-before-team" >/dev/null
tap "激活开关"
if ! wait_log "event=download_start" 120000; then
  applog | tail -20
  die "激活后没开始下载"
fi
PHONE_FILE_ID="$(log_field "event=download_start" file_id)"
info "phone download_start file_id=${PHONE_FILE_ID:-?}"

# 单文件上行限速：B 追不平手机的点位，手机才能「先到 100%」，消失才归因于完成。
if [ "$PHONE_UP_KBPS" != "0" ]; then
  if tap "不限" ; then
    sleep 1
    sh_ input text "$PHONE_UP_KBPS" >/dev/null 2>&1
    sleep 2
    sh_ input keyevent 111 >/dev/null 2>&1 || true   # ESC 收键盘，截图别被键盘挡住
    UP_LINE="$(applog | grep -F 'event=file_upload_limit' | tail -1)"
    if printf '%s' "$UP_LINE" | grep -q "kbps=$PHONE_UP_KBPS "; then
      info "phone 上行限速 $PHONE_UP_KBPS KB/s：$(printf '%s' "$UP_LINE" | grep -o 'kbps=[0-9]* applied=[a-z]*')"
    else
      warn "手机上行限速没落地（${UP_LINE:-无日志}）"
    fi
  else
    warn "点不进单文件限速输入框，跳过手机上行限速"
  fi
fi

PCT=0
for _ in $(seq 1 300); do
  PCT="$(phone_pct)"
  if [ -n "$PCT" ] && [ "$PCT" -ge "$B_START_PCT" ] 2>/dev/null; then break; fi
  sleep 1
done
if [ "${PCT:-0}" -lt "$B_START_PCT" ] 2>/dev/null; then die "手机进度到不了 ${B_START_PCT}%（当前 ${PCT:-?}%）"; fi
say "phone     : 进度 ${PCT}%，起 B"

# ------------------------------------------------------------------ 6. B：加入并下载同一文件

step "5/8 起 B：加入同群并下载同一文件"
java -Dlibtorrent4j.jni.path="$(w "$PEER_NATIVE")" -Dfile.encoding=UTF-8 -cp "$PEER_CP" DesktopPeer join \
  data="$(w "$ART/desktop/B-data")" work="$(w "$ART/desktop")" \
  name="$B_NAME" tail8="$TAIL8" manual="$PC_IP:$S_MSG" download=1 \
  msg_port="$B_MSG_PORT" bt_port="$B_BT_PORT" hold=1800 \
  > "$B_LOG" 2>&1 &
B_PID=$!

B_STARTED=0
for _ in $(seq 1 300); do
  if grep -q 'PEER download_start ok=true' "$B_LOG" 2>/dev/null; then B_STARTED=1; break; fi
  if grep -q 'RESULT=FAIL' "$B_LOG" 2>/dev/null; then break; fi
  sleep 1
done
[ "$B_STARTED" = "1" ] || { tail -20 "$B_LOG"; die "B 没开始下载"; }
say "B         : $(grep -o 'PEER download_start ok=true.*' "$B_LOG" | tail -1)  pid=$B_PID"
chk "desktop.B_downloading" 1 "B 与手机同时在下载 $PAYLOAD_NAME"

# ------------------------------------------------------------------ 7. 队友 ≥ 1

step "6/8 断言 teammates_changed count>0"
TEAM_SEEN=0
for _ in $(seq 1 300); do
  if applog | grep -F 'event=teammates_changed' | grep -qv 'count=0'; then TEAM_SEEN=1; break; fi
  sleep 1
done
TEAM_LINES="$(applog | grep -F 'event=teammates_changed' || true)"
TEAM_MAX="$(printf '%s' "$TEAM_LINES" | grep -o 'count=[0-9]*' | cut -d= -f2 | sort -n | tail -1)"
TEAM_ZERO="$(printf '%s' "$TEAM_LINES" | grep -c 'count=0' || true)"
chk "team.teammates_logged" "$([ "$TEAM_SEEN" = "1" ] && echo 1 || echo 0)" \
  "count 最大=${TEAM_MAX:-?}，count=0 条数=${TEAM_ZERO:-0}"
printf '%s\n' "$TEAM_LINES" | tail -5 | sed 's/^/  log: /'

step "7/8 断言 P4「正在组队 · 2 人」与 P3「组队 2 人」"
P4_TEXTS="$(ui_snapshot "p4-team-2")"
if printf '%s' "$P4_TEXTS" | grep -qF '正在组队 · 2 人'; then P4_OK=1; else P4_OK=0; fi
chk "team.p4_line_2" "$P4_OK" "$(printf '%s' "$P4_TEXTS" | grep -o '正在组队 · [0-9]* 人' | head -1)"
dks screenshot --name "p4-team-2" >/dev/null 2>&1 || warn "P4 截图失败"

back_to_group || die "回不到 P3"
# 输入上行限速时弹出的软键盘会盖住下半屏：先收掉再 dump/截图（P3 上没有输入框，ESC 只收键盘）。
sh_ input keyevent 111 >/dev/null 2>&1 || true
sleep 1
P3_TEXTS="$(ui_snapshot "p3-team-2")"
if printf '%s' "$P3_TEXTS" | grep -qF '组队 2 人'; then P3_OK=1; else P3_OK=0; fi
chk "team.p3_chip_2" "$P3_OK" "$(printf '%s' "$P3_TEXTS" | grep -o '组队 [0-9]* 人' | head -1)"
dks screenshot --name "p3-team-2" >/dev/null 2>&1 || warn "P3 截图失败"

# ------------------------------------------------------------------ 8. 到 100% 后消失

step "8/8 等手机到 100%，断言两处都消失"
DONE_BEFORE="$(count_log "event=ui_file_completed")"
if ! wait_log_count "event=ui_file_completed" $((DONE_BEFORE + 1)) 1500; then
  dks screenshot --name "p4-download-timeout" >/dev/null 2>&1 || true
  applog | tail -20
  die "手机下载没完成（最后一条 pct=$(phone_pct)）"
fi
sleep 3
DONE_PCT="$(phone_pct)"
TEAM_AFTER="$(applog | grep -F 'event=teammates_changed' | grep -c 'count=0' || true)"
B_DONE_AT_END="$(grep -c 'PEER download_done' "$B_LOG" 2>/dev/null || true)"
say "phone     : 完成（最后一条 download_progress pct=${DONE_PCT:-?}）；B 完成行=${B_DONE_AT_END:-0}；count=0 行=${TEAM_AFTER:-0}"

tap "$PAYLOAD_NAME" exact
sleep 1
if ! see_text "写评论" 20000; then die "完成后进不了 P4"; fi
P4_AFTER="$(ui_snapshot "p4-team-gone")"
if printf '%s' "$P4_AFTER" | grep -qF '正在组队'; then GONE_P4=0; else GONE_P4=1; fi
dks screenshot --name "p4-team-gone" >/dev/null 2>&1 || warn "P4 完成截图失败"

back_to_group || die "回不到 P3"
P3_AFTER="$(ui_snapshot "p3-team-gone")"
# 只认「组队 n 人」这一枚 chip：群名里也可能有「组队」两个字（第一版就踩过），
# 拿裸字「组队」判消失等于让群名冒充 chip。
if printf '%s' "$P3_AFTER" | grep -qE '组队 [0-9]+ 人'; then GONE_P3=0; else GONE_P3=1; fi
dks screenshot --name "p3-team-gone" >/dev/null 2>&1 || warn "P3 完成截图失败"

chk "team.cleared_at_100" "$([ "$GONE_P4" = "1" ] && [ "$GONE_P3" = "1" ] && echo 1 || echo 0)" \
  "P4 无「正在组队」=$GONE_P4，P3 无「组队」=$GONE_P3"
chk "team.drop_caused_by_completion" "$([ "${TEAM_AFTER:-0}" = "0" ] && echo 1 || echo 0)" \
  "teammates_changed count=0 条数=${TEAM_AFTER:-0}（0 = 消失不是队友跑光）"
# 完成前最后一拍（通常 99%）的 teammates 仍 ≥ 1：队友是陪到 100% 的，不是先跑了。
LAST_TICK_TEAM="$(applog | grep -F 'event=download_progress' | grep -o 'teammates=[0-9]*' | tail -1 | cut -d= -f2)"
chk "team.teammate_alive_at_last_tick" "$([ "${LAST_TICK_TEAM:-0}" -ge 1 ] && echo 1 || echo 0)" \
  "完成前最后一条 download_progress 的 teammates=${LAST_TICK_TEAM:-?}"

# ------------------------------------------------------------------ 9. 证据落盘

step "9/9 证据落盘"
applog > "$ART/logs/app-logcat.log" 2>/dev/null
"$ADB" -s "$SERIAL" logcat -d -b all > "$ART/logs/full-logcat.log" 2>/dev/null
{
  say "serial=$SERIAL model=$MODEL api=$SDK"
  say "payload=$PAYLOAD_NAME bytes=$WANT_BYTES sha256=$PAYLOAD_SHA"
  say "S=$S_NAME tail8=$TAIL8 msg_port=$S_MSG throttle_kbps=$SEED_KBPS"
  say "phone_file_id=${PHONE_FILE_ID:-?} phone_up_kbps=$PHONE_UP_KBPS"
  say "teammates_max=${TEAM_MAX:-?} teammates_zero_lines=${TEAM_AFTER:-0}"
  say "p3_chip_2=$P3_OK p4_line_2=$P4_OK gone_p3=$GONE_P3 gone_p4=$GONE_P4"
  say "b_completed_lines=${B_DONE_AT_END:-0}"
  say "teammates_at_last_tick=${LAST_TICK_TEAM:-?} last_pct=${DONE_PCT:-?}"
} > "$ART/logs/summary.txt"
say ""
say "---- 汇总 ----"
say "teammates_changed max count : ${TEAM_MAX:-?}"
say "P3 组队 2 人 / P4 正在组队 · 2 人 : $P3_OK / $P4_OK"
say "100% 后消失（P3/P4）        : $GONE_P3 / $GONE_P4"
say "checks  : pass=$CHECKS_PASS fail=$CHECKS_FAIL"
say "evidence: artifacts/team-verify/"
say ""
if [ "$CHECKS_FAIL" -eq 0 ]; then
  say "PASS"
  exit 0
fi
say "FAIL"
exit 1
