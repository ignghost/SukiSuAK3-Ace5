#!/bin/bash

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)/$(basename "${BASH_SOURCE[0]}")"



check_ksu_compat_symbols() {
  local COMMON_DIR="${1:?common source directory required}"
  local EXEC_C="$COMMON_DIR/fs/exec.c"
  if [ ! -f "$EXEC_C" ] || ! grep -qF 'ksu_handle_post_execveat_sucompat' "$EXEC_C"; then
    return 0
  fi

  echo "Detected legacy post-exec SUSFS hook in fs/exec.c"
  local KSU_LINK="$COMMON_DIR/drivers/kernelsu"
  local KSU_ROOT=""
  local SUCOMPAT_C=""
  if [ -e "$KSU_LINK" ]; then
    KSU_ROOT="$(readlink -f "$KSU_LINK" 2>/dev/null || true)"
  fi
  if [ -n "$KSU_ROOT" ] && [ -f "$KSU_ROOT/feature/sucompat.c" ]; then
    SUCOMPAT_C="$KSU_ROOT/feature/sucompat.c"
  elif [ -n "$KSU_ROOT" ]; then
    SUCOMPAT_C="$(find -L "$KSU_ROOT" -maxdepth 3 -type f -name '*.c' -print 2>/dev/null | while read -r f; do grep -qF 'ksu_handle_execveat_sucompat' "$f" && { echo "$f"; break; }; done)"
  fi
  if [ -z "$SUCOMPAT_C" ]; then
    echo "::error title=SukiSU compatibility::Unable to locate compiled KernelSU sucompat source"
    return 23
  fi

  if grep -Eq '^[[:space:]]*(static[[:space:]]+)?(int|void)[[:space:]]+ksu_handle_post_execveat_sucompat[[:space:]]*\(' "$SUCOMPAT_C"; then
    echo "Legacy post-exec compatibility symbol already defined by: $SUCOMPAT_C"
    return 0
  fi

  cat >> "$SUCOMPAT_C" <<'EOF'

/* SUSFS legacy compatibility: retained for older fs/exec.c hook variants. */
int ksu_handle_post_execveat_sucompat(
    int *fd, struct filename **filename_ptr, void *argv,
    void *envp, int *flags, int *retval)
{
    (void)fd;
    (void)filename_ptr;
    (void)argv;
    (void)envp;
    (void)flags;
    (void)retval;
    return 0;
}
EOF
  if ! grep -Eq '^[[:space:]]*(static[[:space:]]+)?(int|void)[[:space:]]+ksu_handle_post_execveat_sucompat[[:space:]]*\(' "$SUCOMPAT_C"; then
    echo "::error title=SukiSU compatibility::Failed to install legacy post-exec compatibility symbol"
    return 23
  fi
  echo "Installed legacy post-exec compatibility symbol in: $SUCOMPAT_C"
}

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