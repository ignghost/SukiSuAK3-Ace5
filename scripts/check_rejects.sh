#!/bin/bash

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)/$(basename "${BASH_SOURCE[0]}")"

check_rejects() {
  local SEARCH_DIR="${1:-.}"
  local REJECT_FILES
  REJECT_FILES=$(find "$SEARCH_DIR" -name "*.rej" 2>/dev/null)
  [ -z "$REJECT_FILES" ] && return 0

  while IFS= read -r REJ_FILE; do
    local MARK="/tmp/.rej_printed_$(echo "$REJ_FILE" | md5sum | cut -d' ' -f1)"
    [ -f "$MARK" ] && continue
    touch "$MARK" 2>/dev/null

    local ORIG_FILE="${REJ_FILE%.rej}"
    ORIG_FILE="${ORIG_FILE#./}"
    local HUNK_FAIL_COUNT
    HUNK_FAIL_COUNT=$(grep -c '^@@ ' "$REJ_FILE" 2>/dev/null)
    echo "::group::❌ 补丁在 ${ORIG_FILE} 出现 hunk FAILED（${HUNK_FAIL_COUNT:-?} 个 hunk 未能应用），点击查看具体信息"
    cat "$REJ_FILE"
    echo "::endgroup::"
  done <<< "$REJECT_FILES"
  return 0
}

check_ksu_compat_symbols() {
  local SEARCH_DIR="${1:-.}"
  local EXEC_REF
  EXEC_REF=$(find "$SEARCH_DIR" -type f -path '*/fs/exec.c' -print -quit 2>/dev/null || true)
  [ -z "$EXEC_REF" ] && return 0

  if ! grep -q 'ksu_handle_post_execveat_sucompat' "$EXEC_REF" 2>/dev/null; then
    return 0
  fi

  local KSU_ROOT="$SEARCH_DIR/drivers/kernelsu"
  if [ -L "$KSU_ROOT" ]; then
    KSU_ROOT=$(readlink -f "$KSU_ROOT" 2>/dev/null || true)
  fi

  if [ -z "$KSU_ROOT" ] || [ ! -d "$KSU_ROOT" ]; then
    echo "::error title=SukiSU compatibility::drivers/kernelsu could not be resolved"
    return 1
  fi

  # Do not require feature/sucompat.c. Newer SukiSU trees can move or remove
  # that file. What matters is that the legacy symbol has one compiled C
  # definition somewhere inside the actual KernelSU source tree.
  local SYMBOL_FILE
  SYMBOL_FILE=$(grep -RIlE '^[[:space:]]*(static[[:space:]]+)?(int|long|void)[[:space:]]+ksu_handle_post_execveat_sucompat[[:space:]]*\(' \
    "$KSU_ROOT" --include='*.c' 2>/dev/null | head -n1 || true)

  if [ -z "$SYMBOL_FILE" ]; then
    echo "::error title=SukiSU compatibility::fs/exec.c references ksu_handle_post_execveat_sucompat but no compiled KernelSU C file defines it"
    echo "  exec.c:     $EXEC_REF"
    echo "  KernelSU:   $KSU_ROOT"
    return 1
  fi

  echo "::notice title=SukiSU compatibility::post-exec sucompat symbol is present in $SYMBOL_FILE"
  return 0
}

_reject_err_trap() {
  local ec=$?
  trap - ERR
  local REJ_FILES
  REJ_FILES=$(find . -name "*.rej" 2>/dev/null)
  if [ -n "$REJ_FILES" ]; then
    check_rejects .
    exit "$ec"
  fi
  trap '_reject_err_trap' ERR
}


if [[ -n "${GITHUB_ENV:-}" && "${BASH_ENV:-}" != "$SELF" ]]; then
  echo "BASH_ENV=$SELF" >> "$GITHUB_ENV"
fi

set -E
trap '_reject_err_trap' ERR