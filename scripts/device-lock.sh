#!/usr/bin/env bash
# 真机独占锁：claim / release / check / list
# 用法：
#   bash scripts/device-lock.sh claim 5b94673a "首启导航验收"
#   bash scripts/device-lock.sh release 5b94673a
#   bash scripts/device-lock.sh check 5b94673a   # 0=空闲 1=被占
#   bash scripts/device-lock.sh list
# 锁文件：artifacts/.device-lock-<serial>；超过 30 分钟视为失效（防崩溃后死锁）。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ART="$ROOT/artifacts"
STALE_SEC=1800
mkdir -p "$ART"

lock_path() { printf '%s/.device-lock-%s' "$ART" "$1"; }

is_stale() {
  local f="$1" now mt
  [ -f "$f" ] || return 1
  now=$(date +%s)
  mt=$(stat -c %Y "$f" 2>/dev/null || echo 0)
  [ $((now - mt)) -gt "$STALE_SEC" ]
}

cmd="${1:-}"
serial="${2:-}"
case "$cmd" in
  claim)
    [ -n "$serial" ] || { echo "用法: claim <serial> <task>" >&2; exit 2; }
    f="$(lock_path "$serial")"
    if [ -f "$f" ] && ! is_stale "$f"; then
      echo "BUSY $serial by: $(cat "$f")" >&2
      exit 1
    fi
    printf '%s\t%s\n' "${3:-未命名任务}" "$(date -Iseconds)" > "$f"
    echo "OK claimed $serial: ${3:-未命名任务}"
    ;;
  release)
    [ -n "$serial" ] || { echo "用法: release <serial>" >&2; exit 2; }
    rm -f "$(lock_path "$serial")"
    echo "OK released $serial"
    ;;
  check)
    [ -n "$serial" ] || { echo "用法: check <serial>" >&2; exit 2; }
    f="$(lock_path "$serial")"
    if [ -f "$f" ] && ! is_stale "$f"; then
      echo "BUSY $serial by: $(cat "$f")"
      exit 1
    fi
    echo "FREE $serial"
    exit 0
    ;;
  list)
    for f in "$ART"/.device-lock-*; do
      [ -e "$f" ] || continue
      s="${f##*/.device-lock-}"
      if is_stale "$f"; then echo "$s STALE $(cat "$f")"; else echo "$s BUSY $(cat "$f")"; fi
    done
    ;;
  *)
    sed -n '2,9p' "$0"
    exit 2
    ;;
esac
