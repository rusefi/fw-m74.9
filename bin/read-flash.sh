#!/usr/bin/env bash
set -euo pipefail
# java -cp rusefi_console.jar com.rusefi.m749.M749Cli --read-flash
exec bash "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/m749-cli.sh" --read-flash "$@"
