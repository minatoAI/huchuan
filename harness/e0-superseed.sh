#!/usr/bin/env bash
#
# e0-superseed.sh — 量 libtorrent 的 super-seeding（BEP 16）能不能吃掉 E0 里那 14% 的重复上传
#
#   用法：
#     bash harness/e0-superseed.sh                # 能力探针 + 两臂各 2 轮（512 MiB，约 25 分钟）
#     bash harness/e0-superseed.sh --probe-only   # 只跑能力探针（约 1 分钟）
#     bash harness/e0-superseed.sh --rounds 3     # 每臂 3 轮
#     bash harness/e0-superseed.sh --file-mb 64   # 冒烟（D 与 F 无关，判定口径不变）
#
#   装置：完全复用 harness/e0-team.sh（S 上行 2 MiB/s + C1/C2 同时下载同一文件），
#         只多传一个 superseed=0|1 给做种者。两臂之间除这一个开关外没有任何差别。
#   轮次顺序：off, on, on, off …（每臂在时间上对称，抵消机器状态漂移）。
#   磁盘：每轮 1.5 GiB（S + 两个下载者各 512 MiB），轮间与收尾都删干净（E0_KEEP=0）。
#
#   产物：artifacts/e0-superseed/probe.log
#         artifacts/e0-superseed/round-<r>-<arm>.log（一轮的完整输出）
#         artifacts/e0-superseed/e0/log-{S,C1,C2}.log（e0-team.sh 自己追加写的原始日志）
#         artifacts/e0-superseed/summary.txt / readings.txt
#
# 这个脚本不进 scripts/：它是 harness 自己的装置，和 e0-team.sh 同级。
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

CORE_LIBS="$ROOT/core/build/libs"
M1_LIBS="$ROOT/core/build/m1-libs"
M1_NATIVE="$ROOT/core/build/m1-native/libtorrent4j.dll"
HARNESS_CLASSES="$ROOT/harness/build/classes"
ART="$ROOT/artifacts/e0-superseed"
E0_ART="$ART/e0"

ROUNDS=2
FILE_MB=512
PROBE_ONLY=0
SKIP_PROBE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --rounds)      ROUNDS="$2"; shift 2 ;;
    --file-mb)     FILE_MB="$2"; shift 2 ;;
    --probe-only)  PROBE_ONLY=1; shift ;;
    --skip-probe)  SKIP_PROBE=1; shift ;;
    -h|--help)     sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

w() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}
die() { echo "FATAL: $*" >&2; echo "FAIL"; exit 1; }
mkdir -p "$ART" "$E0_ART"

command -v java  >/dev/null 2>&1 || die "java not on PATH"
command -v javac >/dev/null 2>&1 || die "javac not on PATH"

# ---------------------------------------------------------------- 构建（一次）
echo "=== build ==========================================================="
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
: > "$ARG_FILE"
while IFS= read -r src; do printf '"%s"\n' "$(w "$src")" >> "$ARG_FILE"; done < <(find "$ROOT/harness/src" -name '*.java')
javac -encoding UTF-8 -cp "$JAVAC_CP" -d "$(w "$HARNESS_CLASSES")" "@$(w "$ARG_FILE")" \
  || die "javac harness failed"

JAVA_ARGS=(-Dlibtorrent4j.jni.path="$(w "$M1_NATIVE")" -Dfile.encoding=UTF-8 -cp "$CP")

# ---------------------------------------------------------------- 能力探针
if [ "$SKIP_PROBE" != "1" ]; then
  echo "=== probe : libtorrent4j 2.1.0-39 到底有没有 super-seeding =========="
  rm -rf "$ART/probe-work"
  java "${JAVA_ARGS[@]}" app.huchuan.harness.SuperSeedProbe "$(w "$ART/probe-work")" \
    > "$ART/probe.log" 2>&1
  PROBE_RC=$?
  sed 's/^/  /' "$ART/probe.log"
  echo "  probe_rc=$PROBE_RC (0 = flags/settings/readback 三条都拿到)"
  if [ "$PROBE_ONLY" = "1" ]; then
    if [ "$PROBE_RC" = "0" ]; then echo "PASS"; exit 0; fi
    echo "FAIL"; exit 1
  fi
  if [ "$PROBE_RC" != "0" ]; then echo "  !! 探针没全过：两臂读数才是最终判据"; fi
fi

# ---------------------------------------------------------------- 两臂
metric_field() { # metric_field "<team_metric 行>" <键名>
  printf '%s' "$1" | tr ' ' '\n' | grep "^$2=" | head -1 | cut -d= -f2
}

round_dir_clean() {
  rm -rf "$E0_ART"/s-work "$E0_ART"/s-data "$E0_ART"/c1-work "$E0_ART"/c1-data \
         "$E0_ART"/c2-work "$E0_ART"/c2-data "$E0_ART"/markers
}
payload_mb() { du -sm "$E0_ART" 2>/dev/null | cut -f1; }

SUMMARY="$ART/summary.txt"
: > "$SUMMARY"
OFF_LINES=()
ON_LINES=()
FAILED=0

