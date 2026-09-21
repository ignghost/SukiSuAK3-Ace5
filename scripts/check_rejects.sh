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

# Verify and repair the legacy SUSFS post-exec hook used by older SUSFS patches.
# This function is intentionally self-contained because Action-Build is cloned
# into kernel_workspace and may not contain the helper shipped by this repo.
check_ksu_compat_symbols() {
  local COMMON_DIR="${1:-.}"
  local EXEC_C="$COMMON_DIR/fs/exec.c"
  local SYMBOL="ksu_handle_post_execveat_sucompat"
  [ -f "$EXEC_C" ] || return 0
  if ! grep -q "$SYMBOL" "$EXEC_C"; then
    echo "✅ No legacy $SYMBOL reference detected"
    return 0
  fi
  echo "Detected legacy post-exec SUSFS hook in fs/exec.c"

  local KSU_LINK="$COMMON_DIR/drivers/kernelsu"
  local KSU_ROOT=""
  local TARGET=""
  if [ -e "$KSU_LINK" ] || [ -L "$KSU_LINK" ]; then
    KSU_ROOT=$(readlink -f "$KSU_LINK" 2>/dev/null || true)
  fi
  if [ -n "$KSU_ROOT" ] && [ -f "$KSU_ROOT/feature/sucompat.c" ]; then
    TARGET="$KSU_ROOT/feature/sucompat.c"
  fi
  if [ -z "$TARGET" ]; then
    TARGET=$(find -L "$COMMON_DIR" -type f -path '*/feature/sucompat.c' -print -quit 2>/dev/null || true)
  fi
  if [ -z "$TARGET" ] && [ -n "$KSU_ROOT" ] && [ -f "$KSU_ROOT/ksu.c" ]; then
    TARGET="$KSU_ROOT/ksu.c"
  fi
  if [ -z "$TARGET" ]; then
    echo "::error title=SukiSU compatibility::Unable to locate a compiled KernelSU C source for $SYMBOL"
    return 22
  fi

  # Look for the symbol and a function body in the same source file.
  if grep -A 40 -F "$SYMBOL" "$TARGET" 2>/dev/null | grep -q '{'; then
    echo "✅ Legacy post-exec compatibility symbol is already defined by: $TARGET"
    return 0
  fi

  echo "Installing legacy post-exec compatibility shim into: $TARGET"
  cat >> "$TARGET" <<'KSU_LEGACY_POST_EXEC_SHIM'

/* Compatibility shim for legacy SUSFS post-exec hook. */
int ksu_handle_post_execveat_sucompat(
    int *fd, struct filename **filename_ptr, void *argv, void *envp,
    int *flags, int *retval)
{
    (void)fd;
    (void)filename_ptr;
    (void)argv;
    (void)envp;
    (void)flags;
    (void)retval;
    return 0;
}
KSU_LEGACY_POST_EXEC_SHIM

  if ! grep -A 40 -F "$SYMBOL" "$TARGET" 2>/dev/null | grep -q '{'; then
    echo "::error title=SukiSU compatibility::Failed to install $SYMBOL into $TARGET"
    return 23
  fi
  echo "✅ Installed legacy post-exec compatibility symbol in: $TARGET"
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