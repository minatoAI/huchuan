#!/usr/bin/env bash
#
# m1-headless.sh — M1 全流程无头验收（可证伪版）
#
#   bash scripts/m1-headless.sh                    # 正常验收；最后一行 PASS / FAIL，rc 0 / 1
#   M1_MUTANT=<名称> bash scripts/m1-headless.sh    # 反向测试：故意注入故障，应当 FAIL
#
# 装置：
#   A 建群 → 打印 16 字符邀请码 → 共享 32 MiB 文件做种 → 等 B 的评论
#   B 只拿后 8 位加入 → 收文件清单 → 下载 → 比对 SHA-256 → 发评论
#
# 每轮生成 runId（run=<runId> 前缀写在进程输出的每一行上），断言只认本轮的行。
# log-A / log-B / selftest.log 是追加写的、不删旧内容：上一轮的绿色证据物理上还在，
# 但没有本轮 runId 的行一律不采信 —— 旧日志无法重放成本轮的 PASS。
#
# 断言五组：
#   1. flow / sha256 / 评论 / 端口 / selftest
#   2. dht 三重：两进程都自报 dht_running=false + settings_pack 的 enable_dht=false + 采样 ≥3 且全 0
#   3. provenance：B 传输中 peer>0、B 的字节来自 libtorrent 计数、A 的 payload_uploaded、B 文件 mtime 晚于本轮开始
#   4. 装置自检：清理校验（rm -rf 失败即 FAIL）、latency 上下界、msg_port 不等、mDNS 硬断言
#   5. 补差：file.list 条目验签这条线上路径（第 5.5 段）
#      A2 先共享 33 条（>32，join.ok 快照按 §10 置空）再注入一条签名对不上的假条目；B2 加入后
#      只能靠 sync.req → file.list 补齐；B2 退出、A2 在没人收广播的时刻共享第 34 条；B2 带本地
#      库重连，第 34 条只能再从 file.list 补来。假条目是本组唯一的证伪支点：收端的条目验签一旦
#      被去掉，它就会进 B2 的清单（files/poison_present 同时变），本组立刻 FAIL。
#
# 反向测试的变异点见 docs/reviews/m1-harness-hardening.md。
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

CORE_LIBS="$ROOT/core/build/libs"
M1_LIBS="$ROOT/core/build/m1-libs"
M1_NATIVE="$ROOT/core/build/m1-native/libtorrent4j.dll"
HARNESS_CLASSES="$ROOT/harness/build/classes"
ART="$ROOT/artifacts/m1"
LOG_A="$ART/log-A.log"
LOG_B="$ART/log-B.log"
LOG_S="$ART/selftest.log"
LOG_CA="$ART/log-catchup-a.log"
LOG_CB="$ART/log-catchup-b.log"
A_SHARE="$ART/a-work/share/payload-32m.bin"
B_DOWNLOAD="$ART/b-data/downloads/payload-32m.bin"

FILE_BYTES=33554432
LATENCY_MAX_MS=3000
SELFTEST_MIN_CHECKS=40
MIN_DHT_SAMPLES=3
# 补差用例首轮共享的文件数：必须 >32，join.ok 的 files 快照才会按 §10 置空。
CATCHUP_FILES=33

MUTANT="${M1_MUTANT:-}"
WAIT_MS="${M1_WAIT_MS:-180000}"

# Windows 原生程序（java/javac）要 Windows 路径；用 mixed 形式（E:/a/b），
# 因为 javac 的 @argfile 会把反斜杠当转义符。
w() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

die() { echo "FATAL: $*" >&2; echo "FAIL"; exit 1; }

PID_A=""
PID_B=""
PID_A2=""
RC_A=-1
RC_B=-1
RC_A2=-1
RC_B2_1=-1
RC_B2_2=-1
RC_SELFTEST=-1
RUN_T0=0
cleanup() {
  if [ -n "$PID_A" ] && kill -0 "$PID_A" 2>/dev/null; then
    kill "$PID_A" 2>/dev/null
    sleep 0.3
    kill -9 "$PID_A" 2>/dev/null
  fi
  if [ -n "$PID_A2" ] && kill -0 "$PID_A2" 2>/dev/null; then
    kill "$PID_A2" 2>/dev/null
    sleep 0.3
    kill -9 "$PID_A2" 2>/dev/null
  fi
}
# 运行锁（§0 里获取）；先定义，保证任何 die 路径都能释放。
release_lock() { rm -rf "${ART}/.runlock"; }
trap 'release_lock; cleanup' EXIT

command -v java      >/dev/null 2>&1 || die "java not on PATH (JDK 17+)"
command -v javac     >/dev/null 2>&1 || die "javac not on PATH (JDK 17+)"
command -v sha256sum >/dev/null 2>&1 || die "sha256sum not on PATH (脚本要独立重算 SHA-256)"

