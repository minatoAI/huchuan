#!/usr/bin/env bash
# Huchuan Windows 绿色版打包：daemon.jar + Huchuan.exe + jlink 精简 JRE。
#
#   bash scripts/package-windows.sh               全流程（jar → exe → jlink → 组装 → 冒烟 → 报告）
#   bash scripts/package-windows.sh --skip-exe    复用 windows/build/Huchuan.exe，不重新编译
#   bash scripts/package-windows.sh --skip-jar    复用 daemon/build/dist/daemon.jar
#   bash scripts/package-windows.sh --clean       先删掉 dist/
#
# 产物（拷走就能跑，目标机不需要装 Java）：
#   dist/runtime/                jlink 出来的精简 JRE（只含 daemon 用到的模块）
#   dist/Huchuan/Huchuan.exe     壳
#   dist/Huchuan/daemon.jar      同一份 jar（壳在 exe 同级优先找它）
#   dist/Huchuan/runtime/        上面那份 runtime 的副本（壳在 exe 同级优先用它）
#
# 为什么必须在这里做冒烟：jdeps 只看得见字节码里的模块引用，看不见 Security provider
# 这类运行期才发现的东西（core 生成邀请码要用 EC KeyPairGenerator，少 jdk.crypto.ec 时
# 内核启动会直接抛 NoSuchAlgorithmException）。所以打包脚本自己用产出的 runtime 起一次
# daemon 并等 /api/health 回 ok，模块少一个就当场失败，而不是等用户双击才发现。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WIN="$ROOT/windows"
BUILD="$WIN/build"
EXE="$BUILD/Huchuan.exe"
JAR="$ROOT/daemon/build/dist/daemon.jar"
DIST="$ROOT/dist"
RUNTIME="$DIST/runtime"
PKG="$DIST/Huchuan"
ART="$ROOT/artifacts/windows"

# 目标体积上限（MiB）：超过就说明模块挑多了，或者压缩没开。
RUNTIME_BUDGET_MB=60
# jdeps 看不到、但 daemon 运行期一定会用到的模块（每条都要写清为什么）。
EXTRA_MODULES=(
  "jdk.crypto.ec"   # core 生成邀请码/组密钥用 EC KeyPairGenerator，缺了内核起不来
)

SKIP_EXE=0
SKIP_JAR=0
CLEAN=0
for arg in "$@"; do
  case "$arg" in
    --skip-exe) SKIP_EXE=1 ;;
    --skip-jar) SKIP_JAR=1 ;;
    --clean) CLEAN=1 ;;
    -h|--help) sed -n '2,19p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "[x] 未知参数：$arg（可用：--skip-exe / --skip-jar / --clean）" >&2; exit 2 ;;
  esac
done

step() { printf '\n[>] %s\n' "$*"; }
ok()   { printf '[ok] %s\n' "$*"; }
die()  { printf '\n[x] 失败：%s\n' "$*" >&2; exit 1; }

command -v cygpath >/dev/null 2>&1 || die "需要在 MSYS/Git Bash 里运行（找不到 cygpath）。"
winpath() { cygpath -w "$1"; }
mib() { awk -v b="$1" 'BEGIN{printf "%.2f", b/1048576}'; }
bytes_of() { stat -c %s "$1" 2>/dev/null || echo 0; }

# ------------------------------------------------------------------ 0. JDK
step "检查 JDK（jlink 要用 JDK 17+）"
JAVA_BIN="$(command -v java 2>/dev/null || true)"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then JAVA_BIN="$JAVA_HOME/bin/java"; fi
[ -n "$JAVA_BIN" ] || die "找不到 java。装一个 JDK 17+ 或设好 JAVA_HOME。"
# 从 java 可执行文件反推 JDK 根目录（<jdk>/bin/java → <jdk>）
JAVA_BIN="$(cd "$(dirname "$JAVA_BIN")" && pwd)/$(basename "$JAVA_BIN")"
JDK_HOME="$(cd "$(dirname "$JAVA_BIN")/.." && pwd)"
JLINK="$JDK_HOME/bin/jlink"
JDEPS="$JDK_HOME/bin/jdeps"
[ -x "$JLINK" ] || die "找不到 jlink（$JLINK）。打包需要完整 JDK，JRE 不行。"
[ -x "$JDEPS" ] || die "找不到 jdeps（$JDEPS）。打包需要完整 JDK，JRE 不行。"

JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n1)"
[ -n "$JAVA_MAJOR" ] || die "读不出 JDK 版本（$JAVA_BIN -version）。"
if [ "$JAVA_MAJOR" -lt 17 ]; then
  die "JDK 版本太低：$JAVA_MAJOR。daemon 的字节码是 17，runtime 也必须 17+。"
fi
# --compress 的写法在 JDK 21 换过：17/18/19/20 只认 0|1|2，21+ 才认 zip-0…zip-9。
if [ "$JAVA_MAJOR" -ge 21 ]; then COMPRESS="--compress=zip-6"; else COMPRESS="--compress=2"; fi
ok "JDK $JAVA_MAJOR：$JDK_HOME（jlink 压缩参数 $COMPRESS）"

if [ "$CLEAN" = "1" ]; then
  step "清掉 dist/"
  rm -rf "$DIST"
fi
mkdir -p "$ART" "$DIST"

# ------------------------------------------------------------------ 1. daemon.jar
if [ "$SKIP_JAR" = "1" ] && [ -f "$JAR" ]; then
  step "daemon.jar：复用（--skip-jar）"
else
  step "构建 daemon.jar（:daemon:daemonJar）"
  ( cd "$ROOT" && ./gradlew --console=plain -q :daemon:daemonJar ) || die "gradle :daemon:daemonJar 失败"
fi
[ -f "$JAR" ] || die "没有 daemon.jar：$JAR"
JAR_BYTES=$(bytes_of "$JAR")
ok "daemon.jar：$(mib "$JAR_BYTES") MiB（$JAR_BYTES 字节）"

# ------------------------------------------------------------------ 2. Huchuan.exe
NEED_EXE=0
if [ ! -f "$EXE" ]; then
  NEED_EXE=1
elif [ -n "$(find "$WIN/src" "$WIN/resources" "$WIN/CMakeLists.txt" -newer "$EXE" -print -quit 2>/dev/null)" ]; then
  NEED_EXE=1   # 源码比 exe 新：装进绿色版的会是旧壳，这种「改了没反应」最难查
fi
if [ "$NEED_EXE" = "1" ] && [ "$SKIP_EXE" = "1" ]; then
  die "windows/build/Huchuan.exe 不存在或比源码旧，但传了 --skip-exe。先跑 bash scripts/build-windows.sh --no-evidence。"
fi
if [ "$NEED_EXE" = "1" ]; then
  step "构建 Huchuan.exe（bash scripts/build-windows.sh --no-evidence）"
  bash "$ROOT/scripts/build-windows.sh" --no-evidence || die "构建 Windows 壳失败"
else
  step "Huchuan.exe：已是最新，复用"
fi
[ -f "$EXE" ] || die "没有 Huchuan.exe：$EXE"
EXE_BYTES=$(bytes_of "$EXE")
ok "Huchuan.exe：$EXE_BYTES 字节"

# ------------------------------------------------------------------ 3. 模块清单
step "算模块清单（jdeps + 运行期补充）"
DEPS_MODULES="$("$JDEPS" --multi-release 17 --ignore-missing-deps --print-module-deps "$JAR" 2>/dev/null | tr -d '\r')"
[ -n "$DEPS_MODULES" ] || die "jdeps 没算出模块（输出为空）：$JAR"
MODULES="$DEPS_MODULES"
for extra in "${EXTRA_MODULES[@]}"; do
  case ",$MODULES," in
    *",$extra,"*) ;;
    *) MODULES="$MODULES,$extra" ;;
  esac
