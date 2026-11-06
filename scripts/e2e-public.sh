#!/usr/bin/env bash
#
# e2e-public.sh — 公开种子的第一次端到端验收：A 发布、B 纯磁力下载、两端 SHA-256 一致
#
#   bash scripts/e2e-public.sh                              # 正向验收；最后一行 PASS / FAIL，rc 0 / 1
#   E2E_MUTANT=publish_private bash scripts/e2e-public.sh   # 反证：把 publish 的 private 位翻回 1，必须 FAIL
#
# 装置（M1Harness 的 A3 / B3 角色，两个独立 JVM）：
#   A3 建群 → 共享 32 MiB 私有文件 → publishPublic → 等 B3
#   B3 只用后 8 位加入 → 拿到 scope=public 的磁力条目 → 激活（startDownload → PublicBtSession.addMagnet）
#      → 从 A3 的公开会话拿元数据 → 下载 → SHA-256 → 评论回 A3
#
# 证据：artifacts/e2e-public/log-A3.log / log-B3.log；断言表与变异结果见 docs/reports/e2e-public.md。
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

CORE_LIBS="$ROOT/core/build/libs"
M1_LIBS="$ROOT/core/build/m1-libs"
M1_NATIVE="$ROOT/core/build/m1-native/libtorrent4j.dll"
HARNESS_CLASSES="$ROOT/harness/build/classes"
ART="$ROOT/artifacts/e2e-public"
LOG_A="$ART/log-A3.log"
LOG_B="$ART/log-B3.log"
A_SHARE="$ART/a-work/share/payload-32m.bin"
B_DOWNLOAD="$ART/b-data/public/data/payload-32m.bin"

FILE_BYTES=33554432
MUTANT="${E2E_MUTANT:-}"
WAIT_MS="${M1_WAIT_MS:-180000}"
if [ -n "$MUTANT" ] && [ "$MUTANT" != "publish_private" ]; then
  echo "FATAL: unsupported E2E_MUTANT=$MUTANT（只认 publish_private）" >&2
  echo "FAIL"
  exit 2
fi

w() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}
die() { echo "FATAL: $*" >&2; echo "FAIL"; exit 1; }

command -v java      >/dev/null 2>&1 || die "java not on PATH (JDK 17+)"
command -v javac     >/dev/null 2>&1 || die "javac not on PATH (JDK 17+)"
command -v sha256sum >/dev/null 2>&1 || die "sha256sum not on PATH（脚本要独立重算 SHA-256）"

mkdir -p "$ART" || die "cannot create $ART"

# 同机并发保护：两个实例会互相占 47300 / 6881 段、互相污染公开 swarm。
LOCK="$ART/.runlock"
if ! mkdir "$LOCK" 2>/dev/null; then
  OTHER="$(cat "$LOCK/pid" 2>/dev/null || echo "")"
  if [ -n "$OTHER" ] && kill -0 "$OTHER" 2>/dev/null; then
    die "另一个 e2e-public.sh 正在跑（pid $OTHER）。同机并发会互相破坏证据，等它结束再来。"
  fi
  rm -rf "$LOCK"
  mkdir "$LOCK" 2>/dev/null || die "cannot take run lock $LOCK"
fi
printf '%s\n' "$$" > "$LOCK/pid"

PID_A=""
PID_B=""
cleanup() {
  if [ -n "$PID_B" ] && kill -0 "$PID_B" 2>/dev/null; then
    kill "$PID_B" 2>/dev/null
    sleep 0.2
    kill -9 "$PID_B" 2>/dev/null
  fi
  if [ -n "$PID_A" ] && kill -0 "$PID_A" 2>/dev/null; then
    kill "$PID_A" 2>/dev/null
    sleep 0.2
    kill -9 "$PID_A" 2>/dev/null
  fi
  rm -rf "$LOCK"
}
trap cleanup EXIT

RUN_ID="$(date +%Y%m%dT%H%M%S)-$$-$RANDOM"
T0="$(date +%s)"
printf '%s\n' "$RUN_ID" > "$ART/last-runid.txt"

echo "=== e2e-public acceptance ============================================"
echo "root      : $ROOT"
echo "run_id    : $RUN_ID"
echo "mutant    : ${MUTANT:-none}"

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

