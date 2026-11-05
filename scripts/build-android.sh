#!/usr/bin/env bash
# 构建互传 Android DEBUG APK。成功时最后一行打印 APK 绝对路径：APK: <path>
#
# 用法：scripts/build-android.sh
# 依赖：JDK 17、Android SDK（platforms/android-37 + build-tools）、仓库自带 gradle wrapper 9.6.0
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# MSYS / Git-Bash 不要把参数当路径改写
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

die() {
    echo "ERR $*" >&2
    exit 1
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    sed -n '2,5p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 0
fi

# ---------- 1. JDK ----------
if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    JAVA_BIN="${JAVA_HOME}/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA_BIN="$(command -v java)"
else
    die "未找到 java。请安装 JDK 17 并设置 JAVA_HOME。"
fi
JAVA_LINE="$("$JAVA_BIN" -version 2>&1 | head -1)"
case "$JAVA_LINE" in
    *\"17.*) ;;
    *) echo "WARN 当前 JDK 不是 17：$JAVA_LINE（基线要求 JDK 17）" >&2 ;;
esac

# ---------- 2. Android SDK ----------
SDK_DIR=""
for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
    "E:/agentHome/android-sdk" "$HOME/AppData/Local/Android/Sdk" "/c/Android/Sdk" "C:/Android/Sdk"; do
    [[ -n "$candidate" ]] || continue
    if [[ -d "$candidate/platforms" ]]; then
        SDK_DIR="$candidate"
        break
    fi
done
[[ -n "$SDK_DIR" ]] || die "未找到 Android SDK。请设置 ANDROID_HOME 指向 SDK 根目录。"

if [[ ! -d "$SDK_DIR/platforms/android-37" && ! -d "$SDK_DIR/platforms/android-37.0" ]]; then
    die "Android SDK 缺少 platforms/android-37（当前：$SDK_DIR）。用 sdkmanager 安装 platforms;android-37。"
fi

# 本机私有配置，不入库：按探测结果重写，保证可重复
printf 'sdk.dir=%s\n' "$SDK_DIR" > local.properties

# ---------- 3. 构建 ----------
[[ -f "$ROOT/gradlew" ]] || die "缺少 gradle wrapper（gradlew）。不要改用本机全局 gradle。"

WRAPPER_VERSION="$(sed -n 's|^distributionUrl=.*gradle-\(.*\)-bin.zip$|\1|p' gradle/wrapper/gradle-wrapper.properties)"
echo "INFO Gradle wrapper：${WRAPPER_VERSION:-未知}"
echo "INFO Android SDK：$SDK_DIR"
echo "INFO JDK：$JAVA_LINE"

"$ROOT/gradlew" --console=plain :app:assembleDebug

# ---------- 4. 输出 APK 绝对路径 ----------
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$APK" ]] || die "构建结束但没有产出 APK：$APK"

if command -v cygpath >/dev/null 2>&1; then
    APK_PRINT="$(cygpath -w "$APK")"
else
    APK_PRINT="$APK"
fi

echo "APK: $APK_PRINT"
