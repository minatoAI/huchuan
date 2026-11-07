#!/usr/bin/env bash
# 互传发版：把 Android APK 与 Windows 绿色版收进 dist/release/<version>/，
# 出 SHA256SUMS 与一份简短说明，最后打印一条 gh release create 命令。
#
#   bash scripts/release.sh 0.1.0               两边都构建（需要 Android SDK 与 MSVC）
#   bash scripts/release.sh 0.1.0 --only apk    只做 APK（没有 MSVC 的机器）
#   bash scripts/release.sh 0.1.0 --only win    只做 Windows 包（没有 Android SDK 的机器）
#   bash scripts/release.sh 0.1.0 --skip-build  不构建，直接收已有产物（app/build 与 dist/Huchuan）
#   bash scripts/release.sh 0.1.0 --dry-run     只打印将要做什么
#
# 这个脚本不联网、不建 tag、不推送、不调用 gh。发布是人的动作。
# APK 的签名归 scripts/build-android-release.sh（release keystore），见 docs/release-signing.md；
# 那个脚本不存在时退回 debug 包，并在终端与 NOTES.md 里写明「不是发布签名」。
#
# 产物：dist/release/<version>/
#   Huchuan-<version>-arm64-v8a.apk
#   Huchuan-windows-x64.zip          解压后是 Huchuan/{Huchuan.exe, daemon.jar, runtime/}
#   SHA256SUMS
#   NOTES.md                         发布页正文（--notes-file 用）
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

die() { printf '\n[x] 失败：%s\n' "$*" >&2; exit 1; }
step() { printf '\n[>] %s\n' "$*"; }
ok() { printf '[ok] %s\n' "$*"; }
warn() { printf '[!] %s\n' "$*" >&2; }
mib() { awk -v b="$1" 'BEGIN{printf "%.1f MB", b/1048576}'; }
bytes_of() { stat -c %s "$1" 2>/dev/null || echo 0; }
has() { command -v "$1" >/dev/null 2>&1; }
winpath() { if has cygpath; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

usage() { sed -n '2,19p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

# ---------------------------------------------------------------- 参数
VERSION=""
ONLY=""
SKIP_BUILD=0
DRY_RUN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --only)   [ $# -ge 2 ] || die "--only 后面要跟 apk 或 win"; ONLY="$2"; shift 2 ;;
    --only=*) ONLY="${1#--only=}"; shift ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --dry-run)    DRY_RUN=1; shift ;;
    -h|--help)    usage; exit 0 ;;
    -*) die "未知参数：$1（可用：--only apk|win / --skip-build / --dry-run / --help）" ;;
    *)  [ -z "$VERSION" ] || die "多余的参数：$1"; VERSION="$1"; shift ;;
  esac
done
[ -n "$VERSION" ] || die "缺少版本号。用法：bash scripts/release.sh 0.1.0 [--only apk|win] [--skip-build] [--dry-run]"
printf '%s' "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$' \
  || die "版本号要写成 1.2.3 或 1.2.3-rc1（当前：$VERSION）"
case "$ONLY" in ""|apk|win) ;; *) die "--only 只接受 apk 或 win（当前：$ONLY）" ;; esac

TAG="v$VERSION"
OUT="dist/release/$VERSION"
APK_NAME="Huchuan-$VERSION-arm64-v8a.apk"
ZIP_NAME="Huchuan-windows-x64.zip"

# 版本号与 app/build.gradle.kts 不一致时，手机上显示的版本会跟发布页对不上。
GRADLE_VNAME="$(sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\(.*\)".*/\1/p' app/build.gradle.kts | head -1)"
GRADLE_VCODE="$(sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)"

COMMIT="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
DIRTY=""
[ -n "$(git status --porcelain --untracked-files=no 2>/dev/null)" ] && DIRTY="（工作区有未提交改动）"

step "发版 $TAG → $OUT/"
printf '    提交 %s%s\n' "$COMMIT" "$DIRTY"
printf '    版本目录 versionName=%s versionCode=%s\n' "${GRADLE_VNAME:-?}" "${GRADLE_VCODE:-?}"
if [ -n "$GRADLE_VNAME" ] && [ "$GRADLE_VNAME" != "$VERSION" ]; then
  warn "版本号不一致：app/build.gradle.kts 是 $GRADLE_VNAME，本次发 $VERSION。"
  warn "app/ 不归这个脚本改；要么把 versionName 一起改掉，要么换个版本号发。"
