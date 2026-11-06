#!/usr/bin/env bash
#
# verify-targetsdk37.sh — targetSdk 37 + Android 17 本地网络权限验收
#
#   bash scripts/verify-targetsdk37.sh                 # 四条验收全跑（约 12-15 分钟）
#   bash scripts/verify-targetsdk37.sh --phase a       # 只跑 (a) 首次启动请求权限 + mDNS
#   bash scripts/verify-targetsdk37.sh --phase bc      # 只跑 (b)(c) 拒绝 / 重试 / 设置授权
#   bash scripts/verify-targetsdk37.sh --phase d       # 只跑 (d) Mi MIX 2S（API 33）不弹权限
#
# 验收项（对应 docs/reports/targetsdk37.md §4）：
#   a 首次启动会请求权限；授权后 mDNS 能发现桌面端（logcat 有 lan 候选）
#   b 拒绝后 P0 停在「本地网络未授权」，只给「重试」；重试能再次拉起或跳设置页
#   c 在系统设置里授权后回到应用，自动复检通过并放行
#   d Mi MIX 2S（API 33）不弹权限、P0 正常放行、加入群组照常
#
# 前置：OPPO(API 37) 与 Mi MIX 2S(API 33) 都在线；adb 见 docs/tooling/android-verify.md。
# 证据：artifacts/targetsdk37/（截图、UI XML、logcat、对端日志、summary.txt）
# 最后一行打印 PASS / FAIL，退出码 0 / 1（环境错误 3）。
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ART="$ROOT/artifacts/targetsdk37"
DEVKIT="$ROOT/tools/devkit/devkit.mjs"
PKG="app.huchuan"
ACTIVITY=".MainActivity"
OPPO="${TS37_OPPO:-3B1F5HEADK1TXSF0}"
MIX="${TS37_MIX:-5b94673a}"
PHASE="all"
PEER_HOLD="${TS37_PEER_HOLD:-900}"

while [ $# -gt 0 ]; do
  case "$1" in
    --phase) shift; PHASE="${1:-all}" ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "FATAL: 未知参数 $1" >&2; exit 2 ;;
  esac
  shift
done

case "$PHASE" in a|b|bc|c|d|all) ;; *) echo "FATAL: --phase 只接受 a|bc|d|all" >&2; exit 2 ;; esac

mkdir -p "$ART/logs" "$ART/shots" "$ART/ui"

# 同一时刻只允许一个实例：真机是独占资源，两个脚本同时跑会互相安装/清日志，
# 断言全乱（实测踩过）。
LOCK="$ART/.running"
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK" 2>/dev/null)" 2>/dev/null; then
  echo "FATAL: 已有另一个 verify-targetsdk37.sh 在跑（pid $(cat "$LOCK")）" >&2
  exit 3
fi
echo $$ > "$LOCK"

# 桌面对端句柄要早于 trap 声明：任何一步 die 都要把 java 对端带走。
PEER_PID=""
PEER_TAIL8=""
peer_stop() {
  if [ -n "$PEER_PID" ] && kill -0 "$PEER_PID" 2>/dev/null; then
    kill "$PEER_PID" 2>/dev/null; sleep 0.3; kill -9 "$PEER_PID" 2>/dev/null
  fi
  PEER_PID=""
}
on_exit() { peer_stop; rm -f "$LOCK"; }
trap on_exit EXIT

say() { printf '%s\n' "$*"; }
step() { printf '\n=== %s ===\n' "$1"; }
die() { say "FATAL: $*" >&2; say "FAIL"; exit 1; }

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

w() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