# ------------------------------------------------------------------ 2. build harness
echo "--- build :harness (javac) ------------------------------------------"
rm -rf "$HARNESS_CLASSES"
mkdir -p "$HARNESS_CLASSES"
ARG_FILE="$ART/harness-sources.txt"
: > "$ARG_FILE"
while IFS= read -r src; do printf '"%s"\n' "$(w "$src")" >> "$ARG_FILE"; done < <(find "$ROOT/harness/src" -name '*.java')
javac -encoding UTF-8 -cp "$JAVAC_CP" -d "$(w "$HARNESS_CLASSES")" "@$(w "$ARG_FILE")" \
  || die "javac harness failed"

JAVA_ARGS=(-Dlibtorrent4j.jni.path="$(w "$M1_NATIVE")" -Dfile.encoding=UTF-8
           -Dm1.runid="$RUN_ID" -Dm1.waitMs="$WAIT_MS" -cp "$CP")
if [ -n "$MUTANT" ]; then
  JAVA_ARGS+=("-Dhuchuan.mutants=$MUTANT" "-Dm1.mutant=$MUTANT")
fi

# ------------------------------------------------------------------ 3. clean
echo "--- clean : work/data dirs ------------------------------------------"
for d in a-work a-data b-work b-data; do
  rm -rf "$ART/$d"
  [ ! -e "$ART/$d" ] || die "cleanup failed: $ART/$d"
done
mkdir -p "$ART/a-work" "$ART/a-data" "$ART/b-work" "$ART/b-data" || die "mkdir work dirs failed"
: > "$LOG_A"
: > "$LOG_B"

# ------------------------------------------------------------------ 4. A3：发布
echo "--- run   : A3 (share 32 MiB private, publish public) ----------------"
java "${JAVA_ARGS[@]}" app.huchuan.harness.M1Harness A3 \
  "$(w "$ART/a-work")" "$(w "$ART/a-data")" \
  msg_port=47300 bt_port=53513 name=A3_笔记本 group_name=公开磁力验收群 \
  > "$LOG_A" 2>&1 &
PID_A=$!

TAIL8=""
MSG_PORT=""
PUB_LINE=""
for _ in $(seq 1 900); do
  TAIL8="$(grep -h 'M1 A3 invite_tail8=' "$LOG_A" 2>/dev/null | head -1 | grep -o 'tail8=[0-9A-Z]*' | cut -d= -f2)"
  MSG_PORT="$(grep -h 'M1 A3 net_ports ' "$LOG_A" 2>/dev/null | head -1 | grep -o 'msg_port=[0-9]*' | cut -d= -f2)"
  PUB_LINE="$(grep -h 'M1 A3 public_publish_done ' "$LOG_A" 2>/dev/null | head -1)"
  if [ -n "$TAIL8" ] && [ -n "$MSG_PORT" ] && [ -n "$PUB_LINE" ]; then break; fi
  kill -0 "$PID_A" 2>/dev/null || break
  sleep 0.2
done
echo "A tail8   : ${TAIL8:-none}   msg_port: ${MSG_PORT:-none}"
echo "A publish : ${PUB_LINE:-none}"

# ------------------------------------------------------------------ 5. B3：磁力下载
RC_B=-1
if printf '%s' "$PUB_LINE" | grep -q 'ok=true'; then
  echo "--- run   : B3 (join by tail8, magnet download) ----------------------"
  java "${JAVA_ARGS[@]}" app.huchuan.harness.M1Harness B3 \
    "$(w "$ART/b-work")" "$(w "$ART/b-data")" \
    "tail8=$TAIL8" "manual=127.0.0.1:$MSG_PORT" \
    msg_port=47300 bt_port=53613 name=B3_台式机 \
    > "$LOG_B" 2>&1 &
  PID_B=$!
  wait "$PID_B" 2>/dev/null
  RC_B=$?
  PID_B=""
  echo "B exit    : $RC_B"
else
  echo "B         : skipped（A3 的 publishPublic 没成功）"
fi

# ------------------------------------------------------------------ 6. 等 A3 收工
for _ in $(seq 1 60); do
  kill -0 "$PID_A" 2>/dev/null || break
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

# ------------------------------------------------------------------ 7. 断言
echo
echo "=== assertions ======================================================="
FAILS=0
chk() {
  if [ "$2" = "1" ]; then
    echo "CHECK PASS $1"
  else
    echo "CHECK FAIL $1"
    FAILS=$((FAILS + 1))
  fi
}
cnt() {
  local n
  n=$(grep -c -- "$1" "$2" 2>/dev/null || true)
  printf '%s' "${n:-0}"
}
fld() { # fld <file> <anchored-pattern> <field>
  grep -h -- "$2" "$1" 2>/dev/null | grep -o -- "$3=[^ ]*" | head -1 | cut -d= -f2-
}

