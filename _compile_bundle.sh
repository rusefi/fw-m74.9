#!/usr/bin/env bash
set -euo pipefail
board_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export RUSEFI_CUSTOM_JAVA_UI_DIR="$board_dir/java-custom-ui"
export META_OUTPUT_ROOT_FOLDER=../../../generated/
export BUNDLE_SIMULATOR=false
cd "$board_dir/ext/rusefi/firmware"
bash bin/compile.sh ../../../meta-info.env build_both_bundles -j12 "$@"
python3 "$board_dir/tests/check_bundles.py"
