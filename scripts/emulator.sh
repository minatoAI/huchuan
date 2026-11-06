#!/usr/bin/env bash
# emulator.sh — Windows 上的 x86_64 Android 模拟器：起停 / 装包 / 截图
#
#   bash scripts/emulator.sh doctor            # 先跑这个：SDK、加速、AVD、设备
#   bash scripts/emulator.sh create            # 建 AVD（已存在则跳过）
#   bash scripts/emulator.sh start [--window] [--wipe]
#   bash scripts/emulator.sh stop
#   bash scripts/emulator.sh install [apk]     # 默认 app/build/outputs/apk/debug/app-debug.apk
#   bash scripts/emulator.sh shot [name]
#   bash scripts/emulator.sh logcat [--grep RE]
#   bash scripts/emulator.sh status
#
# 前置条件：CPU 加速（WHPX 或 AEHD）。emulator 37 在无加速时直接拒绝跑
# x86_64 镜像，连 -accel tcg 也不放行（见 docs/reports/emulator.md）。缺加速时
# start 以退出码 3 结束并打印补救命令，不会留下半启动的进程。
#
# 产物：artifacts/emulator/（日志、pid），截图/UI dump 走 devkit 到 artifacts/screenshots/。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ART="$ROOT/artifacts/emulator"
AVD_NAME="${EMU_AVD:-huchuan_api35}"
AVD_HOME="${EMU_AVD_HOME:-$ART/avd}"
IMAGE="${EMU_IMAGE:-system-images;android-35;google_apis;x86_64}"
SERIAL="${EMU_SERIAL:-emulator-5554}"
BOOT_TIMEOUT="${EMU_BOOT_TIMEOUT:-900}"

say() { printf '%s\n' "$*"; }
die() { say "FATAL: $*" >&2; exit 2; }

# ---- SDK / 工具 ----------------------------------------------------------
find_sdk() {
  local root
  for root in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" E:/agentHome/android-sdk "$HOME/AppData/Local/Android/Sdk"; do
    if [ -n "$root" ] && [ -x "$root/emulator/emulator.exe" -o -x "$root/emulator/emulator" ]; then printf '%s\n' "$root"; return; fi
  done
  die "找不到 Android SDK（设 ANDROID_SDK_ROOT，或把 emulator 包装进 SDK）"
}
SDK="$(find_sdk)"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) EMU="$SDK/emulator/emulator.exe"; ADB="$SDK/platform-tools/adb.exe" ;;
  *) EMU="$SDK/emulator/emulator"; ADB="$SDK/platform-tools/adb" ;;
esac
DEVCON="$(command -v node >/dev/null 2>&1 && echo "$ROOT/tools/devkit/devkit.mjs")"

export ANDROID_SDK_ROOT="$SDK"
export ANDROID_HOME="$SDK"
# AVD 默认放 artifacts，不占 C:（C 只剩 ~21G，镜像+userdata 要 ~5G）
export ANDROID_AVD_HOME="$(cygpath -m "$AVD_HOME" 2>/dev/null || echo "$AVD_HOME")"
mkdir -p "$ART" "$AVD_HOME"

# qemu 后端要能在 PATH 里找到自己的 DLL
export PATH="$SDK/emulator:$SDK/emulator/lib64:$PATH"

# ---- 加速 -----------------------------------------------------------------
accel_code() {
  local out
  out="$("$EMU" -accel-check 2>&1)" || true
  # 输出形如 "accel:" 换行 "6" 换行 "Android Emulator hypervisor driver is not installed..."
  printf '%s\n' "$out" | tr -d '\r' | awk '/^accel:/{getline; print; exit}'
}

require_accel() {
  local code; code="$(accel_code)"
  [ "$code" = "0" ] && return 0
  say "FATAL: 没有可用的 CPU 加速（emulator -accel-check = ${code:-unknown}）。" >&2
  say "" >&2
  say "  x86_64 镜像必须有 WHPX 或 AEHD；emulator 37 不允许纯软件（-accel tcg）跑 x86_64。" >&2
  say "  以管理员任选其一（都要重启）：" >&2
  say "    A) dism /online /enable-feature /featurename:HypervisorPlatform /all /norestart" >&2
  say "       dism /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart" >&2
  say "    B) sdkmanager \"extras;google;Android_Emulator_Hypervisor_Driver\" 后管理员运行 silent_install.bat" >&2
  say "" >&2
  say "  现状与证据见 docs/reports/emulator.md。" >&2
  exit 3
}

# ---- 子命令 ---------------------------------------------------------------
cmd_doctor() {
  say "sdk        : $SDK"
  say "emulator   : $("$EMU" -version 2>&1 | head -1)"
  say "accel      : $(accel_code)  (0=可用; 6=未装 AEHD/WHPX)"
  say "avd home   : $ANDROID_AVD_HOME"
  say "avds       : $("$EMU" -list-avds 2>/dev/null | tr -d '\r' | tr '\n' ' ')"
  say "image      : $IMAGE"
  say "devices    :"
  "$ADB" devices | sed 's/^/  /'
}

