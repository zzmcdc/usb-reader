#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/test-classes
cmake -S app/src/main/cpp -B build/host -G Ninja -DCMAKE_BUILD_TYPE=Debug
cmake --build build/host -j4
python3 tests/make_fixtures.py
python3 scripts/fetch-deps.py --tests
python3 tests/make_extended_fixtures.py
javac -d build/test-classes app/src/main/java/dev/usbreader/core/*.java tests/*.java
java -ea -cp build/test-classes BotTransportTest
java -ea -Xcheck:jni -Djava.library.path=build/host -cp build/test-classes NativeIntegrationTest
java -ea -Xcheck:jni -Djava.library.path=build/host -cp build/test-classes NativeIntegrationTest btrfs f2fs xfs squash-gzip squash-lzo squash-xz squash-lz4 squash-zstd erofs-lz4
java -ea -Xcheck:jni -Djava.library.path=build/host -cp build/test-classes ExtendedFilesystemTest