done
ok "模块：$MODULES（jdeps 给出 $DEPS_MODULES，补 ${EXTRA_MODULES[*]}）"

# ------------------------------------------------------------------ 4. jlink
step "jlink 精简 runtime → dist/runtime"
rm -rf "$RUNTIME"
"$JLINK" --add-modules "$MODULES" \
  --strip-debug --no-man-pages --no-header-files "$COMPRESS" \
  --output "$(winpath "$RUNTIME")" || die "jlink 失败（模块名拼错？JDK $JAVA_MAJOR 不支持 $COMPRESS？）"
[ -x "$RUNTIME/bin/java.exe" ] || die "jlink 没产出 runtime/bin/java.exe"
RUNTIME_BYTES=$(du -sb "$RUNTIME" | cut -f1)
RUNTIME_MB=$(mib "$RUNTIME_BYTES")
ok "runtime：$RUNTIME_BYTES 字节（$RUNTIME_MB MiB，上限 ${RUNTIME_BUDGET_MB} MiB）"
awk -v a="$RUNTIME_BYTES" -v b="$((RUNTIME_BUDGET_MB * 1048576))" 'BEGIN{exit !(a>b)}' \
  && die "runtime 超过 ${RUNTIME_BUDGET_MB} MiB（$RUNTIME_MB MiB）：模块挑多了，回来看 jdeps 的输出。"

# ------------------------------------------------------------------ 5. 组装
step "组装 dist/Huchuan"
rm -rf "$PKG"
mkdir -p "$PKG"
cp -f "$EXE" "$PKG/Huchuan.exe" || die "拷 Huchuan.exe 失败"
cp -f "$JAR" "$PKG/daemon.jar" || die "拷 daemon.jar 失败"
cp -r "$RUNTIME" "$PKG/runtime" || die "拷 runtime 失败"
[ -x "$PKG/runtime/bin/javaw.exe" ] || die "绿色版里没有 runtime/bin/javaw.exe（壳就是找这个文件）"
ok "布局：dist/Huchuan/{Huchuan.exe, daemon.jar, runtime/}"

# ------------------------------------------------------------------ 6. 冒烟
step "冒烟：用打包出来的 runtime 起一次 daemon"
SMOKE_DATA="$DIST/.smoke-data"
rm -rf "$SMOKE_DATA"
mkdir -p "$SMOKE_DATA"
SMOKE_PORT=47291
while netstat -ano 2>/dev/null | grep -qE "TCP +127\.0\.0\.1:$SMOKE_PORT +.*LISTENING"; do
  SMOKE_PORT=$((SMOKE_PORT + 1))
done
"$(winpath "$PKG/runtime/bin/java.exe")" -jar "$(winpath "$PKG/daemon.jar")" \
  --data-dir="$(winpath "$SMOKE_DATA")" --port="$SMOKE_PORT" \
  --msg-port=$((SMOKE_PORT + 1)) --bt-port=$((SMOKE_PORT + 2)) \
  --device-name=PACKAGE-SMOKE --log="$(winpath "$SMOKE_DATA")/daemon.log" \
  > "$SMOKE_DATA/stdout.txt" 2>&1 &
SMOKE_PID=$!
HEALTH=""
for _ in $(seq 1 120); do
  HEALTH=$(curl -s -m 2 "http://127.0.0.1:$SMOKE_PORT/api/health" 2>/dev/null || true)
  case "$HEALTH" in *'"status":"ok"'*) break ;; esac
  if ! kill -0 "$SMOKE_PID" 2>/dev/null; then break; fi
  sleep 0.5
done
case "$HEALTH" in
  *'"status":"ok"'*) ok "daemon 在精简 runtime 上起得来：$HEALTH" ;;
  *)
    tail -n 20 "$SMOKE_DATA/stdout.txt" >&2 2>/dev/null
    kill -9 "$SMOKE_PID" 2>/dev/null
    die "精简 runtime 起不来 daemon：模块缺了。看上面的 stdout（常见：java.security.NoSuchAlgorithmException 缺 jdk.crypto.ec）。"
    ;;
