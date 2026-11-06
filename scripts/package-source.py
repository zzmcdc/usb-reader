#!/usr/bin/env python3
"""Package corresponding source, including pinned native dependencies."""
import hashlib
import json
import os
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[1]
dest = root / "dist"
dest.mkdir(exist_ok=True)
archive = dest / "usb-reader-2.0-source.zip"
dependencies = json.loads((root / "third_party/dependencies.json").read_text())
entries = [
    ".gitignore", ".gitmodules", "README.md", "LICENSE", "LICENSE-MIT", "THIRD_PARTY_NOTICES.md",
    "build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat",
    "gradle", "app", "scripts", "tests", "docs",
    "third_party/dependencies.json", "third_party/ZSTD-LICENSE",
] + ["third_party/" + name for name in dependencies]
excluded_dirs = {".git", ".gradle", "build", ".cxx", "__pycache__"}
files = []
for entry in entries:
    path = root / entry
    if not path.exists():
        raise SystemExit(f"Required source missing: {path}")
    if path.is_file():
        files.append(path)
        continue
    for base, dirs, names in os.walk(path):
        dirs[:] = sorted(d for d in dirs if d not in excluded_dirs)
        relative = Path(base).relative_to(root)
        if relative == Path("tests/fixtures"):
            dirs[:] = []  # Generated images have reproducible generators/downloaders.
            continue
        for name in sorted(names):
            if name.endswith((".pyc", ".pyo")) or name == ".git":
                continue
            files.append(Path(base) / name)
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as output:
    for path in sorted(files):
        output.write(path, "usb-reader-2.0/" + path.relative_to(root).as_posix())
with zipfile.ZipFile(archive) as output:
    bad = output.testzip()
    if bad:
        raise SystemExit(f"Archive CRC check failed: {bad}")
checksums = []
for path in (dest / "usb-reader-debug.apk", archive):
    if path.exists():
        with path.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        checksums.append(f"{digest}  {path.name}\n")
(dest / "SHA256SUMS").write_text("".join(checksums))
print(f"Packaged {len(files)} source files: {archive} ({archive.stat().st_size:,} bytes)")
print("".join(checksums), end="")
