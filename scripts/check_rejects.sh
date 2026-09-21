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
# Verify/fix the legacy SUSFS post-exec hook introduced by older SUSFS patches.
# This is intentionally kept in the shared checker so callers can source this
# file and invoke the function from the same shell.
check_ksu_compat_symbols() {
  local COMMON_DIR="${1:-.}"
  local EXEC_C="$COMMON_DIR/fs/exec.c"
  local SYMBOL='ksu_handle_post_execveat_sucompat'
  local KSU_LINK="$COMMON_DIR/drivers/kernelsu"
  local KSU_ROOT=""
  local SUCOMPAT_C=""

  [ -f "$EXEC_C" ] || return 0
  grep -q "$SYMBOL" "$EXEC_C" || return 0

  echo "Detected legacy post-exec SUSFS hook in fs/exec.c"

  if [ -e "$KSU_LINK" ]; then
    KSU_ROOT=$(readlink -f "$KSU_LINK" 2>/dev/null || true)
  fi

  if [ -n "$KSU_ROOT" ] && [ -f "$KSU_ROOT/feature/sucompat.c" ]; then
    SUCOMPAT_C="$KSU_ROOT/feature/sucompat.c"
  else
    SUCOMPAT_C=$(find -L "$COMMON_DIR" -type f -path '*/feature/sucompat.c' -print -quit 2>/dev/null || true)
  fi

  if [ -z "$SUCOMPAT_C" ]; then
    echo "::error title=SukiSU compatibility::Unable to locate KernelSU feature/sucompat.c"
    return 23
  fi

  # Do not confuse a call/declaration with a real function definition.
  if grep -Eq "(^|[[:space:]])int[[:space:]]+$SYMBOL[[:space:]]*\\(" "$SUCOMPAT_C"; then
    echo "✅ Legacy post-exec compatibility symbol is already defined by: $SUCOMPAT_C"
    return 0
  fi

  echo "Installing legacy post-exec compatibility shim into: $SUCOMPAT_C"
  cat >> "$SUCOMPAT_C" <<'EOF_SHIM'

/* SUSFS legacy compatibility: older SUSFS fs/exec.c expects this post-exec hook.
 * Modern SukiSU performs sucompat handling in ksu_handle_execveat_sucompat().
 * Keep this callback as a no-op ABI shim so the legacy call remains linkable.
 */
int ksu_handle_post_execveat_sucompat(
        int *fd,
        struct filename **filename_ptr,
        void *argv,
        void *envp,
        int *flags,
        int *retval)
{
        (void)fd;
        (void)filename_ptr;
        (void)argv;
        (void)envp;
        (void)flags;
        (void)retval;
        return 0;
}
EOF_SHIM

  if ! grep -Eq "(^|[[:space:]])int[[:space:]]+$SYMBOL[[:space:]]*\\(" "$SUCOMPAT_C"; then
    echo "::error title=SukiSU compatibility::Failed to install $SYMBOL into $SUCOMPAT_C"
    return 24
  fi
  echo "✅ Installed legacy post-exec compatibility symbol in: $SUCOMPAT_C"
}
