#!/usr/bin/env bash
#
# reconnect-race.sh — 两端同时拨号（duplicate_link 竞态）的回归断言
#
#   用法：
#     bash harness/reconnect-race.sh              # 正常臂：必须 PASS
#     bash harness/reconnect-race.sh --mutant no_link_fallback   # 反向臂：必须 FAIL
#
# 装置（三个 JVM 进程接力，两次拉起同一对 data 目录）：
#   ① bootstrap：RA 建群，RB 只拿后 8 位加入；两边各自把群落进 state.json，然后退出
#   ② race：两边带着本地库同时起来（各自 restored_groups=1、各自有重连器），
#           在同一个绝对毫秒各自拨一次，断言
#             ① 10 s 内双方 attach（P6 在线双向 true）
#             ② 之后 60 s 内没有同一 dev 的 peer_link_closed / 上下线抖动
#
# 反向臂关掉内核的撞车兜底（-Dhuchuan.mutants=no_link_fallback）：断言必须 FAIL，
# 否则这条断言没有证伪力 —— 脚本会把「反向臂没 FAIL」当成失败报出来。
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CORE_LIBS="$ROOT/core/build/libs"
M1_LIBS="$ROOT/core/build/m1-libs"
M1_NATIVE="$ROOT/core/build/m1-native/libtorrent4j.dll"
HARNESS_CLASSES="$ROOT/harness/build/classes"
ART="$ROOT/artifacts/race"

MUTANT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --mutant) MUTANT="${2:-}"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
ARM="${MUTANT:-normal}"

w() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}
die() { echo "FATAL: $*" >&2; echo "FAIL"; exit 1; }

echo "=== reconnect race ($ARM) ==========================================="
echo "root      : $ROOT"

( cd "$ROOT" && ./gradlew --console=plain -q :core:jar :core:m1Deps :core:m1Native ) \
  || die "gradle failed"
