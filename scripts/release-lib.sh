#!/usr/bin/env bash
################################################################################
# release-lib.sh - Shared helpers for package.sh and run-experiment.sh.
# SOURCE this file; do not run it. No side effects at source time, no AWS
# calls. Bash 3.2 compatible. Every git call here is read-only and passes
# --no-optional-locks, so it never rewrites the index.
#
#   rl_git_inspect <repo-root>   sets RL_COMMIT_SHA (40 hex), RL_GIT_DIRTY
#                                (true|false) and RL_GIT_STATUS (porcelain,
#                                plus "!! assume-unchanged <path>" /
#                                "!! skip-worktree <path>" lines);
#                                returns 3 on ANY doubt (deploy.sh N37/N48
#                                precedent: a git failure is never "clean").
#                                A tracked file flagged assume-unchanged
#                                (lowercase tag in `git ls-files -v`) or
#                                skip-worktree (tag S) makes the tree DIRTY:
#                                git status/diff do not look at such files, so
#                                edits to them would otherwise be invisible.
#   rl_tree_fingerprint <root>   prints a sha256 over HEAD, `git status
#                                --porcelain`, the flagged index entries and
#                                their working-copy blobs, `git diff HEAD
#                                --binary` and the blob of every untracked
#                                (not ignored) file. Equal fingerprints before
#                                and after a build mean the tree did not change
#                                in between. Returns 3 on any git failure.
#   rl_sha256 <file>             prints the lowercase hex sha256 of a file.
#   rl_sha256_stdin              prints the lowercase hex sha256 of stdin.
#   rl_utc_now                   prints the current UTC time, ISO 8601 (Z).
#   rl_file_size <file>          prints the size in bytes.
################################################################################

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  echo "ERROR: release-lib.sh must be sourced, not executed." >&2
  exit 2
fi

RL_COMMIT_SHA=""
RL_GIT_DIRTY=""
RL_GIT_STATUS=""

# rl_git_overrides_set: 0 (and a message) if an exported Git override variable
# would make `git -C <repo>` inspect another repository, worktree, index or
# config (deploy.sh N37/N48). compgen -e is bash 3.2-safe.
rl_git_overrides_set() {
  local name bad=""
  for name in $(compgen -e); do
    case "${name}" in
      GIT_DIR | GIT_WORK_TREE | GIT_INDEX_FILE | GIT_COMMON_DIR | GIT_OBJECT_DIRECTORY | GIT_ALTERNATE_OBJECT_DIRECTORIES | GIT_CONFIG_*)
        bad="${bad} ${name}"
        ;;
    esac
  done
  if [[ -n "${bad}" ]]; then
    echo "ERROR: refusing to inspect the Git tree with Git override variable(s) set:${bad}" >&2
    return 0
  fi
  return 1
}

# rl_flagged_entries <root>: prints "assume-unchanged <path>" or
# "skip-worktree <path>" for every flagged index entry; returns 1 if git fails.
rl_flagged_entries() {
  local root="$1" listing line tag path
  # -z: paths are printed verbatim (no core.quotePath quoting).
  if ! listing="$(
    git -C "${root}" --no-optional-locks ls-files -v -z | tr '\0' '\n'
    exit "${PIPESTATUS[0]}"
  )"; then
    echo "ERROR: 'git ls-files -v' failed; treating the tree as NOT clean." >&2
    return 1
  fi
  while IFS= read -r line; do
    [[ -n "${line}" ]] || continue
    tag="${line%% *}"
    path="${line#* }"
    case "${tag}" in
      [a-z]) echo "assume-unchanged ${path}" ;;
      S) echo "skip-worktree ${path}" ;;
    esac
  done <<<"${listing}"
  return 0
}