esac
curl -s -m 5 -X POST -H 'Content-Type: application/json' --data-binary '{}' \
  "http://127.0.0.1:$SMOKE_PORT/api/shutdown" > /dev/null 2>&1 || true
for _ in $(seq 1 20); do
  kill -0 "$SMOKE_PID" 2>/dev/null || break
  sleep 0.25
done
kill -9 "$SMOKE_PID" 2>/dev/null
wait "$SMOKE_PID" 2>/dev/null
rm -rf "$SMOKE_DATA"

# ------------------------------------------------------------------ 7. 体积与报告
PKG_BYTES=$(du -sb "$PKG" | cut -f1)
RUNTIME_PKG_BYTES=$(du -sb "$PKG/runtime" | cut -f1)
PKG_EXE_BYTES=$(bytes_of "$PKG/Huchuan.exe")
PKG_JAR_BYTES=$(bytes_of "$PKG/daemon.jar")
PKG_EXE_SHA=$(sha256sum "$PKG/Huchuan.exe" | cut -d' ' -f1)
PKG_JAR_SHA=$(sha256sum "$PKG/daemon.jar" | cut -d' ' -f1)

{
  echo "# Windows 绿色版打包报告"
  echo
  echo "- 生成时间：$(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "- JDK：$JAVA_MAJOR（$JDK_HOME）"
  echo "- jlink 模块：$MODULES"
  echo "  - jdeps 给出：$DEPS_MODULES"
  echo "  - 运行期补充：${EXTRA_MODULES[*]}（jdeps 看不见 Security provider）"
  echo "- 布局：dist/Huchuan/{Huchuan.exe, daemon.jar, runtime/}"
  echo
  echo "| 项 | 字节 | MiB |"
  echo "| --- | ---: | ---: |"
  echo "| Huchuan.exe | $PKG_EXE_BYTES | $(mib "$PKG_EXE_BYTES") |"
  echo "| daemon.jar | $PKG_JAR_BYTES | $(mib "$PKG_JAR_BYTES") |"
  echo "| runtime/ | $RUNTIME_PKG_BYTES | $(mib "$RUNTIME_PKG_BYTES") |"
  echo "| **合计 dist/Huchuan** | **$PKG_BYTES** | **$(mib "$PKG_BYTES")** |"
  echo
  echo "- runtime 预算：${RUNTIME_BUDGET_MB} MiB（实际 $(mib "$RUNTIME_PKG_BYTES") MiB）"
  echo "- Huchuan.exe SHA256：$PKG_EXE_SHA"
  echo "- daemon.jar SHA256：$PKG_JAR_SHA"
  echo
  echo "## 冒烟"
  echo
  echo "用 dist/Huchuan/runtime/bin/java.exe 起 daemon.jar，/api/health 返回："
  echo
  echo '    '"$HEALTH"
  echo
  echo "## 验收"
  echo
  echo '没有 Java 的机器上双击 dist/Huchuan/Huchuan.exe 即可（scripts/m1-windows.sh 里有一条'
  echo '断言用 PATH=C:\Windows\System32 + 空 JAVA_HOME 复现这个场景）。'
} > "$ART/package-report.md"

printf '\n============================ 完成 ============================\n'
printf '绿色版目录：%s\n' "$(winpath "$PKG")"
printf '  Huchuan.exe   %s 字节\n' "$PKG_EXE_BYTES"
printf '  daemon.jar    %s 字节\n' "$PKG_JAR_BYTES"
printf '  runtime/      %s 字节（%s MiB）\n' "$RUNTIME_PKG_BYTES" "$(mib "$RUNTIME_PKG_BYTES")"
printf '  合计          %s 字节（%s MiB）\n' "$PKG_BYTES" "$(mib "$PKG_BYTES")"
printf '报告：%s\n' "$ART/package-report.md"
