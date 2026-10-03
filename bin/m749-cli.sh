#!/usr/bin/env bash
set -euo pipefail
board_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export RUSEFI_CUSTOM_JAVA_UI_DIR="$board_dir/java-custom-ui"
(cd "$board_dir/ext/rusefi" && ./gradlew -q --console=plain :custom-java-ui:installM749Cli)
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
# java.library.path lets System.loadLibrary find libpcanbasic_jni.dylib (macOS PCAN bridge over
# MacCAN) next to the Windows DLLs; macOS does not search the working directory for JNI libraries.
native_dir="$board_dir/ext/rusefi/java_console"
export LD_LIBRARY_PATH="$native_dir${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec "$java_bin" -Djava.library.path="$native_dir" -cp "$board_dir/java-custom-ui/build/install/m749/lib/*" com.rusefi.m749.M749Cli "$@"