# ------------------------------------------------------------------ 0. runId
RUN_ID="$(date +%Y%m%dT%H%M%S)-$$-$RANDOM"
printf '%s' "$RUN_ID" | grep -qE '^[0-9A-Za-z][0-9A-Za-z-]{7,63}$' || die "bad runId: $RUN_ID"
mkdir -p "$ART" || die "cannot create $ART"
for f in "$LOG_A" "$LOG_B" "$LOG_S" "$LOG_CA" "$LOG_CB"; do
  if [ -f "$f" ] && grep -q "^run=$RUN_ID " "$f" 2>/dev/null; then
    die "runId $RUN_ID already present in $f (replay guard)"
  fi
done
printf '%s\n' "$RUN_ID" > "$ART/last-runid.txt"

# 同机并发保护：两个实例会互相删 a-work/b-data、互相占端口、互相污染 mDNS 候选。
LOCK="$ART/.runlock"
if ! mkdir "$LOCK" 2>/dev/null; then
  OTHER="$(cat "$LOCK/pid" 2>/dev/null || echo "")"
  if [ -n "$OTHER" ] && kill -0 "$OTHER" 2>/dev/null; then
    die "另一个 m1-headless.sh 正在跑（pid $OTHER）。同机并发会互相破坏证据，等它结束再来。"
  fi
  rm -rf "$LOCK"
  mkdir "$LOCK" 2>/dev/null || die "cannot take run lock $LOCK"
fi
printf '%s\n' "$$" > "$LOCK/pid"

echo "=== M1 headless acceptance ==========================================="
echo "root      : $ROOT"
echo "run_id    : $RUN_ID"
echo "mutant    : ${MUTANT:-none}"

# 断言小工具：一切只看本轮 runId 的行
cnt() { # cnt <grep-pattern> <file>
  local n
  n=$(grep -c -- "$1" "$2" 2>/dev/null || true)
  printf '%s' "${n:-0}"
}
ran() { printf '^run=%s %s' "$RUN_ID" "$1"; }
fld() { # fld <file> <anchored-pattern> <field>
  grep -h -- "$2" "$1" 2>/dev/null | grep -o -- "$3=[^ ]*" | head -1 | cut -d= -f2-
}

# ------------------------------------------------------------------ 1. build core
echo "--- build :core -----------------------------------------------------"
( cd "$ROOT" && ./gradlew --console=plain -q :core:jar :core:m1Deps :core:m1Native ) \
  || die "gradle :core:jar / m1Deps / m1Native failed"

CORE_JAR="$(ls "$CORE_LIBS"/*.jar 2>/dev/null | head -1)"
[ -n "$CORE_JAR" ] || die "core jar not found in $CORE_LIBS"
[ -f "$M1_NATIVE" ] || die "libtorrent4j.dll not extracted at $M1_NATIVE"

