#!/usr/bin/env bash
# Opens a pull request into main from your current feature branch, WITHOUT developer-only files
# (personal notes and local tool configuration, listed in scripts/developer-only-files.txt).
#
# How it works: it creates release/<branch>-<timestamp> from your branch in a temporary folder,
# removes the developer-only files there, pushes it and opens a pull request into main.
# Your own branch and working folder are not changed.
# Merge the pull request with "Squash and merge" (the only option allowed on main) after testing.
#
# Usage (from the repository root, on your feature branch, everything committed and pushed):
#   scripts/prepare-main-merge.sh
set -euo pipefail

source_branch=$(git rev-parse --abbrev-ref HEAD)
case "$source_branch" in
  feature/*) ;;
  *) echo "Run this from a feature/dev-a/... or feature/dev-b/... branch (now on '$source_branch')." >&2; exit 1 ;;
esac
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "Commit your changes first." >&2; exit 1
fi

release_branch="release/${source_branch#feature/}-$(date +%Y%m%d-%H%M)"
work_dir=$(mktemp -d)
patterns_file="$(pwd)/scripts/developer-only-files.txt"

git fetch -q origin main
git worktree add -q -b "$release_branch" "$work_dir" "$source_branch"
(
  cd "$work_dir"
  files=$(git ls-files | grep -E -f "$patterns_file" || true)
  if [ -n "$files" ]; then
    echo "$files" | xargs git rm -q -r --cached --
    git commit -q -m "Remove developer-only files before merging into main"
  fi
  git push -q -u origin "$release_branch"
)
git worktree remove --force "$work_dir"

gh pr create --base main --head "$release_branch" \
  --title "Merge ${source_branch} into main" \
  --body "Prepared by scripts/prepare-main-merge.sh from \`${source_branch}\` with developer-only files removed. Test manually, then use Squash and merge."
