#!/usr/bin/env bash
#
# m1-device.sh — Android 真机 M1 全流程验收
#
#   bash scripts/m1-device.sh              # 全流程 + 崩溃压力（3 轮 128 MiB，中途取消一次）
#   bash scripts/m1-device.sh --quick      # 跳过压力测试
#   bash scripts/m1-device.sh --rounds 2 --mib 64
#   M1_INSTALL=0 bash scripts/m1-device.sh --quick
#                                          # 不构建/安装 app，用设备上已装的包（验 release 包用）
#
# 链路（脚本自己判定并写在输出里）：
#   android-desktop  手机 + 桌面 JVM 对端（当前唯一可跑的链路）
#   第二台安卓在线时只提示、不切换：android-android 需要第二个 UI 驱动，M2 再做。
#
# 手机侧（app.huchuan）：
#   P0 自检 → P1 用户名 → P5 只输后 8 位加入 → P3 添加文件→本机文件→SAF 选文件做种 → P4 评论/激活/休眠
#   → 退回 P2 看未读角标 → 进群进文件后角标消失 → P6 成员平台
# 桌面侧（app/tools/peer/DesktopPeer.java）：
#   建群 → 等手机加入 → 下载手机共享的文件并比对 SHA-256 → 评论 → 等手机回评论
#   → 延迟补一条评论（给未读角标用）→ 共享 N 个压力文件做种
#
# 断言：邀请码 16 位 / 只输 8 位加入 / 两端 SHA-256 相同 / 评论双向可见 /
#       P2 未读角标出现且进群进文件后消失 / P6 成员平台 / dht_nodes=0 /
#       crash 缓冲区 0 条 / 无 Scudo abort /
#       做种计数：对端下载完成转做种后 P6 那一行是「做种 共享数+1」（不是停在共享数）/
#       重启续种：force-stop 冷启动后 seed_resumed + seed_resume_done resumed=n、
#       P3 状态仍是「做种中」、P4 激活开关仍为开、P6 本机做种数与重启前一致。
#       冷启动自动重连（restart.reconnect 组，四条）：kernel_started reconnect=on /
#       reconnect_attempt 的 sources 含 peer / peer_reconnected elapsed_ms ≤ 5000 /
#       对端 desktop-peer.log 出现 peer_bound role=in；外加 P6 上对端那一行实时变回「在线」。
#       组队通告 0 态：本机在整个 quick 流程里只做种（不下载），队友必然 0 →
#       P3 文件行不出现「组队」chip、P4 不出现「正在组队」行（0 就是不显示，不是显示 0）；
#       logcat 里也不会出现 teammates_changed count≥1。
#       限速（step 12，单位一律 KB/s）：
#       P4 打开「限制速度」设 1024 → 桌面端下载 32 MiB 实测 ≤ 1229 KB/s（20% 余量），关掉开关后 kbps=0；
#       P7 全局限速设上行 512 → 另一份 32 MiB 实测 ≤ 600 KB/s；清掉限制（不限）→ 第三份实测 > 2 MB/s。
#       两条都带内核读回校验（session_up_kbps=512 / ok=true），防止「设置成功但没生效」。
#       debuggable：私有目录哈希要 run-as，release 包上记 SKIP（原因 package not debuggable），
#       手机侧自证改读 core 日志 file_seeding / download_completed 的 sha256=。
# 证据：artifacts/m1-device/（截图、ui-dump、logcat、peer 日志、拉回的文件与哈希）
#
# 最后一行打印 PASS / FAIL，退出码 0 / 1；上一行 checks 单独列 PASS / FAIL / SKIP。
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ART="$ROOT/artifacts/m1-device"
DEVKIT="$ROOT/tools/devkit/devkit.mjs"
PKG="app.huchuan"
ACTIVITY=".MainActivity"

STRESS_ROUNDS="${M1_STRESS_ROUNDS:-3}"
STRESS_MIB="${M1_STRESS_MIB:-128}"
# 群名两端要一致：桌面端建群用它，手机 P2 上的群行也是它（脚本靠点这行进群）。
GROUP_NAME="${M1_GROUP_NAME:-真机验收群}"
# 桌面端在成员表里的名字：java 侧用 name= 传同一个值，P6 的断言靠它在成员行里定位「对端」。
PEER_NAME="${M1_PEER_NAME:-桌面电脑}"
# 桌面端补发评论的延迟：从「收到手机回的那条评论」开始算，留出手机返回 P2 的时间。
ECHO_DELAY_MS="${M1_ECHO_DELAY_MS:-25000}"
# 冷启动自动重连（M1.4）：peer_reconnected 的 elapsed_ms 上限，超了就算候选选错或退避太慢。
RECONNECT_MAX_MS="${M1_RECONNECT_MS:-5000}"
QUICK=0
# 1 = 构建并安装 debug 包（默认）；0 = 用设备上已装的包，只构建桌面对端要用的 core。
INSTALL="${M1_INSTALL:-1}"

while [ $# -gt 0 ]; do
  case "$1" in
    --quick) QUICK=1 ;;
    --rounds) shift; STRESS_ROUNDS="${1:-3}" ;;
    --mib) shift; STRESS_MIB="${1:-128}" ;;
    -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
    *) echo "FATAL: 未知参数 $1" >&2; exit 2 ;;
  esac
  shift
done

# ------------------------------------------------------------------ 基础工具

say() { printf '%s\n' "$*"; }
step() { printf '\n=== %s ===\n' "$1"; }
die() { say "FATAL: $*" >&2; say "FAIL"; exit 1; }

case "$INSTALL" in
  0|1) ;;
  *) die "M1_INSTALL 只能是 0 或 1（当前：$INSTALL）" ;;
esac

CHECKS_FAIL=0
CHECKS_PASS=0
CHECKS_SKIP=0
chk() {
  local name="$1" ok="$2" detail="${3:-}"
  if [ "$ok" = "1" ]; then
    say "CHECK PASS $name${detail:+ ($detail)}"
    CHECKS_PASS=$((CHECKS_PASS + 1))
  else
    say "CHECK FAIL $name${detail:+ ($detail)}"
    CHECKS_FAIL=$((CHECKS_FAIL + 1))
  fi
}
# SKIP 是「这次环境测不了」，不是 FAIL，也不能混进 PASS（汇总单独计数）。
chk_skip() {
  local name="$1" reason="${2:-}"
  say "CHECK SKIP $name${reason:+ ($reason)}"
  CHECKS_SKIP=$((CHECKS_SKIP + 1))
}
warn() { say "WARN  $*"; }
info() { say "INFO  $*"; }

# Windows 原生程序（java/javac/adb）要 Windows 路径；MSYS 的 /tmp 给不了它们。
w() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

# 设备路径不能被 MSYS 改写（/sdcard → C:/Program Files/Git/sdcard）。
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

resolve_adb() {
  local candidate
  for candidate in "${M1_ADB:-}" "${ADB:-}" "${DEVKIT_ADB:-}" \
    "/e/agentHome/android-sdk/platform-tools/adb.exe" \
    "${ANDROID_HOME:-}/platform-tools/adb.exe" \
    "${ANDROID_SDK_ROOT:-}/platform-tools/adb.exe"; do
    [ -n "$candidate" ] || continue
    if [ -f "$candidate" ]; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  command -v adb 2>/dev/null && return 0
  return 1
}

ADB="$(resolve_adb)" || die "找不到 adb（设 M1_ADB 或 ANDROID_HOME）"
[ -f "$DEVKIT" ] || die "找不到 tools/devkit/devkit.mjs"

# devkit 是唯一的真机操作入口；文本输入 devkit 没有动词，用 adb shell input（见 docs/m1-device.md）。
# 注意：MSYS_NO_PATHCONV 只对 Windows 原生程序生效，所以给 node 的脚本路径必须先转成 Windows 形式。
dks() { node "$(w "$DEVKIT")" -s "$SERIAL" "$@" 2>&1; }
sh_() { "$ADB" -s "$SERIAL" shell "$@" 2>&1; }

# uiautomator 偶尔 dump 不出来（MIUI 已知问题），统一重试 3 次。
retry3() {
  local i
  for i in 1 2 3; do
    if "$@"; then return 0; fi
    sleep 1
  done
  return 1
}

tap() {
  local text="$1" exact="${2:-}"
  if [ "$exact" = "exact" ]; then
    retry3 dks tap-text "$text" --exact >/dev/null || { warn "tap 失败：$text"; return 1; }
  else
    retry3 dks tap-text "$text" >/dev/null || { warn "tap 失败：$text"; return 1; }
  fi
  sleep 1
  return 0
}

see_text() {
  local text="$1" timeout="${2:-30000}"
  dks wait-text "$text" --timeout "$timeout" >/dev/null 2>&1
}

# 内核事件日志（tag = huchuan/m1），只留 event=... 部分
applog() {
  "$ADB" -s "$SERIAL" logcat -d -v brief 2>/dev/null \
    | grep -F "huchuan/m1" \
    | sed 's/.*huchuan\/m1[^:]*: //'
}

wait_log() {
  local pattern="$1" timeout="${2:-60000}" start
  start=$(date +%s)
  while [ $(( $(date +%s) - start )) -lt "$timeout" ]; do
    if applog | grep -qF -- "$pattern"; then return 0; fi
    sleep 1
  done
  return 1
}

# 数日志里某个片段出现了几次（多轮压力测试靠它区分「新一轮」和「上一轮」）
count_log() {
  applog | grep -cF -- "$1" || true
}

wait_log_count() {
  local pattern="$1" want="$2" timeout="${3:-60000}" start
  start=$(date +%s)
  while [ $(( $(date +%s) - start )) -lt "$timeout" ]; do
    if [ "$(count_log "$pattern")" -ge "$want" ]; then return 0; fi
    sleep 1
  done
  return 1
}

wait_log_peer() {
  local pattern="$1" timeout="${2:-120000}" start
  start=$(date +%s)
  while [ $(( $(date +%s) - start )) -lt "$timeout" ]; do
    if grep -qF -- "$pattern" "$PEER_LOG" 2>/dev/null; then return 0; fi
    sleep 1
  done
  return 1
}

# 桌面端日志里「手机拨进来」的次数：peer_bound 的 role=in 就是本条链路由手机发起。
# 断言的是「重启之后又多了一条」，所以先记基线再等计数增加：同一轮里更早的 inbound 绑定
# （换网、对端自己重试、加入时的握手）都不能冒充重启后的这一次，只 grep 存在性会放过假证据。
count_peer_bound_in() {
  local n
  n="$(grep -c 'peer_bound .*role=in' "$PEER_LOG" 2>/dev/null || true)"
  printf '%s' "${n:-0}"
}

wait_peer_bound_in() {
  local want="$1" timeout="${2:-30000}" start
  start=$(date +%s)
  while [ $(( $(date +%s) - start )) -lt "$timeout" ]; do
    if [ "$(count_peer_bound_in)" -ge "$want" ] 2>/dev/null; then return 0; fi
    sleep 1
  done
  return 1
}

# 等到指定文件的下载进度到 N%（logcat 的 download_progress pct=NN）。
# 必须按 file_id 过滤：只看「最后一行进度」会被上一轮 100% 的那行骗过去。
wait_progress() {
  local file_id="$1" want="$2" timeout="${3:-120000}" start pct
  start=$(date +%s)
  while [ $(( $(date +%s) - start )) -lt "$timeout" ]; do
    # 注意别写成 'download_progress pct='：中间夹着 group_id / file_id，不是连续子串。
    pct="$(applog | grep -F "$file_id" | grep -F "download_progress" | grep -o 'pct=[0-9]*' | tail -1 | cut -d= -f2)"
    if [ -n "$pct" ] && [ "$pct" -ge "$want" ] 2>/dev/null; then return 0; fi
    sleep 1
  done
  return 1
}

log_field() {
  local pattern="$1" field="$2"
  applog | grep -F -- "$pattern" | grep -o "$field=[^ ]*" | tail -1 | cut -d= -f2
}

