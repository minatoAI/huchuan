#!/usr/bin/env bash
# 构建互传 Android RELEASE APK：仓库外 keystore 签名 → apksigner 验签 → 打印体积 / SHA-256 / 证书指纹。
# 成功时最后一行打印 APK 绝对路径：APK: <path>
#
# 用法：
#   scripts/build-android-release.sh                        # 构建 + 验签
#   scripts/build-android-release.sh --keystore-properties <path>
#   scripts/build-android-release.sh --no-verify            # 不跑 apksigner
#
# 依赖：JDK 17、Android SDK（platforms/android-37 + build-tools 里的 apksigner）、
#       仓库外 keystore.properties（默认 E:/agentHome/huchuan-keys/keystore.properties）。
#       签名材料怎么放、怎么备份、丢了什么后果：docs/release-signing.md。
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

winpath() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

PROPS_PATH=""
DO_VERIFY=1
while [[ $# -gt 0 ]]; do
    case "$1" in
        --keystore-properties) PROPS_PATH="${2:?缺少 properties 路径}"; shift 2 ;;
        --keystore-properties=*) PROPS_PATH="${1#*=}"; shift ;;
        --no-verify) DO_VERIFY=0; shift ;;
        -h|--help) sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "ERR 未知参数：$1" >&2; exit 2 ;;
    esac
done

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
    die "Android SDK 缺少 platforms/android-37（当前：$SDK_DIR）。"
fi

# ---------- 3. 签名材料（仓库外） ----------
# 优先级：--keystore-properties > HUCHUAN_KEYSTORE_PROPERTIES > 本机默认路径。
if [[ -z "$PROPS_PATH" ]]; then
    PROPS_PATH="${HUCHUAN_KEYSTORE_PROPERTIES:-E:/agentHome/huchuan-keys/keystore.properties}"
fi
if [[ ! -f "$PROPS_PATH" ]]; then
    die "找不到 release 签名配置：$PROPS_PATH
    release 包必须是 release keystore 签的，缺材料这里就不出包（Gradle 侧会降级成未签名包 app-release-unsigned.apk）。
    补法：docs/release-signing.md（keystore 与 keystore.properties 都放仓库外）。"
fi

# 只读路径与别名，口令不落到日志里。
STORE_FILE="$(sed -n 's/^storeFile[[:space:]]*=[[:space:]]*//p' "$PROPS_PATH" | tr -d '\r' | head -1)"
KEY_ALIAS="$(sed -n 's/^keyAlias[[:space:]]*=[[:space:]]*//p' "$PROPS_PATH" | tr -d '\r' | head -1)"
[[ -n "$STORE_FILE" ]] || die "$PROPS_PATH 里没有 storeFile。"
[[ -f "$STORE_FILE" ]] || die "keystore 不存在：$STORE_FILE（来自 $PROPS_PATH）"

# Gradle 是 Windows 进程：/e/agentHome/... 这种 MSYS 路径它不认，转成 E:/agentHome/...
PROPS_ARG="$(winpath "$PROPS_PATH" | tr '\\' '/')"

# ---------- 4. 构建 ----------
echo "INFO Gradle wrapper：$(sed -n 's|^distributionUrl=.*gradle-\(.*\)-bin.zip$|\1|p' gradle/wrapper/gradle-wrapper.properties)"
echo "INFO Android SDK：$SDK_DIR"
echo "INFO JDK：$JAVA_LINE"
echo "INFO 签名：$STORE_FILE（alias=$KEY_ALIAS，配置 $PROPS_PATH）"

"$ROOT/gradlew" --console=plain -Phuchuan.keystoreProperties="$PROPS_ARG" :app:assembleRelease

APK_DIR="$ROOT/app/build/outputs/apk/release"
APK="$APK_DIR/app-release.apk"
if [[ ! -f "$APK" ]]; then
    UNSIGNED="$(ls "$APK_DIR"/*-unsigned.apk 2>/dev/null | head -1 || true)"
    if [[ -n "$UNSIGNED" ]]; then
        die "Gradle 产出的是未签名包：$UNSIGNED
    keystore 没被用上：看构建日志里的 [签名] 警告，核 $PROPS_PATH 的 storeFile 与口令。"
    fi
    die "构建结束但没有产出 APK：$APK"
fi

# ---------- 5. 验签 + 指纹 ----------
APK_NATIVE="$(winpath "$APK")"
BYTES="$(stat -c %s "$APK")"
SHA="$(sha256sum "$APK" | cut -d' ' -f1)"

if [[ $DO_VERIFY -eq 1 ]]; then
    SIGNER_JAR="$(ls -1 "$SDK_DIR"/build-tools/*/lib/apksigner.jar 2>/dev/null | sort -V | tail -1 || true)"
    [[ -n "$SIGNER_JAR" ]] || die "Android SDK 里没有 build-tools/*/lib/apksigner.jar，无法验签（可用 --no-verify 跳过）。"
    VERIFY_OUT="$("$JAVA_BIN" -jar "$SIGNER_JAR" verify --print-certs "$APK_NATIVE" 2>&1)" || die "apksigner 验签失败：
$VERIFY_OUT"
    CERT_DN="$(printf '%s\n' "$VERIFY_OUT" | sed -n 's/.*certificate DN: *//p' | head -1)"
    CERT_SHA256="$(printf '%s\n' "$VERIFY_OUT" | sed -n 's/.*certificate SHA-256 digest: *//p' | head -1)"
    [[ -n "$CERT_SHA256" ]] || die "apksigner 没打印签名证书：
$VERIFY_OUT"
    # 32 字节 hex → AA:BB:... 便于和 keystore-info.txt 对读
    CERT_SHA256_COLON="$(printf '%s' "$CERT_SHA256" | tr 'a-f' 'A-F' | sed 's/../&:/g; s/:$//')"
    echo "SIGNER_DN: $CERT_DN"
    echo "SIGNER_SHA256: $CERT_SHA256_COLON"
    echo "INFO 这把证书指纹应与 keystore-info.txt 里的 SHA-256 完全一致。"
else
    echo "WARN 跳过 apksigner 验签（--no-verify）"
fi

echo "BYTES: $BYTES"
echo "SHA256: $SHA"
echo "APK: $APK_NATIVE"
