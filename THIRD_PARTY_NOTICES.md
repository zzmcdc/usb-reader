# Third-party notices

The distributed Android application is GPL-3.0-or-later because it incorporates GNU GRUB.
The original application glue and UI may also be used under LICENSE-MIT. Preserve each
upstream file's own copyright and license notices. This is a source-included project;
redistribution of the APK must comply with GPL corresponding-source requirements.

Pinned repositories are recorded in [dependencies.json](third_party/dependencies.json).

| Component | Use | License |
|---|---|---|
| GNU GRUB | Read-only filesystem modules, LZO, XZ, Zstd and ZFS helpers | GPL-3.0-or-later; embedded code retains its per-file licenses |
| refsprogs | ReFS read-only fsapi and metadata parser | GPL-2.0-or-later, combined here under GPL-3.0-or-later |
| erofs-utils / liberofs | EROFS reader and decompression | GPL-2.0-or-later OR MIT; selected MIT |
| LZ4 1.10.0, lib/lz4.c | LZ4 block decompression | BSD-2-Clause |
| Android platform zlib | Deflate decompression | zlib license (platform library) |
| EfiFs reference images | Tests only, not shipped in APK | Upstream per-file licenses/notices |

Upstream repositories:
- https://gitlab.freedesktop.org/gnu-grub/grub
- https://github.com/unsound/refsprogs
- https://git.kernel.org/pub/scm/linux/kernel/git/xiang/erofs-utils.git
- https://github.com/lz4/lz4
- https://github.com/pbatard/efifs

The former Sleuth Kit engine is no longer linked in version 2.0. Its old checkout, if
present in an existing workspace, is unused and excluded from the source package.

Local adaptations are in app/src/main/cpp, and generated into the CMake build directory:
- POSIX/JNI hosting, bounded read-only USB callbacks and a per-session block cache.
- C++ compatibility for the GRUB disk header; additional SquashFS LZ4/Zstd codecs.
- liberofs exposed through a GRUB filesystem interface, with writes rejected.
- refsprogs block callbacks, Android 8 UTF-8/UTF-16 conversion, metadata read bounds,
  fragmented metadata progression, and exact file-extent byte reads.
- Read-only ReFS I/O handlers avoid upstream buffer alignment errors.

The ReFS test corpus is from xbqt/forefst, commit
`d4d5fd28942eb14ebe11d90f4eb9342f5e3d7b92`, image `wininsiderrefs8gtest2`.
The corpus is downloaded separately and not included in the APK or source archive.
Expected hashes were produced by that independent parser. Retain its attribution when
redistributing corpus data: https://github.com/xbqt/forefst/tree/main/analysis/samples