A_MUTANT="$(fld "$LOG_A" 'M1 A3 run_begin ' mutant)"
A_PUB_OK="$(fld "$LOG_A" 'M1 A3 public_publish_done ' ok)"
A_PUB_TS="$(fld "$LOG_A" 'M1 A3 public_publish_done ' ts)"
A_PUB_ID="$(fld "$LOG_A" 'M1 A3 public_publish_done ' pub_infohash)"
A_PRIV_ID="$(fld "$LOG_A" 'M1 A3 public_publish_done ' private_file_id)"
A_PUB_PORT="$(fld "$LOG_A" 'M1 A3 public_publish_done ' public_port)"
A_SHA="$(fld "$LOG_A" 'M1 A3 file_sha256=' file_sha256)"
A_FILES="$(grep -h 'M1 A3 files_state ' "$LOG_A" 2>/dev/null | head -1)"
A_STATS="$(grep -h 'M1 A3 public_probe ' "$LOG_A" 2>/dev/null | head -1)"
A_PRIV_STATS="$(grep -h 'M1 A3 private_probe ' "$LOG_A" 2>/dev/null | head -1)"
A_REPORT_BODY="$(fld "$LOG_A" 'M1 A3 b_report ' body)"

B_ENTRY="$(grep -h 'M1 B3 public_entry_seen ' "$LOG_B" 2>/dev/null | head -1)"
B_PUB_ID="$(fld "$LOG_B" 'M1 B3 public_entry_seen ' file_id)"
B_DUP="$(grep -h 'M1 B3 add_public_magnet_probe ' "$LOG_B" 2>/dev/null | head -1)"
B_ANN_PORT="$(fld "$LOG_B" 'M1 B3 a_pub_bt_port ' port)"
B_JOIN_TS="$(fld "$LOG_B" 'M1 B3 joined ' ts)"
B_META_TS="$(fld "$LOG_B" 'M1 B3 metadata_arrived ' ts)"
B_CACHE_MTIME="$(fld "$LOG_B" 'M1 B3 metadata_arrived ' cache_mtime)"
B_META_CACHE="$(fld "$LOG_B" 'M1 B3 metadata_arrived ' cache_exists)"
B_PRE_CACHE="$(fld "$LOG_B" 'M1 B3 pre_metadata_cache ' exists)"
B_BG="$(grep -h 'M1 B3 pub_background_metadata ' "$LOG_B" 2>/dev/null | head -1)"
B_KERNEL_META="$(grep -h 'M1 B3 public_metadata_arrived ' "$LOG_B" 2>/dev/null | head -1)"
B_STATS="$(grep -h 'M1 B3 transfer_stats ' "$LOG_B" 2>/dev/null | head -1)"
B_PRIV_PROBE="$(grep -h 'M1 B3 private_probe ' "$LOG_B" 2>/dev/null | head -1)"
B_SESS="$(grep -h 'M1 B3 sessions ' "$LOG_B" 2>/dev/null | head -1)"
B_SHA="$(fld "$LOG_B" 'M1 B3 download_done ' sha256)"

# 7.1 变异开关（正向跑必须是 none；反证跑必须真的生效）
ok=0
if [ -n "$MUTANT" ]; then
  [ "$A_MUTANT" = "$MUTANT" ] && ok=1
  chk "mutant.active (mutant=$A_MUTANT expect=$MUTANT)" $ok
else
  [ "$A_MUTANT" = "none" ] && ok=1
  chk "mutant.off (mutant=$A_MUTANT)" $ok
fi

# 7.2 A 发布 / 私有条目保留
ok=0
if [ "$A_PUB_OK" = "true" ] \
   && printf '%s' "$A_PUB_ID" | grep -qE '^[0-9a-f]{40}$' \
   && [ -n "$A_PRIV_ID" ] && [ "$A_PUB_ID" != "$A_PRIV_ID" ]; then ok=1; fi
chk "A.publish_ok (ok=$A_PUB_OK priv=$A_PRIV_ID pub=$A_PUB_ID)" $ok