LIB_CP=""
for j in "$M1_LIBS"/*.jar; do
  if [ -f "$j" ]; then LIB_CP="$LIB_CP;$(w "$j")"; fi
done
JAVAC_CP="$(w "$CORE_JAR")$LIB_CP"
CP="$JAVAC_CP;$(w "$HARNESS_CLASSES")"

echo "core jar  : $CORE_JAR"
echo "m1 libs   : $(ls "$M1_LIBS" | tr '\n' ' ')"
echo "native    : $M1_NATIVE"

# ------------------------------------------------------------------ 2. build harness
echo "--- build :harness (javac) ------------------------------------------"
rm -rf "$HARNESS_CLASSES"
mkdir -p "$HARNESS_CLASSES"
ARG_FILE="$ART/harness-sources.txt"
: > "$ARG_FILE"
while IFS= read -r src; do printf '"%s"\n' "$(w "$src")" >> "$ARG_FILE"; done < <(find "$ROOT/harness/src" -name '*.java')
ARG_W="@$(w "$ARG_FILE")"
javac -encoding UTF-8 -cp "$JAVAC_CP" -d "$(w "$HARNESS_CLASSES")" "$ARG_W" \
  || die "javac harness failed"

JAVA_ARGS=(-Dlibtorrent4j.jni.path="$(w "$M1_NATIVE")" -Dfile.encoding=UTF-8
           -Dm1.runid="$RUN_ID" -Dm1.waitMs="$WAIT_MS" -cp "$CP")
if [ -n "$MUTANT" ]; then
  JAVA_ARGS+=("-Dm1.mutant=$MUTANT")
  if [ "$MUTANT" = "precopy" ]; then
    JAVA_ARGS+=("-Dm1.mutant.copySrc=$(w "$A_SHARE")")
  fi
fi

# ------------------------------------------------------------------ 3. 字节层自检
echo "--- selftest : B32 / HKDF / 签名 / JSON / 帧 --------------------------"
java "${JAVA_ARGS[@]}" app.huchuan.harness.SelfTest >> "$LOG_S" 2>&1
RC_SELFTEST=$?
tail -3 "$LOG_S" | sed 's/^/  /'

# ------------------------------------------------------------------ 4. 清理（rm -rf 失败即 FAIL）
echo "--- clean : work/data dirs ------------------------------------------"
clean_work() {
  local d
  for d in a-work a-data b-work b-data a2-work a2-data b2-work b2-data; do
    rm -rf "$ART/$d"
    if [ $? -ne 0 ]; then
      echo "  cleanup: rm -rf 失败: $ART/$d" >&2
      return 1
    fi
    if [ -e "$ART/$d" ]; then
      echo "  cleanup: rm -rf 之后仍然存在: $ART/$d" >&2
      return 1
    fi
  done
  if [ -e "$ART/b-data/downloads" ]; then
    echo "  cleanup: downloads 目录残留: $ART/b-data/downloads" >&2
    return 1
  fi
  return 0
}
clean_work || die "cleanup failed: artifacts/m1 下 a-work/a-data/b-work/b-data 没删干净（多半是上一轮 java 进程还占着文件句柄）"
mkdir -p "$ART/a-work" "$ART/a-data" "$ART/b-work" "$ART/b-data" \
         "$ART/a2-work" "$ART/a2-data" "$ART/b2-work" "$ART/b2-data" \
  || die "mkdir work dirs failed"

RUN_T0="$(date +%s)"
echo "run_t0    : $RUN_T0"

# ------------------------------------------------------------------ 5. run A / B
echo "--- run   : A (create group, seed 32 MiB) ---------------------------"
java "${JAVA_ARGS[@]}" app.huchuan.harness.M1Harness A \
  "$(w "$ART/a-work")" "$(w "$ART/a-data")" \
  msg_port=47100 bt_port=51413 name=A_笔记本 group_name=老板的群 \
  >> "$LOG_A" 2>&1 &
PID_A=$!

TAIL8=""
MSG_PORT=""
for _ in $(seq 1 900); do
  TAIL8="$(grep -h "$(ran 'M1 A invite_tail8=')" "$LOG_A" 2>/dev/null | head -1 | grep -o 'tail8=[0-9A-Z]*' | cut -d= -f2)"
  MSG_PORT="$(grep -h "$(ran 'M1 A kernel_started ')" "$LOG_A" 2>/dev/null | head -1 | grep -o 'msg_port=[0-9]*' | cut -d= -f2)"
  if [ -n "$TAIL8" ] && [ -n "$MSG_PORT" ]; then break; fi
  if ! kill -0 "$PID_A" 2>/dev/null; then break; fi
  sleep 0.2
done

RUN_A_OK=1
if [ -z "$TAIL8" ] || [ -z "$MSG_PORT" ]; then
  RUN_A_OK=0
  echo "--- A log (tail) ----------------------------------------------------"
  tail -20 "$LOG_A" 2>/dev/null | sed 's/^/  /'
  echo "  本轮 runId 在 log-A 里的行数     : $(cnt "^run=$RUN_ID " "$LOG_A")"
  echo "  文件里不带本轮标记的旧证据行数   : $(cnt 'M1 A invite_tail8=' "$LOG_A")  ← 旧轮残留，本轮不得采信"
fi

if [ "$RUN_A_OK" = "1" ]; then
  echo "A tail8   : $TAIL8   msg_port: $MSG_PORT"
  echo "--- run   : B (join with tail8 only, download, comment) -------------"
  java "${JAVA_ARGS[@]}" app.huchuan.harness.M1Harness B \
    "$(w "$ART/b-work")" "$(w "$ART/b-data")" \
    "tail8=$TAIL8" "manual=127.0.0.1:$MSG_PORT" \
    msg_port=47100 bt_port=51513 name=B_台式机 \
    >> "$LOG_B" 2>&1 &
  PID_B=$!
  wait "$PID_B" 2>/dev/null
  RC_B=$?
  echo "B exit    : $RC_B"
else
  echo "B         : skipped（A 没产出 invite_tail8 / msg_port，本轮 B 不可能拿到正确证据）"
fi

for _ in $(seq 1 900); do
  if ! kill -0 "$PID_A" 2>/dev/null; then break; fi
  sleep 0.5
done
if kill -0 "$PID_A" 2>/dev/null; then
  kill "$PID_A" 2>/dev/null
  sleep 0.5
  kill -9 "$PID_A" 2>/dev/null
fi
wait "$PID_A" 2>/dev/null
RC_A=$?
PID_A=""
echo "A exit    : $RC_A"

# ------------------------------------------------------------------ 5.5 run A2 / B2：补差
# 这一段单独回答一个问题：file.add 广播之外，清单还能从哪条路进来——只有 sync.req → file.list
# （以及 join.ok 的 files 快照，>32 条时按 §10 置空）。所以这里刻意让 B2 两头都错过广播：
#   · 冷加入时 A2 已经有 34 条（33 条真 + 1 条假），快照置空，B2 只能靠 file.list 分页补齐
#   · B2 退出后 A2 才共享第 34 条真文件，广播没有接收方，B2 重连后只能再从 file.list 补
echo
echo "--- run   : A2 (share $CATCHUP_FILES files first, then wait for a reconnect) -"
java "${JAVA_ARGS[@]}" app.huchuan.harness.M1Harness A2 \
  "$(w "$ART/a2-work")" "$(w "$ART/a2-data")" \
  msg_port=47200 bt_port=52413 name=A2_笔记本 group_name=补差验证群 "files=$CATCHUP_FILES" \
  >> "$LOG_CA" 2>&1 &
PID_A2=$!

T2_TAIL8=""
T2_PORT=""
T2_POISON=""
for _ in $(seq 1 900); do
  T2_TAIL8="$(grep -h "$(ran 'M1 A2 invite_tail8=')" "$LOG_CA" 2>/dev/null | head -1 | grep -o 'tail8=[0-9A-Z]*' | cut -d= -f2)"
  T2_PORT="$(grep -h "$(ran 'M1 A2 net_ports ')" "$LOG_CA" 2>/dev/null | head -1 | grep -o 'msg_port=[0-9]*' | cut -d= -f2)"
  T2_POISON="$(grep -h "$(ran 'M1 A2 poison_injected ')" "$LOG_CA" 2>/dev/null | head -1 | grep -o 'file_id=[0-9a-f]*' | head -1 | cut -d= -f2)"
  if [ -n "$T2_TAIL8" ] && [ -n "$T2_PORT" ] && [ -n "$T2_POISON" ]; then break; fi
  if ! kill -0 "$PID_A2" 2>/dev/null; then break; fi
  sleep 0.2
done

if [ -n "$T2_TAIL8" ] && [ -n "$T2_PORT" ] && [ -n "$T2_POISON" ]; then
  echo "A2 tail8  : $T2_TAIL8   msg_port: $T2_PORT   poison_file_id: $T2_POISON"
  # phase=1：冷加入补 33 条（file.list 两页）；phase=2：带本地库重连补第 34 条。
  for phase in 1 2; do
    want=$CATCHUP_FILES
    if [ "$phase" = "2" ]; then want=$((CATCHUP_FILES + 1)); fi
    echo "--- run   : B2 phase=$phase (expect_files=$want) ---------------------"
    java "${JAVA_ARGS[@]}" app.huchuan.harness.M1Harness B2 \
      "$(w "$ART/b2-work")" "$(w "$ART/b2-data")" \
      "tail8=$T2_TAIL8" "manual=127.0.0.1:$T2_PORT" "phase=$phase" "expect_files=$want" \
      "poison=$T2_POISON" msg_port=47200 bt_port=52513 name=B2_台式机 \
      >> "$LOG_CB" 2>&1
    rc=$?
    echo "B2 p$phase exit : $rc"
    if [ "$phase" = "1" ]; then
      RC_B2_1=$rc
      # B2 退出后 A2 才看得到「离线」，才会共享第 34 条；等它落地再起 phase 2。
      for _ in $(seq 1 600); do
        grep -q "$(ran 'M1 A2 extra_shared ')" "$LOG_CA" 2>/dev/null && break
        sleep 0.2
      done
    else
      RC_B2_2=$rc
    fi
  done
else
  echo "B2        : skipped（A2 没产出 tail8 / msg_port / 伪造条目 file_id）"
fi

for _ in $(seq 1 900); do
  if ! kill -0 "$PID_A2" 2>/dev/null; then break; fi
  sleep 0.5
done
if kill -0 "$PID_A2" 2>/dev/null; then
  kill "$PID_A2" 2>/dev/null
  sleep 0.5
  kill -9 "$PID_A2" 2>/dev/null
fi
wait "$PID_A2" 2>/dev/null
RC_A2=$?
PID_A2=""
echo "A2 exit   : $RC_A2"

# ------------------------------------------------------------------ 6. 断言
echo
echo "=== assertions (只认 run=$RUN_ID 的行) ==============================="
FAILS=0
chk() { # chk <name> <1|0>
  if [ "$2" = "1" ]; then
    echo "CHECK PASS $1"
  else
    echo "CHECK FAIL $1"
    FAILS=$((FAILS + 1))
  fi
}

# --- 6.1 流程 / 身份 / 评论 -------------------------------------------
CODE="$(fld "$LOG_A" "$(ran 'M1 A invite_code=')" invite_code)"
if [ "${#CODE}" -eq 16 ] && printf '%s' "$CODE" | grep -qE '^[0-9ABCDEFGHJKMNPQRSTVWXYZ]{16}$'; then
  chk "flow.A_invite_code_16_chars ($CODE)" 1
else
  chk "flow.A_invite_code_16_chars (got '$CODE')" 0
fi

B_TAIL="$(fld "$LOG_B" "$(ran 'M1 B join_input ')" tail8)"
if [ -n "$B_TAIL" ] && [ "${#B_TAIL}" -eq 8 ] && [ "$B_TAIL" = "${CODE:8:8}" ]; then
  chk "flow.B_joined_with_tail8_only ($B_TAIL)" 1
else
  chk "flow.B_joined_with_tail8_only (tail='$B_TAIL' expected='${CODE:8:8}')" 0
fi

n=$(cnt "$(ran 'M1 B joined ')" "$LOG_B")
[ "$n" -ge 1 ] 2>/dev/null && chk "flow.B_joined (n=$n)" 1 || chk "flow.B_joined (n=$n)" 0

GID_A="$(fld "$LOG_A" "$(ran 'M1 A group_id=')" group_id)"
GID_B="$(fld "$LOG_B" "$(ran 'M1 B joined ')" gid)"
ok=0
if [ -n "$GID_A" ] && [ "$GID_A" = "$GID_B" ]; then ok=1; fi
chk "flow.same_group (A=$GID_A B=$GID_B)" $ok

n=$(cnt "$(ran "M1 A file_shared .*size=$FILE_BYTES")" "$LOG_A")
[ "$n" -ge 1 ] 2>/dev/null && chk "flow.A_shared_32MiB (n=$n)" 1 || chk "flow.A_shared_32MiB (n=$n)" 0

n=$(cnt "$(ran "M1 B file_seen .*size=$FILE_BYTES")" "$LOG_B")
[ "$n" -ge 1 ] 2>/dev/null && chk "flow.B_saw_file_32MiB (n=$n)" 1 || chk "flow.B_saw_file_32MiB (n=$n)" 0

n=$(cnt "$(ran "M1 B download_done .*bytes=$FILE_BYTES")" "$LOG_B")
[ "$n" -ge 1 ] 2>/dev/null && chk "flow.B_downloaded_32MiB (n=$n)" 1 || chk "flow.B_downloaded_32MiB (n=$n)" 0

SHA_A="$(fld "$LOG_A" "$(ran 'M1 A file_sha256=')" file_sha256)"
SHA_B="$(fld "$LOG_B" "$(ran 'M1 B download_done ')" sha256)"
ok=0
if printf '%s' "$SHA_A" | grep -qE '^[0-9a-f]{64}$' && printf '%s' "$SHA_B" | grep -qE '^[0-9a-f]{64}$' && [ "$SHA_A" = "$SHA_B" ]; then
  ok=1
fi
chk "sha256.equal ($SHA_A)" $ok

SHA_FILE_B="$(sha256sum "$B_DOWNLOAD" 2>/dev/null | cut -d' ' -f1)"
SHA_FILE_A="$(sha256sum "$A_SHARE" 2>/dev/null | cut -d' ' -f1)"
ok=0
if [ -n "$SHA_FILE_B" ] && [ -n "$SHA_FILE_A" ] && [ "$SHA_FILE_B" = "$SHA_A" ] && [ "$SHA_FILE_A" = "$SHA_A" ]; then
  ok=1
fi
chk "sha256.independent_recompute (B_file=${SHA_FILE_B:-none} A_share=${SHA_FILE_A:-none})" $ok

OP_B="$(fld "$LOG_B" "$(ran 'M1 B comment_sent ')" op_id)"
OP_LINE="$(grep -h "$(ran 'M1 A op_received ')" "$LOG_A" 2>/dev/null | tail -1)"
OP_A="$(printf '%s' "$OP_LINE" | grep -o 'op_id=[^ ]*' | head -1 | cut -d= -f2)"
LATENCY="$(printf '%s' "$OP_LINE" | grep -o 'latency_ms=[-0-9]*' | head -1 | cut -d= -f2)"
ok=0
if [ -n "$OP_B" ] && [ "$OP_B" = "$OP_A" ]; then ok=1; fi
chk "comment.same_op (B=${OP_B:-none} A=${OP_A:-none})" $ok

ok=0
if [ -n "$LATENCY" ] && [ "$LATENCY" -gt 0 ] 2>/dev/null && [ "$LATENCY" -le $LATENCY_MAX_MS ] 2>/dev/null; then
  ok=1
fi
chk "comment.latency_0_lt_x_le_3s (${LATENCY:-none}ms)" $ok

n=$(cnt "$(ran 'M1 A op_in_store=true')" "$LOG_A")
[ "$n" -ge 1 ] 2>/dev/null && chk "comment.A_in_store (n=$n)" 1 || chk "comment.A_in_store (n=$n)" 0

ok=0
if [ "$RC_A" -eq 0 ] && [ "$RC_B" -eq 0 ]; then ok=1; fi
chk "process.rc_0_0 (A=$RC_A B=$RC_B)" $ok

SC_N="$(fld "$LOG_S" "$(ran 'selftest.checks=')" selftest.checks)"
ok=0
if [ "$RC_SELFTEST" -eq 0 ] \
   && [ -n "$SC_N" ] && [ "$SC_N" -ge $SELFTEST_MIN_CHECKS ] 2>/dev/null \
   && [ "$(cnt "$(ran 'RESULT=PASS$')" "$LOG_S")" -ge 1 ] 2>/dev/null; then
  ok=1
fi
chk "selftest.byte_layer (rc=$RC_SELFTEST checks=${SC_N:-none})" $ok

A_MSG="$(fld "$LOG_A" "$(ran 'M1 A net_ports ')" msg_port)"
B_MSG="$(fld "$LOG_B" "$(ran 'M1 B net_ports ')" msg_port)"
ok=0
if [ -n "$A_MSG" ] && [ "$A_MSG" -gt 0 ] 2>/dev/null \
   && [ -n "$B_MSG" ] && [ "$B_MSG" -gt 0 ] 2>/dev/null \
   && [ "$A_MSG" != "$B_MSG" ] \
   && [ "$A_MSG" = "$MSG_PORT" ]; then
  ok=1
fi
chk "process.msg_port_differs (A=${A_MSG:-none} B=${B_MSG:-none} dialed=${MSG_PORT:-none})" $ok

# --- 6.2 dht 三重 ------------------------------------------------------
A_PROBE="$(grep -h "$(ran 'M1 A bt_probe ')" "$LOG_A" 2>/dev/null | head -1)"
B_PROBE="$(grep -h "$(ran 'M1 B bt_probe ')" "$LOG_B" 2>/dev/null | head -1)"

probe_ok() {
  printf '%s' "$1" | grep -q 'source=libtorrent_session' || return 1
  printf '%s' "$1" | grep -q ' enable_dht=false'         || return 1
  printf '%s' "$1" | grep -q ' dht_running=false'        || return 1
  return 0
}
if probe_ok "$A_PROBE"; then chk "dht.a.enable_dht_false_and_running_false ($A_PROBE)" 1; else chk "dht.a.enable_dht_false_and_running_false (got '$A_PROBE')" 0; fi
if probe_ok "$B_PROBE"; then chk "dht.b.enable_dht_false_and_running_false ($B_PROBE)" 1; else chk "dht.b.enable_dht_false_and_running_false (got '$B_PROBE')" 0; fi

A_SAMPLES=$(cnt "$(ran 'M1 A dht_sample ')" "$LOG_A")
A_BAD=$(grep -h "$(ran 'M1 A dht_sample ')" "$LOG_A" 2>/dev/null | grep -cvE ' dht_nodes=0 dht_running=false enable_dht=false$' || true)
A_BAD=${A_BAD:-0}
if [ "$A_SAMPLES" -ge $MIN_DHT_SAMPLES ] 2>/dev/null && [ "$A_BAD" -eq 0 ] 2>/dev/null; then
  chk "dht.a.samples_ge3_all_zero (n=$A_SAMPLES bad=$A_BAD)" 1
else
  chk "dht.a.samples_ge3_all_zero (n=$A_SAMPLES bad=$A_BAD, need n>=$MIN_DHT_SAMPLES)" 0
fi

B_SAMPLES=$(cnt "$(ran 'M1 B dht_sample ')" "$LOG_B")
B_BAD=$(grep -h "$(ran 'M1 B dht_sample ')" "$LOG_B" 2>/dev/null | grep -cvE ' dht_nodes=0 dht_running=false enable_dht=false$' || true)
B_BAD=${B_BAD:-0}
if [ "$B_SAMPLES" -ge $MIN_DHT_SAMPLES ] 2>/dev/null && [ "$B_BAD" -eq 0 ] 2>/dev/null; then
  chk "dht.b.samples_ge3_all_zero (n=$B_SAMPLES bad=$B_BAD)" 1
else
  chk "dht.b.samples_ge3_all_zero (n=$B_SAMPLES bad=$B_BAD, need n>=$MIN_DHT_SAMPLES)" 0
fi

# --- 6.3 provenance：B 的 32 MiB 必须是从 A 传过来的 --------------------
B_PEERS="$(grep -h "$(ran 'M1 B transfer_peers ')" "$LOG_B" 2>/dev/null | tail -1)"
B_MAX="$(printf '%s' "$B_PEERS" | grep -o ' max=[0-9]*' | tr -d ' ' | cut -d= -f2)"
B_POS="$(printf '%s' "$B_PEERS" | grep -o ' positive=[0-9]*' | tr -d ' ' | cut -d= -f2)"
ok=0
if [ -n "$B_MAX" ] && [ "$B_MAX" -ge 1 ] 2>/dev/null && [ -n "$B_POS" ] && [ "$B_POS" -ge 1 ] 2>/dev/null; then
  ok=1
fi
chk "provenance.b_peers_during_transfer (max=${B_MAX:-none} positive=${B_POS:-none})" $ok

B_STATS="$(grep -h "$(ran 'M1 B transfer_stats ')" "$LOG_B" 2>/dev/null | tail -1)"
B_DL="$(printf '%s' "$B_STATS" | grep -o ' payload_downloaded=[0-9]*' | tr -d ' ' | cut -d= -f2)"
B_DONE="$(printf '%s' "$B_STATS" | grep -o ' total_done=[0-9]*' | tr -d ' ' | cut -d= -f2)"
ok=0
if [ -n "$B_DL" ] && [ "$B_DL" -ge $FILE_BYTES ] 2>/dev/null && [ -n "$B_DONE" ] && [ "$B_DONE" -eq $FILE_BYTES ] 2>/dev/null; then
  ok=1
fi
chk "provenance.b_downloaded_bytes_from_libtorrent (payload_downloaded=${B_DL:-none} total_done=${B_DONE:-none})" $ok

A_STATS="$(grep -h "$(ran 'M1 A transfer_stats ')" "$LOG_A" 2>/dev/null | tail -1)"
A_UL="$(printf '%s' "$A_STATS" | grep -o ' payload_uploaded=[0-9]*' | tr -d ' ' | cut -d= -f2)"
ok=0
if [ -n "$A_UL" ] && [ "$A_UL" -ge $FILE_BYTES ] 2>/dev/null; then ok=1; fi
chk "provenance.a_payload_uploaded_from_libtorrent (payload_uploaded=${A_UL:-none})" $ok

B_MTIME="$(stat -c %Y "$B_DOWNLOAD" 2>/dev/null)"
ok=0
if [ -n "$B_MTIME" ] && [ "$B_MTIME" -gt "$RUN_T0" ] 2>/dev/null; then ok=1; fi
chk "provenance.download_mtime_after_run_start (mtime=${B_MTIME:-none} run_t0=$RUN_T0)" $ok

# --- 6.4 装置自检：mDNS 硬断言 -----------------------------------------
LAN_N="$(fld "$LOG_B" "$(ran 'M1 B join_candidates ')" lan)"
VIA="$(fld "$LOG_B" "$(ran 'M1 B join_via ')" source)"
A_MDNS="$(fld "$LOG_A" "$(ran 'M1 A kernel_started ')" mdns)"

if [ "$A_MDNS" = "on" ]; then chk "mdns.a_started (mdns=on)" 1; else chk "mdns.a_started (got '${A_MDNS:-none}')" 0; fi
if [ -n "$LAN_N" ] && [ "$LAN_N" -ge 1 ] 2>/dev/null; then
  chk "mdns.discovered_lan_candidate (lan=$LAN_N)" 1
else
  chk "mdns.discovered_lan_candidate (lan=${LAN_N:-0})" 0
  echo "DEGRADED: mDNS 局域网发现拿不到候选（协议 §3.1 主路径）。本轮判 FAIL，不允许静默降级到手动 IP:port。"
fi
# join_via 只打印不断言：同机若还有别的实例在广播（另一个 agent 同时跑），
# 它会是第一个 LAN 候选，被 invite_code_mismatch 挡掉后回落到手动 IP:port —— 那是环境噪声，
# 不是 mDNS 退化。mDNS 的硬断言是「发现到了候选」（lan>=1）与「加进的是 A 那个群」（flow.same_group）。
echo "INFO      mdns.join_via=${VIA:-none} (lan=${LAN_N:-0})"

# --- 6.5 补差：file.list 条目验签 -------------------------------------
# 证据全部来自 A2/B2 的日志，同样只认本轮 runId。这一组的证伪点只有一个：
# B2 的清单里绝不能出现 A2 注入的伪造条目。收端的条目验签一旦被去掉，
# poison_present 会变 true、files 会多 1、rejected_bad_sig 会变 0 —— 三条断言同时 FAIL。
CA_MANIFEST="$(fld "$LOG_CA" "$(ran 'M1 A2 join_window ')" files_before_join)"
ok=0
if [ -n "$CA_MANIFEST" ] && [ "$CA_MANIFEST" -gt 32 ] 2>/dev/null; then ok=1; fi
chk "catchup.a_manifest_gt32_before_join (${CA_MANIFEST:-none})" $ok

CB_JOIN_FILES="$(fld "$LOG_CB" "$(ran 'M1 B2 group_joined ')" files)"
ok=0
if [ "$CB_JOIN_FILES" = "0" ]; then ok=1; fi
chk "catchup.b_join_ok_snapshot_empty (files=${CB_JOIN_FILES:-none})" $ok

CB_STATE_FILES="$(fld "$LOG_CB" "$(ran 'M1 B2 state_loaded phase=2 ')" files)"
ok=0
if [ "$CB_STATE_FILES" = "$CATCHUP_FILES" ]; then ok=1; fi
chk "catchup.b_restart_loaded_manifest (files=${CB_STATE_FILES:-none})" $ok

C1_FILES="$(fld "$LOG_CB" "$(ran 'M1 B2 catchup phase=1 ')" files)"
C1_LIST="$(fld "$LOG_CB" "$(ran 'M1 B2 catchup phase=1 ')" list_entries)"
C1_ADD="$(fld "$LOG_CB" "$(ran 'M1 B2 catchup phase=1 ')" broadcast_adds)"
C1_SIG="$(fld "$LOG_CB" "$(ran 'M1 B2 catchup phase=1 ')" sig_ok)"
ok=0
if [ "$C1_FILES" = "$CATCHUP_FILES" ] && [ "$C1_LIST" = "$CATCHUP_FILES" ] && [ "$C1_ADD" = "0" ]; then ok=1; fi
chk "catchup.b_file_list_paged_all (files=${C1_FILES:-none} list_entries=${C1_LIST:-none} broadcast_adds=${C1_ADD:-none})" $ok

ok=0
if [ "$C1_SIG" = "$CATCHUP_FILES" ]; then ok=1; fi
chk "catchup.b_entries_carry_sig (sig_ok=${C1_SIG:-none})" $ok

C1_BAD="$(fld "$LOG_CB" "$(ran 'M1 B2 catchup phase=1 ')" rejected_bad_sig)"
C1_NOSIG="$(fld "$LOG_CB" "$(ran 'M1 B2 catchup phase=1 ')" rejected_no_sig)"
C1_POISON="$(fld "$LOG_CB" "$(ran 'M1 B2 catchup phase=1 ')" poison_present)"
ok=0
if [ -n "$C1_BAD" ] && [ "$C1_BAD" -ge 1 ] 2>/dev/null && [ "$C1_NOSIG" = "0" ]; then ok=1; fi
chk "catchup.poison_rejected_bad_sig (bad_sig=${C1_BAD:-none} no_sig=${C1_NOSIG:-none})" $ok

ok=0
if [ "$C1_POISON" = "false" ] && [ "$C1_FILES" = "$CATCHUP_FILES" ]; then ok=1; fi
chk "catchup.poison_absent_from_manifest (poison_present=${C1_POISON:-none} files=${C1_FILES:-none})" $ok

C2_FILES="$(fld "$LOG_CB" "$(ran 'M1 B2 catchup phase=2 ')" files)"
C2_LIST="$(fld "$LOG_CB" "$(ran 'M1 B2 catchup phase=2 ')" list_entries)"
C2_ADD="$(fld "$LOG_CB" "$(ran 'M1 B2 catchup phase=2 ')" broadcast_adds)"
WANT2=$((CATCHUP_FILES + 1))
ok=0
if [ "$C2_FILES" = "$WANT2" ] && [ "$C2_LIST" = "1" ] && [ "$C2_ADD" = "0" ]; then ok=1; fi
chk "catchup.reconnect_only_via_file_list (files=${C2_FILES:-none} want=$WANT2 list_entries=${C2_LIST:-none} broadcast_adds=${C2_ADD:-none})" $ok

C2_BAD="$(fld "$LOG_CB" "$(ran 'M1 B2 catchup phase=2 ')" rejected_bad_sig)"
C2_POISON="$(fld "$LOG_CB" "$(ran 'M1 B2 catchup phase=2 ')" poison_present)"
ok=0
if [ -n "$C2_BAD" ] && [ "$C2_BAD" -ge 1 ] 2>/dev/null && [ "$C2_POISON" = "false" ]; then ok=1; fi
chk "catchup.poison_rejected_on_reconnect (bad_sig=${C2_BAD:-none} poison_present=${C2_POISON:-none})" $ok

ok=0
if [ "$RC_A2" -eq 0 ] && [ "$RC_B2_1" -eq 0 ] && [ "$RC_B2_2" -eq 0 ]; then ok=1; fi
chk "catchup.process_rc_0_0_0 (A2=$RC_A2 B2p1=$RC_B2_1 B2p2=$RC_B2_2)" $ok


# ------------------------------------------------------------------ 7. 证据
echo
echo "=== evidence ========================================================"
echo "run_id   : $RUN_ID"
echo "A log    : $LOG_A  (本轮 $(cnt "^run=$RUN_ID " "$LOG_A") 行 / 全文件 $(wc -l < "$LOG_A" 2>/dev/null | tr -d ' ' || echo 0) 行)"
echo "B log    : $LOG_B  (本轮 $(cnt "^run=$RUN_ID " "$LOG_B") 行 / 全文件 $(wc -l < "$LOG_B" 2>/dev/null | tr -d ' ' || echo 0) 行)"
echo "A2 log   : $LOG_CA  (本轮 $(cnt "^run=$RUN_ID " "$LOG_CA") 行)"
echo "B2 log   : $LOG_CB  (本轮 $(cnt "^run=$RUN_ID " "$LOG_CB") 行)"
echo "run_t0   : $RUN_T0   B download mtime: ${B_MTIME:-none}"
echo "probes   :"
echo "  A: $A_PROBE"
echo "  B: $B_PROBE"
echo "key lines (this run only):"
grep -hE "$(ran 'M1 (A|B) (run_begin|bt_probe|net_ports|invite_code|invite_tail8|join_candidates|join_via|joined|file_shared|file_seen|download_start|download_done|transfer_peers|transfer_stats|comment_sent|op_received|op_in_store|flow_done)')" \
  "$LOG_A" "$LOG_B" 2>/dev/null | sed 's/^/  /'
echo "catchup (this run only):"
grep -hE "$(ran 'M1 (A2|B2) (run_begin|files_shared|poison_injected|join_window|peer_offline|extra_shared|peer_reconnected|catchup_done|state_loaded|joined|reconnect_start|redial_done|group_joined|catchup|flow_done)')" \
  "$LOG_CA" "$LOG_CB" 2>/dev/null | sed 's/^/  /'
echo "条目验签被拒（reason=bad_sig，本轮）:"
grep -hE "$(ran 'M1 (A2|B2) file_list_rejected .*reason=bad_sig')" "$LOG_CA" "$LOG_CB" 2>/dev/null | sed 's/^/  /'
echo "dht samples (this run only):"
grep -hE "$(ran 'M1 (A|B) dht_sample ')" "$LOG_A" "$LOG_B" 2>/dev/null | sed 's/^/  /'

echo
if [ "$FAILS" -eq 0 ]; then
  echo "PASS"
  exit 0
fi
echo "FAIL ($FAILS checks failed)  run=$RUN_ID"
exit 1
