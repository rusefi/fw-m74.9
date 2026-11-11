#!/usr/bin/env bash



# Console JAR built by the bundle target: M74.9 tab and SLCAN discovery settings
board_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export RUSEFI_CUSTOM_JAVA_UI_DIR="$board_dir/java-custom-ui"
export ABSOLUTE_BOARD_DIR="$board_dir"

export META_OUTPUT_ROOT_FOLDER=$(realpath --relative-to=ext/rusefi/firmware generated)/
echo "META_OUTPUT_ROOT_FOLDER=$META_OUTPUT_ROOT_FOLDER"

export AUTOMATION_REF=`git branch --show-current`
cd ext/rusefi/firmware/
bash bin/compile.sh ../../../meta-info.env "$@"
