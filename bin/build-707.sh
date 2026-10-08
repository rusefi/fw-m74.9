#!/usr/bin/env bash
# Build current board firmware, then create the full 707-based flash image.
set -euo pipefail

board_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$board_dir"
bash compile_firmware.sh "$@"
exec "${PYTHON:-python3}" bin/m749_707_image.py