ok=0
if printf '%s' "$A_FILES" | grep -q "private_file_id=$A_PRIV_ID" \
   && printf '%s' "$A_FILES" | grep -q 'private_scope=private' \
   && printf '%s' "$A_FILES" | grep -q 'private_seeding=true' \
   && printf '%s' "$A_FILES" | grep -q "public_file_id=$A_PUB_ID" \
   && printf '%s' "$A_FILES" | grep -q 'public_scope=public' \
   && printf '%s' "$A_FILES" | grep -q 'public_magnet=yes' \
   && printf '%s' "$A_FILES" | grep -q 'public_inline_torrent=no'; then ok=1; fi
chk "A.private_kept_public_entry ($A_FILES)" $ok

# 7.3 B 的条目 scope=public 且只带磁力
ok=0
if [ -n "$B_PUB_ID" ] && [ "$B_PUB_ID" = "$A_PUB_ID" ] \
   && printf '%s' "$B_ENTRY" | grep -q 'scope=public' \
   && printf '%s' "$B_ENTRY" | grep -q 'magnet=yes' \
   && printf '%s' "$B_ENTRY" | grep -q 'inline_torrent=no'; then ok=1; fi
chk "B.public_entry_scope_magnet ($B_ENTRY)" $ok

# 7.4 addPublicMagnet 的 API 口径：群清单里已有的 infohash 必须是 duplicate
ok=0
printf '%s' "$B_DUP" | grep -q 'reason=duplicate' && ok=1
chk "B.add_public_magnet_duplicate ($B_DUP)" $ok

# 7.5 公开会话拨号：A 的 pubBtPort 对齐 + sess=pub + 没有 sess=priv
ok=0
[ -n "$B_ANN_PORT" ] && [ "$B_ANN_PORT" = "$A_PUB_PORT" ] && ok=1
chk "B.a_pub_bt_port_matches (announced=$B_ANN_PORT A=$A_PUB_PORT)" $ok

N_PUB_DIAL="$(cnt "M1 B3 bt_connect_peer .*file_id=$B_PUB_ID .*sess=pub" "$LOG_B")"
ok=0
[ "$N_PUB_DIAL" -ge 1 ] 2>/dev/null && ok=1
chk "B.pub_session_dial (n=$N_PUB_DIAL)" $ok

N_PRIV_DIAL="$(cnt 'M1 B3 bt_connect_peer .*sess=priv' "$LOG_B")"
ok=0
[ "$N_PRIV_DIAL" = "0" ] && ok=1
chk "B.no_private_dial (n=$N_PRIV_DIAL)" $ok

N_PUB_DL="$(cnt "M1 B3 download_start .*file_id=$B_PUB_ID .*sess=pub" "$LOG_B")"
ok=0
[ "$N_PUB_DL" = "1" ] && ok=1
chk "B.pub_session_download_start (n=$N_PUB_DL)" $ok

N_PRIV_DL="$(cnt 'M1 B3 download_start .*sess=priv' "$LOG_B")"
ok=0
[ "$N_PRIV_DL" = "0" ] && ok=1
chk "B.no_private_download_start (n=$N_PRIV_DL)" $ok

ok=0
if printf '%s' "$B_PRIV_PROBE" | grep -q 'status=missing' \
   && printf '%s' "$B_PRIV_PROBE" | grep -q "file_id=$B_PUB_ID" \
   && printf '%s' "$B_SESS" | grep -q 'private_torrents=0' \
   && printf '%s' "$B_SESS" | grep -q 'public_torrents=1'; then ok=1; fi
chk "B.private_session_has_no_public_torrent ($B_PRIV_PROBE / $B_SESS)" $ok

# 7.6 元数据来自对端：激活前无缓存、元数据时刻晚于 A 发布、缓存也是本轮落下的
ok=0
if [ "$B_PRE_CACHE" = "false" ] && [ "$B_META_CACHE" = "true" ] \
   && [ -n "$B_META_TS" ] && [ "$B_META_TS" -gt "$A_PUB_TS" ] 2>/dev/null \
   && [ -n "$B_JOIN_TS" ] && [ "$B_META_TS" -gt "$B_JOIN_TS" ] 2>/dev/null \
   && [ -n "$B_CACHE_MTIME" ] && [ "$B_CACHE_MTIME" -gt "$A_PUB_TS" ] 2>/dev/null; then ok=1; fi
chk "B.metadata_after_publish_not_local (pre=$B_PRE_CACHE meta_ts=$B_META_TS publish_ts=$A_PUB_TS join_ts=$B_JOIN_TS cache_mtime=$B_CACHE_MTIME)" $ok

