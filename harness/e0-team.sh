#!/usr/bin/env bash
#
# e0-team.sh — E0 组队基线实验（三进程），判定「做不做显式分片」
#
#   用法：bash harness/e0-team.sh
#   环境变量（不给就是本轮之前的原行为）：
#     E0_SUPERSEED=0|1          做种者是否开 BEP 16 super-seeding（默认 0）
#     E0_ART=<dir>              日志与载荷落哪个目录（默认 artifacts/e0）
#     E0_KEEP=0                 跑完就删掉 1.5 GiB 载荷（默认 1 = 留着，与之前一致）
#     E0_FILE_MB=<n>            文件大小 MiB（默认 512，只给冒烟用）
#     E0_WAIT_MS=<ms>           等下载者收工的超时（默认 600000）
#   装置：S 做种者（512 MiB / 上行限速 2 MiB/s）+ C1、C2 同时下载，两个下载者靠内核的
#         「每成员一次拨号」互连（GroupRuntime.connectSeeders）。
#   读数：D = S 的 payload_uploaded ÷ F，T_all = 第一个下载者开始 → 最后一个下载者 100%。
#   判定：D < 1.25 → 只做通告、不做分片；D ≥ 1.25 → rarest-first 没去重，重估分片。
#         每个成员的 numPeers 必须 ≥ 2（做种者 + 队友），否则该轮作废。
#
# 产物：artifacts/e0/log-S.log / log-C1.log / log-C2.log（每行带 run=<runId> 前缀）
#       artifacts/e0/markers/{done-C1.txt,done-C2.txt,stop.txt}
# 磁盘：S 的载荷 512 MiB + 两个下载各 512 MiB = 1.5 GiB，跑完留在 artifacts/e0/（下一轮开始时清掉；
#       E0_KEEP=0 则跑完就清）。
#
# 这个脚本不进 scripts/（那个目录一人一文件），它是 harness 自己的装置。
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

CORE_LIBS="$ROOT/core/build/libs"
M1_LIBS="$ROOT/core/build/m1-libs"
M1_NATIVE="$ROOT/core/build/m1-native/libtorrent4j.dll"
HARNESS_CLASSES="$ROOT/harness/build/classes"
# 输出目录可换（e0-superseed.sh 用它把两臂的日志分开放），默认还是 E0 的老地方
ART="${E0_ART:-$ROOT/artifacts/e0}"
# BEP 16 开关：1 = 做种者开 super-seeding，0 = 原样（默认）
SUPERSEED="${E0_SUPERSEED:-0}"
# 跑完是否留着 1.5 GiB 的载荷：1 = 留（E0 老行为），0 = 删干净
KEEP="${E0_KEEP:-1}"

LOG_S="$ART/log-S.log"
LOG_C1="$ART/log-C1.log"
LOG_C2="$ART/log-C2.log"

# 装置口径 512 MiB；E0_FILE_MB 只用于快速调试（D 与 F 无关，判定阈值不变）
FILE_MB="${E0_FILE_MB:-512}"
FILE_BYTES="$((FILE_MB * 1024 * 1024))"
WAIT_MS="${E0_WAIT_MS:-600000}"

w() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}
die() { echo "FATAL: $*" >&2; echo "FAIL"; exit 1; }

PID_S=""; PID_C1=""; PID_C2=""
RUN_ID="$(date +%Y%m%dT%H%M%S)-$$-$RANDOM"
cleanup() {
  for p in "$PID_C1" "$PID_C2" "$PID_S"; do
    if [ -n "$p" ] && kill -0 "$p" 2>/dev/null; then
      kill "$p" 2>/dev/null
      sleep 0.3
      kill -9 "$p" 2>/dev/null
    fi
  done
}
payload_cleanup() {
  rm -rf "$ART/s-work" "$ART/s-data" "$ART/c1-work" "$ART/c1-data" "$ART/c2-work" "$ART/c2-data" "$ART/markers"
}

