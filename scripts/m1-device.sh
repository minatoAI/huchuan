#!/usr/bin/env bash
#
# m1-device.sh — Android 真机 M1 全流程验收
#
#   bash scripts/m1-device.sh              # 全流程 + 崩溃压力（3 轮 128 MiB，中途取消一次）
#   bash scripts/m1-device.sh --quick      # 跳过压力测试
#   bash scripts/m1-device.sh --rounds 2 --mib 64
#
# 链路（脚本自己判定并写在输出里）：
#   android-desktop  手机 + 桌面 JVM 对端（当前唯一可跑的链路）
#   第二台安卓在线时只提示、不切换：android-android 需要第二个 UI 驱动，M2 再做。
#
# 手机侧（app.huchuan）：
#   P0 自检 → P1 用户名 → P5 只输后 8 位加入 → P3 SAF 选文件做种 → P4 评论/激活/休眠
# 桌面侧（app/tools/peer/DesktopPeer.java）：
#   建群 → 等手机加入 → 下载手机共享的文件并比对 SHA-256 → 评论 → 等手机回评论
#   → 共享 N 个压力文件做种
#
# 断言：邀请码 16 位 / 只输 8 位加入 / 两端 SHA-256 相同 / 评论双向可见 /
#       dht_nodes=0 / crash 缓冲区 0 条 / 无 Scudo abort。
# 证据：artifacts/m1-device/（截图、ui-dump、logcat、peer 日志、拉回的文件与哈希）
#
# 最后一行打印 PASS / FAIL，退出码 0 / 1。
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ART="$ROOT/artifacts/m1-device"
DEVKIT="$ROOT/tools/devkit/devkit.mjs"
PKG="app.huchuan"
ACTIVITY=".MainActivity"

STRESS_ROUNDS="${M1_STRESS_ROUNDS:-3}"
STRESS_MIB="${M1_STRESS_MIB:-128}"
QUICK=0

while [ $# -gt 0 ]; do
  case "$1" in
    --quick) QUICK=1 ;;
    --rounds) shift; STRESS_ROUNDS="${1:-3}" ;;
    --mib) shift; STRESS_MIB="${1:-128}" ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "FATAL: 未知参数 $1" >&2; exit 2 ;;
  esac
  shift
done

# ------------------------------------------------------------------ 基础工具

say() { printf '%s\n' "$*"; }
step() { printf '\n=== %s ===\n' "$1"; }
die() { say "FATAL: $*" >&2; say "FAIL"; exit 1; }

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
warn() { say "WARN  $*"; }
info() { say "INFO  $*"; }

# Windows 原生程序（java/javac/adb）要 Windows 路径；MSYS 的 /tmp 给不了它们。
w() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

# 设备路径不能被 MSYS 改写（/sdcard → C:/Program Files/Git/sdcard）。
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

resolve_adb() {
  local candidate
  for candidate in "${M1_ADB:-}" "${ADB:-}" "${DEVKIT_ADB:-}" \
    "/e/agentHome/android-sdk/platform-tools/adb.exe" \
    "${ANDROID_HOME:-}/platform-tools/adb.exe" \
    "${ANDROID_SDK_ROOT:-}/platform-tools/adb.exe"; do
    [ -n "$candidate" ] || continue
    if [ -f "$candidate" ]; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  command -v adb 2>/dev/null && return 0
  return 1
}

ADB="$(resolve_adb)" || die "找不到 adb（设 M1_ADB 或 ANDROID_HOME）"
[ -f "$DEVKIT" ] || die "找不到 tools/devkit/devkit.mjs"

# devkit 是唯一的真机操作入口；文本输入 devkit 没有动词，用 adb shell input（见 docs/m1-device.md）。
# 注意：MSYS_NO_PATHCONV 只对 Windows 原生程序生效，所以给 node 的脚本路径必须先转成 Windows 形式。
dks() { node "$(w "$DEVKIT")" -s "$SERIAL" "$@" 2>&1; }
sh_() { "$ADB" -s "$SERIAL" shell "$@" 2>&1; }