fi

if [ "$DRY_RUN" = "1" ]; then
  step "dry-run：不构建、不写文件"
  [ "$ONLY" != "win" ] && echo "    会产出 $OUT/$APK_NAME"
  [ "$ONLY" != "apk" ] && echo "    会产出 $OUT/$ZIP_NAME"
  echo "    会产出 $OUT/SHA256SUMS、$OUT/NOTES.md"
  echo "    会打印 gh release create $TAG ... 一行（不执行）"
  exit 0
fi

mkdir -p "$OUT" || die "建目录失败：$OUT"

# ---------------------------------------------------------------- Android
APK_SIGNER=""
APK_DEBUG=0
if [ "$ONLY" != "win" ]; then
  if [ "$SKIP_BUILD" = "1" ]; then
    step "APK：跳过构建（--skip-build）"
  elif [ -f "$ROOT/scripts/build-android-release.sh" ]; then
    step "打 release APK（scripts/build-android-release.sh，release keystore 签名）"
    bash "$ROOT/scripts/build-android-release.sh" || die "scripts/build-android-release.sh 失败"
  else
    warn "没有 scripts/build-android-release.sh，退回 scripts/build-android.sh 的 debug 包。"
    warn "debug 包能装能跑，但不是发布签名；要么补签名脚本（docs/release-signing.md），"
    warn "要么在发布说明里写清楚这是内测包。"
    step "打 debug APK（scripts/build-android.sh）"
    bash "$ROOT/scripts/build-android.sh" || die "scripts/build-android.sh 失败"
  fi

  # 顺序：release 的标准产物 → release 目录里其他 apk（旧的 unsigned 之类）→ debug。
  APK_PATH=""
  for cand in "$ROOT/app/build/outputs/apk/release/app-release.apk" \
              "$(ls -t app/build/outputs/apk/release/*.apk 2>/dev/null | head -1)" \
              "$ROOT/app/build/outputs/apk/debug/app-debug.apk"; do
    [ -n "$cand" ] && [ -f "$cand" ] && { APK_PATH="$cand"; break; }
  done
  [ -n "$APK_PATH" ] && [ -f "$APK_PATH" ] || die "找不到 APK（app/build/outputs/apk/{release,debug}/*.apk）"
  ok "APK：$APK_PATH（$(mib "$(bytes_of "$APK_PATH")")）"

  case "$APK_PATH" in
    */release/*) ;;
    *) APK_DEBUG=1
       warn "这份 APK 来自 debug 变体，发布说明里会写明「debug 签名」。" ;;
  esac

  cp -f "$APK_PATH" "$OUT/$APK_NAME" || die "拷 APK 失败"

  # 签名判定：apksigner 在 SDK 的 build-tools 里。找不到就只告警；
  # 找得到就必须验过——release 变体在缺 keystore 时按设计不签名（docs/release-signing.md），
  # 没签名的包在手机上根本装不上，发出去等于白跑一轮。
  SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -z "$SDK_DIR" ] && [ -f local.properties ]; then
    SDK_DIR="$(sed -n 's/^sdk.dir=//p' local.properties | head -1)"
  fi
  SIGNER_BIN=""
  if [ -n "$SDK_DIR" ]; then
    SIGNER_BIN="$(ls -t "$SDK_DIR"/build-tools/*/apksigner.bat "$SDK_DIR"/build-tools/*/apksigner 2>/dev/null | head -1)"
  fi
  if [ -n "$SIGNER_BIN" ]; then
    if ! "$SIGNER_BIN" verify "$(winpath "$OUT/$APK_NAME")" >/dev/null 2>&1; then
      die "APK 没有有效签名（$OUT/$APK_NAME）。缺 keystore 时 release 变体按设计不签名，见 docs/release-signing.md；\
   内测要装的话用 scripts/build-android.sh 打 debug 包，别拿未签名包发。"
    fi
    CERT="$("$SIGNER_BIN" verify --print-certs "$(winpath "$OUT/$APK_NAME")" 2>&1 \
            | grep -m1 'Signer #1 certificate DN')"
    if [ -n "$CERT" ]; then
      APK_SIGNER="${CERT#*: }"
      case "$APK_SIGNER" in
        *"Android Debug"*) APK_DEBUG=1 ;;
      esac
      ok "签名证书：$APK_SIGNER"
    else
      warn "apksigner 验过了，但没打印证书 DN。"
    fi
  else
    warn "没找到 apksigner（SDK build-tools），跳过签名校验。"
  fi
fi

# ---------------------------------------------------------------- Windows
if [ "$ONLY" != "apk" ]; then
  PKG="$ROOT/dist/Huchuan"
  if [ "$SKIP_BUILD" = "1" ]; then
    step "Windows 包：跳过构建（--skip-build）"
  else
    step "打 Windows 绿色版（scripts/package-windows.sh）"
    bash "$ROOT/scripts/package-windows.sh" || die "scripts/package-windows.sh 失败"
  fi
  for f in Huchuan.exe daemon.jar runtime/bin/javaw.exe; do
    [ -f "$PKG/$f" ] || die "绿色版缺 $f（$PKG）。先跑 bash scripts/package-windows.sh"
  done

  ZIP="$OUT/$ZIP_NAME"
  ZIP_ABS="$(cd "$(dirname "$ZIP")" && pwd)/$ZIP_NAME"   # 打包要 cd 进 dist/，路径必须是绝对的
  rm -f "$ZIP"
  step "压缩 dist/Huchuan → $ZIP_NAME（包内顶层目录 Huchuan/）"
  # 优先 zip（Linux/macOS）；Windows 上没有 zip 就用 jar —— JDK 一定在，而且它写出的
  # 条目分隔符固定是 "/"。刻意不用 PowerShell 的 CreateFromDirectory：Windows PowerShell 5.1
  # 走 .NET Framework，条目名会是 Huchuan\Huchuan.exe 这种反斜杠，在 macOS/Linux 上解开是一坨怪文件。
  if has zip; then
    ( cd "$ROOT/dist" && zip -r -q -9 "$(winpath "$ZIP_ABS")" Huchuan ) || die "zip 失败"
  elif has jar; then
    ( cd "$ROOT/dist" && jar cfM "$(winpath "$ZIP_ABS")" Huchuan ) || die "jar 打包失败"
  else
    die "没有可用的打包工具（zip 与 jar 都没有；jar 随 JDK 17 一起装）"
  fi
  [ -s "$ZIP" ] || die "zip 是空的：$ZIP"

  # 两条断言：顶层目录对、条目分隔符是正斜杠。
  # 先把清单读进变量再匹配：unzip -l | grep -q 会因为 grep 提前退出、unzip 吃到 SIGPIPE，
  # 配上 pipefail 就成了随机失败。
  # 只要文件名清单（-Z1），不要 unzip -l 的表头：表头里有 Windows 路径，会误报。
  if has unzip; then ZIP_NAMES="$(unzip -Z1 "$ZIP")"; else ZIP_NAMES="$(jar tf "$ZIP")"; fi
  [ -n "$ZIP_NAMES" ] || die "读不出 zip 内容：$ZIP"
  case "$ZIP_NAMES" in
    *"Huchuan/Huchuan.exe"*) ;;
    *) die "zip 里没有 Huchuan/Huchuan.exe（解压出来是散的）" ;;
  esac
  case "$ZIP_NAMES" in
    *'\'*) die "zip 里有反斜杠条目（Huchuan\\...），macOS/Linux 解压会出错，换 zip 或 jar 重打" ;;
  esac
  ok "$ZIP_NAME（$(mib "$(bytes_of "$ZIP")")）"
fi

# ---------------------------------------------------------------- 许可文本
# BSD-3 / MIT / OFL 都要求二进制分发时随附声明（THIRD_PARTY_NOTICES.md 的「分发义务」一节）。
step "收许可文本"
cp -f LICENSE "$OUT/LICENSE.txt" || die "拷 LICENSE 失败"
cp -f THIRD_PARTY_NOTICES.md "$OUT/THIRD_PARTY_NOTICES.md" || die "拷 THIRD_PARTY_NOTICES.md 失败"
ok "LICENSE.txt、THIRD_PARTY_NOTICES.md"

# ---------------------------------------------------------------- 说明
step "写 NOTES.md"
{
  echo "# 互传 $VERSION"
  echo
  echo "- 构建时间：$(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "- 提交：$COMMIT$DIRTY"
  echo "- 平台：Android arm64-v8a / Windows x64"
  echo
  echo "## 文件"
  echo
  echo "| 文件 | 大小 | SHA-256 |"
  echo "| --- | ---: | --- |"
  for f in "$OUT/$APK_NAME" "$OUT/$ZIP_NAME"; do
    [ -f "$f" ] || continue
    echo "| $(basename "$f") | $(mib "$(bytes_of "$f")") | $(sha256sum "$f" | cut -d' ' -f1) |"
  done
  echo
  echo "全量校验清单见同目录的 SHA256SUMS；许可与第三方声明见同目录 LICENSE.txt 与 THIRD_PARTY_NOTICES.md。"
  echo
  echo "## 装法"
  echo
  echo "- Android：手机上打开 APK，允许「安装未知应用」后安装。Android 17 首次启动要授权「本地网络」，"
  echo "  不给这个权限内核起不来，界面会停在未启动状态。"
  echo "- Windows：解压后双击 Huchuan\\Huchuan.exe，不需要装 Java；需要 WebView2 Runtime"
  echo "  （Windows 11 自带，Windows 10 可能要装一次）。"
  echo
  echo "## 已知限制"
  echo
  if [ "$APK_DEBUG" = "1" ]; then
    echo "- APK 是 debug 签名（${APK_SIGNER:-debug keystore}），只适合内部测试，不是发布签名。"
  elif [ -n "$APK_SIGNER" ]; then
    echo "- APK 签名证书：$APK_SIGNER。首次安装认这个证书；换证书覆盖安装会失败，要先卸载。"
  fi
  echo "- APK 只含 arm64-v8a，没有 x86_64 模拟器包与 32 位包。"
  echo "- Windows 包没有代码签名，SmartScreen 会拦一次，选「仍要运行」。"
  echo "- 验证范围：Android 13（API 33）与 Android 17（API 37）真机、Windows 11 x64；"
  echo "  其他 Android 版本、Windows 10、ARM Windows 未实测。"
  echo "- 跨网传输取决于两端 NAT 类型，没做过真实跨网实测，见仓库 README 的隐私边界。"
} > "$OUT/NOTES.md" || die "写 NOTES.md 失败"

# ---------------------------------------------------------------- 校验清单
step "写 SHA256SUMS"
(
  cd "$OUT" || exit 1
  rm -f SHA256SUMS
  for f in "$APK_NAME" "$ZIP_NAME" LICENSE.txt THIRD_PARTY_NOTICES.md; do
    [ -f "$f" ] && sha256sum "$f" >> SHA256SUMS
  done
  [ -s SHA256SUMS ] || exit 1
) || die "写 SHA256SUMS 失败"

# ---------------------------------------------------------------- 汇报
step "产物"
for f in "$OUT"/*; do printf '    %-40s %s\n' "$(basename "$f")" "$(mib "$(bytes_of "$f")")"; done

ASSETS=""
for f in "$OUT/$APK_NAME" "$OUT/$ZIP_NAME" "$OUT/SHA256SUMS" "$OUT/LICENSE.txt" "$OUT/THIRD_PARTY_NOTICES.md"; do
  [ -f "$f" ] && ASSETS="$ASSETS $f"
done
[ -n "${ASSETS// /}" ] || die "没有可发布的文件"

printf '\n============ 发布命令（复制到终端执行，本脚本不执行）============\n'
echo "gh release create $TAG$ASSETS --title \"互传 $VERSION\" --notes-file $OUT/NOTES.md --draft"
printf '\n先 --draft 看一眼，再 gh release edit %s --draft=false 转正。\n' "$TAG"
printf 'CI 已经建过 %s 的 draft 时，改用 gh release upload %s <文件> --clobber。\n' "$TAG" "$TAG"
