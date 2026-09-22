#!/usr/bin/env bash
set -euo pipefail
board_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export RUSEFI_CUSTOM_JAVA_UI_DIR="$board_dir/java-custom-ui"
(cd "$board_dir/ext/rusefi" && ./gradlew -q --console=plain :custom-java-ui:installM749Cli)
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
export LD_LIBRARY_PATH="$board_dir/ext/rusefi/java_console${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec "$java_bin" -cp "$board_dir/java-custom-ui/build/install/m749/lib/*" com.rusefi.m749.M749Cli "$@"