resolve_adb() {
  local c
  for c in "${TS37_ADB:-}" "${ADB:-}" "${DEVKIT_ADB:-}" \
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
dks() { node "$(w "$DEVKIT")" -s "$SERIAL" "$@" 2>&1; }
sh_() { "$ADB" -s "$SERIAL" shell "$@" 2>&1; }

# ------------------------------------------------------------------ UI 小工具

shot() {
  dks screenshot --name "$1-$SERIAL" >/dev/null 2>&1
  say "  shot $1"
}

ui_texts() { dks ui-dump --texts 2>/dev/null; }

see_text() {
  dks wait-text "$1" "${2:-15000}" >/dev/null 2>&1
}

# 点文本（默认包含匹配；精确匹配传 exact）
tap_text() {
  if [ "${2:-}" = "exact" ]; then
    dks tap-text "$1" --exact >/dev/null 2>&1
  else
    dks tap-text "$1" >/dev/null 2>&1
  fi
}

# 按 resource-id 点：从最新 ui-dump XML 取 bounds 中心。
# OEM 的无文字按钮（ColorOS 安装引导页的「更多」）只能这么点。
tap_resid() {
  local resid="$1" xml bounds x1 y1 x2 y2
  dks ui-dump >/dev/null 2>&1
  xml="$(ls -t "$ROOT"/artifacts/ui-dumps/"$SERIAL"/*.xml 2>/dev/null | head -1)"
  [ -n "$xml" ] || return 1
  bounds="$(grep -o "resource-id=\"$resid\"[^>]*bounds=\"\[[0-9,]*\]\[[0-9,]*\]\"" "$xml" \
    | grep -o 'bounds="\[[0-9,]*\]\[[0-9,]*\]"' | head -1)"
  [ -n "$bounds" ] || return 1
  x1=$(printf '%s' "$bounds" | sed 's/bounds="\[\([0-9]*\),.*/\1/')
  y1=$(printf '%s' "$bounds" | sed 's/.*\[[0-9]*,\([0-9]*\)\]\[.*/\1/')
  x2=$(printf '%s' "$bounds" | sed 's/.*\]\[\([0-9]*\),.*/\1/')
  y2=$(printf '%s' "$bounds" | sed 's/.*,\([0-9]*\)\]".*/\1/')
  sh_ input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 )) >/dev/null 2>&1
}

perm_granted() {
  sh_ dumpsys package "$PKG" | grep -E 'android.permission.ACCESS_LOCAL_NETWORK: granted' | head -1 \
    | grep -q 'granted=true' && echo 1 || echo 0
}

# 断言只读「本次运行的日志流」：OPPO 上 logcat 缓冲区在安装/UI dump 期间几十秒就被刷掉，
# 用 logcat -d 做断言会随机假阴性（实测：p0_result 明明打过，dump 出来只剩心跳）。
LOGSTREAM=""
STREAM_PID=""
logstream_start() {
  logstream_stop
  LOGSTREAM="$ART/logs/stream-$SERIAL.log"
  : > "$LOGSTREAM"
  ( "$ADB" -s "$SERIAL" logcat -v time -s huchuan/m1:I 2>&1 \
      | grep --line-buffered 'huchuan/m1' > "$LOGSTREAM" ) &
  STREAM_PID=$!
  sleep 2
}
logstream_stop() {
  if [ -n "$STREAM_PID" ] && kill -0 "$STREAM_PID" 2>/dev/null; then
    kill "$STREAM_PID" 2>/dev/null
    sleep 0.3
  fi
  STREAM_PID=""
}
# adb logcat 的流里会混进 NUL 字节，grep 会把它当二进制文件直接跳过（"Binary file matches"），
# 所以统一清掉 NUL 与 CR 再交给断言。
applog() {
  if [ -n "$LOGSTREAM" ] && [ -s "$LOGSTREAM" ]; then
    tr -d '\000\r' < "$LOGSTREAM"
  else
    sh_ logcat -d -s huchuan/m1:I | tr -d '\000\r'
  fi
}
log_field() {
  applog | grep -E "event=$1( |$)" | tail -1 | grep -o "$2=[^ ]*" | head -1 | cut -d= -f2
}
have_log() { applog | grep -qE "$1"; }

wait_log() {
  local waited=0 timeout_ms="${2:-30000}"
  while [ "$waited" -lt "$timeout_ms" ]; do
    if have_log "$1"; then return 0; fi
    sleep 1
    waited=$((waited + 1000))
  done
  return 1
}

# ------------------------------------------------------------------ 安装