cmd_create() {
  if "$EMU" -list-avds 2>/dev/null | tr -d '\r' | grep -qx "$AVD_NAME"; then
    say "avd $AVD_NAME 已存在，跳过"; return 0
  fi
  [ -d "$SDK/system-images" ] || die "没有 system-images，先 sdkmanager \"$IMAGE\""
  say "创建 $AVD_NAME ($IMAGE) → $ANDROID_AVD_HOME"
  echo no | "$SDK/cmdline-tools/latest/bin/avdmanager.bat" create avd \
    -n "$AVD_NAME" -k "$IMAGE" -d pixel_6 --force 2>&1 | tail -5
}

cmd_start() {
  local window=0 wipe=0
  for a in "$@"; do
    case "$a" in
      --window) window=1 ;;
      --wipe) wipe=1 ;;
      *) die "未知参数 $a" ;;
    esac
  done
  require_accel
  cmd_create >/dev/null
  if "$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | grep -q 1; then
    say "已在运行：$SERIAL"; return 0
  fi
  local log="$ART/emulator-run.log" args
  args=(-avd "$AVD_NAME" -no-audio -no-boot-anim -no-snapshot
        -gpu "${EMU_GPU:-swiftshader_indirect}" -memory "${EMU_MEM:-3072}" -cores "${EMU_CORES:-4}")
  [ "$window" = "1" ] || args+=(-no-window)
  [ "$wipe" = "1" ] && args+=(-wipe-data)
  local label="无窗口"; [ "$window" = "1" ] && label="有窗口"
  say "启动 $AVD_NAME（$label）…日志：$log"
  nohup "$EMU" "${args[@]}" >"$log" 2>&1 &
  echo $! >"$ART/emulator.pid"

  local i=0 t0; t0=$(date +%s)
  while [ $i -lt "$BOOT_TIMEOUT" ]; do
    if "$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q 1; then
      say "boot_completed：$(( $(date +%s) - t0 ))s"
      return 0
    fi
    if ! kill -0 "$(cat "$ART/emulator.pid")" 2>/dev/null; then
      say "FATAL: 模拟器进程已退出，尾部日志：" >&2
      tail -15 "$log" >&2
      exit 4
    fi
    sleep 5; i=$((i + 5))
  done
  say "FATAL: ${BOOT_TIMEOUT}s 内没 boot_completed，看 $log" >&2; exit 4
}

cmd_stop() {
  "$ADB" -s "$SERIAL" emu kill 2>/dev/null && say "已发 emu kill"
  local pid; pid="$(cat "$ART/emulator.pid" 2>/dev/null || true)"
  sleep 3
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    say "进程 $pid 还在，按 PID 精确清理"
    taskkill //PID "$pid" //T //F 2>/dev/null || kill "$pid" 2>/dev/null || true
  fi
  rm -f "$ART/emulator.pid"
}

cmd_install() {
  local apk="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
  [ -f "$apk" ] || die "APK 不存在：$apk（先 bash scripts/build-android.sh）"
  [ -n "$DEVCON" ] || die "找不到 node，装包/截图走 tools/devkit"
  node "$DEVCON" install "$apk" -s "$SERIAL"
}

cmd_shot() {
  [ -n "$DEVCON" ] || die "找不到 node，截图走 tools/devkit"
  node "$DEVCON" screenshot -s "$SERIAL" --name "${1:-emulator}"
}

cmd_logcat() {
  local pat="" largs=(--lines "${EMU_LOG_LINES:-400}")
  [ "${1:-}" = "--grep" ] && pat="${2:-}"
  [ -n "$DEVCON" ] || die "找不到 node"
  [ -n "$pat" ] && largs+=(--grep "$pat")
  node "$DEVCON" logcat-dump -s "$SERIAL" "${largs[@]}"
}

cmd_status() {
  say "avd    : $("$EMU" -list-avds 2>/dev/null | tr -d '\r' | tr '\n' ' ')"
  say "pid    : $(cat "$ART/emulator.pid" 2>/dev/null || echo none)"
  say "boot   : $("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  say "accel  : $(accel_code)"
}

cmd="${1:-doctor}"; shift || true
case "$cmd" in
  doctor)  cmd_doctor "$@" ;;
  create)  cmd_create "$@" ;;
  start)   cmd_start "$@" ;;
  stop)    cmd_stop "$@" ;;
  install) cmd_install "$@" ;;
  shot)    cmd_shot "$@" ;;
  logcat)  cmd_logcat "$@" ;;
  status)  cmd_status "$@" ;;
  -h|--help|help) sed -n '2,20p' "$0" ;;
  *) die "未知命令 $cmd（见 bash scripts/emulator.sh --help）" ;;
esac
