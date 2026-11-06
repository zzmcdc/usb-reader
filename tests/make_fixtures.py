#!/usr/bin/env python3
"""Create disposable filesystem images with real mkfs tools. Never opens a block device."""
import hashlib
import math
import mmap
import os
from pathlib import Path
import shutil
import struct
import subprocess
import uuid
import zlib

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "tests/fixtures/generated"
TOOLS = ROOT / "build/test-tools/root/usr"
os.environ["PATH"] = str(TOOLS / "bin") + ":" + str(TOOLS / "sbin") + ":/usr/sbin:" + os.environ["PATH"]


def run(*args, input=None):
    result = subprocess.run([str(a) for a in args], input=input, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(f"{args[0]} failed: {result.stdout}\n{result.stderr}")
    return result.stdout


def blank(name, size):
    path = OUT / name
    with path.open("wb") as f:
        f.truncate(size)
    return path


def put_exfat(path, payload):
    """Populate mkfs.exfat's empty volume with a fragmented file and a Unicode file.
    Directory entry sets, checksums, FAT chains and allocation bitmap follow the exFAT spec.
    fsck.exfat independently verifies the resulting fixture before it reaches our reader.
    """
    with path.open("r+b") as f:
        boot = f.read(512)
        sector = 1 << boot[108]
        cluster = sector << boot[109]
        fat = struct.unpack_from("<I", boot, 80)[0] * sector
        heap = struct.unpack_from("<I", boot, 88)[0] * sector
        root = struct.unpack_from("<I", boot, 96)[0]
        pos = lambda c: heap + (c - 2) * cluster
        f.seek(pos(root)); directory = bytearray(f.read(cluster))
        bitmap_entry = next(directory[i:i+32] for i in range(0, cluster, 32) if directory[i] == 0x81)
        bitmap_cluster = struct.unpack_from("<I", bitmap_entry, 20)[0]
        bitmap_size = struct.unpack_from("<Q", bitmap_entry, 24)[0]
        f.seek(pos(bitmap_cluster)); bitmap = bytearray(f.read(bitmap_size))
        upcase_entry = next(directory[i:i+32] for i in range(0, cluster, 32) if directory[i] == 0x82)
        upcase_cluster = struct.unpack_from("<I", upcase_entry, 20)[0]
        upcase_size = struct.unpack_from("<Q", upcase_entry, 24)[0]
        f.seek(pos(upcase_cluster)); compressed = struct.unpack('<' + 'H' * (upcase_size // 2), f.read(upcase_size))
        uppercase = []; i = 0
        while i < len(compressed):
            code = compressed[i]; i += 1
            if code == 0xffff and len(uppercase) != 65535:
                count = compressed[i]; i += 1
                uppercase.extend(range(len(uppercase), len(uppercase) + count))
            else: uppercase.append(code)
        assert len(uppercase) == 65536

        def checksum(data):
            result = 0
            for byte in data:
                result = (((result & 1) << 15) | (result >> 1)) + byte
                result &= 0xffff
            return result

        cursor = next(i for i in range(0, cluster, 32) if directory[i] == 0)
        next_cluster = root + 4
        for name, data, fragmented in [("payload.bin", payload, True), ("中文😀.txt", "你好，USB！\n".encode(), False)]:
            count = math.ceil(len(data) / cluster)
            chain = [next_cluster + i * (2 if fragmented else 1) for i in range(count)]
            next_cluster = chain[-1] + 2
            for i, c in enumerate(chain):
                bitmap[(c-2)//8] |= 1 << ((c-2)%8)
                f.seek(pos(c)); f.write(data[i*cluster:(i+1)*cluster])
                if fragmented:
                    f.seek(fat + c*4); f.write(struct.pack("<I", chain[i+1] if i+1 < count else 0xffffffff))
            units = list(struct.unpack('<' + 'H' * (len(name.encode('utf-16le'))//2), name.encode('utf-16le')))
            name_count = math.ceil(len(units)/15)
            entries = bytearray(32 * (2 + name_count)); entries[0] = 0x85; entries[1] = 1 + name_count
            struct.pack_into("<H", entries, 4, 0x20)
            # Valid timestamps: 2026-01-01 00:00:00.
            for off in (8, 12, 16): struct.pack_into("<I", entries, off, ((46 << 9) | (1 << 5) | 1) << 16)
            entries[32] = 0xc0; entries[33] = 1 if fragmented else 3; entries[35] = len(units)
            upper_bytes = b''.join(struct.pack('<H', uppercase[u]) for u in units)
            struct.pack_into("<H", entries, 36, checksum(upper_bytes))
            struct.pack_into("<Q", entries, 40, len(data)); struct.pack_into("<I", entries, 52, chain[0]); struct.pack_into("<Q", entries, 56, len(data))
            for j in range(name_count):
                off = 64 + j*32; entries[off] = 0xc1
                for k, unit in enumerate(units[j*15:(j+1)*15]): struct.pack_into("<H", entries, off+2+k*2, unit)
            struct.pack_into("<H", entries, 2, checksum(entries[:2] + entries[4:]))
            directory[cursor:cursor+len(entries)] = entries; cursor += len(entries)
        f.seek(pos(root)); f.write(directory)
        f.seek(pos(bitmap_cluster)); f.write(bitmap)
    run("fsck.exfat", "-n", path)


def copy_into(out, offset, source):
    with out.open("r+b") as dest, source.open("rb") as src:
        dest.seek(offset); shutil.copyfileobj(src, dest, 1024 * 1024)


def partitions(fat, ext):
    sector = 512
    offsets = [2048, 2048 + fat.stat().st_size//sector]
    size = (offsets[1] + ext.stat().st_size//sector + 2048)*sector
    mbr = blank("mbr.img", size)
    block = bytearray(512)
    for i, (offset, source, typ) in enumerate(zip(offsets, (fat, ext), (0x0c, 0x83))):
        struct.pack_into("<B3sB3sII", block, 446+i*16, 0, b'\0\2\0', typ, b'\xfe\xff\xff', offset, source.stat().st_size//sector)
        copy_into(mbr, offset*sector, source)
    block[510:512] = b'\x55\xaa'
    with mbr.open('r+b') as f: f.write(block)
    # GPT has protective MBR, CRC-checked primary and backup headers and tables.
    for sector in (512, 4096):
        starts = [1024*1024//sector, (1024*1024 + fat.stat().st_size)//sector]
        sectors = (1024*1024 + fat.stat().st_size + ext.stat().st_size + 1024*1024)//sector
        gpt = blank(f"gpt{sector}.img", sectors*sector)
        table = bytearray(128*128)
        disk_guid = uuid.uuid4().bytes_le
        for i, (start, src) in enumerate(zip(starts, (fat, ext))):
            struct.pack_into('<16s16sQQQ', table, i*128, uuid.UUID('0fc63daf-8483-4772-8e79-3d69d8477de4').bytes_le,
                             uuid.uuid4().bytes_le, start, start+src.stat().st_size//sector-1, 0)
            name = f'测试分区{i+1}'.encode('utf-16le'); table[i*128+56:i*128+56+len(name)] = name
            copy_into(gpt, start*sector, src)
        table_sectors = len(table)//sector
        def header(current, backup, table_lba):
            h = bytearray(sector)
            struct.pack_into('<8sIIIIQQQQ16sQIII', h, 0, b'EFI PART', 0x10000, 92, 0, 0,
                             current, backup, 2+table_sectors, sectors-table_sectors-2, disk_guid, table_lba, 128, 128, zlib.crc32(table))
            struct.pack_into('<I', h, 16, zlib.crc32(h[:92])); return h
        protective = bytearray(sector); protective[450] = 0xee
        struct.pack_into('<II', protective, 454, 1, min(sectors-1, 0xffffffff)); protective[510:512] = b'\x55\xaa'
        with gpt.open('r+b') as f:
            f.write(protective); f.write(header(1, sectors-1, 2)); f.write(table)
            f.seek((sectors-1-table_sectors)*sector); f.write(table); f.write(header(sectors-1, 1, sectors-1-table_sectors))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    content = OUT / 'content'; content.mkdir(exist_ok=True)
    nested = content / 'nested'; nested.mkdir(exist_ok=True)
    payload = bytes((i*37+11) % 256 for i in range(350003))
    (content/'payload.bin').write_bytes(payload)
    (content/'中文😀.txt').write_text('你好，USB！\n')
    (content/'empty.txt').write_bytes(b'')
    (nested/'deep.txt').write_text('nested contents\n')
    (content/'large.bin').write_bytes(b'large file prefix')
    for bits, size in ((12, 4*1024**2), (16, 32*1024**2), (32, 128*1024**2)):
        img = blank(f'fat{bits}.img', size); run('mkfs.fat', '-F', bits, img)
        for p in content.iterdir(): run('mcopy', '-s', '-i', img, p, '::/')
        # mtools 4.0.43 truncates supplementary Unicode to a single UCS-2 unit.
        # Correct that fixture writer bug in the standard 13-unit VFAT LFN slot.
        with img.open('r+b') as f, mmap.mmap(f.fileno(), 0) as data:
            start = data.find('中文\uf600.t'.encode('utf-16le'))
            if start >= 0:
                start -= 1
                assert data[start+11] == 0x0f
                units = '中文😀.txt'.encode('utf-16le') + b'\0\0'
                units += b'\xff' * (26-len(units))
                data[start+1:start+11] = units[:10]
                data[start+14:start+26] = units[10:22]
                data[start+28:start+32] = units[22:26]
        run('fsck.fat', '-n', img)
    for version in (2, 3, 4):
        img = blank(f'ext{version}.img', 96*1024**2)
        run(f'mkfs.ext{version}', '-q', '-F', '-d', content, img)
        # A sparse regular file >4 GiB exercises 64-bit file sizes and hole reads.
        if version == 4: run('debugfs', '-w', '-R', 'set_inode_field /large.bin size 5368709137', img)
    exfat = blank('exfat.img', 128*1024**2); run('mkfs.exfat', exfat); put_exfat(exfat, payload)
    ntfs = blank('ntfs.img', 96*1024**2); run('mkntfs', '-F', '-Q', ntfs)
    run('ntfscp', '-f', ntfs, content/'payload.bin', '/payload.bin')
    run('ntfscp', '-f', ntfs, content/'中文😀.txt', '/中文😀.txt')
    run('genisoimage', '-quiet', '-R', '-J', '-o', OUT/'iso9660.img', content)
    partitions(OUT/'fat32.img', OUT/'ext4.img')
    blank('unknown.img', 8*1024**2)
    with (OUT/'truncated.img').open('wb') as out, (OUT/'ext4.img').open('rb') as src: out.write(src.read(2048))
    print(f'Created fixtures in {OUT}; payload SHA-256 {hashlib.sha256(payload).hexdigest()}')


if __name__ == '__main__': main()