CORE_JAR="$(ls "$CORE_LIBS"/*.jar 2>/dev/null | head -1)"
[ -n "$CORE_JAR" ] || die "core jar not found"
LIB_CP=""
for j in "$M1_LIBS"/*.jar; do
  if [ -f "$j" ]; then LIB_CP="$LIB_CP;$(w "$j")"; fi
done
JAVAC_CP="$(w "$CORE_JAR")$LIB_CP"
CP="$JAVAC_CP;$(w "$HARNESS_CLASSES")"

mkdir -p "$ART"
rm -rf "$HARNESS_CLASSES"; mkdir -p "$HARNESS_CLASSES"
ARG_FILE="$ART/harness-sources.txt"
: > "$ARG_FILE"
while IFS= read -r src; do printf '"%s"\n' "$(w "$src")" >> "$ARG_FILE"; done < <(find "$ROOT/harness/src" -name '*.java')
javac -encoding UTF-8 -cp "$JAVAC_CP" -d "$(w "$HARNESS_CLASSES")" "@$(w "$ARG_FILE")" \
  || die "javac harness failed"

RUN_ID="$(date +%Y%m%dT%H%M%S)-$$-$RANDOM"
JAVA_ARGS=(-Dlibtorrent4j.jni.path="$(w "$M1_NATIVE")" -Dfile.encoding=UTF-8
           -Dm1.runid="$RUN_ID" -cp "$CP")
if [ -n "$MUTANT" ]; then JAVA_ARGS+=("-Dhuchuan.mutants=$MUTANT"); fi

rm -rf "$ART/ra-work" "$ART/ra-data" "$ART/rb-work" "$ART/rb-data"
mkdir -p "$ART/ra-work" "$ART/ra-data" "$ART/rb-work" "$ART/rb-data" || die "mkdir failed"

LOG_RA="$ART/log-RA-$ARM.log"
LOG_RB="$ART/log-RB-$ARM.log"
: > "$LOG_RA"; : > "$LOG_RB"

w() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
wait_pid() { local pid=$1 secs=$2 i; for ((i = 0; i < secs * 5; i++)); do kill -0 "$pid" 2>/dev/null || return 0; sleep 0.2; done; return 1; }

# ---------------------------------------------------------------- ① bootstrap
echo "--- phase bootstrap ------------------------------------------------"
java "${JAVA_ARGS[@]}" app.huchuan.harness.ReconnectRace RA \
  "$(w "$ART/ra-work")" "$(w "$ART/ra-data")" phase=bootstrap \
  msg_port=47500 bt_port=53713 name=RA >> "$LOG_RA" 2>&1 &
PID_RA=$!
TAIL8=""; RA_PORT=""
for _ in $(seq 1 600); do
  TAIL8="$(grep -h "^run=$RUN_ID M1 RA invite_tail8=" "$LOG_RA" 2>/dev/null | head -1 | cut -d= -f3)"
  RA_PORT="$(grep -h "^run=$RUN_ID M1 RA boot_start " "$LOG_RA" 2>/dev/null | head -1 | grep -o 'msg_port=[0-9]*' | cut -d= -f2)"
  [ -n "$TAIL8" ] && [ -n "$RA_PORT" ] && break
  kill -0 "$PID_RA" 2>/dev/null || break
  sleep 0.2
done
[ -n "$TAIL8" ] && [ -n "$RA_PORT" ] || { tail -20 "$LOG_RA"; die "RA bootstrap did not start"; }

java "${JAVA_ARGS[@]}" app.huchuan.harness.ReconnectRace RB \
  "$(w "$ART/rb-work")" "$(w "$ART/rb-data")" phase=bootstrap \
  "tail8=$TAIL8" "manual=127.0.0.1:$RA_PORT" \
  msg_port=47600 bt_port=53813 name=RB >> "$LOG_RB" 2>&1 &
PID_RB=$!
wait_pid "$PID_RB" 180; RC_RB=$?
wait_pid "$PID_RA" 180; RC_RA=$?
echo "bootstrap exits: RA=$RC_RA RB=$RC_RB  (tail8=$TAIL8 ra_msg_port=$RA_PORT)"
[ "$RC_RA" -eq 0 ] && [ "$RC_RB" -eq 0 ] || { tail -15 "$LOG_RA"; tail -15 "$LOG_RB"; die "bootstrap failed"; }

RB_PORT="$(grep -h "^run=$RUN_ID M1 RB boot_start " "$LOG_RB" 2>/dev/null | head -1 | grep -o 'msg_port=[0-9]*' | cut -d= -f2)"
[ -n "$RB_PORT" ] || die "RB msg port unknown"

# ---------------------------------------------------------------- ② race
echo "--- phase race (both dial at the same millisecond) ------------------"
DIAL_AT="$(( $(date +%s) * 1000 + 20000 ))"
echo "dial_at   : $DIAL_AT   RA peer=127.0.0.1:$RB_PORT   RB peer=127.0.0.1:$RA_PORT"

WAVES="${RACE_WAVES:-3}"
java "${JAVA_ARGS[@]}" app.huchuan.harness.ReconnectRace RA \
  "$(w "$ART/ra-work")" "$(w "$ART/ra-data")" phase=race "dial_at=$DIAL_AT" "tail8=$TAIL8" "waves=$WAVES" \
  "peer=127.0.0.1:$RB_PORT" msg_port=47500 bt_port=53713 name=RA >> "$LOG_RA" 2>&1 &
PID_RA=$!
java "${JAVA_ARGS[@]}" app.huchuan.harness.ReconnectRace RB \
  "$(w "$ART/rb-work")" "$(w "$ART/rb-data")" phase=race "dial_at=$DIAL_AT" "tail8=$TAIL8" "waves=$WAVES" \
  "peer=127.0.0.1:$RA_PORT" msg_port=47600 bt_port=53813 name=RB >> "$LOG_RB" 2>&1 &
PID_RB=$!

wait_pid "$PID_RA" 240; RC_RA=$?
wait_pid "$PID_RB" 240; RC_RB=$?
echo "race exits: RA=$RC_RA RB=$RC_RB"

echo
echo "=== evidence (this run) ============================================="
grep -h "^run=$RUN_ID M1 R[AB] \(race_start\|race_peer\|race_dial_start\|race_attached\|race_stable\|reconnect_link_race_settled\|peer_bound\|peer_link_closed\|reconnect_backoff\)" "$LOG_RA" "$LOG_RB" 2>/dev/null | sed 's/^/  /'

A_OK=1
grep -q "^run=$RUN_ID M1 RA race_attached ok=true " "$LOG_RA" || A_OK=0
grep -q "^run=$RUN_ID M1 RA race_stable ok=true "   "$LOG_RA" || A_OK=0
grep -q "^run=$RUN_ID M1 RB race_attached ok=true " "$LOG_RB" || A_OK=0
grep -q "^run=$RUN_ID M1 RB race_stable ok=true "   "$LOG_RB" || A_OK=0
# 撞车兜底必须真的走到过：只断言「还在线」不够 —— 对称竞态在 loopback 上本来就能收敛，
# 真正被验证的是「被判 duplicate 关掉的那一侧认了既有的活链路（role=in）而不是退避重拨」。
SETTLED="$(grep -hE "^run=$RUN_ID M1 R[AB] race_stable " "$LOG_RA" "$LOG_RB" 2>/dev/null \
  | grep -o 'race_settled=[0-9]*' | cut -d= -f2 | awk '{s+=$1} END {print s+0}')"
[ -z "$SETTLED" ] && SETTLED=0
echo "race_settled total: $SETTLED"
[ "$SETTLED" -ge 1 ] 2>/dev/null || A_OK=0

echo
if [ -z "$MUTANT" ]; then
  if [ "$A_OK" = "1" ] && [ "$RC_RA" -eq 0 ] && [ "$RC_RB" -eq 0 ]; then
    echo "PASS"
    exit 0
  fi
  echo "FAIL (两端没在 10 s 内 attach，或 60 s 内出现了 peer_link_closed 抖动)"
  exit 1
fi
# 反向臂：断言必须 FAIL，否则这条断言没有证伪力
if [ "$A_OK" = "1" ]; then
  echo "MUTANT DID NOT FAIL: 关掉撞车兜底之后断言仍然通过 —— 这条断言没有证伪力，必须重做"
  echo "FAIL"
  exit 1
fi
echo "PASS (mutant $MUTANT 按预期让断言 FAIL)"
exit 0
