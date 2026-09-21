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

# Return the real KernelSU source root. setup.sh normally creates
# common/drivers/kernelsu -> ../../KernelSU/kernel, but tolerate a real
# directory and alternate layouts as well.
_ksu_root() {
  local common="$1"
  local link="$common/drivers/kernelsu"
  local root=""

  if [ -e "$link" ] || [ -L "$link" ]; then
    root=$(readlink -f "$link" 2>/dev/null || true)
    if [ -n "$root" ] && [ -d "$root" ]; then
      printf '%s\n' "$root"
      return 0
    fi
  fi

  for candidate in \
    "$common/KernelSU/kernel" \
    "$common/KernelSU" \
    "$common/drivers/kernelsu"; do
    if [ -d "$candidate" ]; then
      root=$(readlink -f "$candidate" 2>/dev/null || printf '%s' "$candidate")
      printf '%s\n' "$root"
      return 0
    fi
  done

  return 1
}

# Test for an actual function definition, not merely a declaration/call.
_ksu_symbol_defined() {
  local file="$1"
  awk '
    /^[[:space:]]*(static[[:space:]]+)?(inline[[:space:]]+)?int[[:space:]]+ksu_handle_post_execveat_sucompat[[:space:]]*\(/ {
      found=1
      next
    }
    found && /\{/ { exit 0 }
    found && /;/ { exit 1 }
    END { if (!found) exit 1 }
  ' "$file"
}


# Add the legacy SUSFS callback only when fs/exec.c actually calls it and
# the KernelSU tree has no definition. The callback is intentionally a no-op:
# current SukiSU performs sucompat processing in its execveat hook, while this
# legacy SUSFS callback is only required to satisfy older patched fs/exec.c.
_install_legacy_sucompat() {
  local common="$1"
  local exec_c="$common/fs/exec.c"
  local ksu_root=""
  local target=""
  local candidate

  [ -f "$exec_c" ] || return 0
  grep -q 'ksu_handle_post_execveat_sucompat' "$exec_c" || return 0

  ksu_root=$(_ksu_root "$common" 2>/dev/null || true)
  if [ -z "$ksu_root" ]; then
    echo "::error title=SukiSU compatibility::Unable to resolve KernelSU source root"
    return 22
  fi

  # Prefer the existing sucompat implementation, then ksu.c, then another
  # compiled C file in the KernelSU tree. This avoids assuming a particular
  # SukiSU layout/version.
  for candidate in \
    "$ksu_root/feature/sucompat.c" \
    "$ksu_root/ksu.c"; do
    if [ -f "$candidate" ]; then
      target="$candidate"
      break
    fi
  done

  if [ -z "$target" ]; then
    target=$(find -L "$ksu_root" -type f -name '*.c' -print 2>/dev/null | while IFS= read -r candidate; do
      if grep -q 'ksu_handle_execveat_sucompat' "$candidate" 2>/dev/null; then
        printf '%s\n' "$candidate"
        break
      fi
    done)
  fi

  if [ -z "$target" ]; then
    echo "::error title=SukiSU compatibility::Could not find a compiled KernelSU C source file for the legacy post-exec shim"
    echo "Resolved KernelSU root: $ksu_root"
    return 22
  fi

  if _ksu_symbol_defined "$target"; then
    echo "✅ Legacy post-exec compatibility symbol is already defined by: $target"
    return 0
  fi

  echo "Installing legacy post-exec compatibility shim into: $target"
  cat >> "$target" <<'EOF_SHIM'

/*
 * Compatibility shim for SUSFS patches which still call the legacy
 * post-exec sucompat callback. Current SukiSU handles sucompat in its
 * execveat path; this callback only needs to satisfy the legacy caller.
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

  if ! _ksu_symbol_defined "$target"; then
    echo "::error title=SukiSU compatibility::Failed to install ksu_handle_post_execveat_sucompat into $target"
    return 23
  fi

  echo "✅ Installed legacy post-exec compatibility symbol in: $target"
}

# Public hard gate used by the OnePlus workflow. It both repairs the legacy
# SUSFS/SukiSU mismatch and verifies the resulting source before compilation.
check_ksu_compat_symbols() {
  local common="${1:-.}"
  local exec_c="$common/fs/exec.c"

  echo "===== SukiSU/SUSFS compatibility verification ====="
  if [ ! -f "$exec_c" ]; then
    echo "::warning title=SukiSU compatibility::fs/exec.c not found under $common; skipping legacy-hook check"
    return 0
  fi

  if grep -q 'ksu_handle_post_execveat_sucompat' "$exec_c"; then
    echo "Detected legacy post-exec SUSFS hook in fs/exec.c"
    _install_legacy_sucompat "$common"
  else
    echo "✅ No legacy ksu_handle_post_execveat_sucompat reference detected"
  fi
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