# devkit 产物路径从它的 JSON 里取，绝不「猜最新文件」——上一次运行留下的旧图会冒充证据。
artifact_path() {
  local out
  out="$("$@" 2>&1 | tail -1)"
  printf '%s' "$out" | tr ' ' '\n' | sed -n 's/^path=//p' | head -1
}

screenshot() {
  local name="$1" path
  path="$(artifact_path dks screenshot --name "$name")"
  if [ -n "$path" ] && [ -f "$path" ]; then
    cp "$path" "$ART/screenshots/$(basename "$path")"
  else
    warn "截图失败：$name"
  fi
  return 0
}

# ui-dump 的 XML 落盘路径（路径打给调用方，同时留一份进 artifacts/m1-device/ui-dumps/）。
# P4 激活开关的 checked 只在 XML 里（devkit 的 --texts 只吐 text/desc），所以要把路径拿出来。
uidump_path() {
  local name="$1" path
  path="$(artifact_path dks ui-dump --name "$name")"
  if [ -n "$path" ] && [ -f "$path" ]; then
    cp "$path" "$ART/ui-dumps/$(basename "$path")"
  fi
  printf '%s' "$path"
  return 0
}

uidump() { uidump_path "$1" >/dev/null; }

# 回到 P3。软键盘开着时第一次返回只会收键盘，所以按到「添加文件」出现为止。
back_to_group() {
  local i
  for i in 1 2 3 4; do
    if see_text "添加文件" 4000; then return 0; fi
    sh_ input keyevent KEYCODE_BACK >/dev/null 2>&1
    sleep 1
  done
  return 1
}

# 回到 P3：先回 P2，再点群行进去。P7 抽屉关掉之后人停在 P2，
# 只按返回键永远到不了 P3（再按就退出应用了），所以进群这一段必须点。
enter_group() {
  back_to_home || return 1
  tap "$GROUP_NAME" >/dev/null 2>&1 || return 1
  see_text "添加文件" 25000
}

# 回到 P2：「搜索群组」是首页搜索框的占位文案，别的页面没有。
back_to_home() {
  local i
  for i in 1 2 3 4 5; do
    if see_text "搜索群组" 4000; then return 0; fi
    sh_ input keyevent KEYCODE_BACK >/dev/null 2>&1
    sleep 1
  done
  return 1
}

# P2 群行右侧的未读角标文案（spec §3 P2：「{n} 条新消息」）；没有角标时输出空串。
badge_text() {
  dks ui-dump --texts 2>/dev/null | grep -o '[0-9]\+ 条新消息' | head -1
}

# P6 上某个成员那一行 supporting 里的某段文字。
#
# 参数：$1 = 成员名（headline 文字），$2 = 值的 ERE（'^做种 [0-9]+$' / '^(在线|离线)$'），
#       $3 = ui-dump --texts 的输出（不给就现抓一次）。
# 一张 P6 上每个成员占两行文字：name 在高处、supporting（在线/离线 + 做种 n）紧随其下，
# 相邻成员之间隔一整行。NODE 行带 center=y，所以按「y 最近的一对」配对，
# 不依赖成员排序，也不会把「做种 0」错配到上一个成员头上。
# 找不到该成员（或被 dedupe 掉）时输出空串，由调用方判 FAIL。
member_near() {
  local who="$1" pat="$2" texts="${3:-}"
  if [ -z "$texts" ]; then texts="$(dks ui-dump --texts 2>/dev/null)"; fi
  printf '%s\n' "$texts" | awk -v who="$who" -v pat="$pat" '
    function value_of(l) { v = l; sub(/^.* value=/, "", v); sub(/ bounds=.*$/, "", v); gsub(/^"|"$/, "", v); return v }
    function y_of(l,   c) {
      if (match(l, /center=[0-9]+,[0-9]+/)) { c = substr(l, RSTART, RLENGTH); sub(/^center=[0-9]+,/, "", c); return c + 0 }
      return -1
    }
    /^NODE/ {
      v = value_of($0); y = y_of($0)
      if (v == who) { ny[++nn] = y }
      else if (v ~ pat) { sn++; sy[sn] = y; sv[sn] = v }
    }
    END {
      bestd = 1e9; got = 0
      for (i = 1; i <= nn; i++) for (j = 1; j <= sn; j++) {
        d = sy[j] - ny[i]; if (d < 0) d = -d
        if (d < 100 && d < bestd) { bestd = d; best = sv[j]; got = 1 }
      }
      if (got) print best; else print ""
    }'
}

# 某个成员那一行的「做种 n」，只要数字（沿用老调用方的口径）。
member_seeding() {
  member_near "$1" '^做种 [0-9]+$' "${2:-}" | sed 's/^做种 //'
}

# 某个成员那一行的在线状态：'在线' / '离线'（内核连接表的实时值，见 restart.reconnect_p6_online）。
member_status() {
  member_near "$1" '^(在线|离线)$' "${2:-}"
}

# ---------------------------------------------------------------- 组队通告（step 9）

# 当前界面上的文本行（devkit ui-dump --texts）。断言「某字眼不出现」时用它，
# 比 see_text 直接（see_text 只能证明「出现了」）。
ui_texts() { dks ui-dump --texts 2>/dev/null; }

# ---------------------------------------------------------------- P7 全局限速（step 12）

# 打开 P7 抽屉：P2 右上「设置」→ 等「全局限速」出现。
open_settings() {
  back_to_home || return 1
  tap "设置" >/dev/null 2>&1 || return 1
  see_text "全局限速" 20000
}

# 把 P7 上行限速设成 $1 KB/s（0 = 关掉「限制速度」= 两项都按 0 下发）。
#
# 「限制速度」是整行可点的开关：开 = 显示上行/下行输入框，关 = 不限。
# 开关本身没有可读文本，用「上行」输入框是否出现判状态；脚本是唯一状态机驱动者，
# 每次只按当前状态点一下。成功与否由 logcat 的 event=global_limit_set 判定，不靠界面猜测。
p7_upload_limit() {
  local kbps="$1" shot="${2:-}" before
  before="$(count_log "event=global_limit_set")"
  open_settings || return 1
  if [ "$kbps" != "0" ]; then
    if ! see_text "上行" 3000; then
      tap "限制速度" exact >/dev/null 2>&1 || return 1
      see_text "上行" 8000 || return 1
    fi
    tap "上行" >/dev/null 2>&1 || return 1
    sleep 1
    # 先清空旧值：输入框会保留上一次的数字，直接 input text 会追加成 512512。
    sh_ input keyevent KEYCODE_MOVE_END >/dev/null 2>&1
    sh_ input keyevent 67 67 67 67 67 67 >/dev/null 2>&1
    sleep 1
    sh_ input text "$kbps" >/dev/null 2>&1
    sleep 1
  else
    if see_text "上行" 3000; then
      tap "限制速度" exact >/dev/null 2>&1 || return 1
    else
      # 已经是不限：先开再关，逼出本轮可断言的 global_limit_set / applied 日志，
      # 不能拿上一轮的「up_kbps=0」冒充本轮证据。
      tap "限制速度" exact >/dev/null 2>&1 || return 1
      see_text "上行" 8000 || return 1
      tap "限制速度" exact >/dev/null 2>&1 || return 1
    fi
    sleep 2
  fi
  # 截图必须在抽屉还开着的时候拍：关掉之后就只剩 P2 了（第一版就踩了这个坑）。
  if [ -n "$shot" ]; then screenshot "$shot"; fi
  sh_ input keyevent 111 >/dev/null 2>&1 || true   # ESC 收键盘，别让返回键先吃在键盘上
  sleep 1
  back_to_home || return 1
  wait_log_count "event=global_limit_set" $((before + 1)) 20000
}

# 桌面端按需下载：往控制文件里追加一行 download=<文件名>（DesktopPeer 每 400 ms 读一次）。
peer_download() {
  mkdir -p "$(dirname "$PEER_CONTROL")"
  printf 'download=%s\n' "$1" >> "$PEER_CONTROL"
}

# 等桌面端把这个文件下完：PEER auto_done name=<文件名> complete=true
wait_peer_auto_done() {
  local name="$1" timeout="${2:-420}" start
  start=$(date +%s)
  while [ $(( $(date +%s) - start )) -lt "$timeout" ]; do
    if grep -qF "PEER auto_done name=$name complete=true" "$PEER_LOG" 2>/dev/null; then return 0; fi
    sleep 1
  done
  return 1
}

# 桌面端实测速率（KB/s）：auto_done 里的 kbps 字段（startDownload → isComplete 的墙钟均值）。
peer_auto_kbps() {
  grep -F "PEER auto_done name=$1 complete=" "$PEER_LOG" 2>/dev/null | tail -1 \
    | grep -o 'kbps=[0-9]*' | cut -d= -f2
}

# 桌面端**稳态**速率（KB/s）：auto_progress 里 libtorrent 报的瞬时速率中位数，丢掉前 3 个样本（~6 s）。
# 限速判据用稳态而不是均速：均速里混着建链时间，会把「起步慢」误判成「限速低」。
peer_auto_steady_kbps() {
  local samples
  samples="$(grep -F "PEER auto_progress name=$1 " "$PEER_LOG" 2>/dev/null \
    | grep -o 'rate_kbps=[0-9]*' | cut -d= -f2 | tail -n +4 | sort -n)"
  if [ -z "$samples" ]; then
    # 传得比 6 s 还快（不限速的那几份）→ 没有稳态样本，退回均速。
    peer_auto_kbps "$1"
    return
  fi
  printf '%s\n' "$samples" | awk '{ a[NR]=$1 } END { print a[int((NR + 1) / 2)] }'
}

# 前台窗口是不是我们的应用
app_foreground() {
  dks ui-dump --json 2>/dev/null | grep -q "\"packages\":\[\"$PKG\"\]"
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

# run-as 只对 debuggable 包有效。release 包上必然报 "package not debuggable"，
# 这时不能把空哈希当 FAIL（产品没问题），也不能静默当 PASS；调用方按 SKIP 记账。
# 一次运行里包不会换，所以只探一次。
RUN_AS_STATE=""
RUN_AS_REASON=""
probe_run_as() {
  [ -n "$RUN_AS_STATE" ] && return 0
  local out
  out="$("$ADB" -s "$SERIAL" exec-out run-as "$PKG" id 2>&1 | tr -d '\r')"
  case "$out" in
    *"package not debuggable"*)
      RUN_AS_STATE="no"
      RUN_AS_REASON="package not debuggable（release 包）"
      ;;
    uid=*)
      RUN_AS_STATE="ok"
      RUN_AS_REASON="debuggable 包"
      ;;
    *)
      RUN_AS_STATE="no"
      RUN_AS_REASON="run-as 不可用：${out:-adb exec-out run-as 无输出}"
      ;;
  esac
  return 0
}

# 从应用私有目录把文件拉回本机（adb pull 读不了 /data/data，debuggable 包走 run-as）。
# 返回 0 = 拉到非空文件；2 = 能力不可用，调用方必须记 SKIP；1 = 能力可用但文件拉不到（真 FAIL）。
# 结果同时写进 PULL_PRIVATE_STATUS / PULL_PRIVATE_REASON，调用方不用猜 stderr。
PULL_PRIVATE_STATUS=""
PULL_PRIVATE_REASON=""
pull_private() {
  local device_path="$1" local_path="$2" err out
  PULL_PRIVATE_STATUS="fail"
  PULL_PRIVATE_REASON=""
  mkdir -p "$(dirname "$local_path")"
  rm -f "$local_path" "$local_path.err"
  probe_run_as
  if [ "$RUN_AS_STATE" != "ok" ]; then
    PULL_PRIVATE_STATUS="skip"
    PULL_PRIVATE_REASON="$RUN_AS_REASON"
    return 2
  fi
  err="$local_path.err"
  if "$ADB" -s "$SERIAL" exec-out run-as "$PKG" cat "$device_path" > "$local_path" 2>"$err" \
    && [ -s "$local_path" ]; then
    rm -f "$err"
    PULL_PRIVATE_STATUS="ok"
    return 0
  fi
  out="$(tr -d '\r' < "$err" 2>/dev/null | head -2 | tr '\n' ' ')"
  rm -f "$err" "$local_path"
  PULL_PRIVATE_REASON="${out:-文件不存在或为空：$device_path}"
  return 1
}

