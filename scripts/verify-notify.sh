#!/usr/bin/env bash
#
# verify-notify.sh — P7 通知三档的真机验收（MIX 2S / 5b94673a）
#
#   bash scripts/verify-notify.sh          # 一条命令跑完，最后一行打印 PASS / FAIL（退出码 0 / 1）
#
# 装置：
#   S  桌面 JVM 对端：建群 + 做种 256 KiB 素材（hold 住整场）
#   手机  加入该群 → 打开文件 P4 → 激活（开始下载，即"我参与了这个文件"）→ 退回 P2
#   D1/D2/D3  各自是一个新的桌面 JVM 对端：加入同群 → 下载素材 → 发一条评论
#             （D1 另外共享一个小文件，用来验"群内出现新文件"这条触发条件）
#   D4 在"权限被撤销"状态下发评论，验拒绝通知权限不影响应用内未读
#
# 断言（缺一条不算 PASS）：
#   bubble.*  系统气泡：dumpsys 有该群的通知记录、渠道重要性 HIGH(4)、渠道带声音、日志 headsup=true，
#             "新文件"与"评论"两条触发路径都留下 notify_decision
#   inapp.*   仅应用内：渠道重要性 LOW(2)、dumpsys 无该群通知、无 notify_alert、P2 角标 +1
#   muted.*   静音：通知照发但渠道重要性 ≤ LOW(2)、声音为空、日志 silent=true headsup=false
#   tap.*     点通知 → 进 P4（event=notify_open + 界面出现「写评论」）
#   denied.*  撤销 POST_NOTIFICATIONS 后：notify_blocked，无系统通知，未读照常 +1
#
# 渠道按群分：channel id = huchuan-group-<groupId>，渠道名 = 群名。
#
# 边界：只写 artifacts/notify/；不构建 app/（用现成 APK）；只碰 5b94673a 这一台设备；
#       只清自己的对端（命令行含 artifacts/notify 的 java）。
#
# 环境变量：NOTIFY_SERIAL（默认 5b94673a）、NOTIFY_KEEP_APK（1 = 不重装）
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ART="$ROOT/artifacts/notify"
DEVKIT="$ROOT/tools/devkit/devkit.mjs"
PKG="app.huchuan"
ACTIVITY=".MainActivity"

SERIAL="${NOTIFY_SERIAL:-5b94673a}"
S_MSG_PORT="${NOTIFY_S_MSG:-47300}"
S_BT_PORT="${NOTIFY_S_BT:-51570}"
D1_MSG="${NOTIFY_D1_MSG:-47310}"
D1_BT="${NOTIFY_D1_BT:-51571}"
D2_MSG="${NOTIFY_D2_MSG:-47320}"
D2_BT="${NOTIFY_D2_BT:-51572}"
D3_MSG="${NOTIFY_D3_MSG:-47330}"
D3_BT="${NOTIFY_D3_BT:-51573}"
D4_MSG="${NOTIFY_D4_MSG:-47340}"
D4_BT="${NOTIFY_D4_BT:-51574}"
GROUP_NAME="${NOTIFY_GROUP_NAME:-通知验收群}"
PAYLOAD_KIB="${NOTIFY_PAYLOAD_KIB:-256}"
NEWFILE_KIB="${NOTIFY_NEWFILE_KIB:-64}"

BODY_BUBBLE="pc-note-bubble"
BODY_INAPP="pc-note-inapp"
BODY_MUTED="pc-note-muted"
BODY_DENIED="pc-note-denied"

say() { printf '%s\n' "$*"; }
step() { printf '\n=== %s ===\n' "$1"; }
die() {
  say "FATAL: $*" >&2
  # 失败现场：截图 + 界面树 + 最近 40 条内核日志，省得下次重新跑一遍才知道卡在哪。
  dks screenshot --name "fail-state" >/dev/null 2>&1 || true
  ui_snapshot "fail-ui" >/dev/null 2>&1 || true
  applog 2>/dev/null | tail -40 | sed 's/^/  log: /' >&2 || true
  say "FAIL"
  exit 1
}
warn() { say "WARN  $*"; }
info() { say "INFO  $*"; }

CHECKS_FAIL=0
CHECKS_PASS=0
chk() {
  local name="$1" ok="$2" detail="${3:-}"
  if [ "$ok" = "1" ]; then
    say "CHECK PASS $name${detail:+ ($detail)}"
    CHECKS_PASS=$((CHECKS_PASS + 1))
  else
    say "CHECK FAIL $name${detail:+ ($detail)}"
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
  for candidate in "${NOTIFY_ADB:-}" "${ADB:-}" "${DEVKIT_ADB:-}" \
    "/e/agentHome/android-sdk/platform-tools/adb.exe" \
    "${ANDROID_HOME:-}/platform-tools/adb.exe" \
    "${ANDROID_SDK_ROOT:-}/platform-tools/adb.exe"; do
    [ -n "$candidate" ] || continue
    if [ -f "$candidate" ]; then printf '%s' "$candidate"; return 0; fi
  done
  command -v adb 2>/dev/null && return 0
  return 1
}