# ColorOS 对全新安装会插一道「安装增强防护」引导页
# （com.oplus.appdetail/InstallGuideActivity）：必须点右上「更多 → 开始深度扫描」，
# 扫完点「继续安装」。不自动点掉的话 adb install 会一直挂着。
install_apk() {
  local apk="$1" log="$ART/logs/install-$SERIAL.log" pid top i
  : > "$log"
  ( "$ADB" -s "$SERIAL" install -r -t "$(w "$apk")" > "$log" 2>&1 ) &
  pid=$!
  for i in $(seq 1 90); do
    kill -0 "$pid" 2>/dev/null || break
    top="$(sh_ dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity)"
    if printf '%s' "$top" | grep -q 'InstallGuideActivity'; then
      say "  安装被 ColorOS「安装增强防护」拦住，自动点掉（第 $i 次探测）"
      if tap_resid 'com.oplus.appdetail:id/menu_more'; then
        sleep 2
        if see_text '开始深度扫描' 8000; then
          tap_text '开始深度扫描'
          if see_text '继续安装' 60000; then
            tap_text '继续安装'
            say "  已点「继续安装」"
          else
            warn "安装增强防护没扫出「继续安装」"
          fi
        else
          warn "更多菜单没出现「开始深度扫描」"
        fi
      else
        warn "找不到「更多」按钮（$top）"
      fi
    fi
    sleep 2
  done
  wait "$pid" 2>/dev/null
  grep -E 'Success|Failure|Error' "$log" | tail -1
  grep -q 'Success' "$log"
}

pkg_installed() { sh_ pm list packages | grep -q "package:$PKG" && echo 1 || echo 0; }

# 只有卸载重装才能把 ACCESS_LOCAL_NETWORK 复位：ColorOS 的 shell 既没有
# CLEAR_APP_USER_DATA 也没有 REVOKE_RUNTIME_PERMISSIONS（实测都抛 SecurityException），
# pm clear / pm revoke 都用不了。
fresh_install() {
  local apk="$1"
  sh_ am force-stop "$PKG" >/dev/null 2>&1
  if [ "$(pkg_installed)" = "1" ]; then
    "$ADB" -s "$SERIAL" uninstall "$PKG" 2>&1 | tail -1
  fi
  install_apk "$apk" || die "安装失败（看 $ART/logs/install-$SERIAL.log）"
}

# ------------------------------------------------------------------ 桌面对端