# ------------------------------------------------------------------ 0. 设备与链路

say "=== M1 真机验收 ====================================================="
say "root      : $ROOT"

ONLINE="$("$ADB" devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')"
COUNT="$(printf '%s\n' "$ONLINE" | grep -c . || true)"
[ "$COUNT" -ge 1 ] || die "没有在线设备（adb devices 为空）"

SERIAL="${M1_SERIAL:-$(printf '%s\n' "$ONLINE" | head -1)}"
MODEL="$(sh_ getprop ro.product.model | tr -d '\r')"
SDK="$(sh_ getprop ro.build.version.sdk | tr -d '\r')"
say "adb       : $ADB"
say "device    : $SERIAL  $MODEL  API $SDK"
say "install   : $( [ "$INSTALL" = "1" ] && echo "构建 debug 包并安装" || echo "M1_INSTALL=0，用设备上已装的包" )"
# run-as 要等安装/清场之后才能探：设备上还没装包时探到的「不可用」会被缓存，
# 装完 debug 包也一直记 SKIP（首次安装/卸载重装都会踩）。探测放在第 3 步。

CHAIN="android-desktop"
if [ "$COUNT" -ge 2 ]; then
  warn "检测到 $COUNT 台安卓在线；本次仍用 android-desktop（android-android 需要第二个 UI 驱动，M2 再做）"
else
  info "只有 1 台安卓在线（$SERIAL），用桌面 JVM 做对端"
fi
say "chain     : $CHAIN"
say "rounds    : $( [ "$QUICK" = "1" ] && echo 0 || echo "$STRESS_ROUNDS" ) x ${STRESS_MIB} MiB"

# 每次跑之前清掉上一次的证据目录（素材缓存保留），免得旧截图/旧日志冒充本次结果。
rm -rf "$ART/screenshots" "$ART/ui-dumps" "$ART/logs" "$ART/pulled"
mkdir -p "$ART" "$ART/screenshots" "$ART/ui-dumps" "$ART/logs" "$ART/payload" "$ART/pulled" "$ART/desktop"

# 两个实例同时点同一台手机，会把对方的手势和断言搅乱（踩过：5 个残留进程互相抢点击）。
LOCK="$ART/.running"
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK" 2>/dev/null)" 2>/dev/null; then
  die "已有另一个 m1-device.sh 在跑（pid $(cat "$LOCK")），先结束它"
fi
echo $$ > "$LOCK"

PEER_PID=""
PEER_LOG="$ART/logs/desktop-peer.log"
# 按需下载的控制文件：脚本往里写 download=<文件名>，桌面对端照办（step 12 的限速验收用）。
PEER_CONTROL="$ART/desktop/control.txt"
rm -f "$PEER_CONTROL"
cleanup() {
  if [ -n "$PEER_PID" ] && kill -0 "$PEER_PID" 2>/dev/null; then
    kill "$PEER_PID" 2>/dev/null
    sleep 0.3
    kill -9 "$PEER_PID" 2>/dev/null
  fi
  rm -f "$LOCK"
}
trap cleanup EXIT

# ------------------------------------------------------------------ 1. 构建

step "1/12 构建"
# 桌面对端永远用本仓库现编的 core；只有默认模式才构建并安装 app 的 debug 包。
GRADLE_TASKS=(:core:jar :core:m1Deps :core:m1Native)
if [ "$INSTALL" = "1" ]; then GRADLE_TASKS=(:app:assembleDebug "${GRADLE_TASKS[@]}"); fi
( cd "$ROOT" && ./gradlew --console=plain -q "${GRADLE_TASKS[@]}" ) \
  || die "gradle 构建失败"
if [ "$INSTALL" = "1" ]; then
  APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
  [ -f "$APK" ] || die "APK 不存在：$APK"
else
  info "M1_INSTALL=0：不构建 app，沿用设备上已装的 $PKG"
fi

