#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
cmake -S app/src/main/cpp -B build/host -G Ninja -DCMAKE_BUILD_TYPE=Debug
cmake --build build/host -j4
mkdir -p build/test-classes
javac -d build/test-classes app/src/main/java/dev/usbreader/core/*.java tests/*.java
java -ea -Xcheck:jni -Djava.library.path=build/host -cp build/test-classes RefsIntegrationTest
if [[ "${1:-}" == emulator-* ]]; then
    sdk_path="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/home/work/android-sdk}}"
    python3 scripts/pack-sparse-fixture.py tests/fixtures/extended/refs314.img tests/fixtures/extended/refs314.sparse
    # Run scripts/test-android.sh first to push the current classes and native library.
    "$sdk_path/platform-tools/adb" -s "$1" push tests/fixtures/extended/refs314.sparse tests/fixtures/extended/refs-expected.tsv /data/local/tmp/usbreader/tests/fixtures/extended/
    "$sdk_path/platform-tools/adb" -s "$1" shell 'cd /data/local/tmp/usbreader && app_process -Xcheck:jni -Djava.class.path=/data/local/tmp/usbreader/tests.dex.jar -Djava.library.path=/data/local/tmp/usbreader /data/local/tmp RefsIntegrationTest'
fi
