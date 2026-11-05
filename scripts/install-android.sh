#!/usr/bin/env bash
# 把互传 APK 装到在线真机并启动。
#
# 用法：
#   scripts/install-android.sh                 # 装到所有在线设备并启动
#   scripts/install-android.sh -s <serial>     # 只装指定设备（可重复，可逗号分隔）
#   scripts/install-android.sh --apk <path>    # 用指定 APK，不重新构建
#   scripts/install-android.sh --no-launch     # 只装不启动
#   scripts/install-android.sh --list          # 只列在线设备
#
# 依赖：adb（DEVKIT_ADB / ADB / ANDROID_HOME/platform-tools / PATH）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

PACKAGE="app.huchuan"
ACTIVITY="${PACKAGE}/.MainActivity"

serial_args=()
apk=""
do_launch=1
list_only=0

usage() {
    sed -n '2,11p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

die() {
    echo "ERR $*" >&2
    exit 1
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -s|--serial) serial_args+=("${2:?缺少 serial}"); shift 2 ;;
        -s=*|--serial=*) serial_args+=("${1#*=}"); shift ;;
        --apk) apk="${2:?缺少 APK 路径}"; shift 2 ;;
        --apk=*) apk="${1#*=}"; shift ;;
        --no-launch) do_launch=0; shift ;;
        --list) list_only=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "ERR 未知参数：$1" >&2; usage; exit 2 ;;
    esac
done

# ---------- 1. adb ----------
ADB_BIN=""
for candidate in "${DEVKIT_ADB:-}" "${ADB:-}" \
    "${ANDROID_HOME:-}platform-tools/adb" "${ANDROID_SDK_ROOT:-}platform-tools/adb" \
    "E:/agentHome/android-sdk/platform-tools/adb" \
    "$HOME/AppData/Local/Android/Sdk/platform-tools/adb" \
    "C:/Android/Sdk/platform-tools/adb"; do
    [[ -n "$candidate" ]] || continue
    if [[ -x "$candidate" ]]; then
        ADB_BIN="$candidate"
        break
    fi
    if [[ -x "${candidate}.exe" ]]; then
        ADB_BIN="${candidate}.exe"
        break
    fi
done
if [[ -z "$ADB_BIN" ]] && command -v adb >/dev/null 2>&1; then
    ADB_BIN="$(command -v adb)"
fi
[[ -n "$ADB_BIN" ]] || die "未找到 adb。请设置 ANDROID_HOME 或把 platform-tools 加入 PATH。"

# ---------- 2. 设备列表 ----------
serials=()
if [[ ${#serial_args[@]} -gt 0 ]]; then
    for item in "${serial_args[@]}"; do
        IFS=',' read -r -a parts <<< "$item"
        for part in "${parts[@]}"; do
            [[ -n "$part" ]] && serials+=("$part")
        done
    done
else
    while IFS= read -r line; do
        [[ -n "$line" ]] && serials+=("$line")
    done < <("$ADB_BIN" devices | awk 'NR>1 && $2=="device" {print $1}')
fi

[[ ${#serials[@]} -gt 0 ]] || die "没有在线设备。请连上真机后重试。"

echo "INFO adb：$ADB_BIN"
echo "INFO 设备：${serials[*]}"

if [[ $list_only -eq 1 ]]; then
    exit 0
fi

# ---------- 3. APK ----------
if [[ -z "$apk" ]]; then
    apk="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
    if [[ ! -f "$apk" ]]; then
        echo "INFO 未找到 APK，先构建"
        "$ROOT/scripts/build-android.sh" > /dev/null
    fi
fi
[[ -f "$apk" ]] || die "APK 不存在：$apk"

# adb 是 Windows 原生程序，不认 MSYS 的 /e/... 路径
if command -v cygpath >/dev/null 2>&1; then
    apk_native="$(cygpath -w "$apk")"
else
    apk_native="$apk"
fi
echo "INFO APK：$apk_native"

# ---------- 4. 安装 + 启动 ----------
failed=0
install_timeout=300

for serial in "${serials[@]}"; do
    echo "== [$serial] 安装"
    if command -v timeout >/dev/null 2>&1; then
        install_out="$(timeout "$install_timeout" "$ADB_BIN" -s "$serial" install -r "$apk_native" 2>&1)" && install_code=0 || install_code=$?
    else
        install_out="$("$ADB_BIN" -s "$serial" install -r "$apk_native" 2>&1)" && install_code=0 || install_code=$?
    fi
    printf '%s\n' "$install_out" | tail -2

    if [[ $install_code -eq 124 ]]; then
        echo "ERR [$serial] 安装超时 ${install_timeout}s：手机上大概率停在『安装增强防护』页。" >&2
        echo "    处理：在手机上点该页的「继续安装」后重跑本脚本；或到 设置 → 安全 → 安装增强防护 关闭该拦截。" >&2
        failed=1
        continue
    fi
    if [[ $install_code -ne 0 || "$install_out" != *Success* ]]; then
        echo "ERR [$serial] 安装失败（adb exit $install_code）" >&2
        if [[ "$install_out" == *INSTALL_FAILED_* ]]; then
            echo "    提示：先卸载旧版本 adb uninstall $PACKAGE，或带 --downgrade 重装。" >&2
        fi
        failed=1
        continue
    fi

    if [[ $do_launch -eq 0 ]]; then
        echo "OK [$serial] 已安装（未启动）"
        continue
    fi

    echo "== [$serial] 启动"
    "$ADB_BIN" -s "$serial" shell am force-stop "$PACKAGE" > /dev/null 2>&1 || true
    start_out="$("$ADB_BIN" -s "$serial" shell am start -W -n "$ACTIVITY" 2>&1 || true)"
    case "$start_out" in
        *Error*)
            echo "ERR [$serial] 启动失败：$start_out" >&2
            failed=1
            continue
            ;;
    esac

    sleep 2
    pid="$("$ADB_BIN" -s "$serial" shell pidof "$PACKAGE" 2>/dev/null | tr -d ' ' || true)"
    if [[ -z "$pid" ]]; then
        echo "ERR [$serial] 启动后进程不在（可能已崩溃）" >&2
        failed=1
        continue
    fi
    echo "OK [$serial] 已启动，pid=$pid"
done

exit $failed