trap cleanup EXIT

command -v java  >/dev/null 2>&1 || die "java not on PATH"
command -v javac >/dev/null 2>&1 || die "javac not on PATH"

echo "=== E0 team baseline ================================================"
echo "root      : $ROOT"
echo "run_id    : $RUN_ID"
echo "F         : $FILE_BYTES bytes (${FILE_MB} MiB)"
echo "superseed : $SUPERSEED (1 = 做种者开 BEP 16)"

# ---------------------------------------------------------------- 构建
( cd "$ROOT" && ./gradlew --console=plain -q :core:jar :core:m1Deps :core:m1Native ) \
  || die "gradle :core:jar / m1Deps / m1Native failed"

CORE_JAR="$(ls "$CORE_LIBS"/*.jar 2>/dev/null | head -1)"
[ -n "$CORE_JAR" ] || die "core jar not found"
[ -f "$M1_NATIVE" ] || die "libtorrent4j.dll not extracted"

LIB_CP=""
for j in "$M1_LIBS"/*.jar; do
  if [ -f "$j" ]; then LIB_CP="$LIB_CP;$(w "$j")"; fi
done
JAVAC_CP="$(w "$CORE_JAR")$LIB_CP"
CP="$JAVAC_CP;$(w "$HARNESS_CLASSES")"

rm -rf "$HARNESS_CLASSES"
mkdir -p "$HARNESS_CLASSES"
ARG_FILE="$ART/harness-sources.txt"
mkdir -p "$ART"
: > "$ARG_FILE"
while IFS= read -r src; do printf '"%s"\n' "$(w "$src")" >> "$ARG_FILE"; done < <(find "$ROOT/harness/src" -name '*.java')
javac -encoding UTF-8 -cp "$JAVAC_CP" -d "$(w "$HARNESS_CLASSES")" "@$(w "$ARG_FILE")" \
  || die "javac harness failed"

JAVA_ARGS=(-Dlibtorrent4j.jni.path="$(w "$M1_NATIVE")" -Dfile.encoding=UTF-8
           -Dm1.runid="$RUN_ID" -Dm1.waitMs="$WAIT_MS" -cp "$CP")

# ---------------------------------------------------------------- 清理
for d in s-work s-data c1-work c1-data c2-work c2-data markers; do
  rm -rf "$ART/$d" || die "cleanup failed: $ART/$d"
  [ -e "$ART/$d" ] && die "cleanup failed: $ART/$d still exists"
done
mkdir -p "$ART/s-work" "$ART/s-data" "$ART/c1-work" "$ART/c1-data" "$ART/c2-work" "$ART/c2-data" "$ART/markers" \
  || die "mkdir failed"

# ---------------------------------------------------------------- S
echo "--- run : S (seed 512 MiB, upload 2 MiB/s) -------------------------"
java "${JAVA_ARGS[@]}" app.huchuan.harness.E0Team S \
  "$(w "$ART/s-work")" "$(w "$ART/s-data")" \
  msg_port=47300 bt_port=53413 name=E0_S "file_mb=$FILE_MB" "superseed=$SUPERSEED" markers="$(w "$ART/markers")" \
  >> "$LOG_S" 2>&1 &
PID_S=$!

TAIL8=""; S_PORT=""; FILE_LINE=""
for _ in $(seq 1 1200); do
  TAIL8="$(grep -h "^run=$RUN_ID M1 S invite_tail8=" "$LOG_S" 2>/dev/null | head -1 | cut -d= -f3)"
  S_PORT="$(grep -h "^run=$RUN_ID M1 S net_ports " "$LOG_S" 2>/dev/null | head -1 | grep -o 'msg_port=[0-9]*' | cut -d= -f2)"
  FILE_LINE="$(grep -h "^run=$RUN_ID M1 S file_shared " "$LOG_S" 2>/dev/null | head -1)"
  if [ -n "$TAIL8" ] && [ -n "$S_PORT" ] && [ -n "$FILE_LINE" ]; then break; fi
  kill -0 "$PID_S" 2>/dev/null || break
  sleep 0.2
done
[ -n "$TAIL8" ] && [ -n "$S_PORT" ] && [ -n "$FILE_LINE" ] \
  || { tail -20 "$LOG_S"; die "S did not produce tail8 / msg_port / file_shared"; }
echo "S tail8   : $TAIL8   msg_port: $S_PORT"
echo "S file    : $FILE_LINE"

READY=""
for _ in $(seq 1 1200); do
  READY="$(grep -h "^run=$RUN_ID M1 S seeding_ready ok=true" "$LOG_S" 2>/dev/null | head -1)"
  [ -n "$READY" ] && break
  kill -0 "$PID_S" 2>/dev/null || break
  sleep 0.2
done
[ -n "$READY" ] || { tail -20 "$LOG_S"; die "S never reached seeding_ready"; }
echo "S ready   : $READY"

# ---------------------------------------------------------------- C1 / C2 同时下载
echo "--- run : C1 + C2 (download the same file concurrently) -------------"
java "${JAVA_ARGS[@]}" app.huchuan.harness.E0Team C1 \
  "$(w "$ART/c1-work")" "$(w "$ART/c1-data")" \
  "tail8=$TAIL8" "manual=127.0.0.1:$S_PORT" \
  msg_port=47301 bt_port=53513 name=E0_C1 markers="$(w "$ART/markers")" \
  >> "$LOG_C1" 2>&1 &
PID_C1=$!

java "${JAVA_ARGS[@]}" app.huchuan.harness.E0Team C2 \
  "$(w "$ART/c2-work")" "$(w "$ART/c2-data")" \
  "tail8=$TAIL8" "manual=127.0.0.1:$S_PORT" \
  msg_port=47302 bt_port=53613 name=E0_C2 markers="$(w "$ART/markers")" \
  >> "$LOG_C2" 2>&1 &
PID_C2=$!

wait_pid() { # wait_pid <pid> <seconds>
  local pid=$1 secs=$2 i
  for ((i = 0; i < secs * 5; i++)); do
    kill -0 "$pid" 2>/dev/null || return 0
    sleep 0.2
  done
  return 1
}

wait_pid "$PID_C1" 900; RC_C1=$?
wait_pid "$PID_C2" 900; RC_C2=$?
wait_pid "$PID_S" 300; RC_S=$?
for p in "$PID_C1" "$PID_C2" "$PID_S"; do kill -9 "$p" 2>/dev/null; done
PID_S=""; PID_C1=""; PID_C2=""

echo "C1 exit   : $RC_C1    C2 exit: $RC_C2    S exit: $RC_S"

# ---------------------------------------------------------------- 读数
echo
echo "=== metrics =========================================================="
for r in C1 C2; do
  grep -h "^run=$RUN_ID M1 $r \(download_done\|transfer_stats\|progress pct=\|joined\)" "$ART/log-$r.log" 2>/dev/null | tail -6 | sed 's/^/  /'
done
grep -h "^run=$RUN_ID M1 S \(team_metric\|transfer_stats\|peer_progress\|superseed\)" "$LOG_S" 2>/dev/null | sed 's/^/  /'

METRIC="$(grep -h "^run=$RUN_ID M1 S team_metric " "$LOG_S" 2>/dev/null | head -1)"
echo
if [ -z "$METRIC" ]; then
  echo "team_metric 行缺失（S 没走到读数）"
  echo "FAIL"
  exit 1
fi
echo "METRIC: ${METRIC#*team_metric }"
if [ "$KEEP" != "1" ]; then
  payload_cleanup
  echo "cleaned   : $ART 下的载荷已删（E0_KEEP=0）"
fi
if printf '%s' "$METRIC" | grep -q ' valid=true'; then
  echo "PASS"
  exit 0
fi
echo "FAIL (round void: 有成员的 numPeers < 2 或没读到字节)"
exit 1