rl_git_inspect() {
  local root="$1" phys top status sha flagged
  RL_COMMIT_SHA=""
  RL_GIT_DIRTY=""
  RL_GIT_STATUS=""
  if rl_git_overrides_set; then
    return 3
  fi
  if ! phys="$(cd "${root}" && pwd -P)" || [[ -z "${phys}" ]]; then
    echo "ERROR: cannot resolve ${root}." >&2
    return 3
  fi
  if ! top="$(git -C "${root}" rev-parse --show-toplevel 2>/dev/null)" || [[ -z "${top}" ]]; then
    echo "ERROR: 'git rev-parse --show-toplevel' failed in ${root}." >&2
    return 3
  fi
  if [[ "${top}" != "${phys}" ]]; then
    echo "ERROR: Git work tree is '${top}', expected '${phys}'." >&2
    return 3
  fi
  if ! status="$(git -C "${root}" --no-optional-locks status --porcelain --untracked-files=all)"; then
    echo "ERROR: 'git status' failed; treating the tree as NOT clean." >&2
    return 3
  fi
  if ! flagged="$(rl_flagged_entries "${root}")"; then
    return 3
  fi
  if [[ -n "${flagged}" ]]; then
    flagged="$(printf '%s\n' "${flagged}" | sed 's/^/!! /')"
    if [[ -n "${status}" ]]; then status="${status}"$'\n'"${flagged}"; else status="${flagged}"; fi
  fi
  if ! sha="$(git -C "${root}" rev-parse --verify HEAD 2>/dev/null)" || [[ ! "${sha}" =~ ^[0-9a-f]{40}$ ]]; then
    echo "ERROR: could not read HEAD as a 40-hex commit SHA (got '${sha:-}')." >&2
    return 3
  fi
  RL_COMMIT_SHA="${sha}"
  RL_GIT_STATUS="${status}"
  if [[ -n "${status}" ]]; then RL_GIT_DIRTY=true; else RL_GIT_DIRTY=false; fi
  return 0
}

# rl_tree_fingerprint_parts <root>: the fingerprint input (see header).
rl_tree_fingerprint_parts() {
  local root="$1" f kind path blob
  echo "HEAD"
  git -C "${root}" rev-parse --verify HEAD || return 1
  echo "STATUS"
  git -C "${root}" --no-optional-locks status --porcelain --untracked-files=all || return 1
  echo "FLAGGED"
  while IFS= read -r f; do
    [[ -n "${f}" ]] || continue
    kind="${f%% *}"
    path="${f#* }"
    if [[ -f "${root}/${path}" ]]; then
      blob="$(git -C "${root}" hash-object --stdin <"${root}/${path}")" || return 1
    else
      blob=absent
    fi
    echo "${kind} ${blob} ${path}"
  done < <(rl_flagged_entries "${root}" || echo "flagged-listing-failed x")
  echo "DIFF"
  git -C "${root}" --no-optional-locks diff HEAD --binary --no-ext-diff --no-textconv || return 1
  echo "UNTRACKED"
  # The listing ends with a sentinel record "/rc=<status of git ls-files>".
  # Git prints relative paths only, so no path can start with "/". A missing
  # sentinel or a nonzero status fails closed.
  local ls_rc=""
  while IFS= read -r -d '' f; do
    case "${f}" in
      /rc=*)
        ls_rc="${f#/rc=}"
        continue
        ;;
    esac
    if [[ -f "${root}/${f}" && ! -L "${root}/${f}" ]]; then
      blob="$(git -C "${root}" hash-object --stdin <"${root}/${f}")" || return 1
    elif [[ -L "${root}/${f}" ]]; then
      blob="symlink:$(readlink "${root}/${f}")"
    else
      blob=unreadable
    fi
    printf '%s %s\n' "${blob}" "${f}"
  done < <(
    git -C "${root}" --no-optional-locks ls-files -z --others --exclude-standard
    printf '/rc=%s\0' "$?"
  )
  if [[ "${ls_rc}" != 0 ]]; then
    echo "ERROR: 'git ls-files --others' failed (status ${ls_rc:-unknown}); treating the tree as NOT clean." >&2
    return 1
  fi
  return 0
}

rl_tree_fingerprint() {
  local root="$1" parts
  if rl_git_overrides_set; then
    return 3
  fi
  if ! parts="$(rl_tree_fingerprint_parts "${root}")"; then
    echo "ERROR: cannot fingerprint the Git tree in ${root}." >&2
    return 3
  fi
  case "${parts}" in
    *"flagged-listing-failed"*)
      echo "ERROR: cannot list flagged index entries in ${root}." >&2
      return 3
      ;;
  esac
  printf '%s\n' "${parts}" | rl_sha256_stdin
}

rl_sha256() {
  local out
  if command -v shasum >/dev/null 2>&1; then
    out="$(shasum -a 256 "$1")" || return 1
  elif command -v sha256sum >/dev/null 2>&1; then
    out="$(sha256sum "$1")" || return 1
  else
    echo "ERROR: neither shasum nor sha256sum is installed." >&2
    return 1
  fi
  printf '%s\n' "${out%% *}"
}

rl_sha256_stdin() {
  local out
  if command -v shasum >/dev/null 2>&1; then
    out="$(shasum -a 256)" || return 1
  elif command -v sha256sum >/dev/null 2>&1; then
    out="$(sha256sum)" || return 1
  else
    echo "ERROR: neither shasum nor sha256sum is installed." >&2
    return 1
  fi
  printf '%s\n' "${out%% *}"
}

rl_utc_now() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

rl_file_size() {
  wc -c <"$1" | tr -d '[:space:]'
}