CORE_JAR="$(ls "$ROOT"/core/build/libs/*.jar 2>/dev/null | head -1)"
[ -n "$CORE_JAR" ] || die "core jar 不存在"
PEER_CP="$(w "$CORE_JAR")"
for jar in "$ROOT"/core/build/m1-libs/*.jar; do
  [ -f "$jar" ] && PEER_CP="$PEER_CP;$(w "$jar")"
done
PEER_CLASSES="$ART/peer-classes"
mkdir -p "$PEER_CLASSES"
javac -encoding UTF-8 -cp "$PEER_CP" -d "$(w "$PEER_CLASSES")" "$(w "$ROOT/app/tools/peer/DesktopPeer.java")" \
  || die "javac 桌面对端失败"
PEER_CP="$PEER_CP;$(w "$PEER_CLASSES")"
PEER_NATIVE="$(w "$ROOT/core/build/m1-native/libtorrent4j.dll")"
[ -f "$ROOT/core/build/m1-native/libtorrent4j.dll" ] || die "libtorrent4j.dll 未解出"
if [ "$INSTALL" = "1" ]; then
  info "apk=$(du -h "$APK" | cut -f1) peer_classes=$PEER_CLASSES"
  SO_SHA="$(unzip -p "$APK" lib/arm64-v8a/libtorrent4j.so 2>/dev/null | sha256sum | cut -d' ' -f1)"
  SO_BYTES="$(unzip -l "$APK" 2>/dev/null | awk '/lib\/arm64-v8a\/libtorrent4j.so/{print $1}')"
  WANT_SHA="$(sha256_of "$ROOT/artifacts/build-libtorrent/run5-sde1/libtorrent4j.so")"
  chk "apk.selfbuilt_arm64_so" "$([ "$SO_SHA" = "$WANT_SHA" ] && echo 1 || echo 0)" "$SO_BYTES bytes"
else
  info "peer_classes=$PEER_CLASSES"
  chk_skip "apk.selfbuilt_arm64_so" "M1_INSTALL=0 不构建 APK"
fi

# ------------------------------------------------------------------ 2. 素材

step "2/12 素材与设备准备"
PAYLOAD="$ART/payload/m1-payload-32m.bin"
if [ ! -f "$PAYLOAD" ] || [ "$(stat -c %s "$PAYLOAD" 2>/dev/null)" != "33554432" ]; then
  head -c 33554432 /dev/urandom > "$PAYLOAD" || die "生成 32 MiB 素材失败"
fi
PAYLOAD_SHA="$(sha256_of "$PAYLOAD")"
info "payload 32 MiB sha256=$PAYLOAD_SHA"

# 限速验收（step 12）的三份素材：与主素材同尺寸，内容不同才能各自成种。
# 都是 32 MiB：512 KB/s 下要传 ~64 s、1024 KB/s 下 ~32 s，够量出稳态速率；
# 不限速时几秒传完，两档判据不会互相打架。三份各管一件事：
#   m1-throttle-32m.bin → P4 单文件限速（1024 KB/s）
#   m1-free-32m.bin     → P7 全局限速（512 KB/s）
#   m1-restore-32m.bin  → 清掉全局限制之后的恢复速率（> 2 MB/s）
THROTTLE_PAYLOAD="$ART/payload/m1-throttle-32m.bin"
FREE_PAYLOAD="$ART/payload/m1-free-32m.bin"
RESTORE_PAYLOAD="$ART/payload/m1-restore-32m.bin"
for speed_file in "$THROTTLE_PAYLOAD" "$FREE_PAYLOAD" "$RESTORE_PAYLOAD"; do
  if [ ! -f "$speed_file" ] || [ "$(stat -c %s "$speed_file" 2>/dev/null)" != "33554432" ]; then
    head -c 33554432 /dev/urandom > "$speed_file" || die "生成限速素材失败：$speed_file"
  fi
  info "限速素材 $(basename "$speed_file") sha256=$(sha256_of "$speed_file")"
done

STRESS_FILES=()
if [ "$QUICK" = "0" ]; then
  round=1
  while [ "$round" -le "$STRESS_ROUNDS" ]; do
    file="$ART/payload/m1-stress-${round}-${STRESS_MIB}m.bin"
    want=$(( STRESS_MIB * 1024 * 1024 ))
    if [ ! -f "$file" ] || [ "$(stat -c %s "$file" 2>/dev/null)" != "$want" ]; then
      head -c "$want" /dev/urandom > "$file" || die "生成压力素材失败"
    fi
    STRESS_FILES+=("$file")
    info "stress round $round: $(basename "$file") sha256=$(sha256_of "$file")"
    round=$((round + 1))
  done
fi

sh_ svc power stayon true >/dev/null 2>&1
sh_ settings put system screen_off_timeout 1800000 >/dev/null 2>&1
sh_ input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
sh_ wm dismiss-keyguard >/dev/null 2>&1

# ------------------------------------------------------------------ 3. 清场

step "3/12 清场并安装"
# 只清掉本脚本自己的对端（按命令行里的 DesktopPeer 认），不碰机器上别人的 java 进程。
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*DesktopPeer*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }" >/dev/null 2>&1
sh_ am force-stop "$PKG" >/dev/null 2>&1
sh_ pm clear "$PKG" >/dev/null 2>&1
if [ "$INSTALL" = "1" ]; then
  INSTALL_OUT="$("$ADB" -s "$SERIAL" install -r -t -g "$(w "$APK")" 2>&1)"
  printf '%s\n' "$INSTALL_OUT" | grep -E "Success|Failure|Error" | tail -1
  case "$INSTALL_OUT" in
    *Success*) ;;
    *) die "安装失败：$INSTALL_OUT
设备上若是别的签名包，先 adb uninstall $PKG 再跑（脚本不会拿旧包继续跑）。" ;;
  esac
else
  info "M1_INSTALL=0：不安装，沿用设备上已装的 $PKG"
fi
probe_run_as
if [ "$RUN_AS_STATE" = "ok" ]; then
  info "run-as    : 可用（debuggable 包），私有目录哈希走 pull_private"
else
  info "run-as    : 不可用（$RUN_AS_REASON）→ 私有目录哈希断言记 SKIP，手机侧自证改读 logcat sha256="
fi
"$ADB" -s "$SERIAL" logcat -b all -c >/dev/null 2>&1
info "logcat / crash buffer cleared"

for push_file in "$PAYLOAD" "$THROTTLE_PAYLOAD" "$FREE_PAYLOAD" "$RESTORE_PAYLOAD"; do
  push_name="$(basename "$push_file")"
  "$ADB" -s "$SERIAL" push "$(w "$push_file")" "/sdcard/Download/$push_name" 2>&1 | tail -1
  # adb push 会保留本地素材的 mtime（缓存可能是好几天前的），而 MIUI 的「下载」列表按修改时间
  # 倒序：旧 mtime 会被排到屏幕外，SAF 按名字永远看不到（2026-11-08 踩过）。touch 一下再扫。
  sh_ touch "/sdcard/Download/$push_name" >/dev/null 2>&1
  # 媒体库索引要立刻看到，SAF 的「下载」目录里才有这几行。
  sh_ "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/$push_name" >/dev/null 2>&1
done

# ------------------------------------------------------------------ 4. 桌面对端

step "4/12 启动桌面对端"
PHONE_IP="$(sh_ "ip -4 addr show wlan0" | grep -o 'inet [0-9.]*' | head -1 | awk '{print $2}')"
[ -n "$PHONE_IP" ] || die "手机没有 wlan0 IPv4"
PC_IP="$(node -e '
const os=require("os");const want=process.argv[1].split(".").slice(0,3).join(".");
for (const list of Object.values(os.networkInterfaces())) for (const i of list||[]) {
  if (i.family==="IPv4" && !i.internal && i.address.startsWith(want+".")) { console.log(i.address); process.exit(0); }
}
' "$PHONE_IP" 2>/dev/null)"
[ -n "$PC_IP" ] || die "找不到与手机同网段的桌面 IPv4（手机 $PHONE_IP）"
info "phone=$PHONE_IP desktop=$PC_IP"

PEER_DATA="$ART/desktop/data"
rm -rf "$PEER_DATA"
mkdir -p "$PEER_DATA"
# 压力文件路径要给 java 看，必须转成 Windows 形式；用 MSYS 路径它会 share_missing。
PEER_SHARE=""
if [ "${#STRESS_FILES[@]}" -gt 0 ]; then
  for stress_file in "${STRESS_FILES[@]}"; do
    if [ -z "$PEER_SHARE" ]; then
      PEER_SHARE="$(w "$stress_file")"
    else
      PEER_SHARE="$PEER_SHARE,$(w "$stress_file")"
    fi
  done
fi

# 第 2 轮（索引 1）限速 2 MB/s：真机 Wi-Fi 能跑到 14 MB/s，不限速的话 128 MiB 十几秒就完了，
# 「中途取消」根本来不及点。顺带把协议 §4 的单文件限速也走一遍。
PEER_THROTTLE=""
if [ "${#STRESS_FILES[@]}" -gt 1 ]; then
  PEER_THROTTLE="throttle=1:${M1_THROTTLE_KBPS:-2048}"
fi

# expect_comment=from-mix-1 是第 8 步的证据开关，不能省：对端一旦拿到 control=（它平时按脚本节奏干活），
# 就会把「等对端评论」的窗口收成 0，直接打 reply_seen ok=false 走过去 —— 那样它永远不打
# PEER op_received body=from-mix-1，第 8 步的 comment.android_to_pc 只能等到超时。
# 显式声明「我在等这条评论」，对端就照文档 §3 的顺序走：等到回评 → 再按 echo_delay_ms 补 from-pc-2。
java -Dlibtorrent4j.jni.path="$PEER_NATIVE" -Dfile.encoding=UTF-8 -cp "$PEER_CP" DesktopPeer host \
  data="$(w "$PEER_DATA")" work="$(w "$ART/desktop")" name="$PEER_NAME" msg_port=47100 bt_port=51413 \
  group_name="$GROUP_NAME" share="$PEER_SHARE" "$PEER_THROTTLE" hold=2400 \
  control="$(w "$PEER_CONTROL")" expect_comment="from-mix-1" \
  echo="from-pc-2" echo_delay_ms="$ECHO_DELAY_MS" \
  > "$PEER_LOG" 2>&1 &
PEER_PID=$!

TAIL8=""
MSG_PORT=""
for _ in $(seq 1 160); do
  TAIL8="$(grep -o 'tail8=[0-9A-Z]*' "$PEER_LOG" 2>/dev/null | head -1 | cut -d= -f2)"
  MSG_PORT="$(grep -o 'msg_port=[0-9]*' "$PEER_LOG" 2>/dev/null | head -1 | cut -d= -f2)"
  [ -n "$TAIL8" ] && [ -n "$MSG_PORT" ] && break
  kill -0 "$PEER_PID" 2>/dev/null || break
  sleep 0.5
done
if [ -z "$TAIL8" ] || [ -z "$MSG_PORT" ]; then
  tail -20 "$PEER_LOG"
  die "桌面对端没起来（没打印 tail8 / msg_port）"
fi
say "desktop   : tail8=$TAIL8 msg_port=$MSG_PORT pid=$PEER_PID"
chk "desktop.invite_code_16" "$(grep -qE 'invite=[0-9ABCDEFGHJKMNPQRSTVWXYZ]{16}' "$PEER_LOG" && echo 1 || echo 0)" "$TAIL8"

# 压力文件没共享出去就早点停：否则要等到第 9 步才发现 P3 上没有文件。
if [ "${#STRESS_FILES[@]}" -gt 0 ]; then
  if ! wait_log_peer "PEER shared_count" 60000; then
    tail -10 "$PEER_LOG"
    die "桌面对端没能共享压力文件（看 PEER share_missing）"
  fi
  SHARED_N="$(grep -o 'shared_count n=[0-9]*' "$PEER_LOG" | head -1 | cut -d= -f2)"
  chk "desktop.shared_stress_files" "$([ "${SHARED_N:-0}" = "${#STRESS_FILES[@]}" ] && echo 1 || echo 0)" "$SHARED_N 个"
fi

# ------------------------------------------------------------------ 5. 启动应用

step "5/12 启动应用并过 P0/P1"
sh_ am start -n "$PKG/$ACTIVITY" --es huchuan.debug.manual "$PC_IP:$MSG_PORT" 2>&1 | tail -1
if ! wait_log "app_kernel_ready" 40000; then
  applog | tail -20
  die "内核没起来（看 logcat tag=huchuan/m1）"
fi
chk "kernel.started" 1 "$(log_field app_kernel_ready device_id)"
chk "kernel.keystore_keyseed" "$(applog | grep -q 'keyseed_box=keystore-aes-gcm-1' && echo 1 || echo 0)" \
  "$(log_field app_kernel_ready keyseed_box)"
chk "kernel.multicast_lock" "$(applog | grep -q 'multicast_lock=true' && echo 1 || echo 0)"

# 首次装完的冷启动要做 dex2oat，老机器上可能要几十秒。P0 是自动放行的过场，
# 不能拿它的文字当门（跑得快的时候早就翻页了）；只等「应用窗口在前台」+「P1 出现」。
FORE=0
for _ in $(seq 1 90); do
  if app_foreground; then FORE=1; break; fi
  sleep 1
done
if [ "$FORE" = "0" ]; then
  screenshot "p0-no-window"
  die "应用窗口没起来（前台不是 $PKG）"
fi
if ! see_text "用户名" 120000; then
  screenshot "p0-stuck"
  uidump "p0-stuck"
  die "P0 没进 P1（自检卡住？）"
fi
screenshot "p1-username"
tap "保存"
sleep 1

# ------------------------------------------------------------------ 6. 只输后 8 位加入

step "6/12 P5 只输后 8 位加入"
if ! see_text "邀请码后 8 位" 25000; then
  screenshot "p5-missing"
  die "没进 P5（新群组页）"
fi
tap "邀请码后 8 位"
sh_ input text "$TAIL8" >/dev/null 2>&1
sleep 1
screenshot "p5-typed-tail8"
tap "加入"

if ! wait_log "group_joined" 90000; then
  screenshot "p5-join-failed"
  applog | grep -E "join_|group_" | tail -20
  die "加入失败（logcat 没有 group_joined）"
fi
JOIN_TAIL="$(log_field "join_candidates" tail8)"
chk "join.tail8_only" "$([ "$JOIN_TAIL" = "$TAIL8" ] && echo 1 || echo 0)" "input=$JOIN_TAIL"
JOIN_VIA="$(log_field "join_via" source)"
LAN_N="$(log_field "join_candidates" lan)"
LAN_ADDRS="$(log_field "join_candidates" addrs)"
JOIN_ADDR="$(log_field "join_via" addr)"
if [ "${LAN_N:-0}" -ge 1 ]; then
  # 协议 §3.4：同一个 endpoint 手动候选优先记 manual，所以 join_via 是 manual 也可能就是 mDNS 找到的那个地址。
  chk "join.mdns_discovered" 1 "lan=${LAN_N} addr=${LAN_ADDRS}"
  info "join_via=${JOIN_VIA:-none} addr=${JOIN_ADDR:-?}（mDNS 与手动候选同端点时按协议记 manual）"
else
  warn "mDNS 没有发现候选（lan=0）；靠手动 IP:port 兜底 addr=${JOIN_ADDR:-?}"
fi
if ! see_text "添加文件" 25000; then
  screenshot "p3-missing"
  die "加入后没进 P3"
fi
screenshot "p3-group"

# ------------------------------------------------------------------ 7. SAF 共享文件

step "7/12 P3 添加文件 → 本机文件 → SAF 选文件 → 做种"
PAYLOAD_NAME="$(basename "$PAYLOAD")"

# P3 的「添加文件」现在是一个入口两项的弹层：本机文件 / 磁力链接（public-swarm §6.1）。
# 老流程点完按钮直接等 SAF，弹层挡在上面，SAF 永远等不到 —— 必须先点「本机文件」。
#
# 弹层是 ModalBottomSheet，两行有滑入动画：等「磁力链接」进了无障碍树之后再留一拍，点才落得准。
# 「磁力链接」只在弹层里出现，所以它同时也是「弹层开着」的判据；开着就别再点按钮 ——
# 那时按钮在遮罩底下，点了只会把弹层关掉。
# 点完「本机文件」弹层立刻收起、DocumentsUI 接在前台，下一步照旧等 SAF 自己的标志。
open_local_picker() {
  if ! see_text "磁力链接" 1500; then
    tap "添加文件" || return 1
    if ! see_text "磁力链接" 20000; then
      screenshot "p3-add-sheet-missing"
      uidump "p3-add-sheet-missing"
      return 1
    fi
  fi
  sleep 1
  # 截图只留第一次：每多拍一张就多一次 uiautomator dump，真机上一个 dump 要 2-3 s，
  # 第 12 步连共享三份素材时这点开销会累起来（而且窗口没有变化，不需要每份都留证）。
  if [ "$ADD_SHEET_SHOT" = "0" ]; then
    screenshot "p3-add-sheet"
    ADD_SHEET_SHOT=1
  fi
  tap "本机文件" exact
}
ADD_SHEET_SHOT=0

# SAF 抽屉有动画：点完「显示根目录」要等抽屉真的展开再点「下载」，
# 中途点到遮罩只会把抽屉关掉。整段最多重来 3 次。
saf_pick() {
  local name="$1" attempt
  for attempt in 1 2 3; do
    if ! see_text "添加文件" 20000; then return 1; fi
    if ! open_local_picker; then
      warn "SAF 第 $attempt 次没打开选择器（添加文件弹层没走通）"
      sh_ input keyevent KEYCODE_BACK >/dev/null 2>&1
      sleep 1
      continue
    fi
    if ! see_text "显示根目录" 35000; then
      # SAF 没起来（弹层被遮罩吃掉、或 DocumentsUI 停在别的状态）：留下现场再退，别猜。
      warn "SAF 第 $attempt 次没起来（点完「本机文件」等不到「显示根目录」）"
      uidump "saf-not-opened$attempt"
      sh_ input keyevent KEYCODE_BACK >/dev/null 2>&1
      sleep 1
      continue
    fi
    sleep 1
    # 媒体库索引到的话「最近」里直接就有，省一步
    if see_text "$name" 3000; then
      screenshot "saf-recent"
      tap "$name" exact
      return 0
    fi
    # 「显示根目录」是抽屉开关：已经开着再点会关掉，先看「可用空间」这个抽屉标志。
    if ! see_text "可用空间" 3000; then
      tap "显示根目录" exact
      sleep 2
    fi
    if ! see_text "可用空间" 8000; then
      warn "SAF 抽屉没打开"
      uidump "saf-nodrawer$attempt"
      sh_ input keyevent KEYCODE_BACK >/dev/null 2>&1
      sleep 1
      continue
    fi
    tap "下载" exact
    sleep 2
    if see_text "中的文件" 15000 && see_text "$name" 15000; then
      screenshot "saf-downloads"
      tap "$name" exact
      return 0
    fi
    warn "SAF 第 $attempt 次没在下载目录里看到 $name"
    uidump "saf-attempt$attempt"
    sh_ input keyevent KEYCODE_BACK >/dev/null 2>&1
    sleep 1
  done
  return 1
}

saf_pick "$PAYLOAD_NAME" || die "SAF 选不到 $PAYLOAD_NAME"
sleep 2

if ! wait_log "file_seeding" 90000; then
  screenshot "p3-share-failed"
  applog | tail -20
  die "共享失败（logcat 没有 file_seeding）"
fi
SHARE_NAME="$(log_field "share_import" name)"
SHARE_BYTES="$(log_field "share_import" bytes)"
FILE_ID="$(log_field "file_seeding" file_id)"
chk "share.saf_copied" "$([ "$SHARE_BYTES" = "33554432" ] && echo 1 || echo 0)" "$SHARE_NAME $SHARE_BYTES B"
chk "share.seeding" "$([ -n "$FILE_ID" ] && echo 1 || echo 0)" "file_id=$FILE_ID"

DEVICE_SHARE_PATH="/data/data/$PKG/files/m1/share/$SHARE_NAME"
SEED_SHA=""
if pull_private "$DEVICE_SHARE_PATH" "$ART/pulled/android-seed-copy.bin"; then
  SEED_SHA="$(sha256_of "$ART/pulled/android-seed-copy.bin")"
  chk "share.copy_matches_payload" "$([ "$SEED_SHA" = "$PAYLOAD_SHA" ] && echo 1 || echo 0)" "sha=${SEED_SHA:0:16}"
elif [ "$PULL_PRIVATE_STATUS" = "skip" ]; then
  chk_skip "share.copy_matches_payload" "$PULL_PRIVATE_REASON；跨端一致性由 sha256.equal_android_desktop 守"
else
  chk "share.copy_matches_payload" 0 "pull 失败：$PULL_PRIVATE_REASON"
fi

# 手机侧自证：core 的 file_seeding 日志带 sha256=（只加日志字段）。
# release 包拉不了私有目录时这条不依赖 run-as；旧构建没有该字段就记 SKIP，不误判成产品问题。
SHARE_LOG_LINE="$(applog | grep -F 'event=file_seeding' | tail -1)"
SHARE_KERNEL_SHA="$(printf '%s' "$SHARE_LOG_LINE" | grep -o 'sha256=[0-9a-f]*' | tail -1 | cut -d= -f2)"
if printf '%s' "$SHARE_LOG_LINE" | grep -q 'sha256='; then
  chk "share.kernel_sha256" "$([ -n "$SHARE_KERNEL_SHA" ] && [ "$SHARE_KERNEL_SHA" = "$PAYLOAD_SHA" ] && echo 1 || echo 0)" \
    "file_seeding sha256=${SHARE_KERNEL_SHA:0:16}"
else
  chk_skip "share.kernel_sha256" "file_seeding 没有 sha256 字段（旧构建）"
fi

# ------------------------------------------------------------------ 8. 桌面下载 + 评论双向

step "8/12 桌面下载与评论双向"
if ! wait_log_peer "PEER download_done" 300000; then
  tail -30 "$PEER_LOG"
  die "桌面端没下载完"
fi
PC_DL_SHA="$(grep -o 'download_done .*sha256=[0-9a-f]*' "$PEER_LOG" | tail -1 | grep -o 'sha256=[0-9a-f]*' | cut -d= -f2)"
PC_DL_BYTES="$(grep -o 'download_done .*bytes=[0-9]*' "$PEER_LOG" | tail -1 | grep -o 'bytes=[0-9]*' | cut -d= -f2)"
chk "desktop.downloaded_32MiB" "$([ "$PC_DL_BYTES" = "33554432" ] && echo 1 || echo 0)" "$PC_DL_BYTES B"
chk "sha256.equal_android_desktop" "$([ "$PC_DL_SHA" = "$PAYLOAD_SHA" ] && echo 1 || echo 0)" "pc=${PC_DL_SHA:0:16}"

if ! see_text "from-pc-1" 150000; then
  screenshot "p4-missing-pc-comment"
  uidump "p4-no-pc-comment"
  die "手机界面上看不到桌面发的评论"
fi
screenshot "p4-pc-comment"
chk "comment.pc_to_android" 1 "from-pc-1"

tap "写评论"
sh_ input text "from-mix-1" >/dev/null 2>&1
sleep 1
tap "发送"
if ! wait_log_peer "body=from-mix-1" 150000; then
  screenshot "p4-reply-failed"
  tail -20 "$PEER_LOG"
  die "桌面端没收到手机的评论"
fi
chk "comment.android_to_pc" 1 "from-mix-1"
screenshot "p4-both-comments"

# ------------------------------------------------------------------ 9. 未读角标（G4）

step "9/12 未读角标：P2 出数，进群/进文件清零"

# 未读角标只在 P2 上看得到，所以先退回首页；桌面端在这之后才补发评论（echo。
# 手机不在群组页上时收到的新评论，正是 P2 该显示未读的那种。
back_to_home || die "回不到 P2（未读角标要停在首页看）"
if ! wait_log "body=from-pc-2" 180000; then
  screenshot "p2-no-echo"
  applog | tail -20
  die "桌面端没补发评论（echo=from-pc-2，延迟 ${ECHO_DELAY_MS}ms）"
fi
if ! see_text "条新消息" 30000; then
  screenshot "p2-no-badge"
  uidump "p2-no-badge"
  die "P2 群行没出现未读角标"
fi
BADGE="$(badge_text)"
BADGE_N="$(printf '%s' "$BADGE" | grep -o '[0-9]*' | head -1)"
chk "unread.badge_on_p2" "$([ "${BADGE_N:-0}" -ge 1 ] && echo 1 || echo 0)" "${BADGE:-none}"
screenshot "p2-unread"

# 进群：P3 一进来就 markGroupRead，整群未读清零（spec 的「{n} 条新消息」随之消失）。
tap "$GROUP_NAME"
if ! see_text "添加文件" 25000; then
  screenshot "p3-after-badge"
  uidump "p3-after-badge"
  die "点群行没进 P3"
fi
CLEARED_GROUP="$(log_field "ui_mark_read scope=group" cleared)"
chk "unread.marked_on_group_open" "$([ "${CLEARED_GROUP:-0}" -ge 1 ] && echo 1 || echo 0)" "cleared=$CLEARED_GROUP"

# 组队通告 0 态（spec §306）：本机在这个群里只有「做种中的自己的文件」，没有在下任何东西，
# 队友数必然 0 —— 0 就是不显示，P3 行内不该出现「组队」这枚次级 chip。
P3_TEXTS_NO_TEAM="$(ui_texts)"
chk "team.p3_no_chip_at_0" "$(printf '%s' "$P3_TEXTS_NO_TEAM" | grep -q '组队' && echo 0 || echo 1)" \
  "0 队友时 P3 不出现「组队」（不是「组队 0 人」）"
screenshot "p3-no-team-chip"

# 进文件：P4 打开就 markFileRead，这条文件的游标推到最后一条可见 op。
if ! see_text "$PAYLOAD_NAME" 20000; then
  screenshot "p3-no-file"
  die "P3 上没有 $PAYLOAD_NAME"
fi
tap "$PAYLOAD_NAME" exact
if ! see_text "写评论" 25000; then
  screenshot "p4-after-badge"
  uidump "p4-after-badge"
  die "点文件行没进 P4"
fi
MARK_FILE="$(applog | grep -F 'ui_mark_read scope=file' | tail -1)"
chk "unread.marked_on_file_open" "$(printf '%s' "$MARK_FILE" | grep -q "file_id=$FILE_ID" && echo 1 || echo 0)" "file_id=$FILE_ID"

# 同一个 0 态在 P4 上的对应断言：不出现「正在组队 · N 人」这一行。
P4_TEXTS_NO_TEAM="$(ui_texts)"
chk "team.p4_no_line_at_0" "$(printf '%s' "$P4_TEXTS_NO_TEAM" | grep -q '正在组队' && echo 0 || echo 1)" \
  "0 队友时 P4 不出现「正在组队」"
screenshot "p4-no-team-line"

# 回 P2：角标必须消失。这里的「消失」是内核未读归零的结果，不是界面自己记的账。
back_to_home || die "回不到 P2"
if see_text "条新消息" 4000; then
  screenshot "p2-badge-stuck"
  uidump "p2-badge-stuck"
  die "进群与进文件后 P2 角标没消失"
fi
LAST_UNREAD="$(log_field "ui_unread" unread)"
chk "unread.badge_cleared" "$([ "${LAST_UNREAD:-1}" = "0" ] && echo 1 || echo 0)" "kernel unread=$LAST_UNREAD"
screenshot "p2-read"

# 压力测试的起点是 P3（文件行在这里）。
tap "$GROUP_NAME"
see_text "添加文件" 25000 || die "回不到 P3（压力测试起点）"

# ------------------------------------------------------------------ 10. 崩溃压力

if [ "$QUICK" = "1" ]; then
  step "10/12 崩溃压力（--quick 跳过）"
  warn "跳过压力测试"
else
  step "10/12 崩溃压力 ${STRESS_ROUNDS} x ${STRESS_MIB} MiB（第 2 轮中途取消一次）"
  back_to_group || die "回不到 P3（软键盘/返回栈异常）"

  ROUND=1
  for stress in "${STRESS_FILES[@]}"; do
    NAME="$(basename "$stress")"
    SHA="$(sha256_of "$stress")"
    info "round $ROUND: $NAME"

    if ! see_text "$NAME" 40000; then
      uidump "p3-round$ROUND"
      die "P3 上没有 $NAME"
    fi
    tap "$NAME" exact
    sleep 1

    STARTS="$(count_log "download_start")"
    tap "激活开关"
    if ! wait_log_count "download_start" $((STARTS + 1)) 90000; then
      screenshot "p4-activate-failed-$ROUND"
      applog | tail -20
      die "第 $ROUND 轮激活后没开始下载"
    fi

    # 本轮文件的 file_id（进度按它过滤）
    FILE_ID_ROUND="$(applog | grep -F "ui_file_added" | grep -F "name=$NAME" | grep -o 'file_id=[a-f0-9]*' | head -1 | cut -d= -f2)"
    if [ -z "$FILE_ID_ROUND" ]; then
      uidump "p3-round$ROUND"
      die "日志里找不到 $NAME 的 file_id"
    fi

    if [ "$ROUND" = "2" ]; then
      if ! wait_progress "$FILE_ID_ROUND" 25 240000; then
        screenshot "p4-cancel-no-progress"
        die "第 2 轮等不到 25% 进度"
      fi
      # 取消必须发生在「还没下完」的时候；下完了就说明限速没生效，这一轮的取消是假的。
      DONE_AT_CANCEL="$(count_log "ui_file_completed")"
      if [ "$DONE_AT_CANCEL" -gt "$((ROUND - 1))" ]; then
        screenshot "p4-cancel-too-fast"
        die "第 2 轮在取消前就下完了（限速没生效？调大 --mib 或设 M1_THROTTLE_KBPS）"
      fi
      screenshot "p4-round2-before-cancel"
      tap "激活开关"
      if ! wait_log "file_stopped" 40000; then
        die "休眠没有真正停下（logcat 没有 file_stopped）"
      fi
      chk "stress.cancel_hibernate" 1 "round 2 @ >=25%"
      sleep 5
      AFTER_STOP="$(applog | awk '/file_stopped/{flag=1} flag && /download_progress/' | wc -l | tr -d ' ')"
      chk "stress.cancel_stops_transfer" "$([ "$AFTER_STOP" = "0" ] && echo 1 || echo 0)" "stop 后新增进度行 $AFTER_STOP"
      screenshot "p4-round2-hibernated"
      RESTARTS="$(count_log "download_start")"
      tap "激活开关"
      wait_log_count "download_start" $((RESTARTS + 1)) 90000 || die "第 2 轮重新激活失败"
      chk "stress.resume_after_cancel" 1
    fi

    DONE_BEFORE="$(count_log "ui_file_completed")"
    if ! wait_log_count "ui_file_completed" $((DONE_BEFORE + 1)) 900000; then
      screenshot "p4-download-timeout-$ROUND"
      die "第 $ROUND 轮下载没完成"
    fi
    sleep 1
    screenshot "p4-round$ROUND-done"

    DEVICE_FILE="/data/data/$PKG/files/m1/downloads/$NAME"
    PULLED="$ART/pulled/round$ROUND-$NAME"
    PULL_SHA=""
    if pull_private "$DEVICE_FILE" "$PULLED"; then
      PULL_SHA="$(sha256_of "$PULLED")"
      chk "stress.round$ROUND.sha256" "$([ "$PULL_SHA" = "$SHA" ] && echo 1 || echo 0)" "android=${PULL_SHA:0:16} pc=${SHA:0:16}"
    elif [ "$PULL_PRIVATE_STATUS" = "skip" ]; then
      chk_skip "stress.round$ROUND.sha256" "$PULL_PRIVATE_REASON"
    else
      chk "stress.round$ROUND.sha256" 0 "pull 失败：$PULL_PRIVATE_REASON"
    fi

    # 手机侧自证：core 的 download_completed 日志带 sha256=（只加日志字段），按 file_id 取本轮。
    DL_LOG_LINE="$(applog | grep -F 'event=download_completed' | grep -F "file_id=$FILE_ID_ROUND" | tail -1)"
    DL_KERNEL_SHA="$(printf '%s' "$DL_LOG_LINE" | grep -o 'sha256=[0-9a-f]*' | tail -1 | cut -d= -f2)"
    if printf '%s' "$DL_LOG_LINE" | grep -q 'sha256='; then
      chk "stress.round$ROUND.kernel_sha256" "$([ -n "$DL_KERNEL_SHA" ] && [ "$DL_KERNEL_SHA" = "$SHA" ] && echo 1 || echo 0)" \
        "download_completed sha256=${DL_KERNEL_SHA:0:16} pc=${SHA:0:16}"
    else
      chk_skip "stress.round$ROUND.kernel_sha256" "download_completed 没有 sha256 字段（旧构建）"
    fi

    back_to_group || die "第 $ROUND 轮结束后回不到 P3"
    ROUND=$((ROUND + 1))
  done
fi

# ------------------------------------------------------------------ 11. 做种计数与重启续种（G2）

step "11/12 做种计数（P6）与重启续种"

# 对端自报的做种数（协议 §10 peer.announce.seeds）= 它共享出去的文件数 + 下载完成转做种的那个。
# 缺陷现场（docs/m1-device.md §6-6）：下载完成不进做种集合，对端自报停在共享数，
# 于是它明明在给本机做种，P6 上却写「做种 0」。这里断言的是 +1 之后的绝对值。
PEER_DEV="$(grep -o 'device_id=[A-Z0-9]*' "$PEER_LOG" 2>/dev/null | head -1 | cut -d= -f2)"
PEER_SHARED="$(grep -o 'shared_count n=[0-9]*' "$PEER_LOG" 2>/dev/null | head -1 | cut -d= -f2)"
PEER_SHARED="${PEER_SHARED:-0}"
PEER_SEEDS_WANT=$((PEER_SHARED + 1))
PEER_SEEDS_FIRST="$(applog | grep -F "peer_announce dev=$PEER_DEV" | grep -o 'seeds=[0-9]*' | head -1 | cut -d= -f2)"
PEER_SEEDS_LAST="$(applog | grep -F "peer_announce dev=$PEER_DEV" | grep -o 'seeds=[0-9]*' | tail -1 | cut -d= -f2)"

back_to_group || die "回不到 P3（做种计数检查起点）"
screenshot "final-p3"
tap "成员（2 人）" >/dev/null 2>&1 || true
if ! see_text "邀请码" 20000; then
  screenshot "p6-missing"
  uidump "p6-missing"
  die "进不了 P6"
fi
sleep 1
screenshot "final-members"
P6_TEXTS="$(dks ui-dump --texts 2>/dev/null)"

# P6 的平台列来自 MemberRecord（协议 §10 peer.announce.plat）：本机 android、桌面 windows。
P6_OK=0
if printf '%s' "$P6_TEXTS" | grep -q 'value=Android' && printf '%s' "$P6_TEXTS" | grep -q 'value=Windows'; then P6_OK=1; fi
chk "p6.member_platform" "$P6_OK" "Android + Windows"
P6_SEEDS="$(printf '%s' "$P6_TEXTS" | grep -c '做种 ' || true)"
chk "p6.seeding_counts" "$([ "${P6_SEEDS:-0}" -ge 2 ] && echo 1 || echo 0)" "$P6_SEEDS 行"

# (a) 对端那一行：下载完成之后必须比「只有共享文件」时多 1（quick 模式下就是从 0 变 1）。
PEER_ROW="$(member_seeding "$PEER_NAME" "$P6_TEXTS")"
chk "p6.peer_seeding_after_download" "$([ -n "$PEER_ROW" ] && [ "$PEER_ROW" = "$PEER_SEEDS_WANT" ] && echo 1 || echo 0)" \
  "对端[$PEER_NAME]行=做种 ${PEER_ROW:-?}，期望 $PEER_SEEDS_WANT（共享 $PEER_SHARED + 下载完成 1）"
chk "peer_announce.seeds_after_download" "$([ -n "$PEER_SEEDS_LAST" ] && [ "$PEER_SEEDS_LAST" = "$PEER_SEEDS_WANT" ] && echo 1 || echo 0)" \
  "对端自报 seeds 首次 ${PEER_SEEDS_FIRST:-?} → 末次 ${PEER_SEEDS_LAST:-?}"

SELF_SEEDS_BEFORE="$(member_seeding "$MODEL" "$P6_TEXTS")"
chk "p6.self_seeding_row" "$([ -n "$SELF_SEEDS_BEFORE" ] && [ "$SELF_SEEDS_BEFORE" -ge 1 ] 2>/dev/null && echo 1 || echo 0)" \
  "本机[$MODEL]行=做种 ${SELF_SEEDS_BEFORE:-?}"

# ---------------------------------------------------------------- (b) 杀掉进程后冷启动
# force-stop 是真的把进程杀掉：不跑收尾、不再写一次 state.json。
# 重启续种只能靠上一份 state.json，于是「进程死掉」这件事本身就是被测对象。
KERNEL_READY_BEFORE="$(count_log "app_kernel_ready")"
RESUME_DONE_BEFORE="$(count_log "seed_resume_done")"
# 重连证据的基线：内核重连器的日志、对端的 inbound 绑定，都要「重启之后又多一条」才算数。
# 首次启动是 pm clear 之后的全新进程（没有恢复出来的群），重连器根本没起，所以两项基线都是 0。
RECONNECT_ATTEMPTS_BEFORE="$(count_log "reconnect_attempt")"
PEER_BOUND_IN_BEFORE="$(count_peer_bound_in)"
APP_PID_BEFORE="$(sh_ pidof "$PKG" | tr -d '\r')"
sh_ am force-stop "$PKG" >/dev/null 2>&1
sleep 2
APP_PID_AFTER="$(sh_ pidof "$PKG" | tr -d '\r')"
chk "restart.process_killed" "$([ -n "$APP_PID_BEFORE" ] && [ -z "$APP_PID_AFTER" ] && echo 1 || echo 0)" \
  "pid ${APP_PID_BEFORE:-?} → ${APP_PID_AFTER:-无}"
[ -z "$APP_PID_AFTER" ] || die "force-stop 之后进程还在，这不是冷启动"

sh_ am start -n "$PKG/$ACTIVITY" --es huchuan.debug.manual "$PC_IP:$MSG_PORT" 2>&1 | tail -1
if ! wait_log_count "app_kernel_ready" $((KERNEL_READY_BEFORE + 1)) 90000; then
  screenshot "restart-kernel-failed"
  applog | tail -20
  die "冷启动后内核没起来"
fi
if ! wait_log_count "seed_resume_done" $((RESUME_DONE_BEFORE + 1)) 60000; then
  screenshot "restart-no-resume"
  applog | grep -E "seed_|state_loaded" | tail -20
  die "冷启动后没有 seed_resume_done"
fi
RESUMED="$(log_field "seed_resume_done" resumed)"
SKIPPED="$(log_field "seed_resume_done" skipped)"
chk "restart.seed_resume_done" "$([ "${RESUMED:-0}" -ge 1 ] 2>/dev/null && [ "${SKIPPED:-1}" = "0" ] && echo 1 || echo 0)" \
  "seed_resume_done resumed=${RESUMED:-?} skipped=${SKIPPED:-?}"
chk "restart.seed_resumed_logged" "$(applog | grep -F 'seed_resumed' | grep -qF "file_id=$FILE_ID" && echo 1 || echo 0)" \
  "seed_resumed file_id=$FILE_ID"

# ---------------------------------------------------------------- (c) 冷启动自动重连（M1.4 restart.reconnect 组）
# 内核的 PeerReconnector 冷启动后自己拨回去：候选 = 落盘端点(peer) → 手动地址(manual) → mDNS(lan)，
# 指数退避 1/2/4/8/16/30 s、每群并发 3，绑上走 hello、不重新 join。
# 四条证据缺一条都不算「自动重连」，各占一个 CHECK：
#   ① kernel_started reconnect=on        —— 重连器真的起来了（restored_groups ≥ 1 才会起）
#   ② reconnect_attempt sources 含 peer  —— 挑的是上次连过的端点，不是靠 adb 塞的手动地址
#   ③ peer_reconnected elapsed_ms ≤ 5 s  —— 绑定成功，且快到用户看不见
#   ④ 对端 peer_bound role=in 多一条      —— 这条链路由手机自己拨出，对端看到的是 inbound
wait_log_count "reconnect_attempt" $((RECONNECT_ATTEMPTS_BEFORE + 1)) 60000 \
  || warn "冷启动后 60 s 内没有新的 reconnect_attempt（下面四条照实判）"

KERNEL_RECONNECT="$(log_field "kernel_started" reconnect)"
RESTORED_GROUPS="$(log_field "kernel_started" restored_groups)"
chk "restart.reconnect_kernel_on" "$([ "$KERNEL_RECONNECT" = "on" ] && echo 1 || echo 0)" \
  "kernel_started reconnect=${KERNEL_RECONNECT:-none} restored_groups=${RESTORED_GROUPS:-?}"

# sources 是候选来源的逗号列表（优先级 peer > manual > lan，同一 host:port 先出现者赢）。
# adb 的 huchuan.debug.manual 与落盘端点常常是同一个地址、按去重规则记成 peer，
# 所以这里断言「这一段里有 peer」，不断言整段相等：同时用上 mDNS 候选是正常的。
RECONNECT_SOURCES="$(applog | grep -F 'event=reconnect_attempt' | grep -o 'sources=[^ ]*' | tail -1 | cut -d= -f2)"
RECONNECT_SRC_PEER=0
if printf '%s' "$RECONNECT_SOURCES" | tr ',' '\n' | grep -qx 'peer'; then RECONNECT_SRC_PEER=1; fi
chk "restart.reconnect_attempt_peer" "$RECONNECT_SRC_PEER" "sources=${RECONNECT_SOURCES:-none}"

RECONNECT_BIND="$(applog | grep -F 'event=peer_reconnected' | tail -1)"
RECONNECT_ADDR="$(printf '%s' "$RECONNECT_BIND" | grep -o 'addr=[^ ]*' | cut -d= -f2)"
RECONNECT_DEV="$(printf '%s' "$RECONNECT_BIND" | grep -o 'dev=[A-Z0-9]*' | cut -d= -f2)"
RECONNECT_SRC="$(printf '%s' "$RECONNECT_BIND" | grep -o 'source=[a-z]*' | cut -d= -f2)"
RECONNECT_MS="$(printf '%s' "$RECONNECT_BIND" | grep -o 'elapsed_ms=[0-9]*' | cut -d= -f2)"
RECONNECT_OK=0
if [ -n "${RECONNECT_MS:-}" ] && [ "${RECONNECT_MS:-999999}" -le "$RECONNECT_MAX_MS" ] 2>/dev/null \
  && { [ -z "$PEER_DEV" ] || [ "$RECONNECT_DEV" = "$PEER_DEV" ]; }; then RECONNECT_OK=1; fi
chk "restart.reconnect_bind_5s" "$RECONNECT_OK" \
  "peer_reconnected addr=${RECONNECT_ADDR:-none} source=${RECONNECT_SRC:-none} dev=${RECONNECT_DEV:-none}（对端 $PEER_DEV）elapsed_ms=${RECONNECT_MS:-none} ≤ ${RECONNECT_MAX_MS}"

wait_peer_bound_in $((PEER_BOUND_IN_BEFORE + 1)) 30000 \
  || warn "对端 30 s 内没有新的 peer_bound role=in"
PEER_BOUND_IN_AFTER="$(count_peer_bound_in)"
chk "restart.reconnect_peer_bound_in" \
  "$([ "${PEER_BOUND_IN_AFTER:-0}" -gt "${PEER_BOUND_IN_BEFORE:-0}" ] && echo 1 || echo 0)" \
  "desktop-peer.log peer_bound role=in ${PEER_BOUND_IN_BEFORE:-0} → ${PEER_BOUND_IN_AFTER:-0}"
screenshot "restart-reconnect"

# force-stop 不会收起输入法，冷启动时键盘可能还盖着半屏；先按 ESC 收掉，截图才是干净的一页。
sh_ input keyevent 111 >/dev/null 2>&1 || true
sleep 1

# 进群：用户名已经存过，P0 自检过完会直接落到 P2；点群行进 P3。
if ! see_text "搜索群组" 120000; then
  screenshot "restart-p2-missing"
  uidump "restart-p2-missing"
  die "冷启动后没进 P2"
fi
screenshot "restart-p2"
tap "$GROUP_NAME"
if ! see_text "添加文件" 30000; then
  screenshot "restart-p3-missing"
  uidump "restart-p3-missing"
  die "冷启动后进不了 P3"
fi
sleep 1
screenshot "restart-p3"
P3_TEXTS="$(dks ui-dump --texts 2>/dev/null)"
chk "restart.p3_chip_seeding" "$(printf '%s' "$P3_TEXTS" | grep -q 'value=做种中' && echo 1 || echo 0)" \
  "P3 状态仍是做种中（读 localRole，不再靠盘上有没有文件推测）"

# P4 的开关同理由内核状态驱动：做种中 → 开。UI 上 Switch 的 checked 只有 XML 里有。
if ! see_text "$PAYLOAD_NAME" 20000; then
  screenshot "restart-p3-no-file"
  die "重启后 P3 上没有 $PAYLOAD_NAME"
fi
tap "$PAYLOAD_NAME" exact
if ! see_text "写评论" 25000; then
  screenshot "restart-p4-missing"
  uidump "restart-p4-missing"
  die "重启后点文件行进不了 P4"
fi
P4_XML="$(uidump_path "restart-p4")"
P4_SWITCH=""
if [ -n "$P4_XML" ] && [ -f "$P4_XML" ]; then
  P4_SWITCH="$(tr '<' '\n' < "$P4_XML" | grep -o 'checkable="true" checked="[a-z]*"' | head -1 | sed 's/.*checked="//;s/"//')"
fi
chk "restart.p4_switch_on" "$([ "$P4_SWITCH" = "true" ] && echo 1 || echo 0)" "P4 激活开关 checked=${P4_SWITCH:-none}"
screenshot "restart-p4"

# 回 P6：本机那一行的做种数与重启前一致（quick 模式下就是 1）。
back_to_group || die "回不到 P3（重启后续种检查）"
tap "成员（2 人）" >/dev/null 2>&1 || true
if ! see_text "邀请码" 20000; then
  screenshot "restart-p6-missing"
  uidump "restart-p6-missing"
  die "重启后进不了 P6"
fi
sleep 1
screenshot "restart-members"
P6_TEXTS_AFTER="$(dks ui-dump --texts 2>/dev/null)"
SELF_SEEDS_AFTER="$(member_seeding "$MODEL" "$P6_TEXTS_AFTER")"
chk "restart.self_seeding_kept" "$([ -n "$SELF_SEEDS_AFTER" ] && [ "$SELF_SEEDS_AFTER" = "$SELF_SEEDS_BEFORE" ] && echo 1 || echo 0)" \
  "本机做种数 重启前 ${SELF_SEEDS_BEFORE:-?} → 重启后 ${SELF_SEEDS_AFTER:-?}"

# P6 的在线状态是内核连接表的实时投影（M1Runtime 订阅 KernelEvents.peersChanged 后重读一次）。
# 冷启动那一刻连接表是空的（对端一开始必然是「离线」），只有这次重连触发的 peersChanged 重读
# 能把它变回「在线」—— 界面不轮询，缓存也不会自己变。
PEER_ONLINE_AFTER="$(member_status "$PEER_NAME" "$P6_TEXTS_AFTER")"
UI_PEERS_ONLINE="$(applog | grep -F 'event=ui_peers_changed' | grep -c 'online=true' || true)"
chk "restart.reconnect_p6_online" "$([ "$PEER_ONLINE_AFTER" = "在线" ] && echo 1 || echo 0)" \
  "P6 对端[$PEER_NAME]行=${PEER_ONLINE_AFTER:-?}（ui_peers_changed online=true ${UI_PEERS_ONLINE:-0} 条，实时状态不是缓存）"
screenshot "restart-p6-online"

# ------------------------------------------------------------------ 12. 组队通告（0 态）与 P7 全局限速

step "12/12 组队通告（0 态）与限速（P4 单文件 / P7 全局）"

# 组队的两条 0 态断言在 step 9 已经拍过（P3 无 chip、P4 无「正在组队」）。这里补一条日志级的否定证据：
# 整个流程里内核从没报过 teammates_changed count≥1 —— 队友数只在真的有人互连时才变，界面不靠定时器刷。
#
# 判据必须锚定 event= 再取 count。日志行是
#   event=teammates_changed group_id=… file_id=… count=1 peers=2
# 中间隔着 group_id / file_id，写成 grep -o 'teammates_changed count=' 永远匹配不到，
# 这条断言就成了恒真（2026-11-06 修）。可证伪性用两份真实日志对拍过：
#   artifacts/m1-device/logs/app-logcat.log   （0 态：队友从没出现）→ 0 条 → PASS
#   artifacts/team-verify/logs/app-logcat.log （队友 1 人，m2-team-verify.sh 留证）→ 1 条 → FAIL
TEAM_EVENTS="$(applog | grep -F 'event=teammates_changed' | grep -o 'count=[0-9]*' \
  | cut -d= -f2 | awk '$1+0 > 0 {n++} END {print n+0}')"
chk "team.no_teammate_events" "$([ "${TEAM_EVENTS:-0}" = "0" ] && echo 1 || echo 0)" \
  "event=teammates_changed 里 count>0 的条数：${TEAM_EVENTS:-0}"

# ① 先把用得到的两份素材共享进群：torrent 先进会话，限速才有「对已存在的 torrent 也生效」可验。
back_to_group || die "回不到 P3（限速验收起点）"
THROTTLE_NAME="$(basename "$THROTTLE_PAYLOAD")"
FREE_NAME="$(basename "$FREE_PAYLOAD")"
RESTORE_NAME="$(basename "$RESTORE_PAYLOAD")"
for share_name in "$THROTTLE_NAME" "$FREE_NAME"; do
  SEED_BEFORE="$(count_log "event=file_seeding")"
  saf_pick "$share_name" || die "SAF 选不到 $share_name"
  if ! wait_log_count "event=file_seeding" $((SEED_BEFORE + 1)) 90000; then
    screenshot "p12-share-failed"
    applog | tail -20
    die "共享素材失败：$share_name"
  fi
  back_to_group || die "回不到 P3（共享 $share_name 之后）"
done
screenshot "p12-files-seeding"

# ② P4 单文件限速 1024 KB/s：进该文件的 P4 → 打开「限制速度」开关 → 点 KB/s 聚焦输入框 → 输入 1024。
# 走的是「一文件一 torrent + TorrentHandle.setUploadLimit」，与 P7 的 session 级限速是两层。
if ! see_text "$THROTTLE_NAME" 20000; then
  screenshot "p12-p3-no-throttle-file"
  die "P3 上没有 $THROTTLE_NAME"
fi
tap "$THROTTLE_NAME" exact
if ! see_text "限制速度" 25000; then
  screenshot "p12-p4-missing"
  uidump "p12-p4-missing"
  die "点文件行没进 P4"
fi
# P4 的限速入口是「限制速度」开关：打开后才出现 KB/s 输入框。开关已经开着时不要再点，
# 否则会把它关掉（applyFileLimit(false) 会下发 0）；用 KB/s 输入框是否出现判开关状态。
if ! see_text "KB/s" 2000; then
  tap "限制速度" exact >/dev/null 2>&1 || die "点不开「限制速度」开关"
  see_text "KB/s" 8000 || die "打开「限制速度」后没有 KB/s 输入框"
fi
tap "KB/s" >/dev/null 2>&1 || die "点不进单文件限速输入框"
sleep 1
sh_ input text "1024" >/dev/null 2>&1
sleep 2
sh_ input keyevent 111 >/dev/null 2>&1 || true   # ESC 收键盘，截图别被键盘挡
screenshot "p12-p4-file-limit-1024"
FILE_LIMIT_SET="$(applog | grep -F 'event=file_upload_limit' | tail -1)"
chk "limit.file_set_1024" "$(printf '%s' "$FILE_LIMIT_SET" | grep -q 'kbps=1024 applied=true' && echo 1 || echo 0)" \
  "$(printf '%s' "$FILE_LIMIT_SET" | grep -o 'kbps=[0-9]* applied=[a-z]*')"
back_to_group || die "回不到 P3（P4 设完限速之后）"

# ③ 桌面端下载这份 32 MiB：实测速率必须贴着 1024 KB/s（留 20% 余量 → ≤1229；下限 400 防「压根没传」）。
peer_download "$THROTTLE_NAME"
if ! wait_peer_auto_done "$THROTTLE_NAME" 420; then
  tail -20 "$PEER_LOG"
  die "桌面端没下完 $THROTTLE_NAME"
fi
FILE_KBPS="$(peer_auto_kbps "$THROTTLE_NAME")"
FILE_STEADY="$(peer_auto_steady_kbps "$THROTTLE_NAME")"
# 判据分两截：上限 = 标称 1024 留 20% 余量（1229）；下限只证明「确实在传」。
# 稳态速率才是限速的读数：均速会被建链/起步稀释（实测同一套参数均速 327、稳态 981）。
chk "limit.file_1024_enforced" \
  "$([ "${FILE_STEADY:-0}" -ge 100 ] 2>/dev/null && [ "${FILE_STEADY:-0}" -le 1229 ] 2>/dev/null && echo 1 || echo 0)" \
  "桌面稳态 ${FILE_STEADY:-?} KB/s、均速 ${FILE_KBPS:-?} KB/s（标称 1024，判据 ≤1229；不限速时 >8000）"

# ④ 清掉单文件限速：关掉「限制速度」开关 = 0 = 不限（真的把 handle 上的限制清掉，不是「跳过不设」）。
enter_group || die "回不到 P3（清单文件限速）"
tap "$THROTTLE_NAME" exact
see_text "限制速度" 25000 || die "进不了 P4（清单文件限速）"
tap "限制速度" exact >/dev/null 2>&1 || die "关不掉「限制速度」开关"
sleep 2
sh_ input keyevent 111 >/dev/null 2>&1 || true
screenshot "p12-p4-file-limit-cleared"
FILE_LIMIT_CLEARED="$(applog | grep -F 'event=file_upload_limit' | tail -1)"
chk "limit.file_cleared" "$(printf '%s' "$FILE_LIMIT_CLEARED" | grep -q 'kbps=0 applied=true' && echo 1 || echo 0)" \
  "$(printf '%s' "$FILE_LIMIT_CLEARED" | grep -o 'kbps=[0-9]* applied=[a-z]*')"
back_to_group || die "回不到 P3（清单文件限速之后）"

# ⑤ P7 全局限速 512 KB/s（上行；下行不动）。界面标注的单位就是 KB/s，输入的数直接是 KB/s。
p7_upload_limit 512 "p12-p7-limit-512" || die "P7 设不了 512 KB/s 上行限速"
LIMIT_UP="$(log_field "event=global_limit_set" up_kbps)"
chk "limit.global_set_512" "$([ "$LIMIT_UP" = "512" ] && echo 1 || echo 0)" \
  "global_limit_set up_kbps=${LIMIT_UP:-?}（单位 KB/s）"
# 读回校验：下发的是 512，内核会话里生效的也必须是 512（防「设置成功但没生效」）。
chk "limit.global_applied" \
  "$(applog | grep -q 'event=global_limit_applied up_kbps=512 .*session_up_kbps=512 .*ok=true' && echo 1 || echo 0)" \
  "$(applog | grep -o 'event=global_limit_applied .*ok=[a-z]*' | tail -1)"

# ⑥ 桌面端下载第二份 32 MiB：被 session 级 512 KB/s 限住（留 20% 余量 → ≤600 KB/s）。
peer_download "$FREE_NAME"
if ! wait_peer_auto_done "$FREE_NAME" 420; then
  tail -20 "$PEER_LOG"
  die "桌面端没下完 $FREE_NAME"
fi
GLOBAL_KBPS="$(peer_auto_kbps "$FREE_NAME")"
GLOBAL_STEADY="$(peer_auto_steady_kbps "$FREE_NAME")"
chk "limit.global_512_enforced" \
  "$([ "${GLOBAL_STEADY:-0}" -ge 200 ] 2>/dev/null && [ "${GLOBAL_STEADY:-0}" -le 600 ] 2>/dev/null && echo 1 || echo 0)" \
  "桌面稳态 ${GLOBAL_STEADY:-?} KB/s、均速 ${GLOBAL_KBPS:-?} KB/s（标称 512，判据 ≤600）"

# ⑦ 限速还开着的时候把第三份素材也共享进群（它的 torrent 是在限速下建起来的），再清掉限制。
# P7 关抽屉后停在 P2，进群要「回首页 → 点群行」，只按返回键是到不了 P3 的。
enter_group || die "回不到 P3（恢复验收起点）"
SEED_BEFORE="$(count_log "event=file_seeding")"
saf_pick "$RESTORE_NAME" || die "SAF 选不到 $RESTORE_NAME"
if ! wait_log_count "event=file_seeding" $((SEED_BEFORE + 1)) 90000; then
  screenshot "p12-restore-share-failed"
  applog | tail -20
  die "共享恢复素材失败：$RESTORE_NAME"
fi
back_to_group || die "回不到 P3"

p7_upload_limit 0 "p12-p7-limit-cleared" || die "P7 清不掉限速"
LIMIT_CLEARED="$(log_field "event=global_limit_set" up_kbps)"
chk "limit.global_cleared" "$([ "$LIMIT_CLEARED" = "0" ] && echo 1 || echo 0)" \
  "global_limit_set up_kbps=${LIMIT_CLEARED:-?}（清掉 = 下发 0）"
chk "limit.global_applied_cleared" \
  "$(applog | grep -q 'event=global_limit_applied up_kbps=0 .*session_up_kbps=0 .*ok=true' && echo 1 || echo 0)" \
  "$(applog | grep -o 'event=global_limit_applied .*ok=[a-z]*' | tail -1)"

# ⑧ 同一个 session 再下一份：速率必须回到 > 2 MB/s（2048 KB/s）。限制没清掉这里会掉到 ~512。
peer_download "$RESTORE_NAME"
if ! wait_peer_auto_done "$RESTORE_NAME" 300; then
  tail -20 "$PEER_LOG"
  die "桌面端没下完 $RESTORE_NAME"
fi
RESTORE_KBPS="$(peer_auto_kbps "$RESTORE_NAME")"
RESTORE_STEADY="$(peer_auto_steady_kbps "$RESTORE_NAME")"
chk "limit.cleared_speed_restored" "$([ "${RESTORE_STEADY:-0}" -gt 2048 ] 2>/dev/null && echo 1 || echo 0)" \
  "桌面稳态 ${RESTORE_STEADY:-?} KB/s、均速 ${RESTORE_KBPS:-?} KB/s（判据 >2048）"
enter_group || warn "回不到 P3（清掉限速之后）"
screenshot "p12-unlimited-download"

# ⑨ 冷启动恢复：限速不落内核盘，重启后要由 app 按 SharedPreferences 重新下发。
# 先把上行设回 512，再 force-stop + 冷启动；只认「重启之后多出来的那条」，重启前的同名日志冒充不了。
p7_upload_limit 512 "p12-p7-limit-again" || die "P7 设不回 512 KB/s"
RESTORED_BEFORE="$(count_log "event=global_limit_applied up_kbps=512 down_kbps=0 session_up_kbps=512 session_down_kbps=0 ok=true")"
KERNEL_READY_BEFORE="$(count_log "app_kernel_ready")"
sh_ am force-stop "$PKG" >/dev/null 2>&1
sleep 2
sh_ am start -n "$PKG/$ACTIVITY" --es huchuan.debug.manual "$PC_IP:$MSG_PORT" 2>&1 | tail -1
if ! wait_log_count "app_kernel_ready" $((KERNEL_READY_BEFORE + 1)) 90000; then
  screenshot "p12-restart-failed"
  applog | tail -20
  die "冷启动后内核没起来（限速恢复验收）"
fi
wait_log_count "event=global_limit_applied up_kbps=512 down_kbps=0 session_up_kbps=512" $((RESTORED_BEFORE + 1)) 60000 \
  || warn "冷启动后 60 s 内没有看到按偏好重新下发 512"
RESTORED_AFTER="$(count_log "event=global_limit_applied up_kbps=512 down_kbps=0 session_up_kbps=512 session_down_kbps=0 ok=true")"
chk "limit.global_restored_after_restart" \
  "$([ "${RESTORED_AFTER:-0}" -gt "${RESTORED_BEFORE:-0}" ] && echo 1 || echo 0)" \
  "冷启动后按偏好重下发：${RESTORED_BEFORE:-0} → ${RESTORED_AFTER:-0} 条（session_up_kbps=512 ok=true）"
chk "limit.restored_logged" "$(applog | grep -q 'event=limits_restored' && echo 1 || echo 0)" \
  "limits_restored files=$(log_field limits_restored files)"
# 设置也还在界面上：打开 P7 就能看到 512（证据截图）。
if open_settings; then screenshot "p12-restart-restored"; back_to_home || true; fi

# ------------------------------------------------------------------ 13. 断言与证据

step "证据与断言"
enter_group || warn "回不到 P3（最后一屏可能不是 P3）"
screenshot "final-p3"

applog > "$ART/logs/app-logcat.log"
"$ADB" -s "$SERIAL" logcat -d -v brief > "$ART/logs/full-logcat.log" 2>/dev/null
"$ADB" -s "$SERIAL" logcat -b crash -d > "$ART/logs/crash-buffer.log" 2>/dev/null

DHT_TOTAL="$(grep -o 'dht_nodes=[0-9]*' "$ART/logs/app-logcat.log" | wc -l | tr -d ' ')"
DHT_BAD="$(grep -o 'dht_nodes=[0-9]*' "$ART/logs/app-logcat.log" | grep -vc 'dht_nodes=0' | tr -d ' ')"
chk "dht_nodes=0" "$([ "$DHT_TOTAL" -ge 2 ] && [ "$DHT_BAD" = "0" ] && echo 1 || echo 0)" "n=$DHT_TOTAL nonzero=$DHT_BAD"

# crash 缓冲区是全局的：devkit 的 uiautomator dump 在 MIUI 上会自己崩（见 docs/m1-device.md），
# 所以按「崩溃进程是不是本应用」判定；同时把总行数和外来崩溃如实报出来，不藏。
APP_CRASH="$(grep -c "app\.huchuan" "$ART/logs/crash-buffer.log" || true)"
# 按「崩溃块」统计：块头是 FATAL EXCEPTION / Abort message / Fatal signal，
# 块内出现 uiautomator 的算工具自己崩。行数统计没有意义（一个栈几十行）。
CRASH_BLOCKS="$(awk '/FATAL EXCEPTION|Abort message|Fatal signal/ { n++ } END { print n + 0 }' "$ART/logs/crash-buffer.log")"
UIA_BLOCKS="$(awk '
  /FATAL EXCEPTION|Abort message|Fatal signal/ { blk++; next }
  blk > 0 && /uiautomator/ { hit[blk] = 1 }
  END { u = 0; for (i = 1; i <= blk; i++) if (hit[i]) u++; print u + 0 }
' "$ART/logs/crash-buffer.log")"
FOREIGN_BLOCKS=$(( CRASH_BLOCKS - UIA_BLOCKS ))
chk "crash_buffer.app_crash_0" "$([ "$APP_CRASH" = "0" ] && echo 1 || echo 0)"   "app.huchuan=$APP_CRASH；崩溃块 $CRASH_BLOCKS 个，其中 uiautomator 工具 $UIA_BLOCKS 个"
if [ "$FOREIGN_BLOCKS" -gt 0 ]; then
  warn "crash 缓冲区里有 $FOREIGN_BLOCKS 个崩溃块既不是 uiautomator 也不含 app.huchuan，人工看一眼 $ART/logs/crash-buffer.log"
fi

SCUDO="$(grep -cE "Scudo|Fatal signal|Abort message|abort_message" "$ART/logs/full-logcat.log" || true)"
chk "no_scudo_abort" "$([ "$SCUDO" = "0" ] && echo 1 || echo 0)" "$SCUDO hits"

ANR="$(grep -cE "ANR in $PKG|Application Not Responding" "$ART/logs/full-logcat.log" || true)"
chk "no_anr" "$([ "$ANR" = "0" ] && echo 1 || echo 0)" "$ANR hits"

SHOTS="$(ls "$ART/screenshots"/*.png 2>/dev/null | wc -l | tr -d ' ')"
chk "evidence.screenshots" "$([ "$SHOTS" -ge 4 ] && echo 1 || echo 0)" "$SHOTS png"

MEM_LINE="$(sh_ dumpsys meminfo "$PKG" | grep -E "TOTAL PSS|TOTAL RSS" | head -1 | tr -s ' ')"
info "meminfo: ${MEM_LINE:-n/a}"

step "证据清单"
say "logcat    : $ART/logs/app-logcat.log"
say "peer      : $PEER_LOG"
say "screenshots: $ART/screenshots/"
say "pulled    : $ART/pulled/"
say "关键日志行："
grep -hE "event=(app_kernel_ready|kernel_started|reconnect_attempt|peer_reconnected|ui_peers_changed|join_input|join_via|group_joined|share_import|file_seeding|download_start|download_completed|file_stopped|seed_resumed|seed_resume_done|ui_file_completed|ui_op_received)" \
  "$ART/logs/app-logcat.log" 2>/dev/null | sed 's/^/  /' | head -30
say "桌面关键行："
grep -hE "PEER (ready|peer_joined|file_seen|download_done|comment_sent|op_received|reply_seen|share )" \
  "$PEER_LOG" 2>/dev/null | sed 's/^/  /' | head -20

say ""
say "checks    : PASS=$CHECKS_PASS FAIL=$CHECKS_FAIL SKIP=$CHECKS_SKIP（SKIP 不计入 PASS）"
if [ "$CHECKS_SKIP" -gt 0 ]; then
  say "提示      : $CHECKS_SKIP 项 SKIP 的原因见上面的 CHECK SKIP 行；产品判定只看 FAIL 是否为 0。"
fi
if [ "$CHECKS_FAIL" -eq 0 ]; then
  say "PASS"
  exit 0
fi
say "FAIL"
exit 1