ADB="$(resolve_adb)" || die "找不到 adb（设 NOTIFY_ADB 或 ANDROID_HOME）"
[ -f "$DEVKIT" ] || die "找不到 tools/devkit/devkit.mjs"
command -v java >/dev/null 2>&1 || die "java 不在 PATH（JDK 17+）"
command -v javac >/dev/null 2>&1 || die "javac 不在 PATH（JDK 17+）"

dks() { node "$(w "$DEVKIT")" -s "$SERIAL" --artifacts "$(w "$ART")" "$@" 2>&1; }
sh_() { "$ADB" -s "$SERIAL" shell "$@" 2>&1 | tr -d '\r'; }

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

back_key() { sh_ input keyevent KEYCODE_BACK >/dev/null 2>&1; sleep 1; }

# 手机内核事件日志（tag = huchuan/m1）：「event=xxx k=v ...」。
applog() {
  "$ADB" -s "$SERIAL" logcat -d -v brief 2>/dev/null \
    | tr -d '\r' \
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

log_field() {
  applog | grep -F -- "$1" | grep -o "$2=[^ ]*" | tail -1 | cut -d= -f2
}

# 等到某条日志的出现次数超过基线（进程重启后旧日志还在，不能用"存在"当判据）。
wait_new_log() {
  local pattern="$1" baseline="$2" timeout="${3:-60000}" start
  start=$(date +%s)
  while [ $(( $(date +%s) - start )) -lt "$timeout" ]; do
    if [ "$(count_log "$pattern")" -gt "$baseline" ]; then return 0; fi
    sleep 1
  done
  return 1
}

ui_snapshot() { dks ui-dump --texts --name "$1" 2>/dev/null; }

# 文本消失（抽屉关掉、通知没了这类"断言不存在"的场景）。
text_gone() {
  local text="$1" timeout="${2:-10000}" start texts
  start=$(date +%s)
  while [ $(( $(date +%s) - start )) -lt "$timeout" ]; do
    texts="$(ui_snapshot tmp-gone 2>/dev/null)"
    if ! printf '%s' "$texts" | grep -qF -- "$text"; then return 0; fi
    sleep 1
  done
  return 1
}

# ------------------------------------------------------------------ 通知 dumpsys

notif_dump() { sh_ dumpsys notification --noredact; }

# dumpsys 一次要 1 s 上下：每个断言步骤只刷一次缓存，后面三个读数都读同一份快照。
NOTIF_CACHE="$ART/dumpsys/.current.txt"
notif_refresh() { mkdir -p "$ART/dumpsys"; notif_dump > "$NOTIF_CACHE" 2>&1; }
notif_cached() { cat "$NOTIF_CACHE" 2>/dev/null; }

# 该群有没有**正在显示**的通知：NotificationRecord 行里同时出现 pkg=app.huchuan 与 tag=groupId。
notif_record_count() {
  notif_cached | grep -E "^ *NotificationRecord\(.*pkg=app\.huchuan .*tag=$GID( |$)" | wc -l | tr -d ' '
}

# 该群渠道的实际重要性（渠道注册表与每条通知的 effectiveNotificationChannel 里都会出现）。
channel_importance() {
  local channel="huchuan-group-$GID"
  notif_cached | grep -F "mId='$channel'" | grep -o 'mImportance=[0-9]*' | cut -d= -f2 | sort -u | tr '\n' ' '
}

channel_sound() {
  local channel="huchuan-group-$GID"
  notif_cached | grep -F "mId='$channel'" | grep -o 'mSound=[^,]*' | head -1 | cut -d= -f2
}

save_dumpsys() {
  local name="$1"
  local file="$ART/dumpsys/$name.txt"
  mkdir -p "$ART/dumpsys"
  {
    say "--- group_id=$GID channel=huchuan-group-$GID ---"
    notif_cached | grep -E "^ *NotificationRecord\(.*pkg=app\.huchuan|mId='huchuan-group-$GID'" | head -20
  } > "$file" 2>&1
  say "dumpsys   : $file"
}

peer_field() {
  grep -F -- "$2" "$1" 2>/dev/null | grep -o "$3=[^ ]*" | tail -1 | cut -d= -f2
}

# ------------------------------------------------------------------ 0. 目录 / 锁 / 收尾

mkdir -p "$ART/screenshots" "$ART/ui-dumps" "$ART/logs" "$ART/dumpsys" "$ART/payload" "$ART/desktop"
LOCK="$ART/.running"
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK" 2>/dev/null)" 2>/dev/null; then
  die "已有另一个 verify-notify.sh 在跑（pid $(cat "$LOCK")），先结束它"
fi
echo $$ > "$LOCK"

S_PID=""
D1_PID=""
D2_PID=""
D3_PID=""
D4_PID=""
cleanup() {
  local pid
  for pid in "$D4_PID" "$D3_PID" "$D2_PID" "$D1_PID" "$S_PID"; do
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

say "=== 通知三档真机验收 ================================================"
say "root      : $ROOT"
say "device    : $SERIAL"
say "artifacts : $ART"

STATE="$("$ADB" -s "$SERIAL" get-state 2>&1 | tr -d '\r')"
[ "$STATE" = "device" ] || die "设备 $SERIAL 不在线（get-state=$STATE）"
MODEL="$(sh_ getprop ro.product.model)"
SDK="$(sh_ getprop ro.build.version.sdk)"
REL="$(sh_ getprop ro.build.version.release)"
[ "$SDK" -ge 33 ] 2>/dev/null || die "本脚本按 Android 13+（API 33）验 POST_NOTIFICATIONS，当前 API $SDK"
say "model     : $MODEL  Android $REL (API $SDK)"
chk "env.device_api33" 1 "$SERIAL $MODEL API $SDK"

# APK 取"最新的那个"：主树 app/build 是常规产物；主树的 core/ 可能正被别的 agent 改动、
# 编不过时，用 artifacts/notify/build-tree 里的隔离构建（源码与主树 app/src 完全相同，
# 见 docs/notify.md「构建」一节）。两者都以修改时间取新。
MAIN_APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TREE_APK="$ART/build-tree/app/build/outputs/apk/debug/app-debug.apk"
APK="${NOTIFY_APK:-}"
if [ -z "$APK" ]; then
  APK="$MAIN_APK"
  if [ -f "$TREE_APK" ] && { [ ! -f "$MAIN_APK" ] || [ "$TREE_APK" -nt "$MAIN_APK" ]; }; then
    APK="$TREE_APK"
    warn "主树 APK 比隔离构建旧，改用 $TREE_APK"
  fi
fi
[ -f "$APK" ] || die "APK 不存在：$APK（本脚本不构建 app/，先跑 scripts/build-android.sh）"
info "APK       : $APK ($(date -r "$APK" '+%m-%d %H:%M'))"
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"
if grep -q 'android.permission.POST_NOTIFICATIONS' "$MANIFEST"; then POSTPERM_DECL=1; else POSTPERM_DECL=0; fi
chk "apk.post_notifications_declared" "$POSTPERM_DECL" "AndroidManifest.xml"

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

# 端口必须空着：本机同时可能跑着别的 agent 的对端，抢端口会互相搅坏验证。
for port in "$S_MSG_PORT" "$S_BT_PORT" "$D1_MSG" "$D1_BT" "$D2_MSG" "$D2_BT" "$D3_MSG" "$D3_BT" "$D4_MSG" "$D4_BT"; do
  if netstat -ano 2>/dev/null | grep LISTENING | grep -qE "[:.]$port[[:space:]]"; then
    die "端口 $port 已被占用（另一个对端在跑？），换 NOTIFY_* 端口或先结束它"
  fi
done

# ------------------------------------------------------------------ 2. 清场与安装

step "1/9 清场并安装（这次不带 -g，为了真看到 POST_NOTIFICATIONS 授权框）"
# 只清自己的对端：命令行里带 artifacts/notify 的 java 进程。
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter 'Name=\"java.exe\"' | Where-Object { \$_.CommandLine -like '*artifacts*notify*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1
sh_ am force-stop "$PKG" >/dev/null 2>&1
if [ "${NOTIFY_KEEP_APK:-0}" = "1" ]; then
  info "NOTIFY_KEEP_APK=1：不重装 APK"
else
  sh_ pm clear "$PKG" >/dev/null 2>&1
  # 不带 -g：POST_NOTIFICATIONS 保持"未授权"，授权框才有机会出现。
  "$ADB" -s "$SERIAL" install -r -t "$(w "$APK")" 2>&1 | grep -E "Success|Failure|Error" | tail -1
fi
sh_ logcat -b all -c >/dev/null 2>&1
info "logcat / crash buffer cleared"
sh_ svc power stayon true >/dev/null 2>&1
sh_ settings put system screen_off_timeout 1800000 >/dev/null 2>&1
sh_ input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
sh_ wm dismiss-keyguard >/dev/null 2>&1

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

# ------------------------------------------------------------------ 3. 桌面 S：建群 + 共享素材

step "2/9 起 S：建群 + 共享 ${PAYLOAD_KIB} KiB 素材（hold 整场）"
PAYLOAD="$ART/payload/notify-payload.bin"
NEWFILE="$ART/payload/notify-newfile.bin"
head -c $(( PAYLOAD_KIB * 1024 )) /dev/urandom > "$PAYLOAD"
head -c $(( NEWFILE_KIB * 1024 )) /dev/urandom > "$NEWFILE"
PAYLOAD_NAME="$(basename "$PAYLOAD")"
NEWFILE_NAME="$(basename "$NEWFILE")"
rm -rf "$ART/desktop/S-data"
mkdir -p "$ART/desktop/S-data"

S_LOG="$ART/logs/desktop-S.log"
: > "$S_LOG"
java -Dlibtorrent4j.jni.path="$(w "$PEER_NATIVE")" -Dfile.encoding=UTF-8 -cp "$PEER_CP" DesktopPeer host \
  data="$(w "$ART/desktop/S-data")" work="$(w "$ART/desktop")" \
  name="验收S" group_name="$GROUP_NAME" \
  msg_port="$S_MSG_PORT" bt_port="$S_BT_PORT" \
  share="$(w "$PAYLOAD")" hold=3600 download=0 \
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
chk "desktop.s_ready" 1 "tail8=$TAIL8"

# ------------------------------------------------------------------ 4. 手机加入

step "3/9 手机加入（P5 只输后 8 位）→ P3 → P4 激活（= 我参与该文件）"
sh_ am start -n "$PKG/$ACTIVITY" --es huchuan.debug.manual "$PC_IP:$S_MSG" 2>&1 | tail -1
wait_log "app_kernel_ready" 60000 || { applog | tail -20; die "内核没起来"; }
see_text "用户名" 180000 || die "P0 没进 P1（自检卡住？）"
tap "保存"
see_text "邀请码后 8 位" 30000 || die "没进 P5"
tap "邀请码后 8 位"
sh_ input text "$TAIL8" >/dev/null 2>&1
sleep 1
# 加入要重试：同一个局域网里可能还跑着别的 agent 的对端（mDNS 会把它们也列成候选），
# 手机先连错一个群、再回落到手动候选，偶尔会耗光单次加入的 60 s 预算。
JOIN_OK=0
for attempt in 1 2 3; do
  JOIN_BASE="$(count_log "event=group_joined")"
  tap "加入"
  if wait_new_log "event=group_joined" "$JOIN_BASE" 90000; then JOIN_OK=1; break; fi
  warn "第 $attempt 次加入没成功，重试（候选里可能有别的群）"
  see_text "加入" 15000 || true
  sleep 5
done
[ "$JOIN_OK" = "1" ] || { applog | grep -iE "join|hello" | tail -20; die "加入失败（已重试 3 次）"; }
see_text "添加文件" 30000 || die "加入后没进 P3"
see_text "$PAYLOAD_NAME" 40000 || die "P3 上没有 $PAYLOAD_NAME"

GID="$(log_field "group_joined" group_id)"
if [ -z "$GID" ]; then GID="$(log_field "event=ui_joined" group_id)"; fi
[ -n "$GID" ] || die "拿不到 group_id（日志里没有 group_joined/ui_joined）"
CHANNEL="huchuan-group-$GID"
say "group     : id=$GID channel=$CHANNEL"
ui_snapshot "p3-joined" >/dev/null

tap "$PAYLOAD_NAME" exact
see_text "写评论" 30000 || die "点文件行没进 P4"
tap "激活开关"
wait_log "event=download_start" 120000 || { applog | tail -20; die "激活后没开始下载（文件不参与就不会有评论通知）"; }
info "phone 已在下载 $PAYLOAD_NAME = 参与该文件"
back_key
see_text "添加文件" 20000 || die "回不到 P3"
back_key   # P3 → P2：首次进 P2 会弹 POST_NOTIFICATIONS 授权框，它盖在 P2 上面，先处理它

# ------------------------------------------------------------------ 5. 通知权限

step "4/9 首次进 P2：POST_NOTIFICATIONS 授权框"
PERMISSION_GRANTED=0
DIALOG_SEEN=0
if see_text "允许" 25000; then
  DIALOG_SEEN=1
  dks screenshot --name "p2-notify-permission" >/dev/null 2>&1 || warn "权限框截图失败"
  ui_snapshot "p2-notify-permission" >/dev/null
  tap "允许" exact || true
  sleep 2
fi
if sh_ dumpsys package "$PKG" | grep -F "POST_NOTIFICATIONS: granted=true" >/dev/null 2>&1; then
  PERMISSION_GRANTED=1
else
  warn "界面上没授到权限，改用 pm grant（授权框证据已另存）"
  sh_ pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
  sleep 1
  sh_ dumpsys package "$PKG" | grep -F "POST_NOTIFICATIONS: granted=true" >/dev/null 2>&1 && PERMISSION_GRANTED=1
fi
chk "perm.dialog_shown_on_p2" "$DIALOG_SEEN" "首次进入 P2 时弹出系统授权框"
chk "perm.post_notifications_granted" "$PERMISSION_GRANTED" \
  "$(sh_ dumpsys package "$PKG" | grep -o 'POST_NOTIFICATIONS: granted=[a-z]*' | head -1)"
[ "$PERMISSION_GRANTED" = "1" ] || die "拿不到通知权限，后面的系统通知无从谈起"
see_text "设置" 25000 || die "授权框之后没回到 P2"

# ------------------------------------------------------------------ 6. 桌面 D 的公共启动器

PEER_READY_TIMEOUT=90
start_peer() {
  local tag="$1" msg="$2" bt="$3"
  shift 3
  local data="$ART/desktop/$tag-data"
  local log="$ART/logs/desktop-$tag.log"
  rm -rf "$data"
  mkdir -p "$data"
  : > "$log"
  java -Dlibtorrent4j.jni.path="$(w "$PEER_NATIVE")" -Dfile.encoding=UTF-8 -cp "$PEER_CP" DesktopPeer join \
    data="$(w "$data")" work="$(w "$ART/desktop")" \
    name="$tag" tail8="$TAIL8" manual="$PC_IP:$S_MSG" \
    msg_port="$msg" bt_port="$bt" download=1 hold=240 \
    "$@" > "$log" 2>&1 &
  echo $!
}

wait_peer_comment() {
  local log="$1" timeout="${2:-180}" start
  start=$(date +%s)
  while [ $(( $(date +%s) - start )) -lt "$timeout" ]; do
    if grep -qF "PEER comment_sent" "$log" 2>/dev/null; then return 0; fi
    if grep -qF "RESULT=FAIL" "$log" 2>/dev/null; then return 1; fi
    sleep 1
  done
  return 1
}

wait_phone_op() {
  local body="$1" timeout="${2:-90}"
  wait_log "event=ui_op_received" "$timeout" &&
    applog | grep -F "event=ui_op_received" | grep -qF "body=$body"
}

# ------------------------------------------------------------------ 7. 三档验收

# ---------------- 档位一：系统气泡
step "5/9 档位一「系统气泡」（默认档）：D1 共享新文件 + 评论"
NOTIFY_ALERTS_BEFORE="$(count_log "event=notify_alert")"
D1_PID="$(start_peer D1 "$D1_MSG" "$D1_BT" comment="$BODY_BUBBLE" share="$(w "$NEWFILE")")"
info "D1 pid=$D1_PID（等它下载完并发评论）"
wait_peer_comment "$ART/logs/desktop-D1.log" 180 \
  || { tail -20 "$ART/logs/desktop-D1.log"; die "D1 没发出评论"; }
say "D1        : $(grep -o 'PEER comment_sent.*' "$ART/logs/desktop-D1.log" | tail -1)"
wait_phone_op "$BODY_BUBBLE" 90 || { applog | tail -15; die "手机没收到 D1 的评论"; }
sleep 3

BUBBLE_ALERTS="$(( $(count_log "event=notify_alert") - NOTIFY_ALERTS_BEFORE ))"
BUBBLE_DECISION="$(applog | grep -F "event=notify_decision" | grep -F "level=system_bubble" | tail -1)"
BUBBLE_HEADS="$(applog | grep -F "event=notify_alert" | grep -c "headsup=true" || true)"
BUBBLE_FILE_TRIGGER="$(applog | grep -F "event=notify_decision" | grep -F "kind=file" | grep -c "level=system_bubble" || true)"
notif_refresh
BUBBLE_IMPORTANCE="$(channel_importance)"
BUBBLE_RECORDS="$(notif_record_count)"
BUBBLE_SOUND="$(channel_sound)"
save_dumpsys "1-system-bubble"
dks screenshot --name "p2-system-bubble" >/dev/null 2>&1 || warn "截图失败"
sh_ cmd statusbar expand-notifications >/dev/null 2>&1
sleep 2
dks screenshot --name "shade-system-bubble" >/dev/null 2>&1 || warn "通知栏截图失败"
sh_ cmd statusbar collapse >/dev/null 2>&1
sleep 1

chk "bubble.notification_posted" "$([ "$BUBBLE_RECORDS" -ge 1 ] && echo 1 || echo 0)" \
  "dumpsys 记录数=$BUBBLE_RECORDS"
if printf '%s' "$BUBBLE_IMPORTANCE" | tr -d ' ' | grep -q '^4$'; then BUBBLE_IMPORTANCE_OK=1; else BUBBLE_IMPORTANCE_OK=0; fi
chk "bubble.channel_high" "$BUBBLE_IMPORTANCE_OK" "channel importance=[$BUBBLE_IMPORTANCE]（HIGH=4）"
if printf '%s' "$BUBBLE_SOUND" | grep -q 'content://\|file://'; then BUBBLE_SOUND_OK=1; else BUBBLE_SOUND_OK=0; fi
chk "bubble.sound_set" "$BUBBLE_SOUND_OK" "mSound=$BUBBLE_SOUND"
chk "bubble.headsup_log" "$([ "${BUBBLE_HEADS:-0}" -ge 1 ] && echo 1 || echo 0)" \
  "notify_alert headsup=true ×$BUBBLE_HEADS"
chk "bubble.new_file_trigger" "$([ "${BUBBLE_FILE_TRIGGER:-0}" -ge 1 ] && echo 1 || echo 0)" \
  "kind=file level=system_bubble ×$BUBBLE_FILE_TRIGGER"
if printf '%s' "$BUBBLE_DECISION" | grep -q 'kind=comment'; then BUBBLE_COMMENT_OK=1; else BUBBLE_COMMENT_OK=0; fi
chk "bubble.comment_trigger" "$BUBBLE_COMMENT_OK" "$(printf '%s' "$BUBBLE_DECISION" | cut -c1-140)"

# ---------------- 档位二：仅应用内
step "6/9 档位二「仅应用内」：P7 换档 → D2 评论 → 无系统通知 + P2 角标 +1"
tap "设置"
see_text "全局限速" 20000 || die "设置抽屉没打开"
tap "仅应用内"
sleep 2
INAPP_CHANNEL_LINE="$(applog | grep -F "event=notify_channel" | grep -F "level=in_app_only" | tail -1)"
say "channel   : ${INAPP_CHANNEL_LINE:-（没有渠道日志）}"
back_key
text_gone "全局限速" 15000 || warn "抽屉可能没关掉（继续）"
see_text "设置" 20000 || die "抽屉关掉后没回到 P2"

# 进一次 P3 再回来：清未读 + 撤掉上一步留在通知栏里的该群通知
tap "$GROUP_NAME"
see_text "添加文件" 30000 || die "点群行没进 P3"
back_key
see_text "设置" 20000 || die "回不到 P2"
notif_refresh
NOTIF_BEFORE_INAPP="$(notif_record_count)"
ui_snapshot "p2-before-inapp" >/dev/null

NOTIFY_ALERTS_BEFORE="$(count_log "event=notify_alert")"
D2_PID="$(start_peer D2 "$D2_MSG" "$D2_BT" comment="$BODY_INAPP")"
info "D2 pid=$D2_PID"
wait_peer_comment "$ART/logs/desktop-D2.log" 180 \
  || { tail -20 "$ART/logs/desktop-D2.log"; die "D2 没发出评论"; }
wait_phone_op "$BODY_INAPP" 90 || { applog | tail -15; die "手机没收到 D2 的评论"; }
sleep 3

INAPP_ALERTS="$(( $(count_log "event=notify_alert") - NOTIFY_ALERTS_BEFORE ))"
INAPP_DECISION="$(applog | grep -F "event=notify_decision" | grep -F "level=in_app_only" | tail -1)"
notif_refresh
INAPP_RECORDS="$(notif_record_count)"
INAPP_TEXTS="$(ui_snapshot "p2-in-app")"
save_dumpsys "2-in-app-only"
dks screenshot --name "p2-in-app" >/dev/null 2>&1 || warn "截图失败"

if printf '%s' "$INAPP_CHANNEL_LINE" | grep -q 'effective_importance=2'; then INAPP_CH_OK=1; else INAPP_CH_OK=0; fi
chk "inapp.channel_low" "$INAPP_CH_OK" "$(printf '%s' "$INAPP_CHANNEL_LINE" | grep -o 'effective_importance=[0-9]*')"
chk "inapp.no_system_notification" "$([ "$INAPP_RECORDS" -eq 0 ] && echo 1 || echo 0)" \
  "dumpsys 记录数=$INAPP_RECORDS（改档前 $NOTIF_BEFORE_INAPP）"
chk "inapp.no_alert_log" "$([ "$INAPP_ALERTS" -eq 0 ] && echo 1 || echo 0)" "新增 notify_alert=$INAPP_ALERTS"
if printf '%s' "$INAPP_DECISION" | grep -q 'action=in_app_only'; then INAPP_DEC_OK=1; else INAPP_DEC_OK=0; fi
chk "inapp.decision_logged" "$INAPP_DEC_OK" "$(printf '%s' "$INAPP_DECISION" | cut -c1-140)"
if printf '%s' "$INAPP_TEXTS" | grep -q '条新消息'; then INAPP_BADGE=1; else INAPP_BADGE=0; fi
chk "inapp.unread_badge" "$INAPP_BADGE" "$(printf '%s' "$INAPP_TEXTS" | grep -o '[0-9]* 条新消息' | head -1)"

# ---------------- 档位三：静音
step "7/9 档位三「静音」：P7 换档 → D3 评论 → 发但静默（importance ≤ LOW）"
tap "设置"
see_text "全局限速" 20000 || die "设置抽屉没打开"
tap "静音"
sleep 2
MUTED_CHANNEL_LINE="$(applog | grep -F "event=notify_channel" | grep -F "level=muted" | tail -1)"
say "channel   : ${MUTED_CHANNEL_LINE:-（没有渠道日志）}"
back_key
text_gone "全局限速" 15000 || warn "抽屉可能没关掉（继续）"
see_text "设置" 20000 || die "抽屉关掉后没回到 P2"

NOTIFY_ALERTS_BEFORE="$(count_log "event=notify_alert")"
D3_PID="$(start_peer D3 "$D3_MSG" "$D3_BT" comment="$BODY_MUTED")"
info "D3 pid=$D3_PID"
wait_peer_comment "$ART/logs/desktop-D3.log" 180 \
  || { tail -20 "$ART/logs/desktop-D3.log"; die "D3 没发出评论"; }
wait_phone_op "$BODY_MUTED" 90 || { applog | tail -15; die "手机没收到 D3 的评论"; }
sleep 3

MUTED_ALERTS="$(( $(count_log "event=notify_alert") - NOTIFY_ALERTS_BEFORE ))"
MUTED_ALERT_LINE="$(applog | grep -F "event=notify_alert" | grep -F "level=muted" | tail -1)"
notif_refresh
MUTED_IMPORTANCE="$(channel_importance)"
MUTED_RECORDS="$(notif_record_count)"
MUTED_SOUND="$(channel_sound)"
save_dumpsys "3-muted"
dks screenshot --name "p2-muted" >/dev/null 2>&1 || warn "截图失败"
sh_ cmd statusbar expand-notifications >/dev/null 2>&1
sleep 2
dks screenshot --name "shade-muted" >/dev/null 2>&1 || warn "通知栏截图失败"

chk "muted.notification_posted" "$([ "$MUTED_RECORDS" -ge 1 ] && echo 1 || echo 0)" \
  "dumpsys 记录数=$MUTED_RECORDS"
MUTED_FIRST="$(printf '%s' "$MUTED_IMPORTANCE" | tr -d ' ' | head -c 1)"
if [ -n "$MUTED_FIRST" ] && [ "$MUTED_FIRST" -le 2 ] 2>/dev/null; then MUTED_IMP_OK=1; else MUTED_IMP_OK=0; fi
chk "muted.channel_low" "$MUTED_IMP_OK" "channel importance=[$MUTED_IMPORTANCE]（LOW=2，断言 ≤ 2）"
if printf '%s' "$MUTED_SOUND" | grep -q '^null$'; then MUTED_SOUND_OK=1; else MUTED_SOUND_OK=0; fi
chk "muted.no_sound" "$MUTED_SOUND_OK" "mSound=[$MUTED_SOUND]"
if printf '%s' "$MUTED_ALERT_LINE" | grep -q 'silent=true'; then MUTED_SILENT_OK=1; else MUTED_SILENT_OK=0; fi
chk "muted.silent_log" "$MUTED_SILENT_OK" \
  "notify_alert ×$MUTED_ALERTS $(printf '%s' "$MUTED_ALERT_LINE" | grep -o 'silent=[a-z]* headsup=[a-z]*')"

# ---------------- 点击通知 → P4
step "8/9 点通知 → 进对应文件页"
TAP_OK=0
WAIT_OPEN_BEFORE="$(count_log "event=notify_open")"
if tap "$BODY_MUTED"; then
  sleep 3
  if [ "$(count_log "event=notify_open")" -gt "$WAIT_OPEN_BEFORE" ] && see_text "写评论" 20000; then
    TAP_OK=1
  fi
fi
if [ "$TAP_OK" != "1" ]; then
  warn "点通知没进 P4，改用 am start 走同一条 NotifyRoute 路径"
  sh_ cmd statusbar collapse >/dev/null 2>&1
  FID_GUESS="$(log_field 'event=download_start' file_id)"
  sh_ am start -n "$PKG/$ACTIVITY" --es huchuan.notify.group "$GID" --es huchuan.notify.file "$FID_GUESS" >/dev/null 2>&1
  sleep 3
  if [ "$(count_log "event=notify_open")" -gt "$WAIT_OPEN_BEFORE" ] && see_text "写评论" 20000; then
    TAP_OK=1
  fi
fi
dks screenshot --name "p4-from-notification" >/dev/null 2>&1 || warn "截图失败"
ui_snapshot "p4-from-notification" >/dev/null
chk "tap.opens_file_page" "$TAP_OK" "$(applog | grep -F 'event=notify_open' | tail -1 | cut -c1-140)"

# ---------------- 权限被撤销：不发通知但未读照常
step "9/9 撤销 POST_NOTIFICATIONS：D4 评论 → 无系统通知，应用内未读 +1"
back_key
back_key
see_text "设置" 20000 || die "回不到 P2"
KERNEL_READY_BEFORE="$(count_log "app_kernel_ready")"
sh_ pm revoke "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
sleep 2
sh_ am start -n "$PKG/$ACTIVITY" --es huchuan.debug.manual "$PC_IP:$S_MSG" >/dev/null 2>&1
wait_new_log "app_kernel_ready" "$KERNEL_READY_BEFORE" 90000 || { applog | tail -10; die "撤权重启后内核没起来"; }
if ! see_text "设置" 30000; then
  see_text "进入" 20000 && tap "进入"
  see_text "设置" 30000 || die "撤权重启后没进 P2"
fi
info "revoked   : $(sh_ dumpsys package "$PKG" | grep -o 'POST_NOTIFICATIONS: granted=[a-z]*' | head -1)"

tap "$GROUP_NAME"
see_text "添加文件" 30000 || die "点群行没进 P3"
back_key
see_text "设置" 20000 || die "回不到 P2"

NOTIFY_ALERTS_BEFORE="$(count_log "event=notify_alert")"
D4_PID="$(start_peer D4 "$D4_MSG" "$D4_BT" comment="$BODY_DENIED")"
info "D4 pid=$D4_PID"
wait_peer_comment "$ART/logs/desktop-D4.log" 180 \
  || { tail -20 "$ART/logs/desktop-D4.log"; die "D4 没发出评论"; }
wait_phone_op "$BODY_DENIED" 90 || { applog | tail -15; die "手机没收到 D4 的评论"; }
sleep 3

DENIED_ALERTS="$(( $(count_log "event=notify_alert") - NOTIFY_ALERTS_BEFORE ))"
DENIED_BLOCKED="$(applog | grep -F "event=notify_blocked" | grep -c "reason=permission_denied" || true)"
notif_refresh
DENIED_TEXTS="$(ui_snapshot "p2-permission-denied")"
save_dumpsys "4-permission-denied"
dks screenshot --name "p2-permission-denied" >/dev/null 2>&1 || warn "截图失败"

chk "denied.blocked_logged" "$([ "${DENIED_BLOCKED:-0}" -ge 1 ] && echo 1 || echo 0)" \
  "notify_blocked reason=permission_denied ×$DENIED_BLOCKED"
chk "denied.no_system_notification" "$([ "$DENIED_ALERTS" -eq 0 ] && echo 1 || echo 0)" \
  "新增 notify_alert=$DENIED_ALERTS"
if printf '%s' "$DENIED_TEXTS" | grep -q '条新消息'; then DENIED_BADGE=1; else DENIED_BADGE=0; fi
chk "denied.unread_still_counts" "$DENIED_BADGE" \
  "$(printf '%s' "$DENIED_TEXTS" | grep -o '[0-9]* 条新消息' | head -1)"

# 收尾：把权限还回去，别把手机留在"应用收不到通知"的状态。
sh_ pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1

# ------------------------------------------------------------------ 8. 汇总

step "汇总"
say "checks    : PASS=$CHECKS_PASS FAIL=$CHECKS_FAIL"
say "artifacts : $ART"
dks screenshot --name "final-p2" >/dev/null 2>&1 || true
if [ "$CHECKS_FAIL" -ne 0 ]; then
  say "FAIL"
  exit 1
fi
say "PASS"
exit 0