echo
echo "=== arms : off vs on, ${ROUNDS} 轮/臂, F=${FILE_MB} MiB ======================="
for ((r = 1; r <= ROUNDS; r++)); do
  if (( r % 2 == 1 )); then ORDER="off on"; else ORDER="on off"; fi
  for arm in $ORDER; do
    if [ "$arm" = "on" ]; then ss="1"; else ss="0"; fi
    log="$ART/round-${r}-$arm.log"
    echo "--- round ${r}/${ROUNDS} arm=$arm (superseed=$ss) $(date +%H:%M:%S) ---"
    E0_SUPERSEED="$ss" E0_ART="$E0_ART" E0_KEEP=0 E0_FILE_MB="$FILE_MB" \
      bash "$SCRIPT_DIR/e0-team.sh" > "$log" 2>&1
    rc=$?
    metric="$(grep -h '^METRIC: ' "$log" | head -1 | sed 's/^METRIC: //')"
    echo "    rc=$rc  disk_after=$(payload_mb)MiB"
    if [ -z "$metric" ]; then
      echo "    (没有 team_metric 行，本轮作废；日志：$log)"
      printf '%s\tr%s\t%s\tVOID\t-\t-\t-\t-\t-\n' "$arm" "$r" "$ss" >> "$SUMMARY"
      FAILED=$((FAILED + 1))
      round_dir_clean
      continue
    fi
    d="$(metric_field "$metric" D)"
    t="$(metric_field "$metric" T_all)"
    t="${t%s}"
    up="$(metric_field "$metric" uploaded)"
    peers="$(metric_field "$metric" peers)"
    rb="$(metric_field "$metric" super_readback)"
    valid="$(metric_field "$metric" valid)"
    echo "    D=$d  T_all=${t}s  uploaded=$up  peers=$peers  super_readback=$rb  valid=$valid"
    printf '%s\tr%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
      "$arm" "$r" "$ss" "$valid" "$d" "$t" "$up" "$peers" "$rb" >> "$SUMMARY"
    if [ "$valid" = "true" ]; then
      if [ "$arm" = "on" ]; then ON_LINES+=("$arm $d $t"); else OFF_LINES+=("$arm $d $t"); fi
    else
      FAILED=$((FAILED + 1))
    fi
    round_dir_clean
  done
done

# ---------------------------------------------------------------- 汇总
median() { # median <数值...>：偶数个取中间两个的均值
  if [ $# -eq 0 ]; then echo ""; return; fi
  printf '%s\n' "$@" | sort -n | awk '{a[NR]=$1} END {
    if (NR % 2 == 1) printf "%.4f\n", a[(NR+1)/2];
    else printf "%.4f\n", (a[NR/2] + a[NR/2+1]) / 2;
  }'
}

OFF_D=(); OFF_T=(); ON_D=(); ON_T=()
for line in "${OFF_LINES[@]:-}"; do
  [ -z "$line" ] && continue
  OFF_D+=("$(echo "$line" | cut -d' ' -f2)"); OFF_T+=("$(echo "$line" | cut -d' ' -f3)")
done
for line in "${ON_LINES[@]:-}"; do
  [ -z "$line" ] && continue
  ON_D+=("$(echo "$line" | cut -d' ' -f2)"); ON_T+=("$(echo "$line" | cut -d' ' -f3)")
done

off_d_med="$(median "${OFF_D[@]:-}")"
off_t_med="$(median "${OFF_T[@]:-}")"
on_d_med="$(median "${ON_D[@]:-}")"
on_t_med="$(median "${ON_T[@]:-}")"

{
  echo
  echo "=== readings ========================================================="
  printf '%-4s %-6s %-6s %-6s %-9s %-10s %-12s %-7s %s\n' arm round ss valid D T_all uploaded peers readback
  while IFS=$'\t' read -r arm rnd ss valid d t up peers rb; do
    printf '%-4s %-6s %-6s %-6s %-9s %-10s %-12s %-7s %s\n' "$arm" "$rnd" "$ss" "$valid" "$d" "${t}s" "$up" "$peers" "$rb"
  done < "$SUMMARY"
  echo
  echo "off : D 中位数=$off_d_med  T_all 中位数=${off_t_med}s  (n=${#OFF_T[@]})"
  echo "on  : D 中位数=$on_d_med  T_all 中位数=${on_t_med}s  (n=${#ON_T[@]})"
} > "$ART/readings.txt"
cat "$ART/readings.txt"

echo
echo "=== verdict =========================================================="
if [ "${#OFF_T[@]}" -lt 2 ] || [ "${#ON_T[@]}" -lt 2 ]; then
  echo "两臂各需 >=2 轮有效读数：off=${#OFF_T[@]} on=${#ON_T[@]}（无效轮 $FAILED 个）"
  echo "INSUFFICIENT"
  exit 1
fi
awk -v offd="$off_d_med" -v offt="$off_t_med" -v ond="$on_d_med" -v ont="$on_t_med" '
  BEGIN {
    dt = (offt - ont) / offt * 100.0;
    dd = (offd - ond) / offd * 100.0;
    printf "T_all 改善 = %.1f%%   D 改善 = %.1f%%\n", dt, dd;
    if (dt >= 10.0)      print "VERDICT: ENABLE  —— T_all 改善 >= 10%，建议「同群 >=2 个下载者」时对做种者默认开 super-seeding";
    else if (dt < 5.0)   print "VERDICT: HOLD    —— 改善 < 5%，维持现状（不做显式分片、也不开 super-seeding）";
    else                 print "VERDICT: GRAY    —— 5% <= 改善 < 10%，灰区，交 L1 裁决";
  }'
echo "INVALID_ROUNDS: $FAILED"
echo "（读数表 artifacts/e0-superseed/readings.txt；每轮完整输出 artifacts/e0-superseed/round-<r>-<arm>.log）"
exit 0
