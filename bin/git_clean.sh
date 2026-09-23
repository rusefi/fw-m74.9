#!/bin/bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$(git -C "$script_dir/.." rev-parse --show-toplevel)"

echo "=== status BEFORE clean ==="
git status

# Sync URLs without initializing any submodules.
git submodule sync --recursive

# Discard tracked changes in this checkout and all initialized submodules.
# Recursive foreach skips submodules that have not been initialized.
git reset --hard
git submodule foreach --recursive 'git reset --hard'

# Remove untracked and ignored files, directories and nested untracked repos.
git clean -ffxd
git submodule foreach --recursive 'git clean -ffxd'

# Restore recorded revisions only for initialized submodules. Do not use
# --init or --recursive: optional, uninitialized submodules stay untouched.
git submodule update
git submodule foreach --recursive 'git submodule update'

echo "=== status AFTER clean ==="
git status
git submodule status
