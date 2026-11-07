#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_PATH="$SCRIPT_DIR/desktopApp/build/compose/jars/Square-Yuma-linux-x64-1.0.0.jar"

if [[ ! -f "$JAR_PATH" ]]; then
    echo "Building Square-Yuma Desktop Uber-JAR..."
    "$SCRIPT_DIR/gradlew" -p "$SCRIPT_DIR" :desktopApp:packageUberJarForCurrentOS
fi

exec java -jar "$JAR_PATH" "$@"
