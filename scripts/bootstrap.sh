#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
sdk_path="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/home/work/android-sdk}}"
python3 scripts/fetch-deps.py
printf 'sdk.dir=%s\n' "$sdk_path" > local.properties
"$sdk_path/cmdline-tools/latest/bin/sdkmanager" 'platforms;android-36' 'build-tools;35.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1'
echo 'Ready. Run ./gradlew :app:assembleDebug :app:lintDebug'
