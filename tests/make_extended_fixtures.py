#!/usr/bin/env python3
"""Reproducible reference and mkfs-generated fixtures; only writes regular files."""
import hashlib
import os
import pathlib
import subprocess
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
os.chdir(root)
env = os.environ.copy()
tools = root / "build/test-tools/root"
env["PATH"] = ":".join(str(tools / d) for d in ("sbin", "usr/sbin", "usr/bin")) + ":" + env["PATH"]
env["LD_LIBRARY_PATH"] = ":".join(str(tools / d) for d in ("lib/x86_64-linux-gnu", "usr/lib/x86_64-linux-gnu"))
def run(*args):
    subprocess.run(list(map(str, args)), check=True, env=env, stdout=subprocess.DEVNULL)

reference = root / "tests/fixtures/extended"
reference.mkdir(parents=True, exist_ok=True)
formats = ("iso9660 xfs btrfs f2fs zfs jfs reiserfs erofs squash4 udf nilfs2 "
           "minix minix2 minix3 romfs hfs hfsplus ufs1 ufs2").split()
for name in formats:
    with zipfile.ZipFile(root / "third_party/efifs/tests" / (name + ".zip")) as archive:
        for entry in archive.infolist():
            if pathlib.PurePosixPath(entry.filename).name != entry.filename:
                raise ValueError("Unexpected reference archive path")
        archive.extractall(reference)
# Independent ISO parser provides the expected contents for every reference image.
expected = subprocess.check_output(["isoinfo", "-i", str(reference / "iso9660.iso"), "-x", "/EFI/BOOT/BOOTX64.EFI;1"])
assert hashlib.sha256(expected).hexdigest() == "a6d0a727e1b6084935b2d05e378728da0d7a4af70de2c774ff26ab779efe5fd2"
(reference / "bootx64.efi").write_bytes(expected)

generated = root / "tests/fixtures/generated"
content = generated / "content"
for name in ("btrfs", "f2fs", "xfs"):
    path = generated / (name + ".img")
    with path.open("wb") as f:
        f.truncate((320 if name == "xfs" else 128) * 1024 * 1024)
    if name == "btrfs":
        run("mkfs.btrfs", "-f", "-r", content, path)
    elif name == "f2fs":
        run("mkfs.f2fs", "-f", path)
        run("sload.f2fs", "-f", content, path)
    else:
        lines = ["boot", "0 0", "d--755 0 0"]
        for filename in ("payload.bin", "中文😀.txt", "empty.txt"):
            lines.append(f"{filename} ---644 0 0 {content / filename}")
        lines += ["nested d--755 0 0", f"deep.txt ---644 0 0 {content / 'nested/deep.txt'}", "$", "$"]
        prototype = generated / "xfs.proto"
        prototype.write_text("\n".join(lines) + "\n")
        run("mkfs.xfs", "-f", "-p", prototype, path)
for codec in ("gzip", "lzo", "xz", "lz4", "zstd"):
    run("mksquashfs", content, generated / ("squash-" + codec + ".img"), "-comp", codec,
        "-noappend", "-quiet", "-processors", "1")
run("mkfs.erofs", "-zlz4", generated / "erofs-lz4.img", content)
print("Extended reference and generated fixtures ready")