# uiautomator 偶尔 dump 不出来（MIUI 已知问题），统一重试 3 次。
retry3() {
  local i
  for i in 1 2 3; do
    if "$@"; then return 0; fi
    sleep 1
  done
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

see_text() {
  local text="$1" timeout="${2:-30000}"
  dks wait-text "$text" --timeout "$timeout" >/dev/null 2>&1
}

# 内核事件日志（tag = huchuan/m1），只留 event=... 部分
applog() {
  "$ADB" -s "$SERIAL" logcat -d -v brief 2>/dev/null \
    | grep -F "huchuan/m1" \
    | sed 's/.*huchuan\/m1[^:]*: //'
}

wait_log() {
  local pattern="$1" timeout="${2:-60000}" start
  start=$(date +%s)
  while [ $(( $(date +%s) - start )) -lt "$timeout" ]; do
    if applog | grep -qF -- "$pattern"; then return 0; fi
    sleep 1
  done
  return 1
}

# 数日志里某个片段出现了几次（多轮压力测试靠它区分「新一轮」和「上一轮」）
count_log() {
  applog | grep -cF -- "$1" || true
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

wait_log_peer() {
  local pattern="$1" timeout="${2:-120000}" start
  start=$(date +%s)
  while [ $(( $(date +%s) - start )) -lt "$timeout" ]; do
    if grep -qF -- "$pattern" "$PEER_LOG" 2>/dev/null; then return 0; fi
    sleep 1
  done
  return 1
}

# 等到指定文件的下载进度到 N%（logcat 的 download_progress pct=NN）。
# 必须按 file_id 过滤：只看「最后一行进度」会被上一轮 100% 的那行骗过去。
wait_progress() {
  local file_id="$1" want="$2" timeout="${3:-120000}" start pct
  start=$(date +%s)
  while [ $(( $(date +%s) - start )) -lt "$timeout" ]; do
    # 注意别写成 'download_progress pct='：中间夹着 group_id / file_id，不是连续子串。
    pct="$(applog | grep -F "$file_id" | grep -F "download_progress" | grep -o 'pct=[0-9]*' | tail -1 | cut -d= -f2)"
    if [ -n "$pct" ] && [ "$pct" -ge "$want" ] 2>/dev/null; then return 0; fi
    sleep 1
  done
  return 1
}

log_field() {
  local pattern="$1" field="$2"
  applog | grep -F -- "$pattern" | grep -o "$field=[^ ]*" | tail -1 | cut -d= -f2
}

# devkit 产物路径从它的 JSON 里取，绝不「猜最新文件」——上一次运行留下的旧图会冒充证据。
artifact_path() {
  local out
  out="$("$@" 2>&1 | tail -1)"
  printf '%s' "$out" | tr ' ' '\n' | sed -n 's/^path=//p' | head -1
}

screenshot() {
  local name="$1" path
  path="$(artifact_path dks screenshot --name "$name")"
  if [ -n "$path" ] && [ -f "$path" ]; then
    cp "$path" "$ART/screenshots/$(basename "$path")"
  else
    warn "截图失败：$name"
  fi
  return 0
}

uidump() {
  local name="$1" path
  path="$(artifact_path dks ui-dump --name "$name")"
  if [ -n "$path" ] && [ -f "$path" ]; then
    cp "$path" "$ART/ui-dumps/$(basename "$path")"
  fi
  return 0
}

# 回到 P3。软键盘开着时第一次返回只会收键盘，所以按到「添加文件」出现为止。
back_to_group() {
  local i
  for i in 1 2 3 4; do
    if see_text "添加文件" 4000; then return 0; fi
    sh_ input keyevent KEYCODE_BACK >/dev/null 2>&1
    sleep 1
  done
  return 1
}

# 前台窗口是不是我们的应用
app_foreground() {
  dks ui-dump --json 2>/dev/null | grep -q "\"packages\":\[\"$PKG\"\]"
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

# 从应用私有目录把文件拉回本机（adb pull 读不了 /data/data，debuggable 包走 run-as）
pull_private() {
  local device_path="$1" local_path="$2"
  mkdir -p "$(dirname "$local_path")"
  "$ADB" -s "$SERIAL" exec-out run-as "$PKG" cat "$device_path" > "$local_path" 2>/dev/null
  [ -s "$local_path" ]
}

# ------------------------------------------------------------------ 0. 设备与链路

say "=== M1 真机验收 ====================================================="
say "root      : $ROOT"

ONLINE="$("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')"
COUNT="$(printf '%s\n' "$ONLINE" | grep -c . || true)"
[ "$COUNT" -ge 1 ] || die "没有在线设备（adb devices 为空）"

SERIAL="${M1_SERIAL:-$(printf '%s\n' "$ONLINE" | head -1)}"
MODEL="$(sh_ getprop ro.product.model | tr -d '\r')"
SDK="$(sh_ getprop ro.build.version.sdk | tr -d '\r')"
say "adb       : $ADB"
say "device    : $SERIAL  $MODEL  API $SDK"

CHAIN="android-desktop"
if [ "$COUNT" -ge 2 ]; then
  warn "检测到 $COUNT 台安卓在线；本次仍用 android-desktop（android-android 需要第二个 UI 驱动，M2 再做）"
else
  info "只有 1 台安卓在线（$SERIAL），用桌面 JVM 做对端"
fi
say "chain     : $CHAIN"
say "rounds    : $( [ "$QUICK" = "1" ] && echo 0 || echo "$STRESS_ROUNDS" ) x ${STRESS_MIB} MiB"

# 每次跑之前清掉上一次的证据目录（素材缓存保留），免得旧截图/旧日志冒充本次结果。
rm -rf "$ART/screenshots" "$ART/ui-dumps" "$ART/logs" "$ART/pulled"
mkdir -p "$ART" "$ART/screenshots" "$ART/ui-dumps" "$ART/logs" "$ART/payload" "$ART/pulled" "$ART/desktop"

# 两个实例同时点同一台手机，会把对方的手势和断言搅乱（踩过：5 个残留进程互相抢点击）。
LOCK="$ART/.running"
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK" 2>/dev/null)" 2>/dev/null; then
  die "已有另一个 m1-device.sh 在跑（pid $(cat "$LOCK")），先结束它"
fi
echo $$ > "$LOCK"

PEER_PID=""
PEER_LOG="$ART/logs/desktop-peer.log"
cleanup() {
  if [ -n "$PEER_PID" ] && kill -0 "$PEER_PID" 2>/dev/null; then
    kill "$PEER_PID" 2>/dev/null
    sleep 0.3
    kill -9 "$PEER_PID" 2>/dev/null
  fi
  rm -f "$LOCK"
}
trap cleanup EXIT

# ------------------------------------------------------------------ 1. 构建

step "1/9 构建"
( cd "$ROOT" && ./gradlew --console=plain -q :app:assembleDebug :core:jar :core:m1Deps :core:m1Native ) \
  || die "gradle 构建失败"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || die "APK 不存在：$APK"

CORE_JAR="$(ls "$ROOT"/core/build/libs/*.jar 2>/dev/null | head -1)"
[ -n "$CORE_JAR" ] || die "core jar 不存在"
PEER_CP="$(w "$CORE_JAR")"
for jar in "$ROOT"/core/build/m1-libs/*.jar; do
  [ -f "$jar" ] && PEER_CP="$PEER_CP;$(w "$jar")"
done
PEER_CLASSES="$ART/peer-classes"
mkdir -p "$PEER_CLASSES"
javac -encoding UTF-8 -cp "$PEER_CP" -d "$(w "$PEER_CLASSES")" "$(w "$ROOT/app/tools/peer/DesktopPeer.java")" \
  || die "javac 桌面对端失败"
PEER_CP="$PEER_CP;$(w "$PEER_CLASSES")"
PEER_NATIVE="$(w "$ROOT/core/build/m1-native/libtorrent4j.dll")"
[ -f "$ROOT/core/build/m1-native/libtorrent4j.dll" ] || die "libtorrent4j.dll 未解出"
info "apk=$(du -h "$APK" | cut -f1) peer_classes=$PEER_CLASSES"

SO_SHA="$(unzip -p "$APK" lib/arm64-v8a/libtorrent4j.so 2>/dev/null | sha256sum | cut -d' ' -f1)"
SO_BYTES="$(unzip -l "$APK" 2>/dev/null | awk '/lib\/arm64-v8a\/libtorrent4j.so/{print $1}')"
WANT_SHA="$(sha256_of "$ROOT/artifacts/build-libtorrent/run5-sde1/libtorrent4j.so")"
chk "apk.selfbuilt_arm64_so" "$([ "$SO_SHA" = "$WANT_SHA" ] && echo 1 || echo 0)" "$SO_BYTES bytes"

# ------------------------------------------------------------------ 2. 素材

step "2/9 素材与设备准备"
PAYLOAD="$ART/payload/m1-payload-32m.bin"
if [ ! -f "$PAYLOAD" ] || [ "$(stat -c %s "$PAYLOAD" 2>/dev/null)" != "33554432" ]; then
  head -c 33554432 /dev/urandom > "$PAYLOAD" || die "生成 32 MiB 素材失败"
fi
PAYLOAD_SHA="$(sha256_of "$PAYLOAD")"
info "payload 32 MiB sha256=$PAYLOAD_SHA"

STRESS_FILES=()
if [ "$QUICK" = "0" ]; then
  round=1
  while [ "$round" -le "$STRESS_ROUNDS" ]; do
    file="$ART/payload/m1-stress-${round}-${STRESS_MIB}m.bin"
    want=$(( STRESS_MIB * 1024 * 1024 ))
    if [ ! -f "$file" ] || [ "$(stat -c %s "$file" 2>/dev/null)" != "$want" ]; then
      head -c "$want" /dev/urandom > "$file" || die "生成压力素材失败"
    fi
    STRESS_FILES+=("$file")
    info "stress round $round: $(basename "$file") sha256=$(sha256_of "$file")"
    round=$((round + 1))
  done
fi

sh_ svc power stayon true >/dev/null 2>&1
sh_ settings put system screen_off_timeout 1800000 >/dev/null 2>&1
sh_ input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
sh_ wm dismiss-keyguard >/dev/null 2>&1

# ------------------------------------------------------------------ 3. 清场

step "3/9 清场并安装"
# 只清掉本脚本自己的对端（按命令行里的 DesktopPeer 认），不碰机器上别人的 java 进程。
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*DesktopPeer*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }" >/dev/null 2>&1
sh_ am force-stop "$PKG" >/dev/null 2>&1
sh_ pm clear "$PKG" >/dev/null 2>&1
"$ADB" -s "$SERIAL" install -r -t -g "$(w "$APK")" 2>&1 | grep -E "Success|Failure|Error" | tail -1
"$ADB" -s "$SERIAL" logcat -b all -c >/dev/null 2>&1
info "logcat / crash buffer cleared"

"$ADB" -s "$SERIAL" push "$(w "$PAYLOAD")" "/sdcard/Download/$(basename "$PAYLOAD")" 2>&1 | tail -1
sh_ "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/$(basename "$PAYLOAD")" >/dev/null 2>&1

# ------------------------------------------------------------------ 4. 桌面对端

step "4/9 启动桌面对端"
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

PEER_DATA="$ART/desktop/data"
rm -rf "$PEER_DATA"
mkdir -p "$PEER_DATA"
# 压力文件路径要给 java 看，必须转成 Windows 形式；用 MSYS 路径它会 share_missing。
PEER_SHARE=""
if [ "${#STRESS_FILES[@]}" -gt 0 ]; then
  for stress_file in "${STRESS_FILES[@]}"; do
    if [ -z "$PEER_SHARE" ]; then
      PEER_SHARE="$(w "$stress_file")"
    else
      PEER_SHARE="$PEER_SHARE,$(w "$stress_file")"
    fi
  done
fi

# 第 2 轮（索引 1）限速 2 MB/s：真机 Wi-Fi 能跑到 14 MB/s，不限速的话 128 MiB 十几秒就完了，
# 「中途取消」根本来不及点。顺带把协议 §4 的单文件限速也走一遍。
PEER_THROTTLE=""
if [ "${#STRESS_FILES[@]}" -gt 1 ]; then
  PEER_THROTTLE="throttle=1:${M1_THROTTLE_KBPS:-2048}"
fi

java -Dlibtorrent4j.jni.path="$PEER_NATIVE" -Dfile.encoding=UTF-8 -cp "$PEER_CP" DesktopPeer host \
  data="$(w "$PEER_DATA")" work="$(w "$ART/desktop")" name="桌面电脑" msg_port=47100 bt_port=51413 \
  share="$PEER_SHARE" "$PEER_THROTTLE" hold=2400 \
  > "$PEER_LOG" 2>&1 &
PEER_PID=$!

TAIL8=""
MSG_PORT=""
for _ in $(seq 1 160); do
  TAIL8="$(grep -o 'tail8=[0-9A-Z]*' "$PEER_LOG" 2>/dev/null | head -1 | cut -d= -f2)"
  MSG_PORT="$(grep -o 'msg_port=[0-9]*' "$PEER_LOG" 2>/dev/null | head -1 | cut -d= -f2)"
  [ -n "$TAIL8" ] && [ -n "$MSG_PORT" ] && break
  kill -0 "$PEER_PID" 2>/dev/null || break
  sleep 0.5
done
if [ -z "$TAIL8" ] || [ -z "$MSG_PORT" ]; then
  tail -20 "$PEER_LOG"
  die "桌面对端没起来（没打印 tail8 / msg_port）"
fi
say "desktop   : tail8=$TAIL8 msg_port=$MSG_PORT pid=$PEER_PID"
chk "desktop.invite_code_16" "$(grep -qE 'invite=[0-9ABCDEFGHJKMNPQRSTVWXYZ]{16}' "$PEER_LOG" && echo 1 || echo 0)" "$TAIL8"

# 压力文件没共享出去就早点停：否则要等到第 9 步才发现 P3 上没有文件。
if [ "${#STRESS_FILES[@]}" -gt 0 ]; then
  if ! wait_log_peer "PEER shared_count" 60000; then
    tail -10 "$PEER_LOG"
    die "桌面对端没能共享压力文件（看 PEER share_missing）"
  fi
  SHARED_N="$(grep -o 'shared_count n=[0-9]*' "$PEER_LOG" | head -1 | cut -d= -f2)"
  chk "desktop.shared_stress_files" "$([ "${SHARED_N:-0}" = "${#STRESS_FILES[@]}" ] && echo 1 || echo 0)" "$SHARED_N 个"
fi

# ------------------------------------------------------------------ 5. 启动应用

step "5/9 启动应用并过 P0/P1"
sh_ am start -n "$PKG/$ACTIVITY" --es huchuan.debug.manual "$PC_IP:$MSG_PORT" 2>&1 | tail -1
if ! wait_log "app_kernel_ready" 40000; then
  applog | tail -20
  die "内核没起来（看 logcat tag=huchuan/m1）"
fi
chk "kernel.started" 1 "$(log_field app_kernel_ready device_id)"
chk "kernel.keystore_keyseed" "$(applog | grep -q 'keyseed_box=keystore-aes-gcm-1' && echo 1 || echo 0)" \
  "$(log_field app_kernel_ready keyseed_box)"
chk "kernel.multicast_lock" "$(applog | grep -q 'multicast_lock=true' && echo 1 || echo 0)"

# 首次装完的冷启动要做 dex2oat，老机器上可能要几十秒。P0 是自动放行的过场，
# 不能拿它的文字当门（跑得快的时候早就翻页了）；只等「应用窗口在前台」+「P1 出现」。
FORE=0
for _ in $(seq 1 90); do
  if app_foreground; then FORE=1; break; fi
  sleep 1
done
if [ "$FORE" = "0" ]; then
  screenshot "p0-no-window"
  die "应用窗口没起来（前台不是 $PKG）"
fi
if ! see_text "用户名" 120000; then
  screenshot "p0-stuck"
  uidump "p0-stuck"
  die "P0 没进 P1（自检卡住？）"
fi
screenshot "p1-username"
tap "保存"
sleep 1

# ------------------------------------------------------------------ 6. 只输后 8 位加入

step "6/9 P5 只输后 8 位加入"
if ! see_text "邀请码后 8 位" 25000; then
  screenshot "p5-missing"
  die "没进 P5（新群组页）"
fi
tap "邀请码后 8 位"
sh_ input text "$TAIL8" >/dev/null 2>&1
sleep 1
screenshot "p5-typed-tail8"
tap "加入"

if ! wait_log "group_joined" 90000; then
  screenshot "p5-join-failed"
  applog | grep -E "join_|group_" | tail -20
  die "加入失败（logcat 没有 group_joined）"
fi
JOIN_TAIL="$(log_field "join_candidates" tail8)"
chk "join.tail8_only" "$([ "$JOIN_TAIL" = "$TAIL8" ] && echo 1 || echo 0)" "input=$JOIN_TAIL"
JOIN_VIA="$(log_field "join_via" source)"
LAN_N="$(log_field "join_candidates" lan)"
LAN_ADDRS="$(log_field "join_candidates" addrs)"
JOIN_ADDR="$(log_field "join_via" addr)"
if [ "${LAN_N:-0}" -ge 1 ]; then
  # 协议 §3.4：同一个 endpoint 手动候选优先记 manual，所以 join_via 是 manual 也可能就是 mDNS 找到的那个地址。
  chk "join.mdns_discovered" 1 "lan=${LAN_N} addr=${LAN_ADDRS}"
  info "join_via=${JOIN_VIA:-none} addr=${JOIN_ADDR:-?}（mDNS 与手动候选同端点时按协议记 manual）"
else
  warn "mDNS 没有发现候选（lan=0）；靠手动 IP:port 兜底 addr=${JOIN_ADDR:-?}"
fi
if ! see_text "添加文件" 25000; then
  screenshot "p3-missing"
  die "加入后没进 P3"
fi
screenshot "p3-group"

# ------------------------------------------------------------------ 7. SAF 共享文件

step "7/9 P3 SAF 选文件 → 做种"
PAYLOAD_NAME="$(basename "$PAYLOAD")"

# SAF 抽屉有动画：点完「显示根目录」要等抽屉真的展开再点「下载」，
# 中途点到遮罩只会把抽屉关掉。整段最多重来 3 次。
saf_pick() {
  local name="$1" attempt
  for attempt in 1 2 3; do
    if ! see_text "添加文件" 20000; then return 1; fi
    tap "添加文件"
    if ! see_text "显示根目录" 25000; then
      sh_ input keyevent KEYCODE_BACK >/dev/null 2>&1
      sleep 1
      continue
    fi
    sleep 1
    # 媒体库索引到的话「最近」里直接就有，省一步
    if see_text "$name" 3000; then
      screenshot "saf-recent"
      tap "$name" exact
      return 0
    fi
    # 「显示根目录」是抽屉开关：已经开着再点会关掉，先看「可用空间」这个抽屉标志。
    if ! see_text "可用空间" 3000; then
      tap "显示根目录" exact
      sleep 2
    fi
    if ! see_text "可用空间" 8000; then
      warn "SAF 抽屉没打开"
      uidump "saf-nodrawer$attempt"
      sh_ input keyevent KEYCODE_BACK >/dev/null 2>&1
      sleep 1
      continue
    fi
    tap "下载" exact
    sleep 2
    if see_text "中的文件" 15000 && see_text "$name" 15000; then
      screenshot "saf-downloads"
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

saf_pick "$PAYLOAD_NAME" || die "SAF 选不到 $PAYLOAD_NAME"
sleep 2

if ! wait_log "file_seeding" 90000; then
  screenshot "p3-share-failed"
  applog | tail -20
  die "共享失败（logcat 没有 file_seeding）"
fi
SHARE_NAME="$(log_field "share_import" name)"
SHARE_BYTES="$(log_field "share_import" bytes)"
FILE_ID="$(log_field "file_seeding" file_id)"
chk "share.saf_copied" "$([ "$SHARE_BYTES" = "33554432" ] && echo 1 || echo 0)" "$SHARE_NAME $SHARE_BYTES B"
chk "share.seeding" "$([ -n "$FILE_ID" ] && echo 1 || echo 0)" "file_id=$FILE_ID"

DEVICE_SHARE_PATH="/data/data/$PKG/files/m1/share/$SHARE_NAME"
if pull_private "$DEVICE_SHARE_PATH" "$ART/pulled/android-seed-copy.bin"; then
  SEED_SHA="$(sha256_of "$ART/pulled/android-seed-copy.bin")"
else
  SEED_SHA=""
fi
chk "share.copy_matches_payload" "$([ "$SEED_SHA" = "$PAYLOAD_SHA" ] && echo 1 || echo 0)" "sha=${SEED_SHA:0:16}"

# ------------------------------------------------------------------ 8. 桌面下载 + 评论双向

step "8/9 桌面下载与评论双向"
if ! wait_log_peer "PEER download_done" 300000; then
  tail -30 "$PEER_LOG"
  die "桌面端没下载完"
fi
PC_DL_SHA="$(grep -o 'download_done .*sha256=[0-9a-f]*' "$PEER_LOG" | tail -1 | grep -o 'sha256=[0-9a-f]*' | cut -d= -f2)"
PC_DL_BYTES="$(grep -o 'download_done .*bytes=[0-9]*' "$PEER_LOG" | tail -1 | grep -o 'bytes=[0-9]*' | cut -d= -f2)"
chk "desktop.downloaded_32MiB" "$([ "$PC_DL_BYTES" = "33554432" ] && echo 1 || echo 0)" "$PC_DL_BYTES B"
chk "sha256.equal_android_desktop" "$([ "$PC_DL_SHA" = "$PAYLOAD_SHA" ] && echo 1 || echo 0)" "pc=${PC_DL_SHA:0:16}"

if ! see_text "from-pc-1" 150000; then
  screenshot "p4-missing-pc-comment"
  uidump "p4-no-pc-comment"
  die "手机界面上看不到桌面发的评论"
fi
screenshot "p4-pc-comment"
chk "comment.pc_to_android" 1 "from-pc-1"

tap "写评论"
sh_ input text "from-mix-1" >/dev/null 2>&1
sleep 1
tap "发送"
if ! wait_log_peer "body=from-mix-1" 150000; then
  screenshot "p4-reply-failed"
  tail -20 "$PEER_LOG"
  die "桌面端没收到手机的评论"
fi
chk "comment.android_to_pc" 1 "from-mix-1"
screenshot "p4-both-comments"

# ------------------------------------------------------------------ 9. 崩溃压力

if [ "$QUICK" = "1" ]; then
  step "9/9 崩溃压力（--quick 跳过）"
  warn "跳过压力测试"
else
  step "9/9 崩溃压力 ${STRESS_ROUNDS} x ${STRESS_MIB} MiB（第 2 轮中途取消一次）"
  back_to_group || die "回不到 P3（软键盘/返回栈异常）"

  ROUND=1
  for stress in "${STRESS_FILES[@]}"; do
    NAME="$(basename "$stress")"
    SHA="$(sha256_of "$stress")"
    info "round $ROUND: $NAME"

    if ! see_text "$NAME" 40000; then
      uidump "p3-round$ROUND"
      die "P3 上没有 $NAME"
    fi
    tap "$NAME" exact
    sleep 1

    STARTS="$(count_log "download_start")"
    tap "激活开关"
    if ! wait_log_count "download_start" $((STARTS + 1)) 90000; then
      screenshot "p4-activate-failed-$ROUND"
      applog | tail -20
      die "第 $ROUND 轮激活后没开始下载"
    fi

    # 本轮文件的 file_id（进度按它过滤）
    FILE_ID_ROUND="$(applog | grep -F "ui_file_added" | grep -F "name=$NAME" | grep -o 'file_id=[a-f0-9]*' | head -1 | cut -d= -f2)"
    if [ -z "$FILE_ID_ROUND" ]; then
      uidump "p3-round$ROUND"
      die "日志里找不到 $NAME 的 file_id"
    fi

    if [ "$ROUND" = "2" ]; then
      if ! wait_progress "$FILE_ID_ROUND" 25 240000; then
        screenshot "p4-cancel-no-progress"
        die "第 2 轮等不到 25% 进度"
      fi
      # 取消必须发生在「还没下完」的时候；下完了就说明限速没生效，这一轮的取消是假的。
      DONE_AT_CANCEL="$(count_log "ui_file_completed")"
      if [ "$DONE_AT_CANCEL" -gt "$((ROUND - 1))" ]; then
        screenshot "p4-cancel-too-fast"
        die "第 2 轮在取消前就下完了（限速没生效？调大 --mib 或设 M1_THROTTLE_KBPS）"
      fi
      screenshot "p4-round2-before-cancel"
      tap "激活开关"
      if ! wait_log "file_stopped" 40000; then
        die "休眠没有真正停下（logcat 没有 file_stopped）"
      fi
      chk "stress.cancel_hibernate" 1 "round 2 @ >=25%"
      sleep 5
      AFTER_STOP="$(applog | awk '/file_stopped/{flag=1} flag && /download_progress/' | wc -l | tr -d ' ')"
      chk "stress.cancel_stops_transfer" "$([ "$AFTER_STOP" = "0" ] && echo 1 || echo 0)" "stop 后新增进度行 $AFTER_STOP"
      screenshot "p4-round2-hibernated"
      RESTARTS="$(count_log "download_start")"
      tap "激活开关"
      wait_log_count "download_start" $((RESTARTS + 1)) 90000 || die "第 2 轮重新激活失败"
      chk "stress.resume_after_cancel" 1
    fi

    DONE_BEFORE="$(count_log "ui_file_completed")"
    if ! wait_log_count "ui_file_completed" $((DONE_BEFORE + 1)) 900000; then
      screenshot "p4-download-timeout-$ROUND"
      die "第 $ROUND 轮下载没完成"
    fi
    sleep 1
    screenshot "p4-round$ROUND-done"

    DEVICE_FILE="/data/data/$PKG/files/m1/downloads/$NAME"
    PULLED="$ART/pulled/round$ROUND-$NAME"
    if pull_private "$DEVICE_FILE" "$PULLED"; then
      PULL_SHA="$(sha256_of "$PULLED")"
    else
      PULL_SHA=""
    fi
    chk "stress.round$ROUND.sha256" "$([ "$PULL_SHA" = "$SHA" ] && echo 1 || echo 0)" "android=${PULL_SHA:0:16} pc=${SHA:0:16}"

    back_to_group || die "第 $ROUND 轮结束后回不到 P3"
    ROUND=$((ROUND + 1))
  done
fi

# ------------------------------------------------------------------ 10. 断言与证据

step "证据与断言"
screenshot "final-p4"
tap "成员（2 人）" >/dev/null 2>&1 || true
sleep 1
screenshot "final-members"

applog > "$ART/logs/app-logcat.log"
"$ADB" -s "$SERIAL" logcat -d -v brief > "$ART/logs/full-logcat.log" 2>/dev/null
"$ADB" -s "$SERIAL" logcat -b crash -d > "$ART/logs/crash-buffer.log" 2>/dev/null

DHT_TOTAL="$(grep -o 'dht_nodes=[0-9]*' "$ART/logs/app-logcat.log" | wc -l | tr -d ' ')"
DHT_BAD="$(grep -o 'dht_nodes=[0-9]*' "$ART/logs/app-logcat.log" | grep -vc 'dht_nodes=0' | tr -d ' ')"
chk "dht_nodes=0" "$([ "$DHT_TOTAL" -ge 2 ] && [ "$DHT_BAD" = "0" ] && echo 1 || echo 0)" "n=$DHT_TOTAL nonzero=$DHT_BAD"

# crash 缓冲区是全局的：devkit 的 uiautomator dump 在 MIUI 上会自己崩（见 docs/m1-device.md），
# 所以按「崩溃进程是不是本应用」判定；同时把总行数和外来崩溃如实报出来，不藏。
APP_CRASH="$(grep -c "app\.huchuan" "$ART/logs/crash-buffer.log" || true)"
# 按「崩溃块」统计：块头是 FATAL EXCEPTION / Abort message / Fatal signal，
# 块内出现 uiautomator 的算工具自己崩。行数统计没有意义（一个栈几十行）。
CRASH_BLOCKS="$(awk '/FATAL EXCEPTION|Abort message|Fatal signal/ { n++ } END { print n + 0 }' "$ART/logs/crash-buffer.log")"
UIA_BLOCKS="$(awk '
  /FATAL EXCEPTION|Abort message|Fatal signal/ { blk++; next }
  blk > 0 && /uiautomator/ { hit[blk] = 1 }
  END { u = 0; for (i = 1; i <= blk; i++) if (hit[i]) u++; print u + 0 }
' "$ART/logs/crash-buffer.log")"
FOREIGN_BLOCKS=$(( CRASH_BLOCKS - UIA_BLOCKS ))
chk "crash_buffer.app_crash_0" "$([ "$APP_CRASH" = "0" ] && echo 1 || echo 0)"   "app.huchuan=$APP_CRASH；崩溃块 $CRASH_BLOCKS 个，其中 uiautomator 工具 $UIA_BLOCKS 个"
if [ "$FOREIGN_BLOCKS" -gt 0 ]; then
  warn "crash 缓冲区里有 $FOREIGN_BLOCKS 个崩溃块既不是 uiautomator 也不含 app.huchuan，人工看一眼 $ART/logs/crash-buffer.log"
fi

SCUDO="$(grep -cE "Scudo|Fatal signal|Abort message|abort_message" "$ART/logs/full-logcat.log" || true)"
chk "no_scudo_abort" "$([ "$SCUDO" = "0" ] && echo 1 || echo 0)" "$SCUDO hits"

ANR="$(grep -cE "ANR in $PKG|Application Not Responding" "$ART/logs/full-logcat.log" || true)"
chk "no_anr" "$([ "$ANR" = "0" ] && echo 1 || echo 0)" "$ANR hits"

SHOTS="$(ls "$ART/screenshots"/*.png 2>/dev/null | wc -l | tr -d ' ')"
chk "evidence.screenshots" "$([ "$SHOTS" -ge 4 ] && echo 1 || echo 0)" "$SHOTS png"

MEM_LINE="$(sh_ dumpsys meminfo "$PKG" | grep -E "TOTAL PSS|TOTAL RSS" | head -1 | tr -s ' ')"
info "meminfo: ${MEM_LINE:-n/a}"

step "证据清单"
say "logcat    : $ART/logs/app-logcat.log"
say "peer      : $PEER_LOG"
say "screenshots: $ART/screenshots/"
say "pulled    : $ART/pulled/"
say "关键日志行："
grep -hE "event=(app_kernel_ready|join_input|join_via|group_joined|share_import|file_seeding|download_start|file_stopped|ui_file_completed|ui_op_received)" \
  "$ART/logs/app-logcat.log" 2>/dev/null | sed 's/^/  /' | head -30
say "桌面关键行："
grep -hE "PEER (ready|peer_joined|file_seen|download_done|comment_sent|op_received|reply_seen|share )" \
  "$PEER_LOG" 2>/dev/null | sed 's/^/  /' | head -20

say ""
if [ "$CHECKS_FAIL" -eq 0 ]; then
  say "PASS ($CHECKS_PASS checks)"
  exit 0
fi
say "FAIL ($CHECKS_FAIL failed / $((CHECKS_PASS + CHECKS_FAIL)) checks)"
exit 1
