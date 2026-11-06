#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
sdk_path="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/home/work/android-sdk}}"
device_serial="${1:-emulator-5554}"
adb_cmd=("$sdk_path/platform-tools/adb" -s "$device_serial")
# Requires the x86_64 emulator and the APK to have been built. Does not select a physical phone implicitly.
[[ "$device_serial" == emulator-* ]] || { echo 'Use an emulator serial for this test runner.'; exit 1; }
[[ "$("${adb_cmd[@]}" shell getprop ro.product.cpu.abi | tr -d '\r')" == x86_64 ]] || { echo 'An x86_64 emulator is required.'; exit 1; }
mkdir -p build/test-classes build/android-tests
javac -d build/test-classes app/src/main/java/dev/usbreader/core/*.java tests/*.java
jar --create --file build/android-tests/tests.jar -C build/test-classes .
"$sdk_path/build-tools/35.0.0/d8" --min-api 30 --lib "$sdk_path/platforms/android-36/android.jar" \
    --output build/android-tests/tests.dex.jar build/android-tests/tests.jar
"${adb_cmd[@]}" shell mkdir -p /data/local/tmp/usbreader/tests/fixtures
"${adb_cmd[@]}" push build/android-tests/tests.dex.jar /data/local/tmp/usbreader/tests.dex.jar
"${adb_cmd[@]}" push app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/x86_64/libusbreader.so /data/local/tmp/usbreader/libusbreader.so
"${adb_cmd[@]}" push tests/fixtures/generated /data/local/tmp/usbreader/tests/fixtures/
"${adb_cmd[@]}" shell mkdir -p /data/local/tmp/usbreader/tests/fixtures/extended
# The optional multi-gigabyte ReFS reference stays on the host; don't fill emulator storage.
for fixture in tests/fixtures/extended/*.img tests/fixtures/extended/*.iso tests/fixtures/extended/bootx64.efi; do
    [[ "$(basename "$fixture")" == refs* ]] && continue
    "${adb_cmd[@]}" push "$fixture" /data/local/tmp/usbreader/tests/fixtures/extended/
done
"${adb_cmd[@]}" shell 'cd /data/local/tmp/usbreader && app_process -Xcheck:jni -Djava.class.path=/data/local/tmp/usbreader/tests.dex.jar -Djava.library.path=/data/local/tmp/usbreader /data/local/tmp NativeIntegrationTest'
"${adb_cmd[@]}" shell 'cd /data/local/tmp/usbreader && app_process -Xcheck:jni -Djava.class.path=/data/local/tmp/usbreader/tests.dex.jar -Djava.library.path=/data/local/tmp/usbreader /data/local/tmp NativeIntegrationTest btrfs f2fs xfs squash-gzip squash-lzo squash-xz squash-lz4 squash-zstd erofs-lz4'
"${adb_cmd[@]}" shell 'cd /data/local/tmp/usbreader && app_process -Xcheck:jni -Djava.class.path=/data/local/tmp/usbreader/tests.dex.jar -Djava.library.path=/data/local/tmp/usbreader /data/local/tmp ExtendedFilesystemTest'
"${adb_cmd[@]}" shell 'cd /data/local/tmp/usbreader && app_process -Djava.class.path=/data/local/tmp/usbreader/tests.dex.jar /data/local/tmp BotTransportTest'
