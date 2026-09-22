#!/usr/bin/env bash
set -euo pipefail
board_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export RUSEFI_CUSTOM_JAVA_UI_DIR="$board_dir/java-custom-ui"
cd "$board_dir/ext/rusefi"
if [ "$#" -eq 0 ]; then
    exec ./gradlew -q --console=plain :custom-java-ui:runM749Cli
fi
exec ./gradlew -q --console=plain :custom-java-ui:runM749Cli "-Pm749Args=$*"