peer_start() {
  local cpo classdir data log native j i
  data="$ART/peer-data/$1"
  log="$ART/logs/peer-$1.log"
  rm -rf "$data"; mkdir -p "$data"
  classdir="$ART/peer-classes"
  [ -f "$classdir/DesktopPeer.class" ] || die "桌面对端没编译（$classdir）"
  cpo="$(w "$ROOT/core/build/libs/core.jar")"
  for j in "$ROOT"/core/build/m1-libs/*.jar; do [ -f "$j" ] && cpo="$cpo;$(w "$j")"; done
  cpo="$cpo;$(w "$classdir")"
  native="$(w "$ROOT/core/build/m1-native/libtorrent4j.dll")"
  # 起进程之前先清空日志：不清的话第一次读取会读到上一轮残留的 tail8，
  # 拿旧码去 join 必然 E_JOIN_CODE（踩过一次）。
  : > "$log"
  java -Dlibtorrent4j.jni.path="$native" -Dfile.encoding=UTF-8 -cp "$cpo" DesktopPeer host \
    data="$(w "$data")" work="$(w "$ART")" name="桌面电脑" msg_port=47100 bt_port=51413 \
    group_name="验收群" hold="$PEER_HOLD" > "$log" 2>&1 &
  PEER_PID=$!
  # 只认本进程的 "PEER ready" 行：它带本轮的 tail8，比任何裸 tail8= 都稳。
  for i in $(seq 1 120); do
    PEER_TAIL8="$(grep -o 'PEER ready role=host tail8=[0-9A-Z]*' "$log" 2>/dev/null | head -1 | sed 's/.*tail8=//')"
    [ -n "$PEER_TAIL8" ] && break
    kill -0 "$PEER_PID" 2>/dev/null || break
    sleep 1
  done
  [ -n "$PEER_TAIL8" ] || { tail -20 "$log"; die "桌面对端没起来"; }
  info "peer tail8=$PEER_TAIL8 pid=$PEER_PID"
}

# 只清本脚本自己的对端（按命令行里的 DesktopPeer 认），不碰别人的 java
peer_cleanup_stale() {
  powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*DesktopPeer*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1
}

wait_permission_dialog() {
  local waited=0 timeout="${1:-20000}"
  while [ "$waited" -lt "$timeout" ]; do
    if ui_texts | grep -q 'GrantPermissionsActivity'; then return 0; fi
    sleep 1
    waited=$((waited + 1000))
  done
  return 1
}

# ------------------------------------------------------------------ 前置 + 构建

step "0/5 前置"
"$ADB" devices | grep -q "$OPPO" || die "OPPO $OPPO 不在线"
info "OPPO $OPPO 在线"
if [ "$PHASE" = "all" ] || [ "$PHASE" = "d" ]; then
  "$ADB" devices | grep -q "$MIX" || die "MIX $MIX 不在线"
  info "MIX $MIX 在线"
fi

step "1/5 构建"
( cd "$ROOT" && ./gradlew --console=plain -q :app:assembleDebug :core:jar :core:m1Deps :core:m1Native ) \
  || die "gradle 构建失败"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || die "APK 不存在：$APK"

AAPT2="$(ls /e/agentHome/android-sdk/build-tools/*/aapt2.exe 2>/dev/null | tail -1)"
if [ -n "$AAPT2" ]; then
  TSDK="$("$(w "$AAPT2")" dump badging "$(w "$APK")" 2>/dev/null | grep -o "targetSdkVersion:'[0-9]*'" | head -1)"
  chk "apk.targetSdk37" "$(printf '%s' "$TSDK" | grep -q 37 && echo 1 || echo 0)" "$TSDK"
  chk "apk.declares_local_network" \
    "$("$(w "$AAPT2")" dump permissions "$(w "$APK")" 2>/dev/null | grep -q ACCESS_LOCAL_NETWORK && echo 1 || echo 0)" \
    "android.permission.ACCESS_LOCAL_NETWORK"
else
  warn "没有 aapt2，跳过 APK 静态断言"
fi

