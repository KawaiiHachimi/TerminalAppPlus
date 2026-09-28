#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ -z "${JAVA_HOME:-}" ] && [ -d '/Applications/Android Studio.app/Contents/jbr/Contents/Home' ]; then
    export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
fi
./gradlew :app:assembleDebug "$@"
python3 tools/verify-apk-assets.py
