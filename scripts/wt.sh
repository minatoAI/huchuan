#!/usr/bin/env bash
# 多 agent 并行的 git worktree 工具。
#   scripts/wt.sh new <name> [base]   建工作树并切分支 wt/<name>
#   scripts/wt.sh list                列出
#   scripts/wt.sh merge <name>        合回当前分支（--no-ff）
#   scripts/wt.sh rm <name>           删除工作树与分支
set -euo pipefail
cd "$(dirname "$0")/.."
cmd=${1:-}
case "$cmd" in
  new)
    name=${2:?name}; base=${3:-HEAD}
    git worktree add ".worktrees/$name" -b "wt/$name" "$base" >/dev/null
    echo "$(pwd)/.worktrees/$name"
    ;;
  list) git worktree list ;;
  merge)
    name=${2:?name}
    git merge --no-ff "wt/$name" -m "merge wt/$name" ;;
  rm)
    name=${2:?name}
    git worktree remove ".worktrees/$name" --force
    git branch -D "wt/$name" >/dev/null 2>&1 || true
    echo "removed wt/$name" ;;
  *) echo "usage: wt.sh new <name> [base] | list | merge <name> | rm <name>"; exit 2 ;;
esac