CLASSDIR="$ART/peer-classes"
if [ ! -f "$CLASSDIR/DesktopPeer.class" ]; then
  mkdir -p "$CLASSDIR"
  CP="$(w "$ROOT/core/build/libs/core.jar")"
  for j in "$ROOT"/core/build/m1-libs/*.jar; do [ -f "$j" ] && CP="$CP;$(w "$j")"; done
  javac -encoding UTF-8 -cp "$CP" -d "$(w "$CLASSDIR")" "$(w "$ROOT/app/tools/peer/DesktopPeer.java")" \
    || die "javac 桌面对端失败"
fi

SERIAL="$OPPO"
sh_ svc power stayon true >/dev/null 2>&1
sh_ input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
sh_ wm dismiss-keyguard >/dev/null 2>&1
peer_cleanup_stale

# ------------------------------------------------------------------ (a)

phase_a() {
  step "a/5 OPPO：首次启动请求权限 → 授权 → mDNS 发现桌面端"
  peer_start a
  fresh_install "$APK"
  chk "a.initial_denied" "$([ "$(perm_granted)" = "0" ] && echo 1 || echo 0)" \
    "$(sh_ dumpsys package "$PKG" | grep -m1 'ACCESS_LOCAL_NETWORK: granted')"

  sh_ logcat -b all -c >/dev/null 2>&1
  logstream_start
  dks launch "$PKG" >/dev/null 2>&1
  if wait_permission_dialog 25000; then
    chk "a.prompt_on_first_launch" 1 "GrantPermissionsActivity"
    shot "a-permission-dialog"
    tap_text '允许' exact
  else
    shot "a-no-prompt"
    chk "a.prompt_on_first_launch" 0 "首次启动没弹授权框"
  fi
  chk "a.granted_by_prompt" "$(wait_log 'local_network_permission state=granted.*source=prompt' 20000 && echo 1 || echo 0)" "source=prompt"
  chk "a.p0_pass_after_grant" "$(wait_log 'p0_result status=pass' 30000 && echo 1 || echo 0)"
  shot "a-p1-after-grant"

  # P1 → P5 → 只输后 8 位加入；不传 huchuan.debug.manual，这条路只能靠 mDNS。
  see_text '用户名' 20000 || die "授权后没进 P1"
  tap_text '保存'; sleep 1
  see_text '邀请码后 8 位' 25000 || { shot "a-p5-missing"; die "没进 P5"; }
  tap_text '邀请码后 8 位'; sleep 1
  sh_ input text "$PEER_TAIL8" >/dev/null 2>&1; sleep 1
  tap_text '加入'
  if wait_log 'event=group_joined' 90000; then
    LAN="$(log_field join_candidates lan)"
    chk "a.mdns_lan_candidates" "$([ "${LAN:-0}" -ge 1 ] && echo 1 || echo 0)" \
      "lan=$LAN addrs=$(log_field join_candidates addrs)"
    chk "a.joined_via_lan" "$([ "$(log_field join_via source)" = "lan" ] && echo 1 || echo 0)" \
      "join_via=$(log_field join_via source) addr=$(log_field join_via addr)"
  else
    shot "a-join-failed"
    applog | grep -E 'join_|mdns' | tail -10
    chk "a.mdns_lan_candidates" 0 "加入失败，没有 group_joined"
  fi
  shot "a-p3-group"
  sleep 1
  applog > "$ART/logs/oppo-a.log" 2>&1
  logstream_stop
  peer_stop
}

# ------------------------------------------------------------------ (b) + (c)

phase_bc() {
  step "b/5 OPPO：拒绝 → 无权限态 → 重试"
  peer_start bc
  fresh_install "$APK"
  sh_ logcat -b all -c >/dev/null 2>&1
  logstream_start
  dks launch "$PKG" >/dev/null 2>&1
  wait_permission_dialog 25000 || die "首次启动没弹授权框"

  tap_text '不允许' exact; sleep 3
  chk "b.p0_no_permission_state" "$(see_text '本地网络未授权' 15000 && echo 1 || echo 0)" "本地网络未授权"
  chk "b.retry_visible" "$(see_text '重试' 8000 && echo 1 || echo 0)"
  shot "b-p0-no-permission"

  tap_text '重试' exact; sleep 3
  if wait_permission_dialog 15000; then
    chk "b.retry_reprompts" 1 "第二次系统授权框"
    tap_text '不允许' exact; sleep 3
  else
    chk "b.retry_reprompts" 0 "重试没有再次拉起授权框"
  fi
  chk "b.still_no_permission" "$(see_text '本地网络未授权' 15000 && echo 1 || echo 0)"
  shot "b-p0-after-second-deny"

  step "c/5 OPPO：系统设置里授权 → 回到应用自动复检"
  tap_text '重试' exact; sleep 4
  TOP="$(sh_ dumpsys activity activities | grep -m1 topResumedActivity)"
  chk "b.retry_routes_to_settings" "$(printf '%s' "$TOP" | grep -q com.android.settings && echo 1 || echo 0)" \
    "$(printf '%s' "$TOP" | grep -o 'com.android.settings[^ }]*' | head -1)"
  shot "b-settings-page"

  see_text '权限管理' 15000 || die "设置页没有「权限管理」"
  tap_text '权限管理'; sleep 2
  see_text '附近的设备' 15000 || die "权限页没有「附近的设备」"
  tap_text '附近的设备'; sleep 2
  see_text '允许' 15000 || die "权限详情页没有「允许」"
  tap_text '允许' exact; sleep 2
  sh_ input keyevent KEYCODE_BACK >/dev/null 2>&1; sleep 1
  sh_ input keyevent KEYCODE_BACK >/dev/null 2>&1; sleep 1
  sh_ input keyevent KEYCODE_BACK >/dev/null 2>&1; sleep 2

  chk "c.granted_from_settings" "$([ "$(perm_granted)" = "1" ] && echo 1 || echo 0)"
  chk "c.auto_recheck_on_resume" "$(wait_log 'local_network_permission state=granted.*source=resume' 15000 && echo 1 || echo 0)" "source=resume"
  chk "c.p0_pass_after_resume" "$(wait_log 'p0_result status=pass' 30000 && echo 1 || echo 0)"
  chk "c.entered_p1" "$(see_text '用户名' 20000 && echo 1 || echo 0)"
  shot "c-p1-after-auto-recheck"

  # 「回来能用」不只是 P0 放行：授权后内核才起（HuchuanApplication 里推后到这一步），
  # 必须真的能靠 mDNS 摸到桌面对端才算通。
  tap_text '保存'; sleep 1
  see_text '邀请码后 8 位' 25000 || { shot "c-p5-missing"; die "设置授权后没进 P5"; }
  tap_text '邀请码后 8 位'; sleep 1
  sh_ input text "$PEER_TAIL8" >/dev/null 2>&1; sleep 1
  tap_text '加入'
  chk "c.join_after_settings_grant" "$(wait_log 'event=group_joined' 90000 && echo 1 || echo 0)" \
    "lan=$(log_field join_candidates lan) join_via=$(log_field join_via source)"
  shot "c-p3-group"

  sleep 1
  applog > "$ART/logs/oppo-bc.log" 2>&1
  logstream_stop
  peer_stop
}

# ------------------------------------------------------------------ (d)

phase_d() {
  step "d/5 MIX（API 33）：不弹权限、P0 放行、加入照常"
  SERIAL="$MIX"
  SDK="$(sh_ getprop ro.build.version.sdk | tr -d '\r')"
  chk "d.mix_api33" "$([ "$SDK" = "33" ] && echo 1 || echo 0)" "sdk=$SDK"
  chk "d.mix_no_local_network_perm" "$(sh_ pm list permissions | grep -q ACCESS_LOCAL_NETWORK && echo 0 || echo 1)" "系统不认识该权限"

  peer_start d
  fresh_install "$APK"
  sh_ logcat -b all -c >/dev/null 2>&1
  logstream_start
  dks launch "$PKG" >/dev/null 2>&1

  PROMPT=0
  for i in $(seq 1 15); do
    if ui_texts | grep -q 'GrantPermissionsActivity'; then PROMPT=1; break; fi
    sleep 1
  done
  chk "d.no_permission_prompt" "$([ "$PROMPT" = "0" ] && echo 1 || echo 0)"
  chk "d.p0_pass_no_gate" "$(wait_log 'p0_result status=pass local_network=not_required' 30000 && echo 1 || echo 0)"
  chk "d.entered_p1" "$(see_text '用户名' 20000 && echo 1 || echo 0)"
  shot "d-p1"

  tap_text '保存'; sleep 1
  see_text '邀请码后 8 位' 25000 || { shot "d-p5-missing"; die "MIX 没进 P5"; }
  tap_text '邀请码后 8 位'; sleep 1
  sh_ input text "$PEER_TAIL8" >/dev/null 2>&1; sleep 1
  tap_text '加入'
  chk "d.join_works" "$(wait_log 'event=group_joined' 90000 && echo 1 || echo 0)" "lan=$(log_field join_candidates lan)"
  shot "d-p3-group"
  sleep 1
  applog > "$ART/logs/mix-d.log" 2>&1
  logstream_stop
  peer_stop
  SERIAL="$OPPO"
}

case "$PHASE" in
  a) phase_a ;;
  b|bc|c) phase_bc ;;
  d) phase_d ;;
  all) phase_a; phase_bc; phase_d ;;
esac

step "汇总"
# 每次运行单独留一份：summary.txt 只反映最近一次，--phase 分次跑时靠这个留痕。
cp "$SUMMARY" "$ART/summary-$PHASE.txt" 2>/dev/null || true
say "check pass=$CHECKS_PASS fail=$CHECKS_FAIL"
say "evidence: $ART"
if [ "$CHECKS_FAIL" -eq 0 ] && [ "$CHECKS_PASS" -gt 0 ]; then
  say PASS
  exit 0
fi
say FAIL
exit 1