ok=0
if printf '%s' "$B_BG" | grep -q "infohash=$B_PUB_ID" \
   && printf '%s' "$B_BG" | grep -q 'sess=pub' \
   && printf '%s' "$B_KERNEL_META" | grep -q "file_id=$B_PUB_ID" \
   && printf '%s' "$B_KERNEL_META" | grep -q 'private=false'; then ok=1; fi
chk "B.metadata_via_public_swarm ($B_BG)" $ok

# 7.7 字节真值：B 的下载计数来自公开会话，A 的公开会话确实上传了 32 MiB
B_DL_BYTES="$(printf '%s' "$B_STATS" | grep -o 'payload_downloaded=[0-9]*' | cut -d= -f2)"
B_DONE="$(printf '%s' "$B_STATS" | grep -o 'total_done=[0-9]*' | cut -d= -f2)"
ok=0
if [ -n "$B_DL_BYTES" ] && [ "$B_DL_BYTES" -ge $FILE_BYTES ] 2>/dev/null \
   && [ "$B_DONE" = "$FILE_BYTES" ]; then ok=1; fi
chk "B.downloaded_bytes_from_public_session (payload_downloaded=$B_DL_BYTES total_done=$B_DONE)" $ok

A_UL="$(printf '%s' "$A_STATS" | grep -o 'payload_uploaded=[0-9]*' | cut -d= -f2)"
ok=0
[ -n "$A_UL" ] && [ "$A_UL" -ge $FILE_BYTES ] 2>/dev/null && ok=1
chk "A.public_session_uploaded (payload_uploaded=$A_UL)" $ok

A_PRIV_UL="$(printf '%s' "$A_PRIV_STATS" | grep -o 'payload_uploaded=[0-9]*' | cut -d= -f2)"
ok=0
[ "$A_PRIV_UL" = "0" ] && ok=1
chk "A.private_session_uploaded_zero (payload_uploaded=$A_PRIV_UL)" $ok

# 7.8 两端 SHA-256：日志值 + 脚本独立复算
FILE_A_SHA="$(sha256sum "$A_SHARE" 2>/dev/null | cut -d' ' -f1)"
FILE_B_SHA="$(sha256sum "$B_DOWNLOAD" 2>/dev/null | cut -d' ' -f1)"
ok=0
if [ -n "$A_SHA" ] && [ "$A_SHA" = "$B_SHA" ] \
   && [ "$A_SHA" = "$FILE_A_SHA" ] && [ "$A_SHA" = "$FILE_B_SHA" ]; then ok=1; fi
chk "sha256.equal (A_log=$A_SHA B_log=$B_SHA A_file=$FILE_A_SHA B_file=$FILE_B_SHA)" $ok

A_REPORT_SHA="${A_REPORT_BODY#*sha256=}"
ok=0
[ -n "$A_REPORT_SHA" ] && [ "$A_REPORT_SHA" = "$B_SHA" ] && ok=1
chk "B.report_received_by_A (body=$A_REPORT_BODY)" $ok

ok=0
[ "$RC_A" -eq 0 ] && [ "$RC_B" -eq 0 ] && ok=1
chk "process.rc_0_0 (A=$RC_A B=$RC_B)" $ok

# ------------------------------------------------------------------ 8. 证据
echo
echo "=== evidence ========================================================"
echo "run_id   : $RUN_ID"
echo "A log    : $LOG_A"
echo "B log    : $LOG_B"
echo "A share  : $A_SHARE"
echo "B file   : $B_DOWNLOAD"
echo "A pub    : $A_PUB_ID (port=$A_PUB_PORT)"
echo "B meta   : ts=$B_META_TS cache_mtime=$B_CACHE_MTIME"
echo "sha256   : A=$A_SHA B=$B_SHA"
echo "B dials  : pub=$N_PUB_DIAL priv=$N_PRIV_DIAL  downloads: pub=$N_PUB_DL priv=$N_PRIV_DL"
echo "B bytes  : payload_downloaded=$B_DL_BYTES total_done=$B_DONE"
echo "A bytes  : public_uploaded=$A_UL private_uploaded=$A_PRIV_UL"
echo "elapsed  : $(( $(date +%s) - T0 ))s"

echo
if [ "$FAILS" -eq 0 ]; then
  echo "PASS"
  exit 0
fi
echo "FAIL ($FAILS checks failed)  run=$RUN_ID"
exit 1
